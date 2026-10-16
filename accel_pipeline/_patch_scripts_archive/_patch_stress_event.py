p = "generate_synthetic_diurnal.py"
src = open(p, encoding="utf-8").read()

# 1) Module docstring addition.
old_doc_tail = '''Cortisol ground truth is generated from the SAME underlying diurnal curve
used to drive EDA/HR (cortisol awakening response + exponential decline
across the day), plus independent measurement noise, at 4 standard clock
times per day (wake, wake+30, midday, evening) -- so a real relationship is
known to exist by construction, and the validation step checks whether it
gets recovered.
"""'''
assert src.count(old_doc_tail) == 1
new_doc_tail = '''Cortisol ground truth is generated from the SAME underlying diurnal curve
used to drive EDA/HR (cortisol awakening response + exponential decline
across the day), plus independent measurement noise, at 4 standard clock
times per day (wake, wake+30, midday, evening) -- so a real relationship is
known to exist by construction, and the validation step checks whether it
gets recovered.

OPTIONAL STRESS-EVENT SCENARIO (--stress-event, off by default so the plain
diurnal-only fixture above stays reproducible): injects one LARGE, clearly
distinguishable EDA/HR burst (bigger and more sustained than the small ambient
"fluctuation" events every day already gets) on one specific day at one specific
hours-since-wake, PLUS a genuine delayed cortisol elevation on top of the ambient
diurnal curve -- a gaussian-ish bump peaking --stress-cortisol-lag-min minutes after
the event's physiological onset (default 20min, matching merge_windows.py's own
--cortisol-lag-min default) and decaying back toward the ambient curve over roughly
90 minutes. Three extra cortisol samples are drawn around the event (at the lag,
+45min, +90min) in addition to the 4 standard daily labels, tagged source=
stress_event in cortisol_ground_truth.csv's new `source` column (source=diurnal for
the original 4). The point: the plain diurnal fixture only proves the pipeline can
recover an ambient, clock-time-correlated rhythm (and, per validate_diurnal_
correlation.py's residual-correlation fix, that alone is NOT evidence of a real
link); this scenario is for checking something different -- whether a genuine,
time-locked event response can be told apart from that ambient rhythm using
merge_windows.py's lag-based attach_cortisol (the "next cortisol sample >= lag min
after window end" matcher), not by pooling across clock-time labels.
"""'''
src = src.replace(old_doc_tail, new_doc_tail)

# 2) New CLI args.
old_args = '''    ap.add_argument("--seed", type=int, default=RNG_SEED_DEFAULT)
    ap.add_argument("--out-dir", default="synthetic_diurnal_test")
    args = ap.parse_args()'''
assert src.count(old_args) == 1
new_args = '''    ap.add_argument("--seed", type=int, default=RNG_SEED_DEFAULT)
    ap.add_argument("--out-dir", default="synthetic_diurnal_test")
    ap.add_argument("--stress-event", action="store_true",
                     help="inject one large EDA/HR burst + delayed cortisol elevation on one "
                          "day (see module docstring) -- off by default")
    ap.add_argument("--stress-event-day", type=int, default=None,
                     help="1-indexed day to put the stress event on (default: last day)")
    ap.add_argument("--stress-event-hour", type=float, default=6.0,
                     help="hours-since-wake the event's physiological onset starts at")
    ap.add_argument("--stress-amp-eda", type=float, default=6.0,
                     help="peak EDA burst amplitude (uS added) -- bigger than ambient fluctuation events (0.8-3.0)")
    ap.add_argument("--stress-amp-hr", type=float, default=35.0,
                     help="peak HR burst amplitude (bpm added) -- bigger than ambient fluctuation events (8-22)")
    ap.add_argument("--stress-dur-min", type=float, default=8.0,
                     help="EDA/HR burst duration (minutes) -- longer/more sustained than ambient events")
    ap.add_argument("--stress-cortisol-amp", type=float, default=0.22,
                     help="peak cortisol elevation above the ambient diurnal curve at the event")
    ap.add_argument("--stress-cortisol-lag-min", type=float, default=20.0,
                     help="minutes after event onset the cortisol bump peaks -- matches the "
                          "known ~15-30min autonomic-to-cortisol delay")
    args = ap.parse_args()
    if args.stress_event and args.stress_event_day is None:
        args.stress_event_day = args.days'''
src = src.replace(old_args, new_args)

# 3) Per-day loop: compute whether this is the stress day, build the event's time-domain
#    EDA/HR addition and the continuous cortisol bump, add them to the signals/curve.
old_fluct_block = '''        # --- random fluctuation events through waking hours: everyday arousal noise, not scripted tasks ---
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
        hr_signal = np.clip(hr_signal, 40, 190)'''
assert src.count(old_fluct_block) == 1
new_fluct_block = '''        # --- random fluctuation events through waking hours: everyday arousal noise, not scripted tasks ---
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

        # --- optional explicit stress event (--stress-event): one large, sustained burst plus
        # a delayed cortisol elevation layered on top of the ambient curve -- see module docstring ---
        is_stress_day = bool(args.stress_event and (day + 1) == args.stress_event_day)
        stress_cortisol_bump = np.zeros(sec_per_day)
        event_onset_ts_ms = None
        if is_stress_day:
            ev_start_s = int(args.stress_event_hour * 3600)
            event_onset_ts_ms = int(ts_ms[0]) + ev_start_s * 1000
            tail = np.arange(sec_per_day) - ev_start_s
            dur_s = args.stress_dur_min * 60.0
            burst_decay = np.where(tail >= 0, np.exp(-tail / (dur_s / 2)), 0.0)
            eda_event_add = eda_event_add + args.stress_amp_eda * burst_decay
            hr_event_add = hr_event_add + args.stress_amp_hr * burst_decay
            # cortisol: gaussian bump centered at onset + lag, independent of the EDA/HR decay
            # shape above -- this is what makes it a genuinely DELAYED response, not instantaneous
            lag_s = args.stress_cortisol_lag_min * 60.0
            bump_center_s = ev_start_s + lag_s
            bump_width_s = 35.0 * 60.0  # ~35min gaussian half-width -> back near baseline by ~90min
            stress_cortisol_bump = args.stress_cortisol_amp * np.exp(
                -((np.arange(sec_per_day) - bump_center_s) ** 2) / (2 * bump_width_s ** 2))

        eda_signal = eda_base + eda_event_add + rng.normal(0, 0.12, sec_per_day)
        eda_signal = np.clip(eda_signal, 0.05, None)
        hr_signal = hr_base + hr_event_add + rng.normal(0, 1.5, sec_per_day)
        hr_signal = np.clip(hr_signal, 40, 190)'''
src = src.replace(old_fluct_block, new_fluct_block)

# 4) cortisol_at() closure needs the stress bump available by hours-since-wake, and the extra
#    stress-event samples need to be drawn and appended, tagged with a `source` column.
old_cortisol_block = '''        # --- cortisol ground-truth samples at 4 standard clock times, with measurement noise ---
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
            })'''
assert src.count(old_cortisol_block) == 1
new_cortisol_block = '''        # --- cortisol ground-truth samples at 4 standard clock times, with measurement noise ---
        def cortisol_at(hour_since_wake_point, include_stress_bump=False):
            car = car_amp * np.exp(-((hour_since_wake_point - 0.5) ** 2) / (2 * 0.25 ** 2))
            dec = decline_start * np.exp(-hour_since_wake_point / decline_tau_hr) + decline_floor
            val = dec + car
            if include_stress_bump and is_stress_day:
                sec_point = int(hour_since_wake_point * 3600)
                if 0 <= sec_point < sec_per_day:
                    val += stress_cortisol_bump[sec_point]
            return max(0.02, val)

        samples = [
            ("wake", 0.0, "diurnal"),
            ("wake_plus30", 0.5, "diurnal"),
            ("midday", (12.0 - day_wake_hour) % 24, "diurnal"),
            ("evening", (20.0 - day_wake_hour) % 24, "diurnal"),
        ]
        if is_stress_day:
            lag_hr = args.stress_cortisol_lag_min / 60.0
            samples += [
                ("stress_event_lag", args.stress_event_hour + lag_hr, "stress_event"),
                ("stress_event_plus45m", args.stress_event_hour + lag_hr + 0.75, "stress_event"),
                ("stress_event_plus90m", args.stress_event_hour + lag_hr + 1.5, "stress_event"),
            ]
        for label, hsw, source in samples:
            true_val = cortisol_at(hsw, include_stress_bump=True)
            measured = max(0.01, true_val + rng.normal(0, 0.015))
            clock_hour = (day_wake_hour + hsw) % 24
            hh = int(clock_hour)
            mm = int(round((clock_hour - hh) * 60))
            cortisol_rows.append({
                "day": day + 1, "label": label, "clock_time": f"{hh:02d}:{mm:02d}",
                "cortisol_value": round(measured, 4), "true_value_noiseless": round(true_val, 4),
                "source": source,
                "sample_time_ms": int(ts_ms[0]) + int(hsw * 3600) * 1000,
            })
        if is_stress_day:
            event_meta.append({
                "session_id": session_id, "event_onset_ts_ms": event_onset_ts_ms,
                "event_onset_hour_since_wake": args.stress_event_hour,
                "cortisol_lag_min": args.stress_cortisol_lag_min,
                "stress_amp_eda": args.stress_amp_eda, "stress_amp_hr": args.stress_amp_hr,
                "stress_cortisol_amp": args.stress_cortisol_amp,
            })'''
src = src.replace(old_cortisol_block, new_cortisol_block)

# 5) init event_meta list alongside the other row lists.
old_init = '''    hr_rows = []
    eda_rows = []
    cortisol_rows = []'''
assert src.count(old_init) == 1
src = src.replace(old_init, old_init + "\n    event_meta = []")

# 6) write event_meta out alongside the other outputs.
old_write = '''    print(f"Wrote {len(cortisol_df)} cortisol ground-truth rows -> {cortisol_path}")
    print()
    print(cortisol_df.to_string(index=False))'''
assert src.count(old_write) == 1
new_write = '''    print(f"Wrote {len(cortisol_df)} cortisol ground-truth rows -> {cortisol_path}")
    if event_meta:
        import json as _json
        event_path = f"{args.out_dir}/stress_event_ground_truth.json"
        with open(event_path, "w") as fh:
            _json.dump(event_meta, fh, indent=2)
        print(f"Wrote stress-event ground truth -> {event_path}")
    print()
    print(cortisol_df.to_string(index=False))'''
src = src.replace(old_write, new_write)

open(p, "w", encoding="utf-8").write(src)
print("generate_synthetic_diurnal.py patched, size:", len(src.encode()))
