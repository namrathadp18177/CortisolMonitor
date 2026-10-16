path = "hrv_eda_windowing.py"
with open(path) as f:
    src = f.read()
orig = src

anchor = '''def _decompose_eda_session(timestamps_ms, conductance, sampling_rate=HR_EDA_NOMINAL_RATE_HZ,
                            method=EDA_DECOMPOSITION_METHOD_DEFAULT):
    """Runs _decompose_eda_segment() once per contiguous run in one session's full raw
    EDA stream, then stitches the per-segment results back into full-session-length
    tonic/phasic arrays (NaN wherever a segment was too short, skipped, or failed) plus
    a flat list of (session-local index, amplitude) SCR events. Called ONCE per session
    on the complete raw stream -- not per window -- same grouping convention
    compute_hrv_windows/compute_eda_windows already use."""
    n = len(conductance)
    tonic = np.full(n, np.nan)
    phasic = np.full(n, np.nan)
    scr_events = []  # list of (session-local index, amplitude)

    for start, end in _contiguous_segments(timestamps_ms):
        result = _decompose_eda_segment(conductance[start:end], sampling_rate, method)
        if result is None:
            continue
        seg_tonic, seg_phasic, seg_scr_idx, seg_scr_amp = result
        tonic[start:end] = seg_tonic
        phasic[start:end] = seg_phasic
        for local_idx, amp in zip(seg_scr_idx, seg_scr_amp):
            scr_events.append((start + int(local_idx), float(amp)))

    return tonic, phasic, scr_events'''
assert anchor in src, "anchor not found"

replacement = '''IRREGULAR_SPACING_TOLERANCE = 0.2  # a contiguous segment's median inter-sample gap deviating
    # from the nominal rate by more than this fraction is treated as irregularly sampled --
    # cvxEDA/eda_peaks reason in samples-per-second, so handing them unevenly spaced samples
    # as if they were uniform would distort the tonic/phasic split and SCR timing


def _decompose_eda_session(timestamps_ms, conductance, sampling_rate=HR_EDA_NOMINAL_RATE_HZ,
                            method=EDA_DECOMPOSITION_METHOD_DEFAULT):
    """Runs _decompose_eda_segment() once per contiguous run in one session's full raw
    EDA stream, then stitches the per-segment results back into full-session-length
    tonic/phasic arrays (NaN wherever a segment was too short, skipped, or failed) plus
    a flat list of (session-local index, amplitude) SCR events. Called ONCE per session
    on the complete raw stream -- not per window -- same grouping convention
    compute_hrv_windows/compute_eda_windows already use.

    Each contiguous segment's ACTUAL sample spacing is checked against the nominal rate
    before decomposing (see IRREGULAR_SPACING_TOLERANCE above) -- a segment sampled close
    enough to nominal is decomposed as before; one that drifts (batched samples, a few
    dropped ticks that didn't open a big-enough gap to split the segment) is first
    resampled onto a uniform sampling_rate grid (linear interpolation) so cvxEDA/eda_peaks
    see what they assume, then the result is interpolated back onto this segment's own
    original timestamps so every downstream window-aggregation step is unaffected."""
    n = len(conductance)
    tonic = np.full(n, np.nan)
    phasic = np.full(n, np.nan)
    scr_events = []  # list of (session-local index, amplitude)
    ts = np.asarray(timestamps_ms, dtype=float)
    irregular_segments = 0
    total_segments = 0

    for start, end in _contiguous_segments(timestamps_ms):
        total_segments += 1
        seg_ts = ts[start:end]
        seg_conductance = conductance[start:end]
        seg_n = len(seg_conductance)

        is_irregular = False
        if seg_n >= 3:
            nominal_dt_ms = 1000.0 / sampling_rate
            median_dt_ms = float(np.median(np.diff(seg_ts)))
            is_irregular = abs(median_dt_ms - nominal_dt_ms) > IRREGULAR_SPACING_TOLERANCE * nominal_dt_ms

        if not is_irregular:
            result = _decompose_eda_segment(seg_conductance, sampling_rate, method)
            if result is None:
                continue
            seg_tonic, seg_phasic, seg_scr_idx, seg_scr_amp = result
            tonic[start:end] = seg_tonic
            phasic[start:end] = seg_phasic
            for local_idx, amp in zip(seg_scr_idx, seg_scr_amp):
                scr_events.append((start + int(local_idx), float(amp)))
            continue

        irregular_segments += 1
        nominal_dt_ms = 1000.0 / sampling_rate
        grid_ts = np.arange(seg_ts[0], seg_ts[-1] + nominal_dt_ms / 2.0, nominal_dt_ms)
        if len(grid_ts) < EDA_DECOMPOSITION_MIN_SAMPLES:
            continue  # too short even after resampling -- leave this segment's windows NaN
        resampled = np.interp(grid_ts, seg_ts, seg_conductance)
        result = _decompose_eda_segment(resampled, sampling_rate, method)
        if result is None:
            continue
        grid_tonic, grid_phasic, grid_scr_idx, grid_scr_amp = result
        # map the uniform-grid result back onto this segment's own original timestamps
        seg_tonic = np.interp(seg_ts, grid_ts, grid_tonic)
        seg_phasic = np.interp(seg_ts, grid_ts, grid_phasic)
        tonic[start:end] = seg_tonic
        phasic[start:end] = seg_phasic
        for grid_idx, amp in zip(grid_scr_idx, grid_scr_amp):
            peak_t = grid_ts[grid_idx]
            nearest_local = int(np.argmin(np.abs(seg_ts - peak_t)))
            scr_events.append((start + nearest_local, float(amp)))

    if total_segments:
        print(f"  [info] EDA decomposition: {irregular_segments}/{total_segments} contiguous "
              f"segment(s) had irregular sample spacing and were resampled to a uniform "
              f"{sampling_rate:.2f} Hz grid before decomposition.")

    return tonic, phasic, scr_events'''

src = src.replace(anchor, replacement, 1)
assert src != orig
with open(path, "w") as f:
    f.write(src)
print("EDA resampling patch applied, bytes:", len(orig), "->", len(src))
