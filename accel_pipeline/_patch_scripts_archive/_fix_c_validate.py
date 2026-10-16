p = "validate_diurnal_correlation.py"
src = open(p, encoding="utf-8").read()

old_helper_marker = '''def add_residual_columns(result, feature_cols):'''
assert src.count(old_helper_marker) == 1
new_helper = '''def _low_confidence_col_for(feature_col):
    """Maps a feature column to its companion *_low_confidence column, if the merged
    table has one -- see diurnal_correlation.py's own copy of this helper."""
    for prefix in ("hrv_", "eda_", "skintemp_"):
        if feature_col.startswith(prefix):
            return f"{prefix}low_confidence"
    return None


def add_residual_columns(result, feature_cols):'''
src = src.replace(old_helper_marker, new_helper)

old_match = '''        match = day_data[delta <= args.tolerance_min]
        row = {"day": c["day"], "label": c["label"], "clock_time": c["clock_time"],
               "cortisol_value": c["cortisol_value"], "n_windows_matched": len(match)}
        for f in feature_cols:
            row[f] = match[f].mean() if len(match) else np.nan'''
assert src.count(old_match) == 1
new_match = '''        candidates = day_data[delta <= args.tolerance_min]
        row = {"day": c["day"], "label": c["label"], "clock_time": c["clock_time"],
               "cortisol_value": c["cortisol_value"], "n_windows_matched": len(candidates)}
        for f in feature_cols:
            low_conf_col = _low_confidence_col_for(f)
            match = candidates
            if low_conf_col and low_conf_col in candidates.columns:
                match = candidates[candidates[low_conf_col] != True]  # noqa: E712 (NaN-safe != True)
            row[f] = match[f].mean() if len(match) else np.nan'''
src = src.replace(old_match, new_match)

open(p, "w", encoding="utf-8").write(src)
print("validate_diurnal_correlation.py: low-confidence exclusion added, size:", len(src.encode()))
