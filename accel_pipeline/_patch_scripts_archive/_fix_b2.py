p = "hrv_eda_windowing.py"
src = open(p, encoding="utf-8").read()

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
print("match count:", src.count(old_metrics))
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
open(p, "w", encoding="utf-8").write(src)
print("rewrote _hrv_metrics, size:", len(src.encode()))
