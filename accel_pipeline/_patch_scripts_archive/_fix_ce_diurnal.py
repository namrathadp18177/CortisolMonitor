p = "diurnal_correlation.py"
src = open(p, encoding="utf-8").read()

# --- docstring: document the two new behaviors ---
old_doc_block = '''cortisol_samples.csv columns:
    label, clock_time, cortisol_value
    e.g.  wake,        06:30,  0.35
          wake_plus30, 07:00,  0.42
          midday,      12:30,  0.11
          evening,     20:00,  0.08

Merged window CSV(s): output of merge_windows.py (one or more files/sessions;
multiple days are just concatenated here, matched by time-of-day only).
"""'''
assert src.count(old_doc_block) == 1
new_doc_block = '''cortisol_samples.csv columns:
    label, clock_time, cortisol_value
    e.g.  wake,        06:30,  0.35
          wake_plus30, 07:00,  0.42
          midday,      12:30,  0.11
          evening,     20:00,  0.08

    An optional `session_id` column may be added when a cortisol sample is tied to a
    SPECIFIC day/session's recording (e.g. November's daily-cortisol-per-participant
    protocol) -- that sample is then matched ONLY to windows from that exact session,
    not pooled across every other day too. Without `session_id` (the single self-test
    case -- one cortisol test compared against several days of wearable wear, which is
    what this script was originally built for), matching stays day-agnostic/pooled as
    before, since there's no day-specific cortisol value to match a specific day
    against in the first place -- but in that case there is also only ONE cortisol row
    per label, which means the "residual" correlation below is mathematically
    guaranteed to be exactly 0 for every feature (a single-row group has no cross-day
    mean to differ from) -- this script now says so explicitly rather than silently
    printing a 0.000 that could be misread as "no relationship found."

LOW-CONFIDENCE WINDOWS: a window flagged hrv_low_confidence (too few valid beats) or
eda_low_confidence (too little EDA coverage) is excluded before averaging that
signal's own feature columns -- the unreliable reading no longer silently pulls the
label average in whatever direction it happens to be wrong in.

Merged window CSV(s): output of merge_windows.py (one or more files/sessions;
multiple days are just concatenated here, matched by time-of-day only, unless
cortisol_samples.csv's session_id column narrows a given label to one session).
"""'''
src = src.replace(old_doc_block, new_doc_block)

# --- new helper: pick a feature's companion low_confidence column, if any ---
old_clock_fn = '''def clock_to_minutes(hhmm):
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)'''
assert src.count(old_clock_fn) == 1
new_clock_fn = old_clock_fn + '''


def _low_confidence_col_for(feature_col):
    """Maps a feature column to its companion *_low_confidence column, if the merged
    table has one -- "hrv_rmssd" -> "hrv_low_confidence", "eda_mean" -> "eda_low_confidence".
    Returns None if the feature doesn't have a recognized hrv_/eda_/skintemp_ prefix."""
    for prefix in ("hrv_", "eda_", "skintemp_"):
        if feature_col.startswith(prefix):
            return f"{prefix}low_confidence"
    return None'''
src = src.replace(old_clock_fn, new_clock_fn)

open(p, "w", encoding="utf-8").write(src)
print("docstring + helper added, size:", len(src.encode()))
