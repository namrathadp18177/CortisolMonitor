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
By default (--primary-grid hrv, unchanged from before) HRV windows are the primary
grid -- every output row is one HRV window (session_id, window_start_ms,
window_end_ms from that table), with the other signals attached to it. HRV/EDA/
skin-temp all default to the same window_sec (30s), so for those two the join is a
straightforward per-session merge_asof: nearest EDA/skin-temp window_start_ms to
each HRV window_start_ms, within --tolerance-sec (default: one primary window's
worth).

CAVEAT this mode inherits: this watch's HRV windows are frequently low_confidence
(too few valid beats -- see hrv_eda_windowing.py's own IBI-plausibility filtering),
and some windows have NO HRV row at all. In --primary-grid hrv mode, a window with
no HRV row simply never becomes an output row at all -- its EDA/skin-temp/accel data
for that time range is silently dropped too, even when those signals were fine.

--primary-grid union instead builds the row grid from the UNION of (session_id,
window_start_ms) keys across whichever of HRV/EDA/skin-temp have data, then attaches
ALL THREE (HRV included, as hrv_* columns) via the same nearest-merge_asof used for
EDA/skin-temp today. A window missing only its HRV reading still becomes an output
row, with hrv_* columns NaN/low_confidence and eda_*/skintemp_* filled in normally --
nothing with real EDA/skin-temp/accel data is dropped just because HRV happened to
be missing or too sparse for that window. Column shape differs from --primary-grid
hrv mode (HRV's own columns move from bare names to an hrv_ prefix, matching EDA/
skin-temp's existing convention) -- anything reading the merged CSV by column name
needs to account for that if switching modes.

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
        [--tolerance-sec <primary window_sec>] [--primary-grid {hrv,union}]
        [--out merged_windows.csv]

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
CORTISOL_LAG_MAX_MIN_DEFAULT = 90.0  # reject a cortisol match drawn further than this after window end -- without a ceiling, a window near the start of a long session could silently match a cortisol sample drawn hours later in that same session (audit finding: "has a minimum lag but no maximum")
PRIMARY_GRID_DEFAULT = "hrv"  # "hrv" keeps existing behavior; "union" keeps EDA/skin-temp/
                                # accel rows even where HRV has no window at all (see module docstring)


# ---------------------------------------------------------------------------
# Accel: interval-overlap aggregation onto the primary grid (see module docstring)
# ---------------------------------------------------------------------------

ACCEL_SUMMARY_COLS = ["accel_n_subwindows", "accel_pct_motion_flagged", "accel_any_motion_flagged",
                      "accel_mean_mag_g", "accel_pct_low_confidence"]


def aggregate_accel(primary, accel):
    """For each primary window, aggregates every accel sub-window whose interval
    overlaps it. Returns primary's join keys plus accel_* summary columns -- rows
    with no overlapping accel data at all get accel_n_subwindows = 0 and NaN
    summary columns (distinct from 'checked and found no motion').

    Finds overlaps via a per-session sorted interval search (np.searchsorted on each
    session's accel window_start_ms/window_end_ms, which are sorted and non-overlapping)
    instead of a session_id merge followed by filtering -- that approach materializes
    len(primary-rows-in-session) x len(accel-rows-in-session) rows BEFORE the overlap
    filter removes most of them, which runs out of memory once the primary grid grows
    past HRV's own (often sparse) row count -- e.g. under --primary-grid union, where a
    session's primary row count can approach its EDA/skin-temp row count instead of
    HRV's smaller one, while still joining against the same large accel table."""
    if accel is None or len(accel) == 0:
        out = primary[["session_id", "window_start_ms"]].copy()
        for col in ACCEL_SUMMARY_COLS:
            out[col] = np.nan
        return out

    accel_by_session = {sid: g.sort_values("window_start_ms")
                         for sid, g in accel.groupby("session_id", sort=False)}

    results = []
    for session_id, pg in primary.groupby("session_id", sort=False):
        pg = pg[["session_id", "window_start_ms", "window_end_ms"]]
        p_start = pg["window_start_ms"].to_numpy(dtype=float)
        p_end = pg["window_end_ms"].to_numpy(dtype=float)
        n = len(pg)

        n_sub = np.zeros(n, dtype=int)
        pct_motion = np.full(n, np.nan)
        any_motion = np.full(n, None, dtype=object)
        mean_mag = np.full(n, np.nan)
        pct_low_conf = np.full(n, np.nan)

        a = accel_by_session.get(session_id)
        if a is not None and len(a) > 0:
            a_start = a["window_start_ms"].to_numpy(dtype=float)
            a_end = a["window_end_ms"].to_numpy(dtype=float)
            motion_flag = a["motion_flag"].to_numpy(dtype=bool)
            mag_mean_g = a["mag_mean_g"].to_numpy(dtype=float)
            low_conf = a["low_confidence"].to_numpy(dtype=bool)

            # overlap condition: a_start < p_end AND a_end > p_start. a_start/a_end are
            # both sorted ascending (accel_windowing.py emits fixed-width, non-overlapping,
            # increasing windows per session), so each side is a single searchsorted call
            # across every primary window in this session at once.
            idx_hi = np.searchsorted(a_start, p_end, side="left")
            idx_lo = np.searchsorted(a_end, p_start, side="right")

            for i in range(n):
                lo, hi = idx_lo[i], idx_hi[i]
                if hi > lo:
                    n_sub[i] = hi - lo
                    pct_motion[i] = round(float(motion_flag[lo:hi].mean()) * 100, 1)
                    any_motion[i] = bool(motion_flag[lo:hi].any())
                    mean_mag[i] = round(float(mag_mean_g[lo:hi].mean()), 4)
                    pct_low_conf[i] = round(float(low_conf[lo:hi].mean()) * 100, 1)

        res = pg[["session_id", "window_start_ms"]].copy()
        res["accel_n_subwindows"] = n_sub
        res["accel_pct_motion_flagged"] = pct_motion
        res["accel_any_motion_flagged"] = any_motion
        res["accel_mean_mag_g"] = mean_mag
        res["accel_pct_low_confidence"] = pct_low_conf
        results.append(res)

    out = pd.concat(results, ignore_index=True) if results else primary[["session_id", "window_start_ms"]].copy()
    if "accel_n_subwindows" not in out.columns:
        for col in ACCEL_SUMMARY_COLS:
            out[col] = np.nan
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

def attach_cortisol(primary, cortisol, lag_min, lag_max_min=CORTISOL_LAG_MAX_MIN_DEFAULT):
    """Attaches the NEXT cortisol sample drawn >= lag_min minutes after a window ends,
    same as before, but now also REJECTS that match if the sample was drawn more than
    lag_max_min minutes after the window ends. Without this ceiling, merge_asof's
    forward match has no upper bound at all -- a window near the start of a long
    session with no nearby cortisol draw would happily match whatever the NEXT sample
    in that session turns out to be, even if it's hours away and physiologically
    meaningless as "this window's resulting cortisol." Pass lag_max_min=None to
    restore the old unbounded behavior."""
    lag_ms = lag_min * 60_000.0
    keys = primary[["session_id", "window_start_ms", "window_end_ms"]].copy()
    keys["_eligible_from_ms"] = (keys["window_end_ms"] + lag_ms).astype("float64")

    left = keys.sort_values("_eligible_from_ms").reset_index(drop=True)
    right = cortisol[["session_id", "sample_time_ms", "cortisol_value"]].copy()
    right["sample_time_ms"] = right["sample_time_ms"].astype("float64")
    right = right.sort_values("sample_time_ms").reset_index(drop=True)

    # merge_asof's own `tolerance` is measured from the join key (_eligible_from_ms,
    # already shifted forward by lag_min), so passing lag_max_min - lag_min here caps
    # the sample's distance from _eligible_from_ms -- i.e. the sample can land anywhere
    # from lag_min up to lag_max_min minutes after window_end_ms, not lag_max_min minutes
    # after the already-shifted eligibility point.
    tolerance_ms = None
    if lag_max_min is not None:
        if lag_max_min < lag_min:
            raise ValueError(f"attach_cortisol: lag_max_min ({lag_max_min}) must be >= "
                              f"lag_min ({lag_min})")
        tolerance_ms = (lag_max_min - lag_min) * 60_000.0

    joined = pd.merge_asof(
        left, right,
        left_on="_eligible_from_ms", right_on="sample_time_ms",
        by="session_id", direction="forward", tolerance=tolerance_ms,
    )
    joined = joined.rename(columns={"sample_time_ms": "cortisol_sample_time_ms"})
    joined["cortisol_lag_min_actual"] = (
        (joined["cortisol_sample_time_ms"] - joined["window_end_ms"]) / 60_000.0
    ).round(2)
    return joined[["session_id", "window_start_ms", "cortisol_sample_time_ms",
                    "cortisol_value", "cortisol_lag_min_actual"]]


# ---------------------------------------------------------------------------
# Primary grid: either HRV's own windows (old behavior), or the UNION of
# (session_id, window_start_ms) keys across HRV/EDA/skin-temp (see module docstring)
# ---------------------------------------------------------------------------

def _primary_window_sec(*tables):
    """Median window duration (seconds) from the first non-empty table given."""
    for t in tables:
        if t is not None and len(t) > 0:
            return float((t["window_end_ms"] - t["window_start_ms"]).median()) / 1000.0
    raise ValueError("_primary_window_sec: no non-empty table given to infer window_sec from")


def build_union_grid(hrv, eda, skin_temp, window_sec):
    """Builds the primary grid as the union of (session_id, window_start_ms) keys across
    HRV, EDA and skin-temp windows, instead of requiring every output row to already be
    an HRV window. A window that only EDA (or only skin-temp) has a row for still gets a
    row here -- under the old hrv-is-mandatory-primary design that window's EDA/skin-temp/
    accel data would have been silently dropped along with its missing HRV row."""
    window_ms = window_sec * 1000.0
    key_frames, email_frames = [], []
    for t in (hrv, eda, skin_temp):
        if t is not None and len(t) > 0:
            key_frames.append(t[["session_id", "window_start_ms"]])
            if "participant_email" in t.columns:
                email_frames.append(t[["session_id", "participant_email"]].drop_duplicates())
    if not key_frames:
        raise ValueError("build_union_grid: --hrv, --eda and --skin-temp are all empty -- "
                          "nothing to build a union grid from")
    keys = (pd.concat(key_frames, ignore_index=True)
              .drop_duplicates(subset=["session_id", "window_start_ms"]))
    keys["window_end_ms"] = keys["window_start_ms"] + window_ms
    if email_frames:
        emails = pd.concat(email_frames, ignore_index=True).drop_duplicates(subset=["session_id"])
        keys = keys.merge(emails, on="session_id", how="left")
    else:
        keys["participant_email"] = np.nan
    return keys.sort_values(["session_id", "window_start_ms"]).reset_index(drop=True)


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
              cortisol_lag_min=CORTISOL_LAG_MIN_DEFAULT, tolerance_sec=TOLERANCE_SEC_DEFAULT,
              primary_grid=PRIMARY_GRID_DEFAULT, cortisol_lag_max_min=CORTISOL_LAG_MAX_MIN_DEFAULT):
    if primary_grid not in ("hrv", "union"):
        raise ValueError(f"merge_all: primary_grid must be 'hrv' or 'union', got {primary_grid!r}")

    if primary_grid == "hrv":
        if hrv is None or len(hrv) == 0:
            raise ValueError("merge_all: --primary-grid hrv requires --hrv to be non-empty "
                              "(pass --primary-grid union to build the row grid from whichever "
                              "of HRV/EDA/skin-temp has data instead)")
        primary_window_sec = _primary_window_sec(hrv)
        primary = hrv[["session_id", "window_start_ms", "window_end_ms"]].copy()
        out = hrv.rename(columns={c: f"hrv_{c}" for c in HRV_FEATURE_COLS})
    else:
        primary_window_sec = _primary_window_sec(hrv, eda, skin_temp)
        primary = build_union_grid(hrv, eda, skin_temp, primary_window_sec)
        out = primary.copy()

    tolerance_ms = (tolerance_sec if tolerance_sec is not None else primary_window_sec) * 1000.0

    if primary_grid == "union":
        hrv_joined = asof_join(primary, hrv, "hrv", HRV_FEATURE_COLS, tolerance_ms)
        out = out.merge(hrv_joined, on=["session_id", "window_start_ms"], how="left")

    eda_joined = asof_join(primary, eda, "eda", EDA_FEATURE_COLS, tolerance_ms)
    skin_joined = asof_join(primary, skin_temp, "skintemp", SKINTEMP_FEATURE_COLS, tolerance_ms)
    accel_joined = aggregate_accel(primary, accel)

    out = out.merge(eda_joined, on=["session_id", "window_start_ms"], how="left")
    out = out.merge(skin_joined, on=["session_id", "window_start_ms"], how="left")
    out = out.merge(accel_joined, on=["session_id", "window_start_ms"], how="left")

    if cortisol is not None and len(cortisol) > 0:
        cortisol_joined = attach_cortisol(primary, cortisol, cortisol_lag_min, cortisol_lag_max_min)
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
    ap.add_argument("--cortisol-lag-max-min", type=float, default=CORTISOL_LAG_MAX_MIN_DEFAULT,
                     help="reject a cortisol match drawn more than this many minutes after "
                          "window end (default 90) -- pass a negative number (e.g. -1) to "
                          "disable the ceiling and restore the old unbounded behavior")
    ap.add_argument("--tolerance-sec", type=float, default=None,
                     help="max gap (seconds) for an EDA/skin-temp window to be matched to a "
                          "primary window; default is one primary window's own duration")
    ap.add_argument("--primary-grid", choices=["hrv", "union"], default=PRIMARY_GRID_DEFAULT,
                     help="'hrv' (default, unchanged behavior): every output row is an HRV "
                          "window; EDA/skin-temp/accel data for a window with no HRV row is "
                          "dropped. 'union': output rows are the union of (session_id, "
                          "window_start_ms) keys across HRV/EDA/skin-temp, so a window missing "
                          "only its HRV reading still appears, with hrv_* columns NaN instead "
                          "of the whole row vanishing.")
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
        if args.primary_grid == "hrv" and not args.hrv:
            ap.error("--hrv is required with --primary-grid hrv (the default) unless --demo is "
                     "given -- pass --primary-grid union if --hrv may be empty/missing")
        if args.primary_grid == "union" and not (args.hrv or args.eda or args.skin_temp):
            ap.error("--primary-grid union needs at least one of --hrv/--eda/--skin-temp")
        hrv_w = pd.read_csv(args.hrv) if args.hrv else None
        eda_w = _read_opt(args.eda)
        accel_w = _read_opt(args.accel)
        temp_w = _read_opt(args.skin_temp)
        cortisol = _read_opt(args.cortisol)

    lag_max = None if args.cortisol_lag_max_min is not None and args.cortisol_lag_max_min < 0 else args.cortisol_lag_max_min
    merged = merge_all(hrv_w, eda_w, accel_w, temp_w, cortisol,
                        args.cortisol_lag_min, args.tolerance_sec, args.primary_grid, lag_max)
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
