p = "diurnal_correlation.py"
src = open(p, encoding="utf-8").read()

old_loop = '''    rows = []
    for _, crow in cortisol.iterrows():
        delta = (merged["tod_min"] - crow["tod_min"]).abs()
        delta = np.minimum(delta, 1440 - delta)  # wrap around midnight
        match = merged[delta <= args.tolerance_min]
        row = {"label": crow["label"], "clock_time": crow["clock_time"],
               "cortisol_value": crow["cortisol_value"], "n_windows_matched": len(match)}
        for c in feature_cols:
            row[c] = match[c].mean() if len(match) else np.nan
        rows.append(row)

    result = pd.DataFrame(rows)
    result = add_residual_columns(result, feature_cols)'''
assert src.count(old_loop) == 1

new_loop = '''    has_session_scoping = "session_id" in cortisol.columns

    rows = []
    for _, crow in cortisol.iterrows():
        delta = (merged["tod_min"] - crow["tod_min"]).abs()
        delta = np.minimum(delta, 1440 - delta)  # wrap around midnight
        candidates = merged[delta <= args.tolerance_min]
        # session_id present -> this cortisol sample belongs to one specific day's
        # recording; narrow the match to that session instead of pooling every day
        # that happens to share this clock time (see module docstring).
        if has_session_scoping and pd.notna(crow.get("session_id")):
            candidates = candidates[candidates["session_id"] == crow["session_id"]]
        row = {"label": crow["label"], "clock_time": crow["clock_time"],
               "cortisol_value": crow["cortisol_value"], "n_windows_matched": len(candidates)}
        if has_session_scoping and pd.notna(crow.get("session_id")):
            row["session_id"] = crow["session_id"]
        for c in feature_cols:
            low_conf_col = _low_confidence_col_for(c)
            match = candidates
            if low_conf_col and low_conf_col in candidates.columns:
                match = candidates[candidates[low_conf_col] != True]  # noqa: E712 (NaN-safe != True)
            row[c] = match[c].mean() if len(match) else np.nan
        rows.append(row)

    result = pd.DataFrame(rows)
    result = add_residual_columns(result, feature_cols)

    rows_per_label = result.groupby("label").size()
    if (rows_per_label <= 1).all():
        print("\\nNOTE: every label has exactly one cortisol row (no session_id-scoped repeats "
              "across different days/sessions) -- the \\"residual\\" correlation below is "
              "mathematically guaranteed to be 0 for every feature (nothing to subtract a "
              "cross-day mean from) and should not be read as \\"no relationship.\\" It only "
              "becomes meaningful once cortisol_samples.csv has more than one row for the same "
              "label, each tagged with its own session_id -- e.g. several days of daily cortisol "
              "draws from the same participant at the same clock-time label.", file=sys.stderr)'''
src = src.replace(old_loop, new_loop)

open(p, "w", encoding="utf-8").write(src)
print("matching loop rewritten, size:", len(src.encode()))
