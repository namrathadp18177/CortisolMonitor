"""
Standalone helper -- NOT part of the shipped pipeline, just a one-off script to turn
merged_real_new.csv into a plain classification table for Nami's thesis meeting.

Applies generate_classify_synthetic_v2.py's real, shipped _classify() (the
multimodal-corroboration rule) to the real Oct 6 merged window table, and also
reconstructs what the OLD (pre-fix) rule would have predicted on the same data, so the
two can sit side by side in one table instead of a chart.

Display labels are renamed here, at output time only, for clarity to a non-technical
audience -- "physical" (from the classifier's internal motion-gate branch, which fires
on ANY detected movement, not necessarily real exertion) reads as "physical stress" to
anyone unfamiliar with the code, which overstates what a brief roll-over in bed means.
Renamed to "physical_activity" so it doesn't imply "stress" for trivial movement.
"psychological" genuinely IS evidence-gated (requires HR/HRV corroboration, see
generate_classify_synthetic_v2.py's HR_CORROBORATION_FRAC comment), so it's renamed to
the fuller "psychological_stress" to make that explicit rather than ambiguous.
"rest" and "non_stress_arousal" were already unambiguous and are left as-is. This is a
DISPLAY-ONLY rename -- the underlying classifier code, its internal label strings, the
synthetic generator, and the regression tests are untouched, so nothing already
validated this session is at risk; it only affects how labels are shown in these two
output CSVs.

"Confirmed sleep" ground truth (2:39am-9:55am, ~7.2667h) is taken as the first 7.2667
hours of the session, since the recording was started right at bedtime (same
assumption used earlier in this project for the before/after comparison).

Usage (run from the accel_pipeline folder, with generate_classify_synthetic_v2.py and
merged_real_new.csv both present):
    python classify_real_data.py
Writes:
    real_windows_classified_v3.csv   -- one row per 30s window, full detail
    real_classification_summary_v3.csv -- compact counts table for the slide
"""
import pandas as pd

from generate_classify_synthetic_v2 import (
    _classify, BASELINE_SEC, HR_ELEVATED_THRESH_BPM, EDA_ELEVATED_THRESH_US,
    MIN_PERSIST_WINDOWS,
)

SLEEP_HOURS = 7.2667  # 2:39am-9:55am

# Display-only rename, applied after classification -- see module docstring above.
DISPLAY_LABEL = {
    "rest": "rest",
    "physical": "physical_activity",
    "psychological": "psychological_stress",
    "non_stress_arousal": "non_stress_arousal",
}

merged = pd.read_csv("merged_real_new.csv")

# --- new (fixed) rule, via the actual shipped function ---
merged = merged.reset_index(drop=True)
pred_df = _classify(merged).reset_index(drop=True)
merged["new_predicted_label"] = pred_df["predicted_phase"].map(DISPLAY_LABEL)

# --- old (pre-fix) rule, reconstructed: same elevated/persistence/motion logic, but
# ANY persistent non-moving elevation = "psychological", no corroboration check, no
# "non_stress_arousal" label (it didn't exist yet) ---
old_preds = []
for session_id, sess in merged.groupby("session_id"):
    sess = sess.sort_values("window_start_ms")
    baseline_mask = sess["window_start_ms"] < (sess["window_start_ms"].min() + BASELINE_SEC * 1000)
    base_hr = sess.loc[baseline_mask, "hrv_mean_hr_bpm"].mean()
    base_eda = sess.loc[baseline_mask, "eda_mean"].mean()
    hr_dev = (sess["hrv_mean_hr_bpm"] - base_hr).fillna(0.0)
    eda_dev = (sess["eda_mean"] - base_eda).fillna(0.0)
    is_moving = sess["accel_any_motion_flagged"].fillna(False).astype(bool)
    elevated_raw = (hr_dev > HR_ELEVATED_THRESH_BPM) | (eda_dev > EDA_ELEVATED_THRESH_US)
    run_id = (elevated_raw != elevated_raw.shift()).cumsum()
    run_len = elevated_raw.groupby(run_id).transform("size")
    persistent_elevated = elevated_raw & (run_len >= MIN_PERSIST_WINDOWS)
    for idx, pe, mv in zip(sess.index, persistent_elevated, is_moving):
        if not pe:
            old_preds.append((idx, "rest"))
        elif mv:
            old_preds.append((idx, "physical"))
        else:
            old_preds.append((idx, "psychological"))
old_pred_series = pd.Series(dict(old_preds)).reindex(merged.index)
merged["old_predicted_label"] = old_pred_series.map(DISPLAY_LABEL)

# --- ground truth: confirmed sleep = first SLEEP_HOURS of the session ---
t0 = merged["window_start_ms"].min()
merged["is_confirmed_sleep"] = merged["window_start_ms"] < (t0 + SLEEP_HOURS * 3_600_000)

# readable local-elapsed time for the table, plus actual local clock time.
# Raw timestamps are UTC epoch ms; the session was recorded in Cincinnati, OH, which
# was on Eastern Daylight Time (UTC-4) on Oct 6 -- confirmed against the known sleep
# ground truth (session started 2026-10-06 06:39:17 UTC = 2:39:17am local, matching
# the stated sleep-onset time exactly).
merged["elapsed_min"] = ((merged["window_start_ms"] - t0) / 60_000).round(1)
LOCAL_OFFSET_HOURS = -4
merged["time"] = (
    pd.to_datetime(merged["window_start_ms"], unit="ms")
    + pd.Timedelta(hours=LOCAL_OFFSET_HOURS)
).dt.strftime("%Y-%m-%d %I:%M:%S %p")

# --- per-window detail table ---
cols = ["session_id", "time", "elapsed_min", "is_confirmed_sleep",
        "hrv_mean_hr_bpm", "hrv_rmssd", "hrv_low_confidence",
        "eda_mean", "eda_tonic_mean", "eda_phasic_mean", "eda_scr_count",
        "accel_any_motion_flagged",
        "old_predicted_label", "new_predicted_label"]
detail = merged[cols].copy()
detail.to_csv("real_windows_classified_v3.csv", index=False)
print(f"Wrote {len(detail)} rows -> real_windows_classified_v3.csv")

# --- summary table for the slide ---
display_labels = ["rest", "physical_activity", "psychological_stress", "non_stress_arousal"]
summary_rows = []
for sleep_flag, label in [(True, "During confirmed sleep"), (False, "Rest of the day (awake)")]:
    sub = merged[merged["is_confirmed_sleep"] == sleep_flag]
    n = len(sub)
    for rule_col, rule_name in [("old_predicted_label", "Old rule"), ("new_predicted_label", "New rule")]:
        counts = sub[rule_col].value_counts()
        row = {"Period": label, "Rule": rule_name, "n_windows": n}
        for lab in display_labels:
            row[lab] = int(counts.get(lab, 0))
        row["psychological_stress_pct"] = round(100 * row["psychological_stress"] / n, 1) if n else 0.0
        summary_rows.append(row)
summary = pd.DataFrame(summary_rows)
summary.to_csv("real_classification_summary_v3.csv", index=False)
print(f"Wrote -> real_classification_summary_v3.csv")
print()
print(summary.to_string(index=False))
