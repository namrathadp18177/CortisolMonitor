"""
Merge accel + HRV + EDA + skin-temp (+ optionally cortisol) windows into one row per
analysis window.

Cortisol Monitor thesis -- Stage 3/4/5 join step of
docs/accelerometer_stress_research_and_plan.md. accel_windowing.py, hrv_eda_windowing.py
and skin_temp_windowing.py each produce their own windows table for one signal; per
those scripts' own design note ("that alignment ... is meant to happen in a later
pandas merge_asof step"), this script does that join.

THE PRIMARY GRID
-----------------
HRV windows are the primary grid (--primary lets you pick a different one) -- every
output row is one HRV window (session_id, window_start_ms, window_end_ms from that
table), with the other signals attached to it. HRV/EDA/skin-temp all default to the
same window_sec (30s), so for those two the join is a straightforward per-session
merge_asof: nearest EDA/skin-temp window_start_ms to each HRV window_start_ms, within
--tolerance-sec (default: one primary window's worth).

ACCELEROMETER IS HANDLED DIFFERENTLY
-------------------------------------
accel_windowing.py's default window (5s) is much finer than the primary 30s grid --
six accel windows per HRV window, typically. A nearest-single-window merge_asof would
silently throw away five of those six windows, including exactly the kind of
mid-window motion burst Stage 4 wants caught for its data-quality exclusion filter. So
instead, for accel this script aggregates every accel window whose [window_start_ms,
window_end_ms) overlaps the primary window's own [window_start_ms, window_end_ms) into:
  accel_n_subwindows, accel_pct_motion_flagged, accel_any_motion_flagged,
  accel_mean_mag_g, accel_pct_low_confidence
This is the number Stage 4 actually calls for ("flag windows where accelerometer
variance/magnitude exceeds a threshold... using the accelerometer as the filter") --
a single nearest 5s sample would understate how much of the 30s window was in motion.

CORTISOL (OPTIONAL, --cortisol)
--------------------------------
Per Stage 1's timing-alignment rule (cortisol lags the autonomic response by ~15-30
min; the thesis's TSST protocol draws roughly every 20 min), --cortisol attaches, to
each primary window, the NEXT cortisol sample drawn at least --cortisol-lag-min
(default 20) minutes after that window's end -- i.e. "this window's HRV/EDA/skin-temp/
accel features are being used to predict a cortisol value drawn >= lag minutes later,"
which is the actual regression setup Stage 5 describes. Expects a CSV with columns
session_id, participant_email, sample_time_ms, cortisol_value.

Usage:
    python merge_windows.py --hrv hr_windows.csv --eda eda_windows.csv
        --accel accel_windows.csv --skin-temp skin_temp_windows.csv
        [--cortisol cortisol_samples.csv] [--cortisol-lag-min 20]
        [--tolerance-sec <primary window_sec>] [--out merged_windows.csv]

    python merge_windows.py --demo
        Runs accel_windowing.py/hrv_eda_windowing.py/skin_temp_windowing.py's own
        --demo generators in-process and merges the results, so the full Stage
        3/4/5 join can be sanity-checked without any real watch export. See also
        make_stress_test_fixture.py, which exercises this merge against messier
        synthetic data (gaps, a session boundary, batching, duplicate/out-of-order
        rows) closer to what a real pilot export will look like.
"""

import argparse
import os

import numpy as np
import pandas as pd

import accel_windowing
import hrv_eda_windowing
import skin_temp_windowing

TOLERANCE_SEC_DEFAULT = None  # None -> inferred as one primary-window's worth (see main())
CORTISOL_LAG_MIN_DEFAULT = 20.0


# ---------------------------------------------------------------------------
# Accel: interval-overlap aggregation onto the primary grid (see module docstring)
# ---------------------------------------------------------------------------

def aggregate_accel(primary, accel):
    """For each primary window, aggregates every accel sub-window whose interval
    overlaps it. Returns primary's join keys plus accel_* summary columns -- rows
    with no overlapping accel data at all get accel_n_subwindows = 0 and NaN
    summary columns (distinct from 'checked and found no motion')."""
    if accel is None or len(accel) == 0:
        out = primary[["session_id", "window_start_ms"]].copy()
        for col in ["accel_n_subwindows", "accel_pct_motion_flagged", "accel_any_motion_flagged",
                    "accel_mean_mag_g", "accel_pct_low_confidence"]:
            out[col] = np.nan
        return out

    merged = primary[["session_id", "window_start_ms", "window_end_ms"]].merge(
        accel, on="session_id", suffixes=("", "_accel"))
    overlap = merged[
        (merged["window_start_ms_accel"] < merged["window_end_ms"]) &
        (merged["window_end_ms_accel"] > merged["window_start_ms"])
    ]

    agg = overlap.groupby(["session_id", "window_start_ms"]).agg(
        accel_n_subwindows=("motion_flag", "size"),
        accel_pct_motion_flagged=("motion_flag", "mean"),
        accel_any_motion_flagged=("motion_flag", "any"),
        accel_mean_mag_g=("mag_mean_g", "mean"),
        accel_pct_low_confidence=("low_confidence", "mean"),
    ).reset_index()
    agg["accel_pct_motion_flagged"] = (agg["accel_pct_motion_flagged"] * 100).round(1)
    agg["accel_pct_low_confidence"] = (agg["accel_pct_low_confidence"] * 100).round(1)
    agg["accel_mean_mag_g"] = agg["accel_mean_mag_g"].round(4)

    keys = primary[["session_id", "window_start_ms"]].copy()
    out = keys.merge(agg, on=["session_id", "window_start_ms"], how="left")
    out["accel_n_subwindows"] = out["accel_n_subwindows"].fillna(0).astype(int)
    out["accel_any_motion_flagged"] = out["accel_any_motion_flagged"].astype("boolean")
    return out


# ---------------------------------------------------------------------------
# EDA / skin-temp: nearest-window merge_asof onto the primary grid
# ---------------------------------------------------------------------------

def asof_join(primary, other, prefix, feature_cols, tolerance_ms):
    """merge_asof's `by` groups by session_id (exact match) while `on` finds the
    nearest window_start_ms within tolerance_ms -- the alignment approach the
    windowing scripts' own docstrings call for. Rows with no match within
    tolerance get NaN feature columns (distinct from 'matched and value is 0').

    feature_cols may include columns `other` doesn't actually have -- e.g. an
    eda_windows.csv written before hrv_eda_windowing.py grew its
    eda_tonic_*/eda_phasic_*/eda_scr_* columns. Those are filled with NaN here
    rather than raising, so an older windows file still merges; it just won't carry
    the newer features."""
    keys = primary[["session_id", "window_start_ms"]].copy()
    if other is None or len(other) == 0:
        for col in feature_cols:
            keys[f"{prefix}_{col}"] = np.nan
        keys[f"{prefix}_window_start_ms"] = np.nan
        return keys

    missing_cols = [c for c in feature_cols if c not in other.columns]
    present_cols = [c for c in feature_cols if c in other.columns]

    left = keys.sort_values(["window_start_ms"]).reset_index(drop=True)
    right = other[["session_id", "window_start_ms"] + present_cols].copy()
    right = right.sort_values(["window_start_ms"]).reset_index(drop=True)
    # Don't double-prefix a column that already carries the prefix (e.g. EDA's own
    # eda_mean/eda_std/eda_peak_count/eda_slope columns) -- only bare names like
    # n_samples/coverage/low_confidence get the prefix added.
    right = right.rename(columns={
        c: (c if c.startswith(f"{prefix}_") else f"{prefix}_{c}") for c in present_cols
    })
    right = right.rename(columns={"window_start_ms": f"{prefix}_window_start_ms"})

    joined = pd.merge_asof(
        left, right,
        left_on="window_start_ms", right_on=f"{prefix}_window_start_ms",
        by="session_id", direction="nearest", tolerance=tolerance_ms,
    )
    for col in missing_cols:
        joined[col if col.startswith(f"{prefix}_") else f"{prefix}_{col}"] = np.nan
    return joined


# ---------------------------------------------------------------------------
# Cortisol: next sample drawn >= lag minutes after the window ends
# ---------------------------------------------------------------------------

def attach_cortisol(primary, cortisol, lag_min):
    lag_ms = lag_min * 60_000.0
    keys = primary[["session_id", "window_start_ms", "window_end_ms"]].copy()
    keys["_eligible_from_ms"] = (keys["window_end_ms"] + lag_ms).astype("float64")

    left = keys.sort_values("_eligible_from_ms").reset_index(drop=True)
    right = cortisol[["session_id", "sample_time_ms", "cortisol_value"]].copy()
    right["sample_time_ms"] = right["sample_time_ms"].astype("float64")
    right = right.sort_values("sample_time_ms").reset_index(drop=True)

    joined = pd.merge_asof(
        left, right,
        left_on="_eligible_from_ms", right_on="sample_time_ms",
        by="session_id", direction="forward",
    )
    joined = joined.rename(columns={"sample_time_ms": "cortisol_sample_time_ms"})
    joined["cortisol_lag_min_actual"] = (
        (joined["cortisol_sample_time_ms"] - joined["window_end_ms"]) / 60_000.0
    ).round(2)
    return joined[["session_id", "window_start_ms", "cortisol_sample_time_ms",
                    "cortisol_value", "cortisol_lag_min_actual"]]


# ---------------------------------------------------------------------------
# Top-level merge
# ---------------------------------------------------------------------------

HRV_FEATURE_COLS = ["n_hr_samples", "expected_hr_samples", "sample_coverage", "beat_count",
                     "mean_rr", "mean_hr_bpm", "sdnn", "rmssd", "pnn50_percent", "low_confidence"]
EDA_FEATURE_COLS = ["n_samples", "coverage", "eda_mean", "eda_std", "eda_peak_count",
                     "eda_slope", "eda_tonic_mean", "eda_tonic_slope", "eda_phasic_mean",
                     "eda_scr_count", "eda_scr_amplitude_sum", "eda_scr_amplitude_mean",
                     "eda_decomposition_available", "low_confidence"]
# eda_tonic_*/eda_phasic_*/eda_scr_*/eda_decomposition_available are the real tonic/phasic
# decomposition hrv_eda_windowing.py's compute_eda_windows() adds (via neurokit2) alongside
# its original eda_mean/eda_std/eda_peak_count/eda_slope columns above -- listed here so
# asof_join doesn't silently drop them the way it would any column missing from this
# whitelist. If an eda_windows table predates that feature (no neurokit2 at the time, or an
# older --no-eda-decomposition run), these columns simply aren't present in `eda` and
# asof_join's own missing-column handling takes over -- see asof_join below.
SKINTEMP_FEATURE_COLS = ["n_samples", "mean_interval_s", "ambient_mean_c", "ambient_std_c",
                          "skin_mean_c", "skin_std_c", "skin_slope_c_per_s", "low_confidence"]


def merge_all(hrv, eda, accel, skin_temp, cortisol=None,
              cortisol_lag_min=CORTISOL_LAG_MIN_DEFAULT, tolerance_sec=TOLERANCE_SEC_DEFAULT):
    if hrv is None or len(hrv) == 0:
        raise ValueError("merge_all: --hrv is the primary grid and cannot be empty")

    primary_window_sec = ((hrv["window_end_ms"] - hrv["window_start_ms"]).median()) / 1000.0
    tolerance_ms = (tolerance_sec if tolerance_sec is not None else primary_window_sec) * 1000.0

    out = hrv.rename(columns={c: f"hrv_{c}" for c in HRV_FEATURE_COLS})

    eda_joined = asof_join(hrv, eda, "eda", EDA_FEATURE_COLS, tolerance_ms)
    skin_joined = asof_join(hrv, skin_temp, "skintemp", SKINTEMP_FEATURE_COLS, tolerance_ms)
    accel_joined = aggregate_accel(hrv, accel)

    out = out.merge(eda_joined, on=["session_id", "window_start_ms"], how="left")
    out = out.merge(skin_joined, on=["session_id", "window_start_ms"], how="left")
    out = out.merge(accel_joined, on=["session_id", "window_start_ms"], how="left")

    if cortisol is not None and len(cortisol) > 0:
        cortisol_joined = attach_cortisol(hrv, cortisol, cortisol_lag_min)
        out = out.merge(cortisol_joined, on=["session_id", "window_start_ms"], how="left")

    return out.sort_values(["session_id", "window_start_ms"]).reset_index(drop=True)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _read_opt(path):
    return pd.read_csv(path) if path else None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hrv", help="HRV windows CSV (from hrv_eda_windowing.py --out-hrv) -- the primary grid")
    ap.add_argument("--eda", help="EDA windows CSV (from hrv_eda_windowing.py --out-eda)")
    ap.add_argument("--accel", help="accel windows CSV (from accel_windowing.py)")
    ap.add_argument("--skin-temp", help="skin-temp windows CSV (from skin_temp_windowing.py)")
    ap.add_argument("--cortisol", help="optional cortisol samples CSV: session_id, "
                                        "participant_email, sample_time_ms, cortisol_value")
    ap.add_argument("--cortisol-lag-min", type=float, default=CORTISOL_LAG_MIN_DEFAULT)
    ap.add_argument("--tolerance-sec", type=float, default=None,
                     help="max gap (seconds) for an EDA/skin-temp window to be matched to a "
                          "primary window; default is one primary window's own duration")
    ap.add_argument("--out", default="merged_windows.csv")
    ap.add_argument("--demo", action="store_true",
                     help="run accel/hrv-eda/skin-temp --demo generators in-process and merge "
                          "them, instead of reading real windows CSVs")
    ap.add_argument("--plot", action="store_true",
                     help="also save a per-session dashboard PNG (movement / heart rate & HRV "
                          "/ EDA / skin temperature, one image per session) next to --out, via "
                          "plot_dashboard.py. Requires matplotlib.")
    ap.add_argument("--real", action="store_true",
                     help="with --plot: drop the 'SYNTHETIC DATA' stamp on the dashboard "
                          "title. Only pass this once --hrv/--eda/--accel/--skin-temp point "
                          "at an actual watch export, not --demo or a fixture/ generator -- "
                          "default is to assume synthetic, since that's the safer mistake.")
    args = ap.parse_args()

    if args.demo:
        print("Running on SYNTHETIC demo data from all three windowing scripts' own --demo "
              "generators -- for sanity-checking the merge join only.\n")
        accel_demo = accel_windowing.make_synthetic_demo().drop(columns=["_label"])
        accel_w = accel_windowing.compute_windows(accel_demo)  # 5s default -- deliberately finer than primary
        hr_demo, eda_demo = hrv_eda_windowing.make_synthetic_demo()
        hrv_w = hrv_eda_windowing.compute_hrv_windows(hr_demo, hrv_eda_windowing.DEMO_WINDOW_SEC_DEFAULT)
        eda_w = hrv_eda_windowing.compute_eda_windows(eda_demo, hrv_eda_windowing.DEMO_WINDOW_SEC_DEFAULT)
        temp_demo = skin_temp_windowing.make_synthetic_demo()
        temp_w = skin_temp_windowing.compute_windows(temp_demo, skin_temp_windowing.DEMO_WINDOW_SEC_DEFAULT)
        cortisol = _read_opt(args.cortisol)  # --cortisol still honored under --demo, for testing that join
    else:
        if not args.hrv:
            ap.error("--hrv is required (the primary grid) unless --demo is given")
        hrv_w = pd.read_csv(args.hrv)
        eda_w = _read_opt(args.eda)
        accel_w = _read_opt(args.accel)
        temp_w = _read_opt(args.skin_temp)
        cortisol = _read_opt(args.cortisol)

    merged = merge_all(hrv_w, eda_w, accel_w, temp_w, cortisol,
                        args.cortisol_lag_min, args.tolerance_sec)
    merged.to_csv(args.out, index=False)
    print(f"Wrote {len(merged)} merged row(s), {len(merged.columns)} column(s) -> {args.out}")
    print(f"\nColumns: {list(merged.columns)}")

    if "eda_window_start_ms" in merged.columns:
        n_eda_matched = merged["eda_window_start_ms"].notna().sum()
        print(f"\nEDA matched: {n_eda_matched}/{len(merged)}")
    if "skintemp_window_start_ms" in merged.columns:
        n_temp_matched = merged["skintemp_window_start_ms"].notna().sum()
        print(f"Skin-temp matched: {n_temp_matched}/{len(merged)}")
    if "accel_n_subwindows" in merged.columns:
        n_accel_matched = (merged["accel_n_subwindows"] > 0).sum()
        print(f"Accel matched (>=1 overlapping subwindow): {n_accel_matched}/{len(merged)}")
    if "cortisol_value" in merged.columns:
        n_cortisol_matched = merged["cortisol_value"].notna().sum()
        print(f"Cortisol matched: {n_cortisol_matched}/{len(merged)}")

    if args.plot:
        try:
            import plot_dashboard
        except ImportError:
            print("\n--plot was given but matplotlib isn't installed -- run "
                  "'pip install matplotlib' and re-run with --plot to get the dashboard "
                  "PNGs. (The CSV above was still written normally.)")
        else:
            out_dir = os.path.dirname(os.path.abspath(args.out)) or "."
            # Defaults to the "SYNTHETIC DATA" stamp unless --real says otherwise -- both
            # --demo and a fixture/ run (make_stress_test_fixture.py's messier synthetic
            # data, passed as plain --hrv/--eda/... file paths, not --demo) are synthetic.
            paths = plot_dashboard.save_dashboards(merged, out_dir, synthetic=not args.real)
            print()
            for p in paths:
                print(f"Wrote dashboard -> {p}")


if __name__ == "__main__":
    main()
