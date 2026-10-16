import re

p = "skin_temp_windowing.py"
src = open(p, encoding="utf-8").read()

# 1) New tunable constants, right after MIN_SAMPLES_DEFAULT's comment block.
old_const = (
    'MIN_SAMPLES_DEFAULT = 2          # need >= 2 points to fit a slope; a single-sample window\n'
    '                                  # gets low_confidence=True and skin_slope_c_per_s=NaN\n'
)
assert src.count(old_const) == 1, f"const match={src.count(old_const)}"
new_const = old_const + (
    'MIN_SPAN_FRACTION_DEFAULT = 0.5   # a window'"'"'s samples must span at least this fraction of\n'
    '                                  # window_sec -- fitting a "slope over the window" from two\n'
    '                                  # points huddled in the first few seconds and calling it the\n'
    '                                  # window'"'"'s trend would be misleading (audit item: skin-temp\n'
    '                                  # duration/gap checks)\n'
    'MAX_GAP_FRACTION_DEFAULT = 0.5    # if the largest gap between consecutive samples inside a\n'
    '                                  # window exceeds this fraction of window_sec, the "slope" is\n'
    '                                  # really just two disconnected readings bridged by a long dead\n'
    '                                  # gap, not an observed trend -- flagged low_confidence too\n'
)
src = src.replace(old_const, new_const)

# 2) compute_windows signature: accept the two new thresholds.
old_sig = "def compute_windows(df, window_sec=WINDOW_SEC_DEFAULT, min_samples=MIN_SAMPLES_DEFAULT):"
assert src.count(old_sig) == 1
new_sig = (
    "def compute_windows(df, window_sec=WINDOW_SEC_DEFAULT, min_samples=MIN_SAMPLES_DEFAULT,\n"
    "                     min_span_fraction=MIN_SPAN_FRACTION_DEFAULT,\n"
    "                     max_gap_fraction=MAX_GAP_FRACTION_DEFAULT):"
)
src = src.replace(old_sig, new_sig)

# 3) Docstring addition for the new low_confidence behavior (small, factual).
old_doc = (
    "    \"\"\"Bins skin-temp samples into fixed window_sec windows per session (windows never\n"
    "    cross a session boundary, same convention as the other two windowing scripts) and\n"
    "    returns one row per window with ambient/skin mean+std and a skin_c slope (deg C per\n"
    "    second) fit over the window's samples.\"\"\"\n"
)
assert src.count(old_doc) == 1
new_doc = (
    "    \"\"\"Bins skin-temp samples into fixed window_sec windows per session (windows never\n"
    "    cross a session boundary, same convention as the other two windowing scripts) and\n"
    "    returns one row per window with ambient/skin mean+std and a skin_c slope (deg C per\n"
    "    second) fit over the window's samples.\n\n"
    "    low_confidence is True when n_samples < min_samples, OR (for n >= 2) when the samples'\n"
    "    actual timestamp span covers less than min_span_fraction of window_sec, OR when the\n"
    "    largest gap between consecutive samples exceeds max_gap_fraction of window_sec -- a slope\n"
    "    fit over a short span or across a long dead gap is not a trustworthy window trend even\n"
    "    when n_samples alone looks sufficient.\"\"\"\n"
)
src = src.replace(old_doc, new_doc)

# 4) The per-window loop: compute span_s/max_gap_s for every window (not just slope-eligible
#    ones), and fold them into low_confidence.
old_block = '''            if n >= 2:
                span_s = float(t_sec[-1] - t_sec[0])
                mean_interval_s = round(span_s / (n - 1), 3) if span_s > 0 else 0.0
                skin_slope = np.polyfit(t_sec, skin, 1)[0]
            else:
                mean_interval_s = np.nan
                skin_slope = np.nan

            rows.append({
                "session_id": session_id,
                "participant_email": participant_email,
                "window_start_ms": session_start_ms + idx * window_ms,
                "window_end_ms": session_start_ms + (idx + 1) * window_ms,
                "n_samples": n,
                "mean_interval_s": mean_interval_s,
                "ambient_mean_c": round(float(ambient.mean()), 3),
                "ambient_std_c": round(float(ambient.std(ddof=0)), 3) if n > 1 else 0.0,
                "skin_mean_c": round(float(skin.mean()), 3),
                "skin_std_c": round(float(skin.std(ddof=0)), 3) if n > 1 else 0.0,
                "skin_slope_c_per_s": round(float(skin_slope), 6) if not np.isnan(skin_slope) else np.nan,
                "low_confidence": bool(n < min_samples),
            })'''
assert src.count(old_block) == 1, f"block match={src.count(old_block)}"
new_block = '''            if n >= 2:
                span_s = float(t_sec[-1] - t_sec[0])
                mean_interval_s = round(span_s / (n - 1), 3) if span_s > 0 else 0.0
                max_gap_s = float(np.diff(t_sec).max())
                skin_slope = np.polyfit(t_sec, skin, 1)[0]
                span_too_short = span_s < min_span_fraction * window_sec
                gap_too_large = max_gap_s > max_gap_fraction * window_sec
            else:
                span_s = 0.0
                mean_interval_s = np.nan
                max_gap_s = np.nan
                skin_slope = np.nan
                span_too_short = False   # n < min_samples already covers this case
                gap_too_large = False

            rows.append({
                "session_id": session_id,
                "participant_email": participant_email,
                "window_start_ms": session_start_ms + idx * window_ms,
                "window_end_ms": session_start_ms + (idx + 1) * window_ms,
                "n_samples": n,
                "mean_interval_s": mean_interval_s,
                "span_s": round(span_s, 3),
                "max_gap_s": round(max_gap_s, 3) if not np.isnan(max_gap_s) else np.nan,
                "ambient_mean_c": round(float(ambient.mean()), 3),
                "ambient_std_c": round(float(ambient.std(ddof=0)), 3) if n > 1 else 0.0,
                "skin_mean_c": round(float(skin.mean()), 3),
                "skin_std_c": round(float(skin.std(ddof=0)), 3) if n > 1 else 0.0,
                "skin_slope_c_per_s": round(float(skin_slope), 6) if not np.isnan(skin_slope) else np.nan,
                "low_confidence": bool(n < min_samples or span_too_short or gap_too_large),
            })'''
src = src.replace(old_block, new_block)

open(p, "w", encoding="utf-8").write(src)
print("skin_temp_windowing.py patched, new size:", len(src.encode()))
