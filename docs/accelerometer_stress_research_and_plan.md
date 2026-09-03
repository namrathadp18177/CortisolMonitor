# Accelerometer & Stress Detection — Research Summary + Continuation Plan

*CortisolMonitor / Galaxy Watch8 project — August 2026*

Your stated goal: correlate the wearable signals (HRV, EDA, skin temperature, accelerometer) against your optical cortisol readings. Your status: data-collection infrastructure is finished, participant collection hasn't started yet. Everything below is written for that specific situation.

## Part 1 — What the research actually says about accelerometer data and stress

The honest headline finding, straight from the literature, is that **accelerometer data is a weak stress signal on its own, but a genuinely important supporting signal** — just not in the way "detect stress from movement" implies. Its real value in a study like yours is almost entirely as a confound filter and data-quality gate, not as a primary predictor.

### 1. Accelerometer alone is a mediocre stress classifier

The WESAD study — the standard multimodal benchmark dataset for wearable stress detection (wrist + chest, includes accelerometer, EDA, HR, skin temperature) — tested motion-only features explicitly. Using only accelerometer features (mean/STD per axis, peak frequency), binary stress-vs-not classification reached only 64–74% accuracy, compared to 79–89% using physiological signals (EDA, HR) alone. For the harder three-class problem it was 53–57% vs 54–72%. The authors' own conclusion: "using only motion-based features leads to considerably lower classification scores compared to results obtained using physiological features." So if the question is "can I detect stress directly from how someone is moving," the answer from existing research is: not reliably, at least not from simple accelerometer statistics.

### 2. Its real job: separating "stressed" from "physically active" (both raise HR)

This is the finding most relevant to you. A 2025 *Scientific Data* paper explicitly built a dataset combining acute psychological stress conditions AND exercise conditions on the same wrist device (Empatica E4: PPG, EDA, accelerometer, skin temperature), because stress and physical activity both elevate heart rate and can look identical to a classifier that only sees HR. Their approach used the full signal set together — HR, HRV, EDA, skin temperature, *and* motion — to tell the two apart, reaching 93% accuracy distinguishing stress from rest. The accelerometer's contribution wasn't "detect stress" — it was "rule out that an HR spike is just someone walking, not something autonomic-nervous-system-driven."

This matters directly for your correlation-with-cortisol design: if a participant is walking during a cortisol sample window, their HRV will drop and HR will rise for reasons that have nothing to do with cortisol. Without the accelerometer, that session becomes a confound you can't detect after the fact.

### 3. Motion is also a literal noise source in your other signals

Systematic reviews of wearable stress-monitoring note that motion artifacts degrade the *other* channels you're collecting — this is true for EDA and PPG-derived HR, and it's part of why accelerometer is bundled into essentially every serious multimodal stress wearable. Practically: a burst of accelerometer magnitude co-occurring with an EDA or HR reading is often a signal-quality problem, not a genuine physiological event, and should be flagged before you trust that window's data.

### 4. A secondary, thesis-relevant use: sleep/wake and diurnal timing

Because you're correlating against cortisol specifically, it's worth knowing that actigraphy (accelerometer-based sleep/wake detection) is a well-established technique for identifying sleep periods and, more importantly, **wake time** — which matters because cortisol has a strong diurnal rhythm and the Cortisol Awakening Response (CAR) is timing-sensitive (typically sampled at wake, +30min, +45min). If your protocol involves any morning or awakening-related cortisol sampling, the accelerometer can help you objectively confirm when a participant actually woke up, rather than relying on self-report, which is a common source of noise in CAR studies.

### Bottom line for your pipeline

Use the accelerometer as: (a) an activity-level covariate in your correlation/regression model — control for it rather than expect it to independently predict cortisol; (b) a data-quality filter — exclude or flag high-motion windows in your HRV/EDA correlation analysis; (c) optionally, a wake-time/activity-context signal if timing relative to cortisol sampling matters in your protocol. Don't build a separate "accelerometer detects stress" analysis — the evidence says that arm would underperform and isn't where the accelerometer earns its place in the study.

**Sources:**
- [Introducing WESAD, a Multimodal Dataset for Wearable Stress and Affect Detection (ICMI 2018, full PDF)](https://ubi29.informatik.uni-siegen.de/usi/pdf/ubi_icmi2018.pdf)
- [Wearable Physiological Signals under Acute Stress and Exercise Conditions — Scientific Data (Nature)](https://www.nature.com/articles/s41597-025-04845-9)
- [Detection and monitoring of stress using wearables: a systematic review — Frontiers in Computer Science](https://www.frontiersin.org/journals/computer-science/articles/10.3389/fcomp.2024.1478851/full)
- [Extending Stress Detection Reproducibility to Consumer Wearable Sensors (arXiv, 2025)](https://arxiv.org/html/2505.05694v1)
- [Detecting sleep using heart rate and motion data from multisensor consumer-grade wearables — SLEEP (Oxford Academic)](https://academic.oup.com/sleep/article/43/7/zsaa045/5811697)
- [Monitoring stress with a wrist device using context — ScienceDirect](https://www.sciencedirect.com/science/article/pii/S1532046417301855)

## Part 2 — Continuation plan (correlation-with-cortisol study, pre-collection stage)

This is staged so each phase produces something you can act on before committing to the next — you shouldn't write the full analysis pipeline before running a pilot, and you shouldn't recruit real participants before the pilot proves the pipeline end-to-end.

### Stage 1 — Lock down the protocol before recruiting anyone

A few decisions now will save you from unusable data later:

- **Timing alignment.** Cortisol response lags the autonomic nervous system response by roughly 15–30 minutes (cortisol has to be synthesized and released, unlike HR/EDA which change within seconds). Decide now what wearable window you'll correlate against each cortisol sample — e.g. "the 10-minute wearable window ending 20 minutes before each cortisol draw" — rather than deciding it after data collection when it becomes a post-hoc justification problem.
- **Sampling schedule.** How many cortisol samples per session, and at what intervals? This determines how many wearable feature-windows you'll need per session and shapes your statistical power.
- **Session context logging.** Decide what you'll record about what the participant was doing (sitting, walking, eating, caffeine) — self-report timestamps are cheap to collect now and become your ground truth for interpreting accelerometer-flagged windows later.

### Stage 2 — Run a pilot on yourself (and 1–2 others) before real recruitment

You already have the infrastructure; the pilot's job is to prove the *whole chain* works, not just that each sensor streams:

1. Run a full session start-to-finish: watch streaming → phone storage → CSV export → HRV computation, alongside an actual cortisol reading.
2. Check that timestamps across all four vitals streams and the cortisol reading are actually comparable (same clock source, correctly converted time zones — a classic silent bug in multi-device studies).
3. Confirm you're getting enough beats for RMSSD/SDNN to clear your 30-beat confidence floor in a realistic session length, not just in a long test session.
4. Deliberately include a short walking segment in one pilot session so you have a real example of what accelerometer-flagged motion artifacts look like in your own EDA/HR data — useful for Stage 4 below.

### Stage 3 — Build the offline feature-extraction pipeline (Python, off-device)

Your on-device HRV calculator is good for real-time display, but for the actual thesis analysis you'll want a reproducible offline pipeline (Python + pandas is the standard choice here) that takes your exported CSVs and produces one row per analysis window with:

- HRV: RMSSD, SDNN, pNN50, mean HR, mean IBI (recompute from the raw per-second CSV, same formulas as your on-device `HrvCalculator`, so the two stay consistent as a sanity check)
- EDA: mean skin conductance, and ideally a tonic/phasic decomposition if your timeline allows — even just mean + peak count is a reasonable starting feature set
- Skin temperature: mean and slope over the window
- Accelerometer: vector magnitude (√(x²+y²+z²)) mean and variance per window, plus a simple "% of window flagged as active" threshold
- The matched cortisol value for that window, per your Stage 1 alignment rule

### Stage 4 — Data quality pass, using the accelerometer as the filter

Before any correlation: flag windows where accelerometer variance/magnitude exceeds a threshold you set from your pilot data (Stage 2's walking segment gives you a real reference point). Report how many windows get excluded — that number itself is a legitimate methods-section detail, not just cleanup.

### Stage 5 — Statistical analysis

With windows matched to cortisol values: start with simple correlation (Pearson/Spearman) between each wearable feature and cortisol, then move to a regression model that includes accelerometer-derived activity level as a covariate rather than a predictor of interest — this directly operationalizes the "control for confounds" role from Part 1. If you end up with multiple sessions per participant, a mixed-effects model (participant as random effect) is the standard way to avoid pseudo-replication inflating your significance.

### Stage 6 — Revisit the Firebase-sync decision once you scale past yourself

You deliberately deferred Firebase sync while building the pipeline solo. Once you're running multiple participants, you'll likely want centralized storage rather than manually pulling CSVs off each phone — worth revisiting that decision specifically at the point you move from pilot to real recruitment, not before.

### Stage 7 — Write-up

Document limitations honestly: wrist-PPG-derived HRV has known accuracy limits vs. chest-strap ECG (worth a citation-backed caveat in your methods section), motion artifacts were mitigated but not eliminated, and cortisol's own lag/diurnal variability limits how tightly any autonomic signal can track it minute-to-minute.

---

**Suggested immediate next step:** finalize the Stage 1 timing-alignment rule (what wearable window maps to each cortisol sample) since it's the one decision that touches every later stage, then run the Stage 2 pilot before recruiting.

---

**Update (2026-09-01):** `accel_pipeline/accel_windowing.py` implements the Stage 3/4 windowing described above -- reads exported `accel_*.csv` files, bins samples into 5-second windows per session, and outputs vector-magnitude mean/std plus a `motion_flag` (and a `low_confidence` coverage flag for sparse windows). Run `python accel_windowing.py --demo` for a synthetic sanity check; no real watch session has been exported yet, so `--threshold-std-g` (default 0.05g) is still a placeholder -- recalibrate it against the Stage 2 pilot's walking segment once that pilot is run, then run the script for real against that session's `accel_*.csv` export.
