"""
Accelerometer windowing & motion-flagging pipeline.

Cortisol Monitor thesis - Stage 3/4 of docs/accelerometer_stress_research_and_plan.md.

Turns raw accelerometer samples exported by VitalsCsvExporter (accel_*.csv /
accel_session_*.csv, one row per sample: session_id, participant_email,
timestamp_ms, x_raw, y_raw, z_raw, status, received_at_ms) into fixed-length
windows with a movement-magnitude summary and a binary motion flag, per
Stage 3 ("Accelerometer: vector magnitude mean/variance per window, plus a
simple '% of window flagged as active' threshold") and Stage 4 ("flag windows
where accelerometer variance/magnitude exceeds a threshold... using the
accelerometer as the filter") of the plan above.

This does NOT try to classify stress from motion -- the research review in
that same doc found accelerometer-only stress classification underperforms
(WESAD: 64-74% vs 79-89% for physiological signals). It only produces the
motion covariate / data-quality flag the plan calls for, for later use in
Stage 5's regression (as a covariate, not a predictor) and Stage 4's
window-exclusion filter.

Raw x/y/z are counts, not physical units. Samsung's Galaxy Watch accelerometer
counts convert to g by dividing by 4096 (see WatchHealthManager.kt /
VitalsCsvExporter.java: confirmed against real data -- a stationary reading
works out to ~1.0g).

Usage:
    python accel_windowing.py <accel_csv_or_dir> [--window-sec 5]
        [--threshold-std-g 0.05] [--min-coverage 0.5] [--out features.csv]

    python accel_windowing.py --demo
        Runs the pipeline on a small synthetic (clearly-labeled) session
        with a stationary / walking / stationary pattern, so the windowing
        and flagging logic can be sanity-checked before any real watch
        session has been exported.

IMPORTANT: no real watch session has been exported yet (as of 2026-09-01).
THRESHOLD_STD_G_DEFAULT below is a documented placeholder, not a calibrated
value. Recalibrate it against the Stage 2 pilot's deliberate walking segment
once that pilot is run -- per the plan's own Stage 4 guidance ("a threshold
you set from your pilot data"). Don't treat --demo output as anything more
than a logic check.
"""

import argparse
import glob
import os

import numpy as np
import pandas as pd

COUNTS_PER_G = 4096.0    # Samsung Galaxy Watch accelerometer conversion (see module docstring)
NOMINAL_RATE_HZ = 25.0   # ACCELEROMETER_CONTINUOUS sample rate (WatchHealthManager.kt)

WINDOW_SEC_DEFAULT = 5.0
THRESHOLD_STD_G_DEFAULT = 0.05   # PLACEHOLDER -- see module docstring, recalibrate against real pilot data
MIN_COVERAGE_DEFAULT = 0.5       # windows with < 50% of the expected sample count are marked low_confidence

REQUIRED_COLUMNS = ["session_id", "participant_email", "timestamp_ms", "x_raw", "y_raw", "z_raw"]


def load_accel_csv(path):
    df = pd.read_csv(path)
    missing = [c for c in REQUIRED_COLUMNS if c not in df.columns]
    if missing:
        raise ValueError(f"{path}: missing expected column(s) {missing} -- not an accel export?")
    return df


def load_accel(input_path):
    """Accepts either a single accel_*.csv file, or a directory containing several
    (as produced by repeated VitalsCsvExporter exports), and concatenates them."""
    if os.path.isdir(input_path):
        files = sorted(glob.glob(os.path.join(input_path, "accel*.csv")))
        if not files:
            raise FileNotFoundError(f"No accel*.csv files found in {input_path}")
        return pd.concat([load_accel_csv(f) for f in files], ignore_index=True)
    return load_accel_csv(input_path)


def compute_windows(df, window_sec=WINDOW_SEC_DEFAULT,
                     threshold_std_g=THRESHOLD_STD_G_DEFAULT,
                     min_coverage=MIN_COVERAGE_DEFAULT):
    """Converts raw counts -> g, computes per-sample vector magnitude, bins samples
    into fixed window_sec windows (windows never cross a session boundary -- each
    session restarts its own window clock at its first sample), and returns one
    row per window."""
    df = df.copy()
    df["x_g"] = df["x_raw"] / COUNTS_PER_G
    df["y_g"] = df["y_raw"] / COUNTS_PER_G
    df["z_g"] = df["z_raw"] / COUNTS_PER_G
    df["mag_g"] = np.sqrt(df["x_g"] ** 2 + df["y_g"] ** 2 + df["z_g"] ** 2)

    window_ms = window_sec * 1000.0
    expected_samples = window_sec * NOMINAL_RATE_HZ

    rows = []
    for session_id, g in df.groupby("session_id", sort=False):
        g = g.sort_values("timestamp_ms").drop_duplicates(subset=["timestamp_ms"])
        session_start_ms = g["timestamp_ms"].iloc[0]
        participant_email = g["participant_email"].iloc[0]
        window_idx = ((g["timestamp_ms"] - session_start_ms) // window_ms).astype(int)
        for idx, wdf in g.groupby(window_idx):
            n = len(wdf)
            mag_mean = wdf["mag_g"].mean()
            mag_std = wdf["mag_g"].std(ddof=0) if n > 1 else 0.0
            coverage = n / expected_samples
            rows.append({
                "session_id": session_id,
                "participant_email": participant_email,
                "window_start_ms": session_start_ms + idx * window_ms,
                "window_end_ms": session_start_ms + (idx + 1) * window_ms,
                "n_samples": n,
                "expected_samples": round(expected_samples),
                "coverage": round(coverage, 3),
                "mag_mean_g": round(mag_mean, 4),
                "mag_std_g": round(mag_std, 4),
                "motion_flag": bool(mag_std > threshold_std_g),
                "low_confidence": bool(coverage < min_coverage),
            })
    out = pd.DataFrame(rows).sort_values(["session_id", "window_start_ms"]).reset_index(drop=True)
    return out


def summarize(windows, min_coverage=MIN_COVERAGE_DEFAULT):
    total = len(windows)
    if total == 0:
        print("No windows produced.")
        return
    n_motion = int(windows["motion_flag"].sum())
    n_low_conf = int(windows["low_confidence"].sum())
    print(f"\n{total} window(s) across {windows['session_id'].nunique()} session(s)")
    print(f"  motion-flagged                              : {n_motion} ({100 * n_motion / total:.1f}%)")
    print(f"  low-confidence (< {int(min_coverage * 100)}% of expected samples) : "
          f"{n_low_conf} ({100 * n_low_conf / total:.1f}%)")
    print("\nPer-session breakdown:")
    per = windows.groupby("session_id").agg(
        windows=("window_start_ms", "count"),
        motion_flagged=("motion_flag", "sum"),
        low_confidence=("low_confidence", "sum"),
        mean_mag_g=("mag_mean_g", "mean"),
    )
    print(per.to_string())


def make_synthetic_demo():
    """Builds a small, clearly-labeled SYNTHETIC session (not real watch data):
    5min stationary, 2min simulated walking, 3min stationary again (10 minutes
    total -- extended from an earlier 1-minute version so the demo/dashboards
    have enough elapsed time to look like an actual session), at 25 Hz -- just
    enough to sanity-check the windowing/flagging logic end-to-end before any
    real watch export exists. The '_label' column is dropped before the frame
    is fed through the same code path a real accel_*.csv would use."""
    rng = np.random.default_rng(42)
    rate_hz = NOMINAL_RATE_HZ
    t0 = 1_756_000_000_000  # arbitrary epoch ms
    rows = []
    t = t0

    def add_segment(duration_s, axis_noise_std_g, label):
        nonlocal t
        n = int(duration_s * rate_hz)
        for _ in range(n):
            # A stationary watch reads ~1g total (gravity), split arbitrarily across
            # axes depending on wrist orientation -- here mostly on z. "Walking" segments
            # just get more per-axis noise added, which is all this demo needs to exercise
            # the magnitude-std threshold; it isn't a physiologically accurate gait model.
            noise = rng.normal(0, axis_noise_std_g, size=3)
            x, y, z = noise[0], noise[1], 1.0 + noise[2]
            rows.append({
                "session_id": "demo_session_1",
                "participant_email": "demo@example.com",
                "timestamp_ms": t,
                "x_raw": round(x * COUNTS_PER_G),
                "y_raw": round(y * COUNTS_PER_G),
                "z_raw": round(z * COUNTS_PER_G),
                "status": -1,
                "received_at_ms": t,
                "_label": label,
            })
            t += round(1000 / rate_hz)

    add_segment(300, 0.01, "stationary")
    add_segment(120, 0.30, "walking")
    add_segment(180, 0.01, "stationary")
    return pd.DataFrame(rows)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", nargs="?", help="accel_*.csv file, or a directory of them")
    ap.add_argument("--window-sec", type=float, default=WINDOW_SEC_DEFAULT)
    ap.add_argument("--threshold-std-g", type=float, default=THRESHOLD_STD_G_DEFAULT,
                     help="magnitude-std-per-window threshold above which a window is "
                          "motion_flag=True (placeholder -- recalibrate against a real "
                          "walking segment, see module docstring)")
    ap.add_argument("--min-coverage", type=float, default=MIN_COVERAGE_DEFAULT)
    ap.add_argument("--out", default=None,
                     help="output features CSV path (default: <input>_windows.csv, "
                          "or accel_demo_windows.csv for --demo)")
    ap.add_argument("--demo", action="store_true",
                     help="run on a small synthetic stationary/walking/stationary session "
                          "instead of a real accel export")
    args = ap.parse_args()

    if args.demo:
        print("Running on SYNTHETIC demo data (no real watch export exists yet) -- "
              "for sanity-checking the pipeline only, not for any analysis conclusions.\n")
        demo = make_synthetic_demo()
        demo = demo.drop(columns=["_label"])
        windows = compute_windows(demo, args.window_sec, args.threshold_std_g, args.min_coverage)
        out_path = args.out or "accel_demo_windows.csv"
    else:
        if not args.input:
            ap.error("input is required unless --demo is given")
        df = load_accel(args.input)
        windows = compute_windows(df, args.window_sec, args.threshold_std_g, args.min_coverage)
        base = args.input.rstrip("/\\") if os.path.isdir(args.input) else os.path.splitext(args.input)[0]
        out_path = args.out or (base + "_windows.csv")

    windows.to_csv(out_path, index=False)
    print(f"Wrote {len(windows)} window(s) -> {out_path}")
    summarize(windows, args.min_coverage)


if __name__ == "__main__":
    main()
