"""
SYNTHETIC diurnal test fixture -- NOT real data, NOT part of the shipped
pipeline. Built only to validate diurnal_correlation.py and the windowing/
merge pipeline against a KNOWN ground-truth relationship before real
multi-day watch data + real cortisol numbers are available.

Generates N days of continuous synthetic EDA + HR raw exports (same column
schema the real VitalsCsvExporter produces, so they run through the real
hrv_eda_windowing.py / merge_windows.py unmodified) with:
  - a realistic diurnal shape (low overnight, rising after waking, daytime
    plateau with drift, declining toward evening)
  - random "fluctuation" events through the day (brief EDA/HR bursts --
    everyday arousal noise, not scripted stress/exercise) so the data is not
    a clean sine wave
  - per-day random variation (every day's baseline/peak/event timing differs
    a bit, not identical days repeated)

Cortisol ground truth is generated from the SAME underlying diurnal curve
used to drive EDA/HR (cortisol awakening response + exponential decline
across the day), plus independent measurement noise, at 4 standard clock
times per day (wake, wake+30, midday, evening) -- so a real relationship is
known to exist by construction, and the validation step checks whether it
gets recovered.
"""
import argparse
import json
import numpy as np
import pandas as pd

RNG_SEED_DEFAULT = 2026


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--days", type=int, default=5)
    ap.add_argument("--wake-hour", type=float, default=7.0)
    ap.add_argument("--tz-offset-hours", type=float, default=-4.0)
    ap.add_argument("--start-date", default="2026-11-01")
    ap.add_argument("--seed", type=int, default=RNG_SEED_DEFAULT)
    ap.add_argument("--out-dir", default="synthetic_diurnal_test")
    args = ap.parse_args()
    rng = np.random.default_rng(args.seed)

    day0 = pd.Timestamp(args.start_date, tz="UTC") - pd.Timedelta(hours=args.tz_offset_hours)
    # ^ interpret start-date as LOCAL midnight; convert to the UTC instant it represents
    sec_per_day = 86400

    hr_rows = []
    eda_rows = []
    cortisol_rows = []

    for day in range(args.days):
        session_id = f"synth_day{day+1}"
        day_wake_hour = args.wake_hour + rng.normal(0, 0.15)   # day-to-day wake jitter
        car_amp = rng.uniform(0.18, 0.30)                      # cortisol awakening response size, varies by day
        decline_floor = rng.uniform(0.04, 0.08)
        decline_start = rng.uniform(0.30, 0.42)
        decline_tau_hr = rng.uniform(5.0, 7.0)

        t0 = day0 + pd.Timedelta(days=day)
        secs = np.arange(sec_per_day)
        ts = t0 + pd.to_timedelta(secs, unit="s")
        local_hour = ((ts.tz_convert(None) if ts.tz is None else ts) + pd.Timedelta(hours=args.tz_offset_hours))
        hour_of_day = secs / 3600.0  # 0..24 local hours from local midnight (since t0 = local midnight)
        hours_since_wake = (hour_of_day - day_wake_hour) % 24

        # --- underlying cortisol curve (ground truth), evaluated continuously ---
        car_bump = car_amp * np.exp(-((hours_since_wake - 0.5) ** 2) / (2 * 0.25 ** 2))
        decline = decline_start * np.exp(-hours_since_wake / decline_tau_hr) + decline_floor
        cortisol_curve = np.where(hours_since_wake < 20, decline + car_bump, decline)  # wrap guard
        cortisol_curve = np.clip(cortisol_curve, 0.02, None)

        # --- EDA diurnal shape: low overnight, ramp after waking, daytime plateau+drift, evening decline ---
        awake = (hours_since_wake >= 0) & (hours_since_wake < 15.5)  # ~15.5h awake window
        eda_overnight = 1.6 + 0.3 * np.sin(hour_of_day / 24 * 2 * np.pi)
        ramp = np.clip(hours_since_wake / 1.5, 0, 1)          # 1.5h ramp-up after waking
        wind_down = np.clip((15.5 - hours_since_wake) / 2.0, 0, 1)  # 2h wind-down before next sleep
        daytime_level = 5.5 + 1.2 * np.sin((hours_since_wake - 6) / 10 * np.pi) + rng.normal(0, 0.4)
        eda_base = np.where(awake, eda_overnight + ramp * wind_down * (daytime_level - eda_overnight), eda_overnight)
        eda_base = np.clip(eda_base, 0.5, None)

        # --- HR diurnal shape: low overnight (sleep), higher through waking hours ---
        hr_overnight = 58 + 3 * np.sin(hour_of_day / 24 * 2 * np.pi)
        hr_daytime = 74 + 6 * np.sin((hours_since_wake - 5) / 10 * np.pi)
        hr_base = np.where(awake, hr_overnight + ramp * (hr_daytime - hr_overnight), hr_overnight)

        # --- random fluctuation events through waking hours: everyday arousal noise, not scripted tasks ---
        n_events = rng.integers(3, 6)
        event_starts_hr = np.sort(rng.uniform(1.0, 14.0, size=n_events))  # hours-since-wake
        eda_event_add = np.zeros(sec_per_day)
        hr_event_add = np.zeros(sec_per_day)
        for ev_h in event_starts_hr:
            dur_s = rng.uniform(180, 480)
            amp_eda = rng.uniform(0.8, 3.0)
            amp_hr = rng.uniform(8, 22)
            ev_start_s = int(ev_h * 3600)
            tail = np.arange(sec_per_day) - ev_start_s
            decay = np.where(tail >= 0, np.exp(-tail / (dur_s / 2)), 0.0)
            eda_event_add += amp_eda * decay
            hr_event_add += amp_hr * decay

        eda_signal = eda_base + eda_event_add + rng.normal(0, 0.12, sec_per_day)
        eda_signal = np.clip(eda_signal, 0.05, None)
        hr_signal = hr_base + hr_event_add + rng.normal(0, 1.5, sec_per_day)
        hr_signal = np.clip(hr_signal, 40, 190)

        ts_ms = (t0.value // 1_000_000) + secs * 1000

        eda_rows.append(pd.DataFrame({
            "session_id": session_id, "participant_email": "synthetic_diurnal@example.com",
            "timestamp_ms": ts_ms, "conductance_uS": np.round(eda_signal, 4),
            "status": 0, "received_at_ms": ts_ms + 50,
        }))

        rr_ms = 60000.0 / hr_signal
        hr_rows.append(pd.DataFrame({
            "session_id": session_id, "participant_email": "synthetic_diurnal@example.com",
            "timestamp_ms": ts_ms, "heart_rate_bpm": np.round(hr_signal, 1),
            "hr_status": 0,
            "ibi_list_ms": [json.dumps([round(float(r), 1)]) for r in rr_ms],
            "ibi_status_list": "[0]",
            "received_at_ms": ts_ms + 50,
        }))

        # --- cortisol ground-truth samples at 4 standard clock times, with measurement noise ---
        def cortisol_at(hour_since_wake_point):
            car = car_amp * np.exp(-((hour_since_wake_point - 0.5) ** 2) / (2 * 0.25 ** 2))
            dec = decline_start * np.exp(-hour_since_wake_point / decline_tau_hr) + decline_floor
            return max(0.02, dec + car)

        samples = [
            ("wake", 0.0),
            ("wake_plus30", 0.5),
            ("midday", (12.0 - day_wake_hour) % 24),
            ("evening", (20.0 - day_wake_hour) % 24),
        ]
        for label, hsw in samples:
            true_val = cortisol_at(hsw)
            measured = max(0.01, true_val + rng.normal(0, 0.015))
            clock_hour = (day_wake_hour + hsw) % 24
            hh = int(clock_hour)
            mm = int(round((clock_hour - hh) * 60))
            cortisol_rows.append({
                "day": day + 1, "label": label, "clock_time": f"{hh:02d}:{mm:02d}",
                "cortisol_value": round(measured, 4), "true_value_noiseless": round(true_val, 4),
            })

    eda_df = pd.concat(eda_rows, ignore_index=True)
    hr_df = pd.concat(hr_rows, ignore_index=True)
    cortisol_df = pd.DataFrame(cortisol_rows)

    import os
    os.makedirs(args.out_dir, exist_ok=True)
    eda_path = f"{args.out_dir}/eda_synthetic_diurnal.csv"
    hr_path = f"{args.out_dir}/hr_synthetic_diurnal.csv"
    cortisol_path = f"{args.out_dir}/cortisol_ground_truth.csv"
    eda_df.to_csv(eda_path, index=False)
    hr_df.to_csv(hr_path, index=False)
    cortisol_df.to_csv(cortisol_path, index=False)

    print(f"Wrote {len(eda_df)} EDA rows -> {eda_path}")
    print(f"Wrote {len(hr_df)} HR rows -> {hr_path}")
    print(f"Wrote {len(cortisol_df)} cortisol ground-truth rows -> {cortisol_path}")
    print()
    print(cortisol_df.to_string(index=False))


if __name__ == "__main__":
    main()
