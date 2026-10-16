"""
Validation-only script: pairs EACH cortisol ground-truth sample (day + clock
time) with the matching merged window from THAT SAME day (tight match, since
for synthetic data the days line up exactly -- real pilot data uses
diurnal_correlation.py's day-agnostic time-of-day matching instead, because
the real cortisol test and the real watch-wear days don't line up).

Not part of the shipped pipeline. Only exists to check whether a known,
designed relationship between cortisol and the wearable features is actually
recovered by straightforward correlation, before trusting the same method on
noisy real single-subject data.
"""
import argparse
import numpy as np
import pandas as pd


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--merged", required=True)
    ap.add_argument("--cortisol", required=True)
    ap.add_argument("--tz-offset-hours", type=float, required=True)
    ap.add_argument("--tolerance-min", type=float, default=15.0)
    ap.add_argument("--start-date", default="2026-11-01")
    args = ap.parse_args()

    merged = pd.read_csv(args.merged)
    cortisol = pd.read_csv(args.cortisol)

    day0 = pd.Timestamp(args.start_date)
    local_dt = pd.to_datetime(merged["window_start_ms"], unit="ms") + pd.Timedelta(hours=args.tz_offset_hours)
    merged["local_day"] = ((local_dt.dt.normalize() - day0).dt.days) + 1
    merged["local_minutes"] = local_dt.dt.hour * 60 + local_dt.dt.minute + local_dt.dt.second / 60.0
    # session_id is synth_dayN; local calendar day can roll over around midnight, so
    # trust the session's own day number instead of the wall-clock date for matching
    merged["session_day"] = merged["session_id"].str.extract(r"synth_day(\d+)").astype(int)

    feature_cols = ["eda_tonic_mean", "eda_phasic_mean", "eda_scr_count", "eda_mean",
                    "hrv_mean_hr_bpm", "hrv_rmssd"]

    def clock_to_minutes(hhmm):
        h, m = hhmm.split(":")
        return int(h) * 60 + int(m)

    rows = []
    for _, c in cortisol.iterrows():
        day_data = merged[merged["session_day"] == c["day"]]
        target_min = clock_to_minutes(c["clock_time"])
        delta = (day_data["local_minutes"] - target_min).abs()
        delta = np.minimum(delta, 1440 - delta)
        match = day_data[delta <= args.tolerance_min]
        row = {"day": c["day"], "label": c["label"], "clock_time": c["clock_time"],
               "cortisol_value": c["cortisol_value"], "n_windows_matched": len(match)}
        for f in feature_cols:
            row[f] = match[f].mean() if len(match) else np.nan
        rows.append(row)

    result = pd.DataFrame(rows)
    print(result.to_string(index=False))
    print()
    print(f"=== correlation: cortisol_value vs each feature (n={len(result)} day x label points) ===")
    for f in feature_cols:
        sub = result[["cortisol_value", f]].dropna()
        if len(sub) < 3:
            print(f"{f:18s}: n too small")
            continue
        pear = sub["cortisol_value"].corr(sub[f], method="pearson")
        spear = sub["cortisol_value"].corr(sub[f], method="spearman")
        print(f"{f:18s}: pearson={pear:+.3f}  spearman={spear:+.3f}  n={len(sub)}")


if __name__ == "__main__":
    main()
