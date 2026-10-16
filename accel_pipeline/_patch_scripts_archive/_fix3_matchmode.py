p = "diurnal_correlation.py"
src = open(p, encoding="utf-8").read()

old_arg = '''    ap.add_argument("--tolerance-min", type=float, default=15.0, help="how close (minutes) a window's time-of-day must be to a cortisol sample's clock time to be matched")
    ap.add_argument("--out", default="diurnal_correlation_result.csv")'''
assert src.count(old_arg) == 1
new_arg = '''    ap.add_argument("--tolerance-min", type=float, default=15.0, help="how close (minutes) a window's time-of-day must be to a cortisol sample's clock time to be matched")
    ap.add_argument("--match-mode", choices=["auto", "pooled", "session"], default="auto",
                     help="'auto' (default): use session_id-scoped matching for any cortisol row "
                          "that has one, fall back to pooled (day-agnostic) matching for rows that "
                          "don't -- can silently mix both per row. 'pooled': always ignore "
                          "session_id, even if the column exists -- the explicit single-cortisol- "
                          "test mode. 'session': every cortisol row MUST have a non-null session_id "
                          "-- the explicit multi-day/multi-session mode; errors out instead of "
                          "silently pooling a row that's missing one.")
    ap.add_argument("--out", default="diurnal_correlation_result.csv")'''
src = src.replace(old_arg, new_arg)

old_scoping = '''    has_session_scoping = "session_id" in cortisol.columns

    rows = []
    for _, crow in cortisol.iterrows():
        delta = (merged["tod_min"] - crow["tod_min"]).abs()
        delta = np.minimum(delta, 1440 - delta)  # wrap around midnight
        candidates = merged[delta <= args.tolerance_min]
        # session_id present -> this cortisol sample belongs to one specific day's
        # recording; narrow the match to that session instead of pooling every day
        # that happens to share this clock time (see module docstring).
        if has_session_scoping and pd.notna(crow.get("session_id")):
            candidates = candidates[candidates["session_id"] == crow["session_id"]]'''
assert src.count(old_scoping) == 1
new_scoping = '''    has_session_col = "session_id" in cortisol.columns
    if args.match_mode == "session":
        if not has_session_col or cortisol["session_id"].isna().any():
            missing = cortisol[~cortisol.get("session_id", pd.Series(dtype=object)).notna()] \\
                if has_session_col else cortisol
            ap.error("--match-mode session requires every cortisol row to have a non-null "
                      f"session_id; {len(missing)} row(s) don't (labels: "
                      f"{missing['label'].tolist() if 'label' in missing.columns else '?'}). "
                      "Fix the CSV, or pass --match-mode pooled/auto instead.")
    elif args.match_mode == "pooled" and has_session_col:
        print("NOTE: --match-mode pooled -- ignoring the session_id column present in "
              "--cortisol; every row is matched day-agnostically across all of --merged.",
              file=sys.stderr)

    rows = []
    for _, crow in cortisol.iterrows():
        delta = (merged["tod_min"] - crow["tod_min"]).abs()
        delta = np.minimum(delta, 1440 - delta)  # wrap around midnight
        candidates = merged[delta <= args.tolerance_min]
        # session_id present -> this cortisol sample belongs to one specific day's
        # recording; narrow the match to that session instead of pooling every day
        # that happens to share this clock time (see module docstring). --match-mode
        # makes this explicit instead of silently inferred per row (audit finding:
        # "diurnal matching still allows unscoped pooling").
        use_session_scope = (
            args.match_mode == "session"
            or (args.match_mode == "auto" and has_session_col and pd.notna(crow.get("session_id")))
        )
        if use_session_scope:
            candidates = candidates[candidates["session_id"] == crow["session_id"]]'''
src = src.replace(old_scoping, new_scoping)

# has_session_scoping was used later for the output row / degenerate-residual check too --
# rename references consistently.
old_row_tag = '''        if has_session_scoping and pd.notna(crow.get("session_id")):
            row["session_id"] = crow["session_id"]'''
assert src.count(old_row_tag) == 1
new_row_tag = '''        if use_session_scope:
            row["session_id"] = crow["session_id"]'''
src = src.replace(old_row_tag, new_row_tag)

open(p, "w", encoding="utf-8").write(src)
print("explicit --match-mode added, size:", len(src.encode()))
