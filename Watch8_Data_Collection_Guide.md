# Collecting ECG & EDA Data from the Samsung Galaxy Watch8

Complete technical reference for pulling raw ECG, EDA, and heart-rate/HRV data off the Galaxy Watch8 and into the CortisolMonitor Android app. Compiled from Samsung's official developer documentation (developer.samsung.com/health/sensor) and Android's Wear OS Data Layer documentation (developer.android.com/training/wearables), July 2026.

---

## 1. The architecture, in one paragraph

There is no way to read raw ECG/EDA off a Galaxy Watch8 from a phone app over generic Bluetooth. Samsung's sensor data is only exposed through the **Samsung Health Sensor SDK**, and that SDK only runs *inside a Wear OS app installed on the watch itself*. So the system is necessarily **two apps**: a small Wear OS app on the watch that talks to the sensor hardware, and the existing CortisolMonitor phone app, connected to each other over the **Wear OS Data Layer API** (Google's phone↔watch bridge, not Samsung's). The watch app measures, the phone app receives and feeds the Random Forest pipeline. This is a different shape than the "wearable via Bluetooth" line in the proposal — worth updating there.

```
[Watch hardware sensors]
        │
        ▼
[Samsung Health Sensor SDK]   ← runs ON the watch, inside a Wear OS app
        │
        ▼
[Wear OS Data Layer API]      ← MessageClient / DataClient / ChannelClient
        │  (Bluetooth/Wi-Fi, phone↔watch only — not iOS)
        ▼
[CortisolMonitor phone app]   ← existing Android app, Random Forest model
```

---

## 2. Tools & accounts required

| Item | Notes |
|---|---|
| Android Studio | Latest stable version |
| JDK 17+ | Required by the current Samsung Health Sensor SDK codelabs |
| Android target API level 36 (Baklava) or later | For the newest permission model; SDK also supports older targets with a different permission |
| A physical Galaxy Watch8 | The emulator cannot exercise real sensors; EDA specifically requires real Watch8-series hardware |
| A physical Android phone | To run the companion mobile app and test the Data Layer transfer |
| Samsung account signed into the watch | Needed for Health Platform / Samsung Health |
| Samsung Health Sensor SDK library (`samsung-health-sensor-api.aar`) | Free download from developer.samsung.com/health/sensor/process.html, current version 1.4.1 |
| Wi-Fi network reachable by both PC and watch | Used for wireless ADB debugging |

No paid developer account or approval is needed to build and test — see §7 on developer mode.

---

## 3. Part A — Environment and watch setup

### A1. Project structure

Create the Android Studio project as **Wear OS → Empty Wear App**, and check **"Pair with Empty Phone app"** at creation time. This produces two Gradle modules in one project: `wear` and `mobile`. The two modules **must share the same application ID** in their respective `build.gradle` files — this is how the Data Layer API matches them as a pair at runtime.

### A2. Connect the watch to Android Studio (wireless ADB)

1. On the watch: **Settings → Connection → Wi-Fi**, connect to the same Wi-Fi network as your PC.
2. On the watch: **Settings → About watch → Software**, tap **Software version** 5 times to unlock Developer options.
3. **Settings → Developer options → ADB debugging** → enable.
4. Under Developer options, find **Wireless debugging**, enable it, and when prompted "Allow debugging over this Wi-Fi network," choose **Always allow on this network**.
5. Back in Developer options, select **Turn off automatic Wi-Fi** (prevents the watch from dropping off the debug network).
6. In **Wireless debugging → Pair new device**, note the **Wi-Fi pairing code** and **IP:port** shown.
7. In Android Studio's terminal:
   ```
   adb pair <IP address>:<port> <Wi-Fi pairing code>
   ```
8. Approve the "Always allow from this computer" prompt on the watch.
9. Then:
   ```
   adb connect <IP address of your watch>:<port>
   ```
   A `connected to <IP>` message confirms the watch is now a debuggable target in Android Studio.

### A3. Health Platform developer mode (separate from Android Developer options — easy to conflate)

Samsung Health Sensor SDK data only flows to apps whose package name + release-key signature are registered with Samsung's Health backend (see §7). While developing, you bypass that check with a **separate** developer-mode toggle inside the watch's Health Platform app:

1. Pull down the watch's quick panel → **Settings** icon → **Apps** → scroll to **Health Platform**.
2. Tap the **Health Platform** title quickly ~10 times.
3. `[Dev mode]` appears next to the title once active.

Without this, an unregistered app gets `SDK_POLICY_ERROR` when it tries to track anything. Toggle it the same way to turn it back off. Samsung is explicit that this mode is for your own testing only — never document it for end users of a shipped app.

---

## 4. Part B — Building the watch (Wear OS) app

### B1. Import the SDK

Place `samsung-health-sensor-api.aar` in the `wear` module's `libs/` folder, then in `wear/build.gradle`:

```groovy
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}
dependencies {
    implementation fileTree(dir: 'libs', include: '*.aar')
}
```

### B2. Manifest permissions

Every tracker type needs the app to hold a permission and the user to grant it at runtime (standard Android runtime permission dialog). Which permission depends on your target Android API level.

| Tracker type | Permission — target API 36+ (Android 16 "Baklava") | Permission — target API 35 or lower |
|---|---|---|
| `EDA_CONTINUOUS` | `com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA` | `android.permission.BODY_SENSORS` |
| `ECG_ON_DEMAND` | same as above | same as above |
| `PPG_CONTINUOUS` / `PPG_ON_DEMAND` | same as above | same as above |
| `BIA_ON_DEMAND` / `MF_BIA_ON_DEMAND` | same as above | same as above |
| `HEART_RATE_CONTINUOUS` | `HealthPermissions.READ_HEART_RATE` | `android.permission.BODY_SENSORS` |
| `SKIN_TEMPERATURE_CONTINUOUS` / `_ON_DEMAND` | `HealthPermissions.READ_SKIN_TEMPERATURE` | `android.permission.BODY_SENSORS` |
| `SPO2_ON_DEMAND` | `HealthPermissions.READ_OXYGEN_SATURATION` | `android.permission.BODY_SENSORS` |
| `ACCELEROMETER_CONTINUOUS` | `Manifest.permission.ACTIVITY_RECOGNITION` | same |
| `SWEAT_LOSS` | `ACTIVITY_RECOGNITION` + `READ_ADDITIONAL_HEALTH_DATA` (both) | `ACTIVITY_RECOGNITION` + `BODY_SENSORS` (both) |

Manifest declaration:

```xml
<uses-permission android:name="android.permission.BODY_SENSORS" />
<uses-permission android:name="com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA" />
```

Runtime check and request follow the standard Android pattern (`ActivityCompat.checkSelfPermission` / `ActivityCompat.requestPermissions`); Samsung's guide provides boilerplate for this, no SDK-specific quirks here.

### B3. Connect to the Health Tracking Service

```kotlin
healthTrackingService = HealthTrackingService(connectionListener, this)
healthTrackingService.connectService()

// later, e.g. onDestroy()
healthTrackingService.disconnectService()
```

```kotlin
private val connectionListener = object : ConnectionListener {
    override fun onConnectionSuccess() {
        // proceed to capability check, then start tracking
    }
    override fun onConnectionEnded() { /* connection dropped */ }
    override fun onConnectionFailed(e: HealthTrackerException) {
        if (e.hasResolution()) e.resolve(this@MainActivity)
    }
}
```

### B4. Capability check

Not every tracker type exists on every device/software version — always check before starting a tracker, rather than assuming:

```kotlin
val available = healthTrackingService.trackingCapability.supportHealthTrackerTypes
if (!available.contains(HealthTrackerType.EDA_CONTINUOUS)) {
    // not supported on this device/firmware — EDA needs Watch8-series or later
}
```

### B5. Data types — full reference

All values arrive as a `List<DataPoint>` in `TrackerEventListener.onDataReceived()`. Each `DataPoint` exposes `getTimestamp()` and `getValue(ValueKey.<Set>.<FIELD>)`.

**Continuous trackers** (run indefinitely until you unset the listener):

| Tracker type | Raw/Processed | Rate | Key fields (`ValueKey.<Set>`) | Notes |
|---|---|---|---|---|
| `EDA_CONTINUOUS` | Raw | 1 Hz | `EdaSet.SKIN_CONDUCTANCE`, `EdaSet.STATUS` | **Watch8 series or later only.** Gives one `EdaSet` point per second while the display is on; batched points while the display is off (see §B7 on `flush()`). SCL (tonic) and SCR (phasic peaks) are not separated for you — derive them from this stream with standard EDA signal processing (low-pass for SCL, band-pass/peak-detection for SCR). |
| `HEART_RATE_CONTINUOUS` | Processed | 1 Hz | `HeartRateSet.HEART_RATE`, `HeartRateSet.HEART_RATE_STATUS`, `HeartRateSet.IBI_LIST`, `HeartRateSet.IBI_STATUS_LIST` | IBI (inter-beat interval, i.e. RR intervals) is delivered here, up to 4 values per data point. **This is the practical source for continuous HRV (RMSSD, SDNN, LF/HF)** — no finger contact needed, runs passively in the background. Filter on status flags (HR valid when status == 1; IBI valid when its status == 0). |
| `PPG_CONTINUOUS` | Raw | 25 Hz | `PpgSet` (green/IR/red channels) | Alternate raw signal if you want to build your own HR/HRV pipeline instead of using Samsung's processed IBI. |
| `ACCELEROMETER_CONTINUOUS` | Raw | 25 Hz | `AccelerometerSet` (x, y, z) | Useful for motion-artifact rejection on EDA/PPG. |
| `SKIN_TEMPERATURE_CONTINUOUS` | Processed | — | `SkinTemperatureSet` | Watch5+; not body temperature, skin + ambient only. |

**On-demand trackers** (foreground-only, max ~30 seconds per measurement, cannot run alongside a continuous tracker simultaneously):

| Tracker type | Raw/Processed | Rate | Key fields | Notes |
|---|---|---|---|---|
| `ECG_ON_DEMAND` | Raw | 500 Hz | `EcgSet.ECG_MV`, `EcgSet.LEAD_OFF` | **Not continuous.** Requires the app in the foreground and the user actively holding a finger on the watch's home key/bezel for the full ~30 seconds. `LEAD_OFF == 0` means electrodes are in contact (valid reading); `== 5` means no contact (discard). This is a real constraint on the thesis's "real-time continuous ECG" framing — see §8. |
| `PPG_ON_DEMAND` | Raw | 100 Hz | `PpgSet` | Higher rate than continuous PPG, same 30-second/foreground limits. |
| `SPO2_ON_DEMAND` | Processed | — | `Spo2Set` | Requires Health Platform v1.3.0+. |
| `BIA_ON_DEMAND` / `MF_BIA_ON_DEMAND` | Processed | — | `BiaSet` / `MfBiaSet` | Body composition; MF-BIA is Watch8+ only. Not relevant to the current feature set but available. |

General rule for on-demand types: use in the foreground only, one at a time, don't run a continuous tracker simultaneously (it will return invalid values while an on-demand measurement is in progress).

### B6. Start, receive, and stop tracking

```kotlin
val edaTracker = healthTrackingService.getHealthTracker(HealthTrackerType.EDA_CONTINUOUS)

val listener = object : HealthTracker.TrackerEventListener {
    override fun onDataReceived(list: MutableList<DataPoint>) {
        for (dp in list) {
            val conductance = dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE)
            val status = dp.getValue(ValueKey.EdaSet.STATUS)
            val ts = dp.getTimestamp()
            // hand off to your feature-extraction / transfer pipeline
        }
    }
    override fun onFlushCompleted() { /* a requested flush() finished */ }
    override fun onError(error: HealthTracker.TrackerError) { /* handle */ }
}

edaTracker.setEventListener(listener)   // starts tracking
// ...
edaTracker.unsetEventListener()          // stops tracking
```

Only one `TrackerEventListener` may be set per tracker type at a time. A recurring `SDK_POLICY_ERROR` means the app's policy hasn't been approved/registered yet — expected and bypassed while Health Platform developer mode is on (§A3).

### B7. Flushing batched data

When the watch display is off, Health Platform batches samples instead of delivering them one at a time, and delivers the batch once a buffer threshold is hit (Samsung's example: continuous heart rate normally delivers ~600 samples every 10 minutes when the screen is off). Call `.flush()` to force whatever has been collected so far to be delivered immediately:

```kotlin
heartRateTracker.flush()
```

Useful right before you're about to hand data off to the phone, so you're not waiting on the batching window.

---

## 5. Part C — Getting data from the watch to the phone (Wear OS Data Layer API)

This is Google's API, separate from Samsung's SDK, and it's how any Wear OS app talks to its paired phone companion. It only works phone↔watch on Android — it does not work if the watch is paired to an iPhone.

There are three client types; pick based on payload size and timing needs:

| Client | Best for | Behavior |
|---|---|---|
| `MessageClient` | Small, timely payloads (a batch of recent EDA/HR samples, a status ping) | Fire-and-forget one-way send (or request/response). Delivered only if both devices are currently connected — not buffered if disconnected. This is what Samsung's own official example (heart-rate transfer, see §6) uses. |
| `DataClient` (`DataItem` / `PutDataMapRequest`) | State that should eventually sync even if the phone is briefly out of range | Buffered and synced automatically once both sides reconnect. Non-urgent by default (system may delay up to 30 min); call `.setUrgent()` to push it through faster. Payload capped at 100 KB per item. |
| `ChannelClient` | Continuous/large streams — e.g. raw 500 Hz ECG for a 30-second capture, or a continuous EDA/PPG feed | Opens a bidirectional stream (input/output streams or file transfer) between the two nodes, not bound by the 100 KB `DataItem` cap. This is the right tool if you decide to stream raw waveform data rather than pre-extracted features. |

For this thesis's data volumes (EDA at 1 Hz, HR/IBI at 1 Hz), `MessageClient` sending periodic small JSON batches — exactly as in Samsung's own reference implementation — is almost certainly sufficient and simplest. Reach for `ChannelClient` only if you decide to transfer raw 500 Hz ECG waveforms wholesale rather than extracting RMSSD/SDNN on-watch first.

### C1. Minimal working pattern (adapted from Samsung's official example)

**Watch side — send:**

```kotlin
val nodes = capabilityClient.getAllCapabilities(CapabilityClient.FILTER_REACHABLE).await()
val targetNode = nodes.values.flatMap { it.nodes }.firstOrNull() // or filter by advertised capability

messageClient.sendMessage(
    targetNode.id,
    "/msg",                       // path — must match the phone-side IntentFilter
    jsonPayload.toByteArray()
).addOnSuccessListener { /* queued for delivery */ }
 .addOnFailureListener { /* not connected / failed */ }
```

**Phone side — manifest:**

```xml
<service
    android:name=".DataListenerService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
        <data android:host="*" android:pathPrefix="/msg" android:scheme="wear" />
    </intent-filter>
</service>
```

**Phone side — receive:**

```kotlin
class DataListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/msg") {
            val json = event.data.decodeToString()
            // parse, feed into the Random Forest feature pipeline
        }
    }
}
```

`CapabilityClient` is how the watch finds "which node is my phone" — the phone module advertises a capability string (e.g. `"wear"`) at build time via a `wear.xml` config, and the watch looks up reachable nodes advertising it.

Samsung published a complete, working sample doing exactly this pattern for heart-rate + IBI transfer (watch → phone, MessageClient), downloadable from their Heart Rate Data Transfer code lab — worth pulling down and using as the starting skeleton rather than building from scratch.

---

## 6. Best starting point: Samsung's own sample projects

Rather than building each piece from documentation alone, these official, working, downloadable projects cover almost everything needed:

- **EDA Tracking sample** (developer.samsung.com/codelab/health/electrodermal-activity.html) — full `EDA_CONTINUOUS` implementation with permission handling, tracker lifecycle, unit tests.
- **ECG Monitor sample** (developer.samsung.com/health/sensor/sample/ecg-monitor.html) — `ECG_ON_DEMAND`, lead-off detection, 30-second measurement flow.
- **Heart Rate Tracker sample** (developer.samsung.com/health/sensor/sample/hr-tracker.html) — `HEART_RATE_CONTINUOUS` + IBI extraction.
- **Heart Rate Data Transfer code lab** (developer.samsung.com/codelab/health/heart-rate-data-transfer.html) — the exact watch→phone pattern needed here, via `MessageClient`, complete with a paired watch+mobile module project. This is the closest existing template to the architecture this thesis needs.

Realistic path: start from the Heart Rate Data Transfer sample (gets you the two-module project + Data Layer plumbing for free), then add the `EDA_CONTINUOUS` tracker from the EDA sample into the same watch module, and add `ECG_ON_DEMAND` from the ECG sample as a third tracker triggered by a separate on-demand button.

---

## 7. Distribution and legal notes

- **Developer mode is sufficient for thesis work.** No Samsung approval is required to build, install, and run the app on your own watch with Health Platform developer mode on (§A3). This is not a limited trial — it's fully functional, just restricted to devices you've put in developer mode yourself.
- **Public distribution would require a partner request.** Only relevant if this app were ever going to be installed on watches outside your own hands (e.g. published, or given to study participants who don't want developer mode manually enabled on their own watch). The process: submit a partner request at developer.samsung.com/SHealth/business-partner, after which Samsung registers your app's package name + SHA-256 release-key signature. Not needed for a thesis project running on lab-controlled hardware.
- **Samsung's own data-use caveat, verbatim:** "Data measured by the Samsung Health Sensor SDK is for fitness and wellness information only, not for the diagnosis or treatment of any medical condition." Worth an explicit line in the thesis's limitations section, and possibly relevant to IRB framing since this is human-subjects physiological data collection.

---

## 8. Feasibility notes specific to this thesis

- **EDA is genuinely continuous and passive** — matches the proposal's "real-time, continuous" framing for that signal.
- **Raw ECG is not.** `ECG_ON_DEMAND` requires the participant to actively hold a finger on the watch bezel, foreground app, ~30 seconds per reading. It cannot run continuously in the background. If the proposal's real-time claim needs to hold for the ECG-derived features (RMSSD, SDNN, LF/HF, mean HR), the practical path is to compute those from **`HEART_RATE_CONTINUOUS`'s IBI stream** instead of R-peak-detecting the raw ECG waveform — IBI is delivered continuously and passively, with no finger contact. Raw `ECG_ON_DEMAND` can still be captured as periodic spot-checks (e.g. once per session) if a genuine ECG waveform is wanted for validation, but it shouldn't be the continuous-monitoring data source.
- **Suggested watch-app architecture:** a foreground service running `EDA_CONTINUOUS` and `HEART_RATE_CONTINUOUS` simultaneously (both are continuous types, so no on-demand conflict), periodically calling `flush()` and pushing batches to the phone via `MessageClient`, with `ECG_ON_DEMAND` available as a separate user-triggered action for spot ECG captures.
- Feature extraction (RMSSD/SDNN/LF-HF from IBI series; SCL/SCR from the EDA stream) can happen either on-watch before transfer or on-phone after receipt — on-phone is simpler to iterate on and keeps the watch app lightweight, at the cost of transferring slightly more raw data.

---

## Sources

- [Samsung Health Sensor SDK — Overview](https://developer.samsung.com/health/sensor/overview.html)
- [Measure Electrodermal Activity on Galaxy Watch (code lab)](https://developer.samsung.com/codelab/health/electrodermal-activity.html)
- [Health Sensor Data Specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html)
- [App creation process / partner request](https://developer.samsung.com/health/sensor/process.html)
- [ECG Monitor sample](https://developer.samsung.com/health/sensor/sample/ecg-monitor.html)
- [Connect Galaxy Watch with Android Studio](https://developer.samsung.com/health/sensor/guide/connect-watch.html)
- [Health Platform's developer mode](https://developer.samsung.com/health/sensor/guide/developer-mode.html)
- [Importing the SDK library (app module)](https://developer.samsung.com/health/sensor/guide/app-module.html)
- [Connecting with Health Platform](https://developer.samsung.com/health/sensor/guide/connect-health-platform.html)
- [Capability check](https://developer.samsung.com/health/sensor/guide/capability-check.html)
- [Permission request](https://developer.samsung.com/health/sensor/guide/permission-request.html)
- [Tracking data / flush()](https://developer.samsung.com/health/sensor/guide/tracking-data.html)
- [ValueKey API reference](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.html)
- [Measure and Transfer Heart Rate Data from Galaxy Watch to a Paired Android Phone](https://developer.samsung.com/health/blog/en/transfer-heart-rate-from-galaxy-watch-to-a-phone)
- [Android: Sync data items with the Data Layer API](https://developer.android.com/training/wearables/data/data-items)
- [Android: Choose a client type (Data Layer)](https://developer.android.com/training/wearables/data/client-types)
