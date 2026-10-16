"""
Runs the full offline pipeline (accel_windowing.py, hrv_eda_windowing.py,
skin_temp_windowing.py, merge_windows.py) against make_stress_test_fixture.py's
messier synthetic dataset, and reports what it finds -- a repeatable regression check
for the pipeline's own code, independent of any real watch export. Re-run this after
changing any of the four scripts above.

What it checks:
  - all four raw CSVs parse and window without error
  - the deliberate connection GAP shows up somewhere in the output (either as
    low_confidence windows or as missing window indices -- both are reported, since
    as of 2026-09-22 gap windows are silently OMITTED rather than emitted as explicit
    low-confidence rows; see the printed note)
  - session 2 (the clean session) windows independently of session 1 (i.e. session
    boundaries don't bleed into each other)
  - the merge join's match rates against the primary (HRV) grid
  - the --cortisol join picks a sane sample for each window

Also saves a dashboard_<session_id>.png per session (via plot_dashboard.py, passed
--plot) under --out-dir -- a quick visual sanity check alongside the printed numbers.

This is a diagnostic script, not a pass/fail test suite -- read its output, don't just
check its exit code.

Usage:
    python run_stress_test.py [--out-dir fixture]
"""

import argparse
import json
import os
import subprocess
import sys

import pandas as pd


def run(cmd):
    print(f"$ {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    print(result.stdout)
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr)
        raise SystemExit(f"FAILED: {' '.join(cmd)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out-dir", default="fixture")
    args = ap.parse_args()
    d = args.out_dir
    py = sys.executable

    print("=" * 70)
    print("1/5 -- generating fixture")
    print("=" * 70)
    run([py, "make_stress_test_fixture.py", "--out-dir", d])

    print("=" * 70)
    print("2/5 -- windowing each signal")
    print("=" * 70)
    run([py, "accel_windowing.py", f"{d}/accel_fixture.csv", "--out", f"{d}/accel_windows.csv"])
    run([py, "hrv_eda_windowing.py", "--hr", f"{d}/hr_fixture.csv", "--eda", f"{d}/eda_fixture.csv",
         "--out-hrv", f"{d}/hrv_windows.csv", "--out-eda", f"{d}/eda_windows.csv"])
    run([py, "skin_temp_windowing.py", f"{d}/skin_temp_fixture.csv", "--out", f"{d}/skin_temp_windows.csv"])

    print("=" * 70)
    print("3/5 -- merging")
    print("=" * 70)
    run([py, "merge_windows.py", "--hrv", f"{d}/hrv_windows.csv", "--eda", f"{d}/eda_windows.csv",
         "--accel", f"{d}/accel_windows.csv", "--skin-temp", f"{d}/skin_temp_windows.csv",
         "--cortisol", f"{d}/cortisol_fixture.csv", "--out", f"{d}/merged_windows.csv", "--plot"])

    print("=" * 70)
    print("4/5 -- gap check")
    print("=" * 70)
    meta = json.load(open(os.path.join(d, "fixture_meta.json")))
    accel = pd.read_csv(os.path.join(d, "accel_windows.csv"))
    s1 = accel[accel.session_id == meta["session_1"]].copy()
    gap_start_rel_s = (meta["gap_start_ms"] - meta["t0_session_1"]) / 1000
    gap_end_rel_s = (meta["gap_end_ms"] - meta["t0_session_1"]) / 1000
    s1["rel_start_s"] = (s1["window_start_ms"] - meta["t0_session_1"]) / 1000
    windows_touching_gap = s1[(s1["rel_start_s"] + 5 > gap_start_rel_s) & (s1["rel_start_s"] < gap_end_rel_s)]
    n_flagged = int(windows_touching_gap["low_confidence"].sum()) if len(windows_touching_gap) else 0
    print(f"Injected gap: {gap_start_rel_s:.0f}s-{gap_end_rel_s:.0f}s into session 1 "
          f"({(gap_end_rel_s - gap_start_rel_s):.0f}s wide)")
    print(f"Accel windows whose 5s span overlaps the gap: {len(windows_touching_gap)}, "
          f"of which low_confidence: {n_flagged}")
    print("NOTE: as of 2026-09-22, a window with ZERO samples is OMITTED from the output "
          "entirely (not emitted as a low_confidence row) -- it will not appear in "
          "windows_touching_gap above at all. Check the row count directly:")
    expected_n_windows = int((s1["rel_start_s"].max() + 5) // 5) if len(s1) else 0
    present_n_windows = len(s1)
    print(f"  session 1 accel: {present_n_windows} window rows present, spanning an index "
          f"range implying ~{expected_n_windows} if none were missing -- "
          f"{expected_n_windows - present_n_windows} row(s) appear to be silently missing.")

    print("=" * 70)
    print("5/5 -- merge match rates + cortisol sanity")
    print("=" * 70)
    merged = pd.read_csv(os.path.join(d, "merged_windows.csv"))
    print(f"Total merged rows (= HRV primary-grid windows): {len(merged)}")
    print(f"  sessions present: {sorted(merged['session_id'].unique().tolist())}")
    print(f"  EDA matched:       {merged['eda_window_start_ms'].notna().sum()}/{len(merged)}")
    print(f"  skin-temp matched: {merged['skintemp_window_start_ms'].notna().sum()}/{len(merged)}")
    print(f"  accel matched:     {(merged['accel_n_subwindows'] > 0).sum()}/{len(merged)}")
    if "cortisol_value" in merged.columns:
        print(f"  cortisol matched:  {merged['cortisol_value'].notna().sum()}/{len(merged)}")
        print(f"  cortisol_lag_min_actual range: "
              f"{merged['cortisol_lag_min_actual'].min():.1f}-{merged['cortisol_lag_min_actual'].max():.1f} "
              f"(should all be >= the --cortisol-lag-min used)")

    print("\nDone. Inspect the CSVs (and the dashboard_*.png figures) under", d, "for full detail.")


if __name__ == "__main__":
    main()
