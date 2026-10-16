p = "hrv_eda_windowing.py"
src = open(p, encoding="utf-8").read()

# 1) New constant for the run-split gap tolerance.
old_const = "IBI_MAX_RELATIVE_JUMP = 0.25"
# find the full existing comment block ending for that constant to append after it cleanly
idx = src.index(old_const)
line_end = src.index("\n", idx)
# find end of the trailing comment lines for this constant (consume subsequent comment-only lines)
j = line_end + 1
while src[j:j+1] == " " or src[j:j+20].lstrip().startswith("#"):
    nxt = src.index("\n", j)
    if src[j:nxt].strip().startswith("#"):
        j = nxt + 1
    else:
        break
insertion_point = j
new_const_block = (
    "RUN_MAX_GAP_FACTOR = 1.5          # a run of successive beats breaks whenever the gap to the\n"
    "                                  # next 1-second HR sample exceeds this factor x the nominal\n"
    "                                  # sample interval, OR the previous sample had zero valid\n"
    "                                  # beats -- RMSSD/pNN50 are defined on SUCCESSIVE beats, so\n"
    "                                  # diffing across a dropout/rejected-sample gap would measure\n"
    "                                  # elapsed real time, not a true beat-to-beat interval (audit\n"
    "                                  # finding: \"HRV outlier removal can join beats across\n"
    "                                  # rejected intervals\")\n"
)
src = src[:insertion_point] + new_const_block + src[insertion_point:]

# 2) New _split_into_runs() helper, placed right before _filter_plausible_ibis.
old_marker = "def _filter_plausible_ibis(ibi_ms):"
assert src.count(old_marker) == 1
new_helper = '''def _split_into_runs(wdf, nominal_dt_ms=None):
    """Splits a window's per-second HR rows (already sorted by timestamp_ms) into
    contiguous beat runs: within a run, every row directly follows the previous one
    (gap <= RUN_MAX_GAP_FACTOR x the nominal sample interval) AND the previous row had
    at least one valid beat. A run breaks before any row where either condition fails,
    since the first beat after a dropout/rejected-sample gap is not actually the next
    heartbeat after whatever beat came before the gap. Returns a list of IBI-ms lists,
    one per run, each in arrival order -- mean_rr/sdnn can pool across runs freely
    (order doesn't matter for those), but successive-difference metrics (rmssd, pnn50)
    must only diff within a single run; see _hrv_metrics."""
    if nominal_dt_ms is None:
        nominal_dt_ms = 1000.0 / HR_EDA_NOMINAL_RATE_HZ
    runs = []
    current = []
    prev_ts = None
    prev_had_beats = True
    for ts, ibis in zip(wdf["timestamp_ms"], wdf["_valid_ibis"]):
        gap_too_big = prev_ts is not None and (ts - prev_ts) > nominal_dt_ms * RUN_MAX_GAP_FACTOR
        breaks_run = (prev_ts is None) or gap_too_big or (not prev_had_beats)
        if breaks_run and current:
            runs.append(current)
            current = []
        if ibis:
            current.extend(ibis)
        prev_ts = ts
        prev_had_beats = bool(ibis)
    if current:
        runs.append(current)
    return runs


def _filter_plausible_ibis(ibi_ms):'''
src = src.replace(old_marker, new_helper)

# 3) _hrv_metrics now takes a list of runs instead of one flat list.
old_metrics = '''def _hrv_metrics(ibi_ms):
    """Same formulas as HrvCalculator.compute() -- see module docstring. Returns
    NaN metrics (with beat_count = len(ibi_ms)) if there are fewer than 2 valid IBI
    values, matching HrvCalculator's "returns null" case."""
    n = len(ibi_ms)
    if n < 2:
        return {"beat_count": n, "mean_rr": np.nan, "mean_hr_bpm": np.nan,
                "sdnn": np.nan, "rmssd": np.nan, "pnn50_percent": np.nan}

    arr = np.array(ibi_ms, dtype=float)
    mean_rr = arr.mean()
    sdnn = arr.std(ddof=0)

    diffs = np.diff(arr)
    rmssd = np.sqrt(np.mean(diffs ** 2))
    pnn50 = 100.0 * np.sum(np.abs(diffs) > PNN50_THRESHOLD_MS) / (n - 1)
    mean_hr = 60000.0 / mean_rr if mean_rr > 0 else 0.0

    return {"beat_count": n, "mean_rr": round(mean_rr, 2), "mean_hr_bpm": round(mean_hr, 2),
            "sdnn": round(sdnn, 2), "rmssd": round(rmssd, 2), "pnn50_percent": round(pnn50, 2)}'''
assert src.count(old_metrics) == 1

new_metrics = '''def _hrv_metrics(ibi_runs):
    """Same formulas as HrvCalculator.compute() -- see module docstring -- except RMSSD/
    pNN50 are now computed from successive differences taken WITHIN each run only, then
    pooled, instead of diffing a single array flattened across the whole window. ibi_runs
    is a list of IBI-ms sequences, one per contiguous beat run (see _split_into_runs).
    mean_rr/sdnn pool every beat regardless of run (order-independent), but rmssd/pnn50
    would be wrong if a diff were taken across a run boundary -- see _split_into_runs'
    docstring. Returns NaN metrics (with beat_count = total beats) if there are fewer
    than 2 valid IBI values total, matching HrvCalculator's "returns null" case; rmssd/
    pnn50 are separately NaN if beats exist but no run has >= 2 of them (no successive
    pair to diff at all)."""
    all_beats = [v for run in ibi_runs for v in run]
    n = len(all_beats)
    if n < 2:
        return {"beat_count": n, "mean_rr": np.nan, "mean_hr_bpm": np.nan,
                "sdnn": np.nan, "rmssd": np.nan, "pnn50_percent": np.nan}

    arr = np.array(all_beats, dtype=float)
    mean_rr = arr.mean()
    sdnn = arr.std(ddof=0)
    mean_hr = 60000.0 / mean_rr if mean_rr > 0 else 0.0

    diff_runs = [np.diff(np.array(run, dtype=float)) for run in ibi_runs if len(run) >= 2]
    if diff_runs:
        all_diffs = np.concatenate(diff_runs)
        rmssd = np.sqrt(np.mean(all_diffs ** 2))
        pnn50 = 100.0 * np.sum(np.abs(all_diffs) > PNN50_THRESHOLD_MS) / len(all_diffs)
    else:
        rmssd = np.nan
        pnn50 = np.nan

    return {"beat_count": n, "mean_rr": round(mean_rr, 2), "mean_hr_bpm": round(mean_hr, 2),
            "sdnn": round(sdnn, 2),
            "rmssd": round(rmssd, 2) if not np.isnan(rmssd) else np.nan,
            "pnn50_percent": round(pnn50, 2) if not np.isnan(pnn50) else np.nan}'''
src = src.replace(old_metrics, new_metrics)

# 4) compute_hrv_windows: build runs, filter+despike each run separately, pass run list
#    to _hrv_metrics -- replaces the old flatten-everything-then-filter-once approach.
old_loop = '''            ibi_window_raw = [v for lst in wdf["_valid_ibis"] for v in lst]
            ibi_window, n_rejected = _filter_plausible_ibis(ibi_window_raw)
            metrics = _hrv_metrics(ibi_window)'''
assert src.count(old_loop) == 1
new_loop = '''            ibi_runs_raw = _split_into_runs(wdf)
            ibi_runs, n_rejected = [], 0
            for run in ibi_runs_raw:
                filtered_run, n_rej_run = _filter_plausible_ibis(run)
                n_rejected += n_rej_run
                if filtered_run:
                    ibi_runs.append(filtered_run)
            metrics = _hrv_metrics(ibi_runs)'''
src = src.replace(old_loop, new_loop)

open(p, "w", encoding="utf-8").write(src)
print("hrv_eda_windowing.py: run-splitting fix applied, size:", len(src.encode()))
