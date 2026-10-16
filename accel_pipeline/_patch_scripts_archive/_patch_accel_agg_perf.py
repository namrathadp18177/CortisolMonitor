p = "merge_windows.py"
src = open(p, encoding="utf-8").read()

old_fn = '''def aggregate_accel(primary, accel):
    """For each primary window, aggregates every accel sub-window whose interval
    overlaps it. Returns primary's join keys plus accel_* summary columns -- rows
    with no overlapping accel data at all get accel_n_subwindows = 0 and NaN
    summary columns (distinct from 'checked and found no motion')."""
    if accel is None or len(accel) == 0:
        out = primary[["session_id", "window_start_ms"]].copy()
        for col in ["accel_n_subwindows", "accel_pct_motion_flagged", "accel_any_motion_flagged",
                    "accel_mean_mag_g", "accel_pct_low_confidence"]:
            out[col] = np.nan
        return out

    merged = primary[["session_id", "window_start_ms", "window_end_ms"]].merge(
        accel, on="session_id", suffixes=("", "_accel"))
    overlap = merged[
        (merged["window_start_ms_accel"] < merged["window_end_ms"]) &
        (merged["window_end_ms_accel"] > merged["window_start_ms"])
    ]

    agg = overlap.groupby(["session_id", "window_start_ms"]).agg(
        accel_n_subwindows=("motion_flag", "size"),
        accel_pct_motion_flagged=("motion_flag", "mean"),
        accel_any_motion_flagged=("motion_flag", "any"),
        accel_mean_mag_g=("mag_mean_g", "mean"),
        accel_pct_low_confidence=("low_confidence", "mean"),
    ).reset_index()
    agg["accel_pct_motion_flagged"] = (agg["accel_pct_motion_flagged"] * 100).round(1)
    agg["accel_pct_low_confidence"] = (agg["accel_pct_low_confidence"] * 100).round(1)
    agg["accel_mean_mag_g"] = agg["accel_mean_mag_g"].round(4)

    keys = primary[["session_id", "window_start_ms"]].copy()
    out = keys.merge(agg, on=["session_id", "window_start_ms"], how="left")
    out["accel_n_subwindows"] = out["accel_n_subwindows"].fillna(0).astype(int)
    out["accel_any_motion_flagged"] = out["accel_any_motion_flagged"].astype("boolean")
    return out'''
assert src.count(old_fn) == 1, f"fn match={src.count(old_fn)}"

new_fn = '''ACCEL_SUMMARY_COLS = ["accel_n_subwindows", "accel_pct_motion_flagged", "accel_any_motion_flagged",
                      "accel_mean_mag_g", "accel_pct_low_confidence"]


def aggregate_accel(primary, accel):
    """For each primary window, aggregates every accel sub-window whose interval
    overlaps it. Returns primary's join keys plus accel_* summary columns -- rows
    with no overlapping accel data at all get accel_n_subwindows = 0 and NaN
    summary columns (distinct from 'checked and found no motion').

    Finds overlaps via a per-session sorted interval search (np.searchsorted on each
    session's accel window_start_ms/window_end_ms, which are sorted and non-overlapping)
    instead of a session_id merge followed by filtering -- that approach materializes
    len(primary-rows-in-session) x len(accel-rows-in-session) rows BEFORE the overlap
    filter removes most of them, which runs out of memory once the primary grid grows
    past HRV's own (often sparse) row count -- e.g. under --primary-grid union, where a
    session's primary row count can approach its EDA/skin-temp row count instead of
    HRV's smaller one, while still joining against the same large accel table."""
    if accel is None or len(accel) == 0:
        out = primary[["session_id", "window_start_ms"]].copy()
        for col in ACCEL_SUMMARY_COLS:
            out[col] = np.nan
        return out

    accel_by_session = {sid: g.sort_values("window_start_ms")
                         for sid, g in accel.groupby("session_id", sort=False)}

    results = []
    for session_id, pg in primary.groupby("session_id", sort=False):
        pg = pg[["session_id", "window_start_ms", "window_end_ms"]]
        p_start = pg["window_start_ms"].to_numpy(dtype=float)
        p_end = pg["window_end_ms"].to_numpy(dtype=float)
        n = len(pg)

        n_sub = np.zeros(n, dtype=int)
        pct_motion = np.full(n, np.nan)
        any_motion = np.full(n, None, dtype=object)
        mean_mag = np.full(n, np.nan)
        pct_low_conf = np.full(n, np.nan)

        a = accel_by_session.get(session_id)
        if a is not None and len(a) > 0:
            a_start = a["window_start_ms"].to_numpy(dtype=float)
            a_end = a["window_end_ms"].to_numpy(dtype=float)
            motion_flag = a["motion_flag"].to_numpy(dtype=bool)
            mag_mean_g = a["mag_mean_g"].to_numpy(dtype=float)
            low_conf = a["low_confidence"].to_numpy(dtype=bool)

            # overlap condition: a_start < p_end AND a_end > p_start. a_start/a_end are
            # both sorted ascending (accel_windowing.py emits fixed-width, non-overlapping,
            # increasing windows per session), so each side is a single searchsorted call
            # across every primary window in this session at once.
            idx_hi = np.searchsorted(a_start, p_end, side="left")
            idx_lo = np.searchsorted(a_end, p_start, side="right")

            for i in range(n):
                lo, hi = idx_lo[i], idx_hi[i]
                if hi > lo:
                    n_sub[i] = hi - lo
                    pct_motion[i] = round(float(motion_flag[lo:hi].mean()) * 100, 1)
                    any_motion[i] = bool(motion_flag[lo:hi].any())
                    mean_mag[i] = round(float(mag_mean_g[lo:hi].mean()), 4)
                    pct_low_conf[i] = round(float(low_conf[lo:hi].mean()) * 100, 1)

        res = pg[["session_id", "window_start_ms"]].copy()
        res["accel_n_subwindows"] = n_sub
        res["accel_pct_motion_flagged"] = pct_motion
        res["accel_any_motion_flagged"] = any_motion
        res["accel_mean_mag_g"] = mean_mag
        res["accel_pct_low_confidence"] = pct_low_conf
        results.append(res)

    out = pd.concat(results, ignore_index=True) if results else primary[["session_id", "window_start_ms"]].copy()
    if "accel_n_subwindows" not in out.columns:
        for col in ACCEL_SUMMARY_COLS:
            out[col] = np.nan
    out["accel_any_motion_flagged"] = out["accel_any_motion_flagged"].astype("boolean")
    return out'''
src = src.replace(old_fn, new_fn)

open(p, "w", encoding="utf-8").write(src)
print("aggregate_accel rewritten, new size:", len(src.encode()))
