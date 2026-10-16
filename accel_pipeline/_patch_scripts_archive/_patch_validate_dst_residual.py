p = "validate_diurnal_correlation.py"
src = open(p, encoding="utf-8").read()

new_src = '''"""
Validation-only script: pairs EACH cortisol ground-truth sample (day + clock
time) with the matching merged window from THAT SAME day (tight match, since
for synthetic data the days line up exactly -- real pilot data uses
diurnal_correlation.py's day-agnostic time-of-day matching instead, because
the real cortisol test and the real watch-wear days don't line up).

Not part of the shipped pipeline. Only exists to check whether a known,
designed relationship between cortisol and the wearable features is actually
recovered by straightforward correlation, before trusting the same method on
noisy real single-subject data.

STATISTICAL CAVEAT: this script correlates across however many (day, label)
points exist. Pooling many days at the SAME label together, then correlating raw
values, mixes two different things: genuine day-to-day covariation between
cortisol and the wearable signal (what this validation is actually meant to check),
and the ordinary diurnal rhythm that both series share on their own regardless of
any real link (e.g. HR/EDA are naturally different at 7am than at 8pm, and so is
cortisol -- that alone produces a strong RAW correlation with zero real link).
Confirmed by direct variance decomposition on this script's own synthetic data:
with cortisol_value itself.) The "residual" numbers below subtract each label's
own cross-day mean from both series before correlating, isolating the day-to-day
part -- treat "raw" as descriptive only and "residual" as the number that actually
speaks to a real relationship.

TIMEZONE: pass --tz as an IANA zone name (e.g. America/New_York) rather than a
fixed --tz-offset-hours -- see diurnal_correlation.py's docstring for why a fixed
offset silently breaks across the Nov 1, 2026 US DST transition.
"""
import argparse
import sys
import numpy as np
import pandas as pd


def local_datetime(ts_ms, tz_name, tz_offset_hours):
    if tz_name:
        return pd.to_datetime(ts_ms, unit="ms", utc=True).dt.tz_convert(tz_name)
    return pd.to_datetime(ts_ms, unit="ms") + pd.Timedelta(hours=tz_offset_hours)


def add_residual_columns(result, feature_cols):
    """Subtracts each label's own cross-day mean from cortisol_value and every
    feature column -- see module docstring. This is the demeaning step that
    separates genuine day-to-day covariation from shared diurnal shape; skipping
    it is exactly how an earlier version of this script's RAW correlation (e.g.
    pearson around -0.86/-0.90/+0.86 on three features) collapsed to +0.37/+0.04/
    +0.04 once residuals were used instead -- confirming the raw numbers were
    almost entirely the shared diurnal rhythm, not a real wearable-cortisol link."""
    out = result.copy()
    group_mean = out.groupby("label")[["cortisol_value"] + feature_cols].transform("mean")
    out["cortisol_value_resid"] = out["cortisol_value"] - group_mean["cortisol_value"]
    for c in feature_cols:
        out[f"{c}_resid"] = out[c] - group_mean[c]
    return out


def print_correlations(result, feature_cols, resid):
    suffix = "_resid" if resid else ""
    cort_col = f"cortisol_value{suffix}"
    kind = "residual (within-label, day-to-day only)" if resid else "raw (pooled across days)"
    print(f"\\n=== correlation -- {kind} (n={len(result)} day x label points) ===")
    for f in feature_cols:
        sub = result[[cort_col, f"{f}{suffix}"]].dropna()
        if len(sub) < 3:
            print(f"{f:18s}: n too small")
            continue
        pear = sub[cort_col].corr(sub[f"{f}{suffix}"], method="pearson")
        spear = sub[cort_col].corr(sub[f"{f}{suffix}"], method="spearman")
        print(f"{f:18s}: pearson={pear:+.3f}  spearman={spear:+.3f}  n={len(sub)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--merged", required=True)
    ap.add_argument("--cortisol", required=True)
    ap.add_argument("--tz", default=None, help="IANA timezone name, e.g. America/New_York (preferred, DST-correct)")
    ap.add_argument("--tz-offset-hours", type=float, default=None, help="legacy fixed-offset mode; see docstring")
    ap.add_argument("--tolerance-min", type=float, default=15.0)
    ap.add_argument("--start-date", default="2026-11-01")
    args = ap.parse_args()

    if not args.tz and args.tz_offset_hours is None:
        ap.error("pass --tz America/New_York (preferred) or --tz-offset-hours (legacy, DST-unsafe)")
    if args.tz_offset_hours is not None:
        print(f"WARNING: --tz-offset-hours {args.tz_offset_hours} is DST-unsafe -- see docstring.",
              file=sys.stderr)

    merged = pd.read_csv(args.merged)
    cortisol = pd.read_csv(args.cortisol)

    day0 = pd.Timestamp(args.start_date)
    local_dt = local_datetime(merged["window_start_ms"], args.tz, args.tz_offset_hours)
    local_dt_naive = local_dt.dt.tz_localize(None) if getattr(local_dt.dt, "tz", None) is not None else local_dt
    merged["local_day"] = ((local_dt_naive.dt.normalize() - day0).dt.days) + 1
    merged["local_minutes"] = local_dt_naive.dt.hour * 60 + local_dt_naive.dt.minute + local_dt_naive.dt.second / 60.0
    # session_id is synth_dayN; local calendar day can roll over around midnight, so
    # trust the session's own day number instead of the wall-clock date for matching
    merged["session_day"] = merged["session_id"].str.extract(r"synth_day(\\d+)").astype(int)

    feature_cols = ["eda_tonic_mean", "eda_phasic_mean", "eda_scr_count", "eda_mean",
                    "hrv_mean_hr_bpm", "hrv_rmssd"]
    feature_cols = [c for c in feature_cols if c in merged.columns]

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
    result = add_residual_columns(result, feature_cols)
    print(result.to_string(index=False))

    print_correlations(result, feature_cols, resid=False)
    print_correlations(result, feature_cols, resid=True)


if __name__ == "__main__":
    main()
'''
open(p, "w", encoding="utf-8").write(new_src)
print("validate_diurnal_correlation.py rewritten, size:", len(new_src.encode()))
