"""
Skin temperature windowing pipeline.

Cortisol Monitor thesis -- Stage 3 of docs/accelerometer_stress_research_and_plan.md
(the accelerometer half lives in accel_windowing.py, the HRV/EDA half in
hrv_eda_windowing.py, both next to this file -- this script does the skin-temperature
half of the same stage):

    "Skin temperature: mean and slope over the window"

Input (from VitalsCsvExporter.java, writeSkinTempRows()):
  skin_temp_*.csv / skin_temp_session_*.csv / skin_temp_day_*.csv
    columns: session_id, participant_email, timestamp_ms, ambient_c, skin_c,
             status, received_at_ms

IMPORTANT -- unlike EDA_CONTINUOUS/HEART_RATE_CONTINUOUS (both confirmed 1 Hz) and
ACCELEROMETER_CONTINUOUS (confirmed 25 Hz), Samsung's own tracker table in
Watch8_Data_Collection_Guide.md lists SKIN_TEMPERATURE_CONTINUOUS's sample rate as
"--" (undocumented). So, unlike accel_windowing.py/hrv_eda_windowing.py, this script
deliberately does NOT compute an expected_samples/coverage ratio from an assumed Hz --
fabricating one would look calibrated when it isn't. Instead each window just reports
its actual n_samples and mean_interval_s (informational, for figuring out the real
rate once a pilot export exists), and low_confidence is n_samples < --min-samples
(default 2 -- you need at least 2 points to fit a slope at all). Revisit this once a
real skin_temp_*.csv export shows the actual sampling behavior.

Also unlike hr_*.csv (whose ibi_status_list semantics are documented in
HrvCalculator.java) or eda_*.csv, no documentation was found for what `status` means
on a skin-temp row, so this script does NOT filter on it -- filtering on an unverified
status column risks silently dropping real data. The demo/summary output reports the
observed status value distribution instead, as a nudge to revisit this once real
status values are seen.

Column names (ambient_mean_c, ambient_std_c, skin_mean_c, skin_std_c, skin_slope_c_per_s)
follow the same mean/std/slope shape as hrv_eda_windowing.py's EDA columns, for the same
reason: a real session's feature table should be mergeable/extendable without a renaming
pass.

Like the other two scripts, this does NOT align skin-temp windows with HRV/EDA/accel
windows or cortisol samples -- that join is merge_windows.py's job (see its docstring),
per VitalsCsvExporter's own design note that alignment happens in a later pandas
merge_asof step, once accel + HRV + EDA + skin-temp windows all exist.

IMPORTANT: no real watch session has been exported yet (as of 2026-09-22). --demo
runs a small SYNTHETIC session (same session_id/epoch/segment lengths as
accel_windowing.py's and hrv_eda_windowing.py's own --demo) to sanity-check the
parsing/windowing/slope logic -- it is not real skin-temperature data. The demo's
skin_c dips slightly during the "stress-like" segment (illustrating peripheral
vasoconstriction, a real documented acute-stress response) purely so the slope
column has something non-zero to compute -- this is not modeled from real data and
proves nothing about this participant's actual physiology.

Usage:
    python skin_temp_windowing.py <skin_temp_csv_or_dir> [--window-sec 30]
        [--min-samples 2] [--out skin_temp_windows.csv]

    python skin_temp_windowing.py --demo [--window-sec 15]
        Defaults to 15s windows for --demo specifically, so the four windows line
        up with the demo's 30s/15s/15s baseline/stress/baseline segments (same
        convention as hrv_eda_windowing.py's --demo).
"""

import argparse
import glob
import os

import numpy as np
import pandas as pd

WINDOW_SEC_DEFAULT = 30.0        # matches hrv_eda_windowing.py's real-data default, so a
                                  # real session's HRV/EDA/skin-temp windows land on the
                                  # same grid by default -- override once real data shows
                                  # the actual skin-temp sample rate is very different
DEMO_WINDOW_SEC_DEFAULT = 15.0   # aligns cleanly with the demo's 30/15/15s segments
MIN_SAMPLES_DEFAULT = 2          # need >= 2 points to fit a slope; a single-sample window
                                  # gets low_confidence=True and skin_slope_c_per_s=NaN
MIN_SPAN_FRACTION_DEFAULT = 0.5   # a window's samples must span at least this fraction of
                                  # window_sec -- fitting a "slope over the window" from two
                                  # points huddled in the first few seconds and calling it the
                                  # window's trend would be misleading (audit item: skin-temp
                                  # duration/gap checks)
MAX_GAP_FRACTION_DEFAULT = 0.5    # if the largest gap between consecutive samples inside a
                                  # window exceeds this fraction of window_sec, the "slope" is
                                  # really just two disconnected readings bridged by a long dead
                                  # gap, not an observed trend -- flagged low_confidence too

REQUIRED_COLUMNS = ["session_id", "participant_email", "timestamp_ms",
                     "ambient_c", "skin_c", "status"]


def load_skin_temp_csv(path):
    df = pd.read_csv(path)
    missing = [c for c in REQUIRED_COLUMNS if c not in df.columns]
    if missing:
        raise ValueError(f"{path}: missing expected column(s) {missing} -- not a skin_temp export?")
    return df


def load_skin_temp(input_path):
    """Accepts either a single skin_temp_*.csv file, or a directory containing several,
    and concatenates them -- same convention as accel_windowing.py's load_accel()."""
    if os.path.isdir(input_path):
        files = sorted(glob.glob(os.path.join(input_path, "skin_temp*.csv")))
        if not files:
            raise FileNotFoundError(f"No skin_temp*.csv files found in {input_path}")
        return pd.concat([load_skin_temp_csv(f) for f in files], ignore_index=True)
    return load_skin_temp_csv(input_path)


def compute_windows(df, window_sec=WINDOW_SEC_DEFAULT, min_samples=MIN_SAMPLES_DEFAULT,
                     min_span_fraction=MIN_SPAN_FRACTION_DEFAULT,
                     max_gap_fraction=MAX_GAP_FRACTION_DEFAULT):
    """Bins skin-temp samples into fixed window_sec windows per session (windows never
    cross a session boundary, same convention as the other two windowing scripts) and
    returns one row per window with ambient/skin mean+std and a skin_c slope (deg C per
    second) fit over the window's samples.

    low_confidence is True when n_samples < min_samples, OR (for n >= 2) when the samples'
    actual timestamp span covers less than min_span_fraction of window_sec, OR when the
    largest gap between consecutive samples exceeds max_gap_fraction of window_sec -- a slope
    fit over a short span or across a long dead gap is not a trustworthy window trend even
    when n_samples alone looks sufficient."""
    window_ms = window_sec * 1000.0

    rows = []
    for session_id, g in df.groupby("session_id", sort=False):
        g = g.sort_values("timestamp_ms").drop_duplicates(subset=["timestamp_ms"])
        session_start_ms = g["timestamp_ms"].iloc[0]
        participant_email = g["participant_email"].iloc[0]
        window_idx = ((g["timestamp_ms"] - session_start_ms) // window_ms).astype(int)
        for idx, wdf in g.groupby(window_idx):
            n = len(wdf)
            ambient = wdf["ambient_c"].to_numpy(dtype=float)
            skin = wdf["skin_c"].to_numpy(dtype=float)
            t_sec = (wdf["timestamp_ms"].to_numpy(dtype=float) - wdf["timestamp_ms"].iloc[0]) / 1000.0

            if n >= 2:
                span_s = float(t_sec[-1] - t_sec[0])
                mean_interval_s = round(span_s / (n - 1), 3) if span_s > 0 else 0.0
                max_gap_s = float(np.diff(t_sec).max())
                skin_slope = np.polyfit(t_sec, skin, 1)[0]
                span_too_short = span_s < min_span_fraction * window_sec
                gap_too_large = max_gap_s > max_gap_fraction * window_sec
            else:
                span_s = 0.0
                mean_interval_s = np.nan
                max_gap_s = np.nan
                skin_slope = np.nan
                span_too_short = False   # n < min_samples already covers this case
                gap_too_large = False

            rows.append({
                "session_id": session_id,
                "participant_email": participant_email,
                "window_start_ms": session_start_ms + idx * window_ms,
                "window_end_ms": session_start_ms + (idx + 1) * window_ms,
                "n_samples": n,
                "mean_interval_s": mean_interval_s,
                "span_s": round(span_s, 3),
                "max_gap_s": round(max_gap_s, 3) if not np.isnan(max_gap_s) else np.nan,
                "ambient_mean_c": round(float(ambient.mean()), 3),
                "ambient_std_c": round(float(ambient.std(ddof=0)), 3) if n > 1 else 0.0,
                "skin_mean_c": round(float(skin.mean()), 3),
                "skin_std_c": round(float(skin.std(ddof=0)), 3) if n > 1 else 0.0,
                "skin_slope_c_per_s": round(float(skin_slope), 6) if not np.isnan(skin_slope) else np.nan,
                "low_confidence": bool(n < min_samples or span_too_short or gap_too_large),
            })
    return pd.DataFrame(rows).sort_values(["session_id", "window_start_ms"]).reset_index(drop=True)


def summarize(windows, df_raw, min_samples=MIN_SAMPLES_DEFAULT):
    total = len(windows)
    if total == 0:
        print("No skin-temp windows produced.")
        return
    n_low_conf = int(windows["low_confidence"].sum())
    print(f"\n{total} skin-temp window(s) across {windows['session_id'].nunique()} session(s)")
    print(f"  low-confidence (< {min_samples} samples): {n_low_conf} ({100 * n_low_conf / total:.1f}%)")
    if "status" in df_raw.columns:
        print(f"  observed status value(s) in input: {sorted(df_raw['status'].dropna().unique().tolist())} "
              f"(not filtered on -- see module docstring)")
    print("\nPer-session breakdown:")
    per = windows.groupby("session_id").agg(
        windows=("window_start_ms", "count"),
        mean_n_samples=("n_samples", "mean"),
        mean_skin_c=("skin_mean_c", "mean"),
        low_confidence=("low_confidence", "sum"),
    )
    print(per.to_string())


def make_synthetic_demo():
    """SYNTHETIC session, not real watch data: same 5min/2min/3min (10 minutes total --
    extended from an earlier 1-minute version) baseline/stress-like/recovery structure
    as accel_windowing.py's and hrv_eda_windowing.py's --demo, at an arbitrary 5s sample
    interval (the real device rate is unconfirmed -- see module docstring -- 5s is just
    sparse enough to exercise low_confidence and dense enough for a meaningful slope
    within a 15s demo window). skin_c dips ~0.3C during the stress-like segment
    (illustrating peripheral vasoconstriction) and recovers -- illustrative only, not
    modeled from real data."""
    rng = np.random.default_rng(11)
    t0 = 1_756_000_000_000  # same arbitrary epoch as the other two scripts' demos
    session_id = "demo_session_1"
    participant_email = "demo@example.com"
    sample_interval_ms = 5000

    rows = []
    state = {"t": t0, "skin_level": 33.5}

    def add_sample(skin_target, ambient_mean):
        t = state["t"]
        state["skin_level"] += (skin_target - state["skin_level"]) * 0.25
        skin_c = state["skin_level"] + rng.normal(0, 0.03)
        ambient_c = ambient_mean + rng.normal(0, 0.05)
        rows.append({
            "session_id": session_id, "participant_email": participant_email,
            "timestamp_ms": t, "ambient_c": round(ambient_c, 3), "skin_c": round(skin_c, 3),
            "status": 0, "received_at_ms": t,
        })
        state["t"] += sample_interval_ms

    n_baseline = 300 * 1000 // sample_interval_ms
    n_stress = 120 * 1000 // sample_interval_ms
    n_recovery = 180 * 1000 // sample_interval_ms
    for _ in range(int(n_baseline)):
        add_sample(skin_target=33.5, ambient_mean=23.0)
    for _ in range(int(n_stress)):
        add_sample(skin_target=33.2, ambient_mean=23.0)
    for _ in range(int(n_recovery)):
        add_sample(skin_target=33.5, ambient_mean=23.0)

    return pd.DataFrame(rows)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", nargs="?", help="skin_temp_*.csv file, or a directory of them")
    ap.add_argument("--window-sec", type=float, default=None,
                     help=f"default {WINDOW_SEC_DEFAULT}s normally, {DEMO_WINDOW_SEC_DEFAULT}s "
                          f"for --demo")
    ap.add_argument("--min-samples", type=int, default=MIN_SAMPLES_DEFAULT)
    ap.add_argument("--out", default=None,
                     help="output features CSV path (default: <input>_windows.csv, "
                          "or skin_temp_demo_windows.csv for --demo)")
    ap.add_argument("--demo", action="store_true",
                     help="run on a small synthetic baseline/stress-like/recovery session "
                          "instead of a real skin_temp export")
    args = ap.parse_args()

    window_sec = args.window_sec
    if window_sec is None:
        window_sec = DEMO_WINDOW_SEC_DEFAULT if args.demo else WINDOW_SEC_DEFAULT

    if args.demo:
        print("Running on SYNTHETIC demo data (no real watch export exists yet) -- "
              "for sanity-checking the pipeline only, not for any analysis conclusions.\n")
        df = make_synthetic_demo()
        out_path = args.out or "skin_temp_demo_windows.csv"
    else:
        if not args.input:
            ap.error("input is required unless --demo is given")
        df = load_skin_temp(args.input)
        base = args.input.rstrip("/\\") if os.path.isdir(args.input) else os.path.splitext(args.input)[0]
        out_path = args.out or (base + "_windows.csv")

    windows = compute_windows(df, window_sec, args.min_samples)
    windows.to_csv(out_path, index=False)
    print(f"Wrote {len(windows)} window(s) -> {out_path}")
    summarize(windows, df, args.min_samples)


if __name__ == "__main__":
    main()
