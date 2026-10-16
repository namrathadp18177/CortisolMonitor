"""
Diurnal correlation -- NOT part of the shipped classifier pipeline.

Matches wearable window features (EDA tonic/phasic/SCR, HR, HRV) to a set of
cortisol samples taken at specific clock times, by time-of-day (not by exact
calendar day), then correlates them.

Does NOT use generate_classify_synthetic_v2.py's _classify() or BASELINE_SEC --
no calm-start assumption, confirmed separately.

Usage:
    python diurnal_correlation.py --merged a.csv b.csv c.csv \
        --cortisol cortisol_samples.csv --tz-offset-hours -4 \
        --tolerance-min 15

cortisol_samples.csv columns:
    label, clock_time, cortisol_value
    e.g.  wake,        06:30,  0.35
          wake_plus30, 07:00,  0.42
          midday,      12:30,  0.11
          evening,     20:00,  0.08

Merged window CSV(s): output of merge_windows.py (one or more files/sessions;
multiple days are just concatenated here, matched by time-of-day only).
"""
import argparse
import sys
import pandas as pd
import numpy as np


def time_of_day_minutes(ts_ms, tz_offset_hours):
    dt = pd.to_datetime(ts_ms, unit="ms") + pd.Timedelta(hours=tz_offset_hours)
    return dt.dt.hour * 60 + dt.dt.minute + dt.dt.second / 60.0


def clock_to_minutes(hhmm):
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--merged", nargs="+", required=True, help="one or more merge_windows.py output CSVs")
    ap.add_argument("--cortisol", required=True, help="CSV: label, clock_time (HH:MM), cortisol_value")
    ap.add_argument("--tz-offset-hours", type=float, required=True, help="e.g. -4 for EDT; must be passed explicitly, never assumed")
    ap.add_argument("--tolerance-min", type=float, default=15.0, help="how close (minutes) a window's time-of-day must be to a cortisol sample's clock time to be matched")
    ap.add_argument("--out", default="diurnal_correlation_result.csv")
    args = ap.parse_args()

    frames = []
    for f in args.merged:
        df = pd.read_csv(f)
        df["__source_file"] = f
        frames.append(df)
    merged = pd.concat(frames, ignore_index=True)
    merged["tod_min"] = time_of_day_minutes(merged["window_start_ms"], args.tz_offset_hours)

    cortisol = pd.read_csv(args.cortisol)
    cortisol["tod_min"] = cortisol["clock_time"].apply(clock_to_minutes)

    feature_cols = ["eda_tonic_mean", "eda_phasic_mean", "eda_scr_count", "eda_mean",
                    "hrv_mean_hr_bpm", "hrv_rmssd"]
    feature_cols = [c for c in feature_cols if c in merged.columns]

    rows = []
    for _, crow in cortisol.iterrows():
        delta = (merged["tod_min"] - crow["tod_min"]).abs()
        delta = np.minimum(delta, 1440 - delta)  # wrap around midnight
        match = merged[delta <= args.tolerance_min]
        row = {"label": crow["label"], "clock_time": crow["clock_time"],
               "cortisol_value": crow["cortisol_value"], "n_windows_matched": len(match)}
        for c in feature_cols:
            row[c] = match[c].mean() if len(match) else np.nan
        rows.append(row)

    result = pd.DataFrame(rows)
    result.to_csv(args.out, index=False)
    print(f"Wrote {len(result)} matched cortisol timepoint(s) -> {args.out}")
    print()
    print(result.to_string(index=False))

    print()
    print("=== correlation: cortisol_value vs each feature (n =", len(result), ") ===")
    if len(result) < 4:
        print("(too few timepoints for a meaningful correlation -- this is expected with placeholder data)")
    for c in feature_cols:
        sub = result[["cortisol_value", c]].dropna()
        if len(sub) < 3:
            print(f"{c:18s}: n too small ({len(sub)})")
            continue
        pear = sub["cortisol_value"].corr(sub[c], method="pearson")
        print(f"{c:18s}: pearson={pear:+.3f}  n={len(sub)}")


if __name__ == "__main__":
    main()
