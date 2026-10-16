p = "hrv_eda_windowing.py"
src = open(p, encoding="utf-8").read()

old_fn = '''def _filter_plausible_ibis(ibi_ms):
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
    return filtered, n_in - len(filtered)'''
print("match count:", src.count(old_fn))
assert src.count(old_fn) == 1

new_fn = '''def _filter_plausible_ibis(ibi_ms):
    """Drops IBI values outside a physiologically plausible range, then despikes the
    remaining sequence (in arrival order) by rejecting any beat that jumps more than
    IBI_MAX_RELATIVE_JUMP from the last ACCEPTED beat -- a single corrupted IBI sample
    (sensor glitch, double-counted beat) would otherwise distort RMSSD/SDNN far more
    than its one-sample share should. This runs AFTER _parse_valid_ibis' own status==0
    filter -- the device's own status flag and this plausibility check catch different
    failure modes (status flags a sample the sensor itself didn't trust; this catches a
    sample the sensor trusted but that is still physiologically impossible or a wild
    outlier relative to its neighbours).

    Returns (list_of_runs, n_rejected) -- NOT a single flat list. A run breaks at every
    rejected beat (range violation or despike), not just at sensor-level gaps (that part
    is _split_into_runs' job, upstream of this function): the accepted beats immediately
    before and after a rejected one are not truly successive, so a diff across that gap
    would measure the sum of (at least) two real beat intervals, not one true
    beat-to-beat interval -- the same reasoning _split_into_runs applies to dropouts,
    applied here to an individually-rejected beat in the middle of an otherwise-good
    stretch. last_accepted also resets on a rejection, since the next beat has no known
    time relationship to a beat that no longer has an accepted beat directly before it."""
    n_in = len(ibi_ms)
    runs = []
    current = []
    last_accepted = None
    for v in ibi_ms:
        in_range = IBI_MIN_PLAUSIBLE_MS <= v <= IBI_MAX_PLAUSIBLE_MS
        accepted = in_range and (
            last_accepted is None or abs(v - last_accepted) / last_accepted <= IBI_MAX_RELATIVE_JUMP
        )
        if accepted:
            current.append(v)
            last_accepted = v
        else:
            if current:
                runs.append(current)
                current = []
            last_accepted = None  # a rejected beat severs the "last accepted" chain too
    if current:
        runs.append(current)
    n_accepted = sum(len(r) for r in runs)
    return runs, n_in - n_accepted'''
src = src.replace(old_fn, new_fn)

# compute_hrv_windows's loop: _filter_plausible_ibis now returns MULTIPLE sub-runs per
# input run, so extend (not append) them into ibi_runs.
old_loop = '''            ibi_runs_raw = _split_into_runs(wdf)
            ibi_runs, n_rejected = [], 0
            for run in ibi_runs_raw:
                filtered_run, n_rej_run = _filter_plausible_ibis(run)
                n_rejected += n_rej_run
                if filtered_run:
                    ibi_runs.append(filtered_run)
            metrics = _hrv_metrics(ibi_runs)'''
assert src.count(old_loop) == 1
new_loop = '''            ibi_runs_raw = _split_into_runs(wdf)
            ibi_runs, n_rejected = [], 0
            for run in ibi_runs_raw:
                filtered_sub_runs, n_rej_run = _filter_plausible_ibis(run)
                n_rejected += n_rej_run
                ibi_runs.extend(filtered_sub_runs)  # each rejection can split one run into several
            metrics = _hrv_metrics(ibi_runs)'''
src = src.replace(old_loop, new_loop)

open(p, "w", encoding="utf-8").write(src)
print("beat-rejection run-splitting applied, size:", len(src.encode()))
