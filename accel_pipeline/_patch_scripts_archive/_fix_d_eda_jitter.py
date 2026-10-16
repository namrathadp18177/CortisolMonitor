p = "hrv_eda_windowing.py"
src = open(p, encoding="utf-8").read()

old_const = '''IRREGULAR_SPACING_TOLERANCE = 0.2  # a contiguous segment's median inter-sample gap deviating
    # from the nominal rate by more than this fraction is treated as irregularly sampled --
    # cvxEDA/eda_peaks reason in samples-per-second, so handing them unevenly spaced samples
    # as if they were uniform would distort the tonic/phasic split and SCR timing'''
assert src.count(old_const) == 1

new_const = '''IRREGULAR_SPACING_TOLERANCE = 0.2  # a contiguous segment's median inter-sample gap deviating
    # from the nominal rate by more than this fraction is treated as irregularly sampled --
    # cvxEDA/eda_peaks reason in samples-per-second, so handing them unevenly spaced samples
    # as if they were uniform would distort the tonic/phasic split and SCR timing
IRREGULAR_GAP_JITTER_TOLERANCE = 0.2   # a single inter-sample gap deviating from nominal by
    # more than this fraction counts as "jittery"
IRREGULAR_GAP_JITTER_FRACTION = 0.10   # ...and if more than this fraction of a segment's gaps
    # are jittery, the segment is treated as irregular EVEN IF its median gap looks fine --
    # a median-only check misses a segment that's mostly on-rate with a scattered handful of
    # bad gaps throughout (audit finding: "EDA irregularity detection relies on median sample
    # spacing ... intermittent irregularities aren't overlooked")'''
src = src.replace(old_const, new_const)

old_check = '''        is_irregular = False
        if seg_n >= 3:
            nominal_dt_ms = 1000.0 / sampling_rate
            median_dt_ms = float(np.median(np.diff(seg_ts)))
            is_irregular = abs(median_dt_ms - nominal_dt_ms) > IRREGULAR_SPACING_TOLERANCE * nominal_dt_ms'''
assert src.count(old_check) == 1

new_check = '''        is_irregular = False
        if seg_n >= 3:
            nominal_dt_ms = 1000.0 / sampling_rate
            gaps_ms = np.diff(seg_ts)
            median_dt_ms = float(np.median(gaps_ms))
            median_off = abs(median_dt_ms - nominal_dt_ms) > IRREGULAR_SPACING_TOLERANCE * nominal_dt_ms
            # per-gap jitter check: catches a segment whose MEDIAN gap looks fine but that has a
            # scattered handful of individually bad gaps throughout (a median alone can't see this)
            jittery_frac = float(np.mean(np.abs(gaps_ms - nominal_dt_ms) >
                                          IRREGULAR_GAP_JITTER_TOLERANCE * nominal_dt_ms))
            jitter_off = jittery_frac > IRREGULAR_GAP_JITTER_FRACTION
            is_irregular = median_off or jitter_off'''
src = src.replace(old_check, new_check)

open(p, "w", encoding="utf-8").write(src)
print("EDA jitter check added, size:", len(src.encode()))
