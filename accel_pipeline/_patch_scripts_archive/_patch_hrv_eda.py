import re

path = "hrv_eda_windowing.py"
with open(path) as f:
    src = f.read()

orig = src

# --- 1. new plausibility/despike constants, right after HRV_MIN_BEATS_DEFAULT block ---
anchor = 'HRV_MIN_BEATS_DEFAULT = 30        # HrvCalculator.hasEnoughData()\nPNN50_THRESHOLD_MS = 50.0         # HrvCalculator\'s fixed NN50 threshold'
assert anchor in src, "anchor1 not found"
replacement = anchor + '''
IBI_MIN_PLAUSIBLE_MS = 300   # 200 bpm -- above even maximal exertion; faster IBIs are sensor
                              # noise/double-counted beats, not real heartbeats
IBI_MAX_PLAUSIBLE_MS = 2000  # 30 bpm -- below resting bradycardia/deep sleep; slower IBIs are
                              # almost always a missed-beat artifact, not a real heartbeat
IBI_MAX_RELATIVE_JUMP = 0.25  # reject a beat that differs from the previous ACCEPTED beat by
                               # more than 25% (a simple ectopic-beat/artifact despike, in the
                               # same spirit as the fixed-threshold rules used by Kubios/Malik's
                               # rule -- not a substitute for a real arrhythmia detector, just a
                               # guard against single corrupted samples skewing RMSSD/SDNN)'''
src = src.replace(anchor, replacement, 1)

# --- 2. add _filter_plausible_ibis() right before _hrv_metrics() ---
anchor2 = 'def _hrv_metrics(ibi_ms):'
assert anchor2 in src, "anchor2 not found"
new_func = '''def _filter_plausible_ibis(ibi_ms):
    """Drops IBI values outside a physiologically plausible range, then despikes the
    remaining sequence (in arrival order) by rejecting any beat that jumps more than
    IBI_MAX_RELATIVE_JUMP from the last ACCEPTED beat -- a single corrupted IBI sample
    (sensor glitch, double-counted beat) would otherwise distort RMSSD/SDNN far more
    than its one-sample share should. Returns (filtered_list, n_rejected). This runs
    AFTER _parse_valid_ibis' own status==0 filter -- the device's own status flag and
    this plausibility check catch different failure modes (status flags a sample the
    sensor itself didn't trust; this catches a sample the sensor trusted but that is
    still physiologically impossible or a wild outlier relative to its neighbours)."""
    n_in = len(ibi_ms)
    range_ok = [v for v in ibi_ms if IBI_MIN_PLAUSIBLE_MS <= v <= IBI_MAX_PLAUSIBLE_MS]

    filtered = []
    last_accepted = None
    for v in range_ok:
        if last_accepted is None or abs(v - last_accepted) / last_accepted <= IBI_MAX_RELATIVE_JUMP:
            filtered.append(v)
            last_accepted = v
        # else: rejected as a despike outlier relative to the last accepted beat
    return filtered, n_in - len(filtered)


def _hrv_metrics(ibi_ms):'''
src = src.replace(anchor2, new_func, 1)

# --- 3. use the filter inside compute_hrv_windows, and record ibi_rejected_count ---
anchor3 = '''            ibi_window = [v for lst in wdf["_valid_ibis"] for v in lst]  # flatten, in order
            metrics = _hrv_metrics(ibi_window)
            low_confidence = bool(metrics["beat_count"] < hrv_min_beats or coverage < min_coverage)
            row = {
                "session_id": session_id,
                "participant_email": participant_email,
                "window_start_ms": session_start_ms + idx * window_ms,
                "window_end_ms": session_start_ms + (idx + 1) * window_ms,
                "n_hr_samples": n_hr_samples,
                "expected_hr_samples": round(expected_samples),
                "sample_coverage": round(coverage, 3),
                **metrics,
                "low_confidence": low_confidence,
            }'''
assert anchor3 in src, "anchor3 not found"
replacement3 = '''            ibi_window_raw = [v for lst in wdf["_valid_ibis"] for v in lst]  # flatten, in order
            ibi_window, n_rejected = _filter_plausible_ibis(ibi_window_raw)
            metrics = _hrv_metrics(ibi_window)
            low_confidence = bool(metrics["beat_count"] < hrv_min_beats or coverage < min_coverage)
            row = {
                "session_id": session_id,
                "participant_email": participant_email,
                "window_start_ms": session_start_ms + idx * window_ms,
                "window_end_ms": session_start_ms + (idx + 1) * window_ms,
                "n_hr_samples": n_hr_samples,
                "expected_hr_samples": round(expected_samples),
                "sample_coverage": round(coverage, 3),
                **metrics,
                "ibi_rejected_implausible": n_rejected,
                "low_confidence": low_confidence,
            }'''
assert anchor3 in src
src = src.replace(anchor3, replacement3, 1)

# --- 4. dedupe HR raw samples by timestamp before windowing ---
anchor4 = '''    for session_id, g in df.groupby("session_id", sort=False):
        g = g.sort_values("timestamp_ms")
        session_start_ms = g["timestamp_ms"].iloc[0]
        participant_email = g["participant_email"].iloc[0]
        window_idx = ((g["timestamp_ms"] - session_start_ms) // window_ms).astype(int)
        for idx, wdf in g.groupby(window_idx):
            n_hr_samples = len(wdf)'''
assert anchor4 in src, "anchor4 not found"
replacement4 = '''    for session_id, g in df.groupby("session_id", sort=False):
        g = g.sort_values("timestamp_ms").drop_duplicates(subset=["timestamp_ms"])
        session_start_ms = g["timestamp_ms"].iloc[0]
        participant_email = g["participant_email"].iloc[0]
        window_idx = ((g["timestamp_ms"] - session_start_ms) // window_ms).astype(int)
        for idx, wdf in g.groupby(window_idx):
            n_hr_samples = len(wdf)'''
src = src.replace(anchor4, replacement4, 1)

# --- 5. dedupe EDA raw samples by timestamp before windowing ---
anchor5 = '''    for session_id, g in df.groupby("session_id", sort=False):
        g = g.sort_values("timestamp_ms").reset_index(drop=True)
        session_start_ms = g["timestamp_ms"].iloc[0]
        participant_email = g["participant_email"].iloc[0]
        window_idx = ((g["timestamp_ms"] - session_start_ms) // window_ms).astype(int)

        if decompose:'''
assert anchor5 in src, "anchor5 not found"
replacement5 = '''    for session_id, g in df.groupby("session_id", sort=False):
        g = g.sort_values("timestamp_ms").drop_duplicates(subset=["timestamp_ms"]).reset_index(drop=True)
        session_start_ms = g["timestamp_ms"].iloc[0]
        participant_email = g["participant_email"].iloc[0]
        window_idx = ((g["timestamp_ms"] - session_start_ms) // window_ms).astype(int)

        if decompose:'''
src = src.replace(anchor5, replacement5, 1)

assert src != orig, "no changes applied!"
with open(path, "w") as f:
    f.write(src)
print("hrv_eda_windowing.py patched OK, bytes:", len(orig), "->", len(src))
