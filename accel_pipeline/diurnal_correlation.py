"""
Diurnal correlation -- NOT part of the shipped classifier pipeline.

Matches wearable window features (EDA tonic/phasic/SCR, HR, HRV) to a set of
cortisol samples taken at specific clock times, by time-of-day (not by exact
calendar day), then correlates them.

Does NOT use generate_classify_synthetic_v2.py's _classify() or BASELINE_SEC --
no calm-start assumption, confirmed separately.

TIMEZONE: pass --tz as an IANA zone name (e.g. America/New_York), not a fixed
--tz-offset-hours. A fixed offset is wrong for any watch-wear data collected across
a US daylight-saving transition -- DST ends Sunday Nov 1, 2026, which falls inside
the planned November data-collection window, so a hardcoded -4 (EDT) would silently
mislabel every window after that date by an hour once clocks fall back to -5 (EST).
--tz-offset-hours still works for a single short session known not to cross a DST
boundary, but prints a loud warning -- --tz is the safe default for anything spanning
more than a few days.

STATISTICAL CAVEAT (read this before trusting a "pearson=0.8"-looking number):
averaging wearable features by cortisol clock-time label, across however many days
of data happen to fall in --merged, pools together two different things: (1) any
real day-to-day covariation between the wearable signal and cortisol, which is what
this script is trying to measure, and (2) the ordinary diurnal rhythm both cortisol
and most physiological signals share on their own (HR/EDA tend to be higher midday
than overnight; cortisol is too) -- a correlation computed on the RAW label means can
be large and statistically convincing while reflecting almost entirely (2), not (1).
This script now reports BOTH numbers for exactly that reason: "raw" (the original,
confounded, pooled-across-days number) and "residual" (each label's cross-day mean
subtracted from both series first, so what's left is only the day-to-day wiggle
around each label's own typical level -- the part that can actually indicate a real
link). Treat "raw" as descriptive context only; "residual" is the one that speaks to
whether the watch is tracking something beyond the shared daily rhythm.

Usage:
    python diurnal_correlation.py --merged a.csv b.csv c.csv \
        --cortisol cortisol_samples.csv --tz America/New_York \
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


def time_of_day_minutes_tz(ts_ms, tz_name):
    """DST-correct: converts each timestamp through its own UTC offset for tz_name,
    so a session spanning a DST transition gets the right local clock time on both
    sides of it -- unlike a single fixed --tz-offset-hours applied to every row."""
    dt = pd.to_datetime(ts_ms, unit="ms", utc=True).dt.tz_convert(tz_name)
    return dt.dt.hour * 60 + dt.dt.minute + dt.dt.second / 60.0


def time_of_day_minutes_fixed_offset(ts_ms, tz_offset_hours):
    dt = pd.to_datetime(ts_ms, unit="ms") + pd.Timedelta(hours=tz_offset_hours)
    return dt.dt.hour * 60 + dt.dt.minute + dt.dt.second / 60.0


def clock_to_minutes(hhmm):
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


def add_residual_columns(result, feature_cols):
    """For cortisol_value and every feature column, subtracts that column's own
    per-label mean (across whatever rows/days share the label) and adds the result
    as a '<col>_resid' column. With exactly one row per label (the common case here,
    since diurnal_correlation.py already averages all matched windows down to one row
    per label) every residual is identically 0 -- residual correlation is only
    informative once --merged spans enough distinct days that attach_cortisol-style
    per-day cortisol values exist per label; see validate_diurnal_correlation.py for
    that multi-day-per-label case, where this same demeaning is what separates a real
    relationship from shared diurnal shape."""
    out = result.copy()
    group_mean = out.groupby("label")[["cortisol_value"] + feature_cols].transform("mean")
    out["cortisol_value_resid"] = out["cortisol_value"] - group_mean["cortisol_value"]
    for c in feature_cols:
        out[f"{c}_resid"] = out[c] - group_mean[c]
    return out


def print_correlations(result, feature_cols, resid=False):
    suffix = "_resid" if resid else ""
    cort_col = f"cortisol_value{suffix}"
    label = "residual (within-label, day-to-day only)" if resid else "raw (pooled across days/labels)"
    print(f"\n=== correlation -- {label} ===")
    if resid:
        print("(subtracts each label's own cross-day mean from both series first -- see module docstring)")
    if len(result) < 4:
        print("(too few timepoints for a meaningful correlation -- this is expected with placeholder data)")
    for c in feature_cols:
        sub = result[[cort_col, f"{c}{suffix}"]].dropna()
        if len(sub) < 3:
            print(f"{c:18s}: n too small ({len(sub)})")
            continue
        pear = sub[cort_col].corr(sub[f"{c}{suffix}"], method="pearson")
        print(f"{c:18s}: pearson={pear:+.3f}  n={len(sub)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--merged", nargs="+", required=True, help="one or more merge_windows.py output CSVs")
    ap.add_argument("--cortisol", required=True, help="CSV: label, clock_time (HH:MM), cortisol_value")
    ap.add_argument("--tz", default=None,
                     help="IANA timezone name, e.g. America/New_York -- DST-correct, preferred over "
                          "--tz-offset-hours for anything spanning more than a few days")
    ap.add_argument("--tz-offset-hours", type=float, default=None,
                     help="legacy fixed-offset mode (e.g. -4 for EDT). WARNING: wrong for any data "
                          "collected across a DST transition (US DST ends Nov 1, 2026) -- prefer --tz")
    ap.add_argument("--tolerance-min", type=float, default=15.0, help="how close (minutes) a window's time-of-day must be to a cortisol sample's clock time to be matched")
    ap.add_argument("--out", default="diurnal_correlation_result.csv")
    args = ap.parse_args()

    if not args.tz and args.tz_offset_hours is None:
        ap.error("pass --tz America/New_York (preferred, DST-correct) or --tz-offset-hours (legacy, "
                  "DST-unsafe)")
    if args.tz_offset_hours is not None:
        print(f"WARNING: --tz-offset-hours {args.tz_offset_hours} is a FIXED offset -- it will silently "
              f"mislabel every window's time-of-day by an hour on whichever side of a DST transition "
              f"it does not match (US DST ends Nov 1, 2026). Use --tz America/New_York instead unless "
              f"you are certain this data never crosses a DST boundary.", file=sys.stderr)

    frames = []
    for f in args.merged:
        df = pd.read_csv(f)
        df["__source_file"] = f
        frames.append(df)
    merged = pd.concat(frames, ignore_index=True)
    if args.tz:
        merged["tod_min"] = time_of_day_minutes_tz(merged["window_start_ms"], args.tz)
    else:
        merged["tod_min"] = time_of_day_minutes_fixed_offset(merged["window_start_ms"], args.tz_offset_hours)

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
    result = add_residual_columns(result, feature_cols)
    result.to_csv(args.out, index=False)
    print(f"Wrote {len(result)} matched cortisol timepoint(s) -> {args.out}")
    print()
    print(result.to_string(index=False))

    print_correlations(result, feature_cols, resid=False)
    print_correlations(result, feature_cols, resid=True)


if __name__ == "__main__":
    main()
