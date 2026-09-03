# Architecture & Technical Review Prompt — CortisolMonitor

Paste everything below into a fresh AI coding assistant (or hand to a human reviewer) that has access to the `CortisolMonitor` repository. It gives the reviewer the app's purpose and current architecture up front, then asks for a structured technical audit.

---

## 1. What this app does, end to end

CortisolMonitor is an Android app (package `com.example.myapplication`) built for a stress-monitoring research project (MS thesis, BioMEMS Lab). It combines a subjective questionnaire score, an objective salivary-cortisol/DHEA biomarker reading, and — in an in-progress extension — continuous wearable signals (HRV, EDA, skin temperature, motion) from a Samsung Galaxy Watch8, feeding all of it into an on-device machine-learning model that classifies overall stress level.

**Patient flow:**
1. Register/log in (Firebase Auth, `RegisterLoginActivity`).
2. Complete health questionnaires — PHQ-9, BDI, GAD-7, BRS (`QuestionnaireActivity`/`QuestionnaireActivity2`/`QuestionnaireMain`, backed by a Room DAO).
3. Run a saliva biomarker test: connect to an external "Optical Detector" device over Bluetooth LE (`BluetoothManager`, `BiomarkerActivty`, `BiomarkerInstructions`), which reports cortisol and DHEA readings.
4. Optionally wear the Galaxy Watch8 during a session — the watch app (separate Wear OS module) streams heart rate/IBI, EDA, skin temperature and accelerometer samples in real time to the phone over the Wear OS Data Layer (`WatchDataListenerService`, `LiveVitalsActivity`, `VitalsSessionHistoryActivity`).
5. View combined results: questionnaire + biomarker + (optionally) wearable data are combined and run through an on-device ONNX Random Forest model (`StressPredictor`) to classify stress level, shown via `CombinedResultsActivity` / `IntegratedResultsActivity` / `FinalResultsActivity`, with historical trend graphs (`CortisolGraphActivity`, MPAndroidChart/GraphView).
6. Data is periodically synced from local SQLite up to Firebase Firestore (`SyncManager`).

**Care-provider flow:** a separate role can register/manage patients, browse a patient's questionnaire/biomarker/vitals history, and view detail screens per session (`CareProviderDashboardActivity`, `RegisterPatientActivity`, `PatientHistoryDetailActivity`, etc.). Patients are also associated to a provider via QR code scan (`QRScanActivity`/`QRWizardActivity`, ML Kit barcode scanning).

**Other pieces:** an in-app support chatbot (Kommunicate SDK, `ChatbotActivity`); CSV export of raw wearable sessions for offline research analysis (`VitalsCsvExporter`, consumed by a separate Python `accel_pipeline/` and `wesad/` model-training pipeline outside the shipped app).

---

## 2. How I want this app to be (my vision)

This is a **research-grade app** — the priority is that the data it collects and shows is accurate, traceable, and trustworthy enough to use in a thesis/research context, not a polished consumer product. It should work cleanly in **two distinct modes**, with no overlap or leakage between them:

- **Patient mode** — in plain words: this is the mode a study participant uses to answer a mental-health questionnaire, take a saliva cortisol/DHEA test with the provided analyzer, optionally wear the smartwatch during a session, and see their own results and history. It's their personal tool for doing an assessment and looking back at what they've recorded — nothing about managing other people.
- **Care provider mode** — in plain words: this is the mode for the researcher/clinician side. It's for registering patients, looking at a given patient's questionnaire answers, biomarker results, and (if collected) watch data, and keeping track of a caseload of patients over time. A care provider should never see this as "their own" data the way a patient does — it's oversight of other people's records.

A patient account and a care-provider account should behave like two clearly separate apps sharing one codebase — each should only ever see the screens, data, and actions relevant to its own role. Please keep this in mind while reviewing: flag anywhere the two modes bleed into each other (a patient screen reachable from provider mode or vice versa, shared state that isn't role-scoped, role-checking logic duplicated/inconsistent across activities, etc.).

## 3. Current technical architecture (as built)

- **Platform:** Native Android, Java (phone app, `:app` module) + Kotlin (Wear OS watch app, `:myapplication` module) + a third-party charting library pulled in as its own Gradle module (`:GraphView-master`). `minSdk 26`, `targetSdk`/`compileSdk 34`.
- **Auth:** Firebase Authentication, role (`patient`/`care_provider`) cached in `SharedPreferences`. There's also a local `PasswordHasher` class — worth checking whether that's dead code or a second, parallel auth path.
- **"Backend":** there is no separate server/API — Firebase (Auth + Firestore + Realtime Database, **both** included as dependencies) is the entire backend. Most business logic runs client-side in the Android app itself.
- **Local data:** several independent `SQLiteOpenHelper` subclasses (`DatabaseHelper` — questionnaires/biomarker/app data, `UserDatabaseHelper`, `VitalsDatabaseHelper` — deliberately a separate DB file for raw watch samples, `HistoryDataHelper`), plus a Room DAO (`QuestionnaireDao`/`QuestionnaireResponse`) used for part of the questionnaire flow only. So the app has raw SQLite and Room coexisting for related data.
- **Cloud sync:** `SyncManager` pushes local SQLite rows up to Firestore per authenticated user; no visible conflict-resolution or offline-queue/retry strategy.
- **Device integrations:**
  - BLE to an external "Optical Detector" biomarker analyzer (custom GATT profile, hardcoded UUIDs/name in `BluetoothManager`).
  - USB serial (`felHR85:UsbSerial`, `USB_PERMISSION`, `android.hardware.usb.host`) — a second hardware path alongside BLE.
  - Wear OS Data Layer API (`play-services-wearable`) for phone↔watch streaming; the watch side uses the Samsung Health Sensor SDK (Watch8-series only for continuous EDA).
- **On-device ML:** ONNX Runtime Android running a pre-trained Random Forest (`random_forest_model_ir9.onnx`) with a fixed, order-dependent 9-feature input vector.
- **Other SDKs:** ML Kit barcode scanning + CameraX (QR flow), Kommunicate chat SDK.
- **Permissions:** manifest requests both the legacy Bluetooth permissions (`BLUETOOTH`, `BLUETOOTH_ADMIN`) and the Android 12+ ones (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`), plus fine/coarse location (needed for BLE scan on older APIs), camera, and broad storage permissions.

---

## 4. What I want you to review

Go through the actual codebase (don't just take my summary above at face value — verify it) and identify concrete architectural and technical issues, organized by area:

**Database / data layer**
- Is having raw `SQLiteOpenHelper` classes *and* Room in the same app, for related data (questionnaires vs. everything else), a real maintainability/consistency risk, or is it contained enough to be fine?
- Any schema design problems: missing indexes/foreign keys, inconsistent typing, migration handling (note `DatabaseHelper` is already on schema version 6 — check the migration path is safe), risk of orphaned data across the multiple DB files.
- Is keeping `VitalsDatabaseHelper` as a fully separate DB file from `DatabaseHelper` (rather than one schema) going to cause problems for querying/joining patient identity, sessions, and vitals together later?

**Backend / cloud (Firebase)**
- Why both Firestore *and* Realtime Database are included — is each actually used, or is one dead weight? Any data duplicated or drifting between the two?
- Review `SyncManager` for correctness: does it handle sync failures, retries, partial syncs, or conflicting writes from multiple devices/sessions for the same user? What happens if the app is killed mid-sync?
- Firebase security rules (if present in the repo) — do they properly scope patient data so one patient/provider can't read another's records? Is there any server-side validation at all, or is everything trusted from the client?
- Any secrets, API keys, or credentials committed to the repo that shouldn't be?

**Frontend / app structure**
- The app is built almost entirely as separate `Activity` classes (dozens of them) rather than a smaller set of screens with fragments/navigation component/a shared ViewModel layer. Is this causing real duplication or state-management problems (e.g., user/session state re-fetched or re-derived independently in many places)?
- Check for God-object risk in classes like `DatabaseHelper` and `MainActivity`.
- Threading: are SQLite/Firebase/BLE/ONNX calls kept off the main thread consistently, or are there places risking ANRs?
- Error handling / user feedback: what happens in the UI when Bluetooth connection fails, sync fails, or the ONNX model throws?

**Hardware & ML integration**
- `StressPredictor` depends on a hardcoded, order-sensitive 9-feature vector — how fragile is this to a mismatched model file or a missing input, and is there validation before inference?
- `BluetoothManager` hardcodes device name/UUIDs for one specific analyzer firmware — how does the app behave with a different firmware version or if the device isn't found/times out?
- Two parallel hardware paths (BLE and USB serial) for what looks like the same biomarker device — check whether both are actually maintained/tested or if one is stale.

**Cross-cutting / security**
- Note: the manifest already documents one fixed vulnerability (`BiomarkerInstructions` used to have an exported, unauthenticated `ACTION_VIEW` intent filter). Do a pass for similar issues — check every `exported="true"` component (`MainActivity`, `WatchDataListenerService`) for whether it validates its caller/input appropriately.
- `PasswordHasher` alongside Firebase Auth — is this used anywhere, and if so, is it a legitimate local-fallback need or a second, weaker auth surface?
- Any PII/health data (cortisol values, questionnaire answers, patient emails) logged via `Log.d`/`Log.e` that shouldn't be in production logs?

**Cross-device / cross-version consistency ("does it work the same on any device?")**
- `minSdk` 26 → `targetSdk` 34 spans many Android permission-model changes (runtime permissions, Bluetooth permission split in Android 12/API 31, notification permission in Android 13/API 33 — I don't see `POST_NOTIFICATIONS` requested anywhere, check if it's needed). Verify permission requests are actually branched correctly per API level, not just declared in the manifest.
- OEM background-execution restrictions (Samsung/Xiaomi/etc. aggressively kill background BLE/foreground services) — will a live vitals-streaming session from the watch survive the phone screen turning off or the app backgrounding, on a range of OEM skins?
- Layouts: are these Activities built with fixed/absolute dimensions or `ConstraintLayout` that scales properly across phone screen sizes (small phones vs. large phones vs. tablets), and both portrait/landscape where relevant (e.g., `QRScanActivity` is locked portrait — intentional?)?
- Dark mode / theme: `AppTheme.Light` is hardcoded on multiple activities — does the rest of the app respect system dark mode, or is theming inconsistent screen-to-screen?
- Watch-side: continuous EDA requires Watch8-series or later specifically — confirm the app degrades gracefully (not crashes) on an older/unsupported Wear OS device, and that Bluetooth/Wear pairing state is handled the same across different watch models.
- Any hardcoded pixel values, screen-size assumptions, or device-specific behavior (e.g., USB serial device paths) that could behave differently across manufacturers.

---

## 5. What each screen does, in plain words

I went through every screen in the app and confirmed what it actually does (not just its class name). Grouped by where it belongs:

**Shared — login & first-time setup**
- **Main screen (on launch)** — checks whether you're already logged in and what role your account is, then sends you straight to the right home screen (patient or care provider) without you having to choose.
- **Register / Log in** — create a new account or sign into an existing one.
- **Activate account** — the verification step a brand-new account has to go through before it can be used.
- **Welcome walkthrough (4 steps)** — shown once, right after logging in for the first time: explains the four things you'll do in the app (fill out questionnaires, take a saliva cortisol test, view your combined results, get recommendations).

**Patient mode**
- **Home tab** — two test cards: "Cortisol Test" (tap to start) and "DHEA Test" (shown as coming soon, not usable yet).
- **Assessments tab** — a "Begin/Continue" button plus a list of your past assessment visits; tap one to see everything you answered in it.
- **Questionnaire screens** — the actual questionnaires, one question per screen (a depression/anxiety-type screener first, then follow-up questionnaires based on your screener answers).
- **Questionnaire results** — your score for a completed questionnaire, plus an overall stress read-out combining all your scores.
- **Session detail** — every question and answer from one past visit, laid out for review.
- **Response viewer** — a plain list of all your saved questionnaire answers.
- **Cortisol test instructions** — step-by-step guidance before you run a cortisol test.
- **Cortisol test screen** — walks you through actually running the test with the handheld analyzer.
- **Bluetooth device list** — shows nearby analyzer devices so you can pick yours and connect.
- **QR scan / QR wizard** — scan a QR code (on the analyzer, a test tube, or a test cartridge) as a guided, step-by-step part of setting up a test.
- **Cortisol result screen** — your test result plotted on a graph, with a shaded band showing what's considered a normal range.
- **Cortisol graph (over time)** — your cortisol readings across multiple tests/days, browsable day by day.
- **Combined results** — your questionnaire results and cortisol/DHEA results brought together into one summary.
- **Add Data (+) tab** — log everyday things outside the formal tests: symptoms, conditions, habits, notes, prescriptions, or reports, and see your history of each.
- **Insights tab** — currently a placeholder; nothing functional here yet.
- **Profile tab** — your hub for: combined results, cortisol graph, questionnaire history, your account details, your care provider's info, and help/support chat.
- **Live watch vitals** — if you're wearing the smartwatch, shows your most recent heart rate, skin-conductance, and skin-temperature readings live, so you can confirm it's working.
- **Watch session history** — a list of your past watch-recording sessions (one per time you pressed Start/Stop on the watch).
- **Watch session detail** — how long a given watch session ran and how much data it captured, with a button to export it.
- **Watch data by day** — for one signal at a time (heart rate, skin conductance, or skin temperature), lists which days have data and shows the full graph for a day you pick.
- **Account details / Settings** — your own profile info and general app settings.
- **Help & support chat** — an in-app chat window for support.

**Care-provider mode**
- **Care provider dashboard** — the provider's home screen; also quietly syncs data in the background when it opens.
- **My account** — the care provider's own profile screen.
- **Register patient** — the form used to add a new patient.
- **Modify patient → Edit patient** — first pick what you want to change about an existing patient, then make the edit.
- **Patient list / history** — browse your patients, tap one to open their full history.
- **Patient overview** — one patient's full record: questionnaires, cortisol results, and watch data if collected.
- **Export patient's day results** — pulls one patient's cortisol test results for a specific day into a CSV file (this screen is shared with patient mode too, reached from the cortisol graph).
- **Care provider info** — shown to a patient so they can see who their assigned provider is.

**Watch app (on the Galaxy Watch8 itself)**
- A single screen with a Start/Stop control: pressing Start begins recording heart rate, skin conductance, skin temperature and motion, and streaming it live to the phone; Stop ends the session.

**Not user-facing (internal/developer only)**
- A hidden developer screen for manually tweaking the analyzer's LED brightness and sensor sensitivity while testing the hardware, and a raw-data debug screen showing values like battery voltage straight from the analyzer — neither is meant for a patient or provider to see in normal use.

**Worth flagging while reviewing:** there appear to be two different "combined results" screens (one plainer, one with extra illustration graphics) and a screen whose only job is to seed a placeholder result so history isn't empty on first use — please confirm with me which of these is the one actually meant to be current before assuming either is dead code.

---

## 6. How I want the findings delivered

For each issue found, give me:
1. **Severity** (critical / high / medium / low) and **area** (DB, backend, frontend, hardware/ML, cross-device).
2. **File and location** in the repo.
3. **Concrete failure scenario** — what breaks, for whom, under what condition (not just "this is bad practice").
4. **A specific fix or next step**, not just "consider refactoring."

Rank the list by severity, most important first. Call out clearly if something I described in section 3 turns out to be inaccurate once you've actually looked at the code.
