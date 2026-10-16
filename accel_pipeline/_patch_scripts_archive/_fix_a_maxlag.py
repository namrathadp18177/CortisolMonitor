p = "merge_windows.py"
src = open(p, encoding="utf-8").read()

old_const = "CORTISOL_LAG_MIN_DEFAULT = 20.0\n"
assert src.count(old_const) == 1
new_const = old_const + (
    "CORTISOL_LAG_MAX_MIN_DEFAULT = 90.0  # reject a cortisol match drawn further than this "
    "after window end -- without a ceiling, a window near the start of a long session could "
    "silently match a cortisol sample drawn hours later in that same session (audit finding: "
    "\"has a minimum lag but no maximum\")\n"
)
assert src.count(old_const) == 1
src = src.replace(old_const, new_const, 1)

old_fn = '''def attach_cortisol(primary, cortisol, lag_min):
    lag_ms = lag_min * 60_000.0
    keys = primary[["session_id", "window_start_ms", "window_end_ms"]].copy()
    keys["_eligible_from_ms"] = (keys["window_end_ms"] + lag_ms).astype("float64")

    left = keys.sort_values("_eligible_from_ms").reset_index(drop=True)
    right = cortisol[["session_id", "sample_time_ms", "cortisol_value"]].copy()
    right["sample_time_ms"] = right["sample_time_ms"].astype("float64")
    right = right.sort_values("sample_time_ms").reset_index(drop=True)

    joined = pd.merge_asof(
        left, right,
        left_on="_eligible_from_ms", right_on="sample_time_ms",
        by="session_id", direction="forward",
    )
    joined = joined.rename(columns={"sample_time_ms": "cortisol_sample_time_ms"})
    joined["cortisol_lag_min_actual"] = (
        (joined["cortisol_sample_time_ms"] - joined["window_end_ms"]) / 60_000.0
    ).round(2)
    return joined[["session_id", "window_start_ms", "cortisol_sample_time_ms",
                    "cortisol_value", "cortisol_lag_min_actual"]]'''
assert src.count(old_fn) == 1

new_fn = '''def attach_cortisol(primary, cortisol, lag_min, lag_max_min=CORTISOL_LAG_MAX_MIN_DEFAULT):
    """Attaches the NEXT cortisol sample drawn >= lag_min minutes after a window ends,
    same as before, but now also REJECTS that match if the sample was drawn more than
    lag_max_min minutes after the window ends. Without this ceiling, merge_asof's
    forward match has no upper bound at all -- a window near the start of a long
    session with no nearby cortisol draw would happily match whatever the NEXT sample
    in that session turns out to be, even if it's hours away and physiologically
    meaningless as "this window's resulting cortisol." Pass lag_max_min=None to
    restore the old unbounded behavior."""
    lag_ms = lag_min * 60_000.0
    keys = primary[["session_id", "window_start_ms", "window_end_ms"]].copy()
    keys["_eligible_from_ms"] = (keys["window_end_ms"] + lag_ms).astype("float64")

    left = keys.sort_values("_eligible_from_ms").reset_index(drop=True)
    right = cortisol[["session_id", "sample_time_ms", "cortisol_value"]].copy()
    right["sample_time_ms"] = right["sample_time_ms"].astype("float64")
    right = right.sort_values("sample_time_ms").reset_index(drop=True)

    # merge_asof's own `tolerance` is measured from the join key (_eligible_from_ms,
    # already shifted forward by lag_min), so passing lag_max_min - lag_min here caps
    # the sample's distance from _eligible_from_ms -- i.e. the sample can land anywhere
    # from lag_min up to lag_max_min minutes after window_end_ms, not lag_max_min minutes
    # after the already-shifted eligibility point.
    tolerance_ms = None
    if lag_max_min is not None:
        if lag_max_min < lag_min:
            raise ValueError(f"attach_cortisol: lag_max_min ({lag_max_min}) must be >= "
                              f"lag_min ({lag_min})")
        tolerance_ms = (lag_max_min - lag_min) * 60_000.0

    joined = pd.merge_asof(
        left, right,
        left_on="_eligible_from_ms", right_on="sample_time_ms",
        by="session_id", direction="forward", tolerance=tolerance_ms,
    )
    joined = joined.rename(columns={"sample_time_ms": "cortisol_sample_time_ms"})
    joined["cortisol_lag_min_actual"] = (
        (joined["cortisol_sample_time_ms"] - joined["window_end_ms"]) / 60_000.0
    ).round(2)
    return joined[["session_id", "window_start_ms", "cortisol_sample_time_ms",
                    "cortisol_value", "cortisol_lag_min_actual"]]'''
src = src.replace(old_fn, new_fn)

# Wire it through merge_all() and the CLI.
old_merge_all_sig = '''def merge_all(hrv, eda, accel, skin_temp, cortisol=None,
              cortisol_lag_min=CORTISOL_LAG_MIN_DEFAULT, tolerance_sec=TOLERANCE_SEC_DEFAULT,
              primary_grid=PRIMARY_GRID_DEFAULT):'''
assert src.count(old_merge_all_sig) == 1
new_merge_all_sig = '''def merge_all(hrv, eda, accel, skin_temp, cortisol=None,
              cortisol_lag_min=CORTISOL_LAG_MIN_DEFAULT, tolerance_sec=TOLERANCE_SEC_DEFAULT,
              primary_grid=PRIMARY_GRID_DEFAULT, cortisol_lag_max_min=CORTISOL_LAG_MAX_MIN_DEFAULT):'''
src = src.replace(old_merge_all_sig, new_merge_all_sig)

old_attach_call = "        cortisol_joined = attach_cortisol(primary, cortisol, cortisol_lag_min)"
assert src.count(old_attach_call) == 1
new_attach_call = "        cortisol_joined = attach_cortisol(primary, cortisol, cortisol_lag_min, cortisol_lag_max_min)"
src = src.replace(old_attach_call, new_attach_call)

old_cli = '''    ap.add_argument("--cortisol-lag-min", type=float, default=CORTISOL_LAG_MIN_DEFAULT)'''
assert src.count(old_cli) == 1
new_cli = old_cli + '''
    ap.add_argument("--cortisol-lag-max-min", type=float, default=CORTISOL_LAG_MAX_MIN_DEFAULT,
                     help="reject a cortisol match drawn more than this many minutes after "
                          "window end (default 90) -- pass a negative number (e.g. -1) to "
                          "disable the ceiling and restore the old unbounded behavior")'''
src = src.replace(old_cli, new_cli)

old_merge_call = '''    merged = merge_all(hrv_w, eda_w, accel_w, temp_w, cortisol,
                        args.cortisol_lag_min, args.tolerance_sec, args.primary_grid)'''
assert src.count(old_merge_call) == 1
new_merge_call = '''    lag_max = None if args.cortisol_lag_max_min is not None and args.cortisol_lag_max_min < 0 else args.cortisol_lag_max_min
    merged = merge_all(hrv_w, eda_w, accel_w, temp_w, cortisol,
                        args.cortisol_lag_min, args.tolerance_sec, args.primary_grid, lag_max)'''
src = src.replace(old_merge_call, new_merge_call)

open(p, "w", encoding="utf-8").write(src)
print("merge_windows.py: max-lag fix applied, size:", len(src.encode()))
