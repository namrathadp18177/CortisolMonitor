"""
Generates a deliberately messier synthetic dataset for stress-testing the full
windowing + merge pipeline (accel_windowing.py, hrv_eda_windowing.py,
skin_temp_windowing.py, merge_windows.py) end to end -- BEFORE touching a real watch
session.

Each windowing script's own --demo is a clean, single-session, single-signal sanity
check. This is different on purpose: one shared dataset, across ALL FOUR raw export
files, with the specific kinds of messiness a real Galaxy Watch8 export is expected to
have and that a clean --demo never exercises:

  - a dropped-connection GAP in accel + HR + EDA mid-session (BLE drop / phone locked)
  - a BATCHING region where samples arrive in bursts rather than evenly spaced
    (matches VitalsCsvExporter's own note that samples batch while the display is off)
  - exact DUPLICATE rows in accel + HR (simulates a double-write on export)
  - OUT-OF-ORDER rows (the CSVs are shuffled before being written, since a real export
    is not guaranteed to arrive in timestamp order)
  - MIXED VALID/INVALID IBI status flags in the HR stream (exercises
    hrv_eda_windowing.py's _parse_valid_ibis filter)
  - a SECOND, separate session with no injected problems (exercises session-boundary
    handling: each session's window clock must restart independently, and the merge
    must not blend sessions together)
  - a duplicate-timestamp row in skin-temp (exercises skin_temp_windowing.py's
    drop_duplicates)
  - a fake cortisol sample log, to exercise merge_windows.py's --cortisol join
    against something other than its own clean --demo values

This does NOT test real hardware behavior (see docs/accelerometer_stress_research_and_plan.md
Stage 2 for that) -- it tests that the OFFLINE CODE survives realistic messiness before
any real session-time gets spent on it.

Usage:
    python make_stress_test_fixture.py [--out-dir fixture]
    Then run run_stress_test.py, which feeds the generated CSVs through the full
    pipeline and reports what it finds.
"""

import argparse
import json
import os

import numpy as np
import pandas as pd

RNG_SEED = 2026
PARTICIPANT = "fixture@example.com"

SESSION_1 = "fixture_session_1_messy"
SESSION_2 = "fixture_session_2_clean"

T0_SESSION_1 = 1_758_000_000_000  # arbitrary epoch ms, deliberately different from the
T0_SESSION_2 = 1_758_100_000_000  # windowing scripts' own --demo epoch (1_756_000_000_000)
                                   # so this fixture can never be mistaken for demo output


def _shuffle(rows, rng):
    rows = list(rows)
    rng.shuffle(rows)
    return rows


def make_accel(rng):
    """Session 1: 5 min @ 25Hz, stationary throughout except a 20s deliberate walking
    burst (so accel_windowing.py's motion_flag has something real to catch), PLUS a
    70s connection gap (no samples at all) partway through, PLUS ~40 exact-duplicate
    rows scattered in. Session 2: 90s @ 25Hz, clean, stationary."""
    rows = []

    def add_run(session_id, t_start, duration_s, axis_noise_std_g, rate_hz=25.0):
        n = int(duration_s * rate_hz)
        t = t_start
        for _ in range(n):
            noise = rng.normal(0, axis_noise_std_g, size=3)
            x, y, z = noise[0], noise[1], 1.0 + noise[2]
            rows.append({
                "session_id": session_id, "participant_email": PARTICIPANT,
                "timestamp_ms": t, "x_raw": round(x * 4096), "y_raw": round(y * 4096),
                "z_raw": round(z * 4096), "status": -1, "received_at_ms": t,
            })
            t += round(1000 / rate_hz)
        return t

    t = T0_SESSION_1
    t = add_run(SESSION_1, t, 90, 0.01)          # 0:00-1:30 stationary
    t = add_run(SESSION_1, t, 20, 0.30)          # 1:30-1:50 walking burst
    gap_start = t
    t += 70_000                                   # 1:50-3:00 CONNECTION GAP -- no samples
    t = add_run(SESSION_1, t, 140, 0.01)         # 3:00-5:20 stationary again

    # ~40 exact-duplicate rows, scattered through the clean stationary stretch, to
    # simulate a double-write on export.
    stationary_rows = [r for r in rows if r["session_id"] == SESSION_1]
    dupes = [dict(r) for r in rng.choice(stationary_rows, size=40, replace=False)]
    rows.extend(dupes)

    add_run(SESSION_2, T0_SESSION_2, 90, 0.01)

    return _shuffle(rows, rng), gap_start, gap_start + 70_000


def make_hr(rng):
    """Session 1: 1Hz nominally, but with the SAME 70s gap as accel (same underlying
    BLE drop), a batching region right after the gap (several samples sharing a
    burst-arrival pattern instead of evenly spaced -- simulates buffered delivery once
    the connection resumes), ~15 exact-duplicate rows, and a run of IBI entries with a
    non-zero (invalid) status mixed in with valid ones. Session 2: clean, 1Hz."""
    rows = []

    def add_second(session_id, t, mean_ibi_ms=850, ibi_jitter_ms=35, force_invalid_ibi=False):
        n_ibi = int(rng.integers(1, 4))
        ibis = [int(max(300, round(rng.normal(mean_ibi_ms, ibi_jitter_ms)))) for _ in range(n_ibi)]
        statuses = [0] * n_ibi
        if force_invalid_ibi and n_ibi > 1:
            statuses[0] = 1  # matches HrvCalculator's convention: status != 0 is invalid
        rows.append({
            "session_id": session_id, "participant_email": PARTICIPANT, "timestamp_ms": t,
            "heart_rate_bpm": round(60000.0 / np.mean(ibis)), "hr_status": 0,
            "ibi_list_ms": json.dumps(ibis), "ibi_status_list": json.dumps(statuses),
            "received_at_ms": t,
        })

    t = T0_SESSION_1
    for _ in range(90):
        add_second(SESSION_1, t); t += 1000
    for i in range(20):
        add_second(SESSION_1, t, mean_ibi_ms=650, ibi_jitter_ms=15,
                   force_invalid_ibi=(i % 4 == 0))
        t += 1000
    t += 70_000  # same 70s connection gap as accel

    # Batching: the next 30 "seconds" of readings arrive as 6 bursts of 5, each burst's
    # 5 samples only ~200ms apart (all still carrying nominally 1s-apart HR values) --
    # this is what VitalsCsvExporter's own docstring means by samples "batched ... while
    # the display is off": real arrival time != real physiological time.
    burst_t = t
    for _burst in range(6):
        for _sample_in_burst in range(5):
            add_second(SESSION_1, burst_t)
            burst_t += 200
        burst_t += 1000

    t = burst_t
    for _ in range(120):
        add_second(SESSION_1, t); t += 1000

    session1_rows = [r for r in rows if r["session_id"] == SESSION_1]
    dupes = [dict(r) for r in rng.choice(session1_rows, size=15, replace=False)]
    rows.extend(dupes)

    t2 = T0_SESSION_2
    for _ in range(90):
        add_second(SESSION_2, t2); t2 += 1000

    return _shuffle(rows, rng)


def make_eda(rng):
    """Session 1: same 70s gap as accel/HR (one underlying BLE drop affects all three
    continuous streams together, which is the realistic failure mode). Session 2: clean."""
    rows = []

    def add_second(session_id, t, level):
        conductance = level + rng.normal(0, 0.04)
        rows.append({
            "session_id": session_id, "participant_email": PARTICIPANT, "timestamp_ms": t,
            "conductance_uS": round(max(0.0, conductance), 4), "status": 0, "received_at_ms": t,
        })

    t = T0_SESSION_1
    for _ in range(90):
        add_second(SESSION_1, t, 2.0); t += 1000
    for _ in range(20):
        add_second(SESSION_1, t, 4.0); t += 1000
    t += 70_000
    for _ in range(140 + 30 + 6):  # matches HR's post-gap + batching-equivalent stretch, loosely
        add_second(SESSION_1, t, 2.2); t += 1000

    t2 = T0_SESSION_2
    for _ in range(90):
        add_second(SESSION_2, t2, 2.0); t2 += 1000

    return _shuffle(rows, rng)


def make_skin_temp(rng):
    """Sparse (one sample per 20s -- rate is unconfirmed, see skin_temp_windowing.py's
    docstring), covering both sessions, PLUS one deliberate duplicate-timestamp row in
    session 1 to exercise skin_temp_windowing.py's drop_duplicates."""
    rows = []

    def add_sample(session_id, t, skin_c, ambient_c=23.0):
        rows.append({
            "session_id": session_id, "participant_email": PARTICIPANT, "timestamp_ms": t,
            "ambient_c": round(ambient_c + rng.normal(0, 0.05), 3),
            "skin_c": round(skin_c + rng.normal(0, 0.03), 3),
            "status": 0, "received_at_ms": t,
        })

    t = T0_SESSION_1
    for _ in range(16):  # ~320s of session 1, every 20s
        add_sample(SESSION_1, t, 33.5); t += 20_000
    # Deliberate duplicate timestamp (same t as the row just written), differing value --
    # tests that drop_duplicates(subset=["timestamp_ms"]) keeps exactly one.
    add_sample(SESSION_1, t - 20_000, 33.9)

    t2 = T0_SESSION_2
    for _ in range(5):
        add_sample(SESSION_2, t2, 33.4); t2 += 20_000

    return _shuffle(rows, rng)


def make_cortisol():
    """A fake cortisol sample log spanning both sessions -- session 1 gets a pre-task
    draw plus two post-window draws at realistic TSST-style offsets, session 2 (a much
    shorter clean run) gets a single draw, so merge_windows.py's --cortisol path has
    something to match against besides its own --demo values."""
    return pd.DataFrame([
        {"session_id": SESSION_1, "participant_email": PARTICIPANT,
         "sample_time_ms": T0_SESSION_1 - 5 * 60_000, "cortisol_value": 4.8},
        {"session_id": SESSION_1, "participant_email": PARTICIPANT,
         "sample_time_ms": T0_SESSION_1 + 25 * 60_000, "cortisol_value": 7.9},
        {"session_id": SESSION_1, "participant_email": PARTICIPANT,
         "sample_time_ms": T0_SESSION_1 + 45 * 60_000, "cortisol_value": 6.4},
        {"session_id": SESSION_2, "participant_email": PARTICIPANT,
         "sample_time_ms": T0_SESSION_2 + 25 * 60_000, "cortisol_value": 5.1},
    ])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out-dir", default="fixture")
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)

    rng = np.random.default_rng(RNG_SEED)

    accel_rows, gap_start, gap_end = make_accel(rng)
    hr_rows = make_hr(rng)
    eda_rows = make_eda(rng)
    temp_rows = make_skin_temp(rng)
    cortisol_df = make_cortisol()

    pd.DataFrame(accel_rows).to_csv(os.path.join(args.out_dir, "accel_fixture.csv"), index=False)
    pd.DataFrame(hr_rows).to_csv(os.path.join(args.out_dir, "hr_fixture.csv"), index=False)
    pd.DataFrame(eda_rows).to_csv(os.path.join(args.out_dir, "eda_fixture.csv"), index=False)
    pd.DataFrame(temp_rows).to_csv(os.path.join(args.out_dir, "skin_temp_fixture.csv"), index=False)
    cortisol_df.to_csv(os.path.join(args.out_dir, "cortisol_fixture.csv"), index=False)

    meta = {
        "session_1": SESSION_1, "session_2": SESSION_2,
        "gap_start_ms": int(gap_start), "gap_end_ms": int(gap_end),
        "t0_session_1": T0_SESSION_1, "t0_session_2": T0_SESSION_2,
        "accel_rows": len(accel_rows), "hr_rows": len(hr_rows),
        "eda_rows": len(eda_rows), "skin_temp_rows": len(temp_rows),
    }
    with open(os.path.join(args.out_dir, "fixture_meta.json"), "w") as f:
        json.dump(meta, f, indent=2)

    print(f"Wrote fixture CSVs to {args.out_dir}/:")
    for k, v in meta.items():
        print(f"  {k}: {v}")


if __name__ == "__main__":
    main()
