p = "merge_windows.py"
src = open(p, encoding="utf-8").read()

# 1) Module docstring: document the new --primary-grid option right under "THE PRIMARY GRID".
old_doc = '''THE PRIMARY GRID
-----------------
HRV windows are the primary grid (--primary lets you pick a different one) -- every
output row is one HRV window (session_id, window_start_ms, window_end_ms from that
table), with the other signals attached to it. HRV/EDA/skin-temp all default to the
same window_sec (30s), so for those two the join is a straightforward per-session
merge_asof: nearest EDA/skin-temp window_start_ms to each HRV window_start_ms, within
--tolerance-sec (default: one primary window's worth).'''
assert src.count(old_doc) == 1, f"doc match={src.count(old_doc)}"
new_doc = '''THE PRIMARY GRID
-----------------
By default (--primary-grid hrv, unchanged from before) HRV windows are the primary
grid -- every output row is one HRV window (session_id, window_start_ms,
window_end_ms from that table), with the other signals attached to it. HRV/EDA/
skin-temp all default to the same window_sec (30s), so for those two the join is a
straightforward per-session merge_asof: nearest EDA/skin-temp window_start_ms to
each HRV window_start_ms, within --tolerance-sec (default: one primary window's
worth).

CAVEAT this mode inherits: this watch's HRV windows are frequently low_confidence
(too few valid beats -- see hrv_eda_windowing.py's own IBI-plausibility filtering),
and some windows have NO HRV row at all. In --primary-grid hrv mode, a window with
no HRV row simply never becomes an output row at all -- its EDA/skin-temp/accel data
for that time range is silently dropped too, even when those signals were fine.

--primary-grid union instead builds the row grid from the UNION of (session_id,
window_start_ms) keys across whichever of HRV/EDA/skin-temp have data, then attaches
ALL THREE (HRV included, as hrv_* columns) via the same nearest-merge_asof used for
EDA/skin-temp today. A window missing only its HRV reading still becomes an output
row, with hrv_* columns NaN/low_confidence and eda_*/skintemp_* filled in normally --
nothing with real EDA/skin-temp/accel data is dropped just because HRV happened to
be missing or too sparse for that window. Column shape differs from --primary-grid
hrv mode (HRV's own columns move from bare names to an hrv_ prefix, matching EDA/
skin-temp's existing convention) -- anything reading the merged CSV by column name
needs to account for that if switching modes.'''
src = src.replace(old_doc, new_doc)

# 2) Usage block: mention the new flag.
old_usage = '''Usage:
    python merge_windows.py --hrv hr_windows.csv --eda eda_windows.csv
        --accel accel_windows.csv --skin-temp skin_temp_windows.csv
        [--cortisol cortisol_samples.csv] [--cortisol-lag-min 20]
        [--tolerance-sec <primary window_sec>] [--out merged_windows.csv]'''
assert src.count(old_usage) == 1
new_usage = '''Usage:
    python merge_windows.py --hrv hr_windows.csv --eda eda_windows.csv
        --accel accel_windows.csv --skin-temp skin_temp_windows.csv
        [--cortisol cortisol_samples.csv] [--cortisol-lag-min 20]
        [--tolerance-sec <primary window_sec>] [--primary-grid {hrv,union}]
        [--out merged_windows.csv]'''
src = src.replace(old_usage, new_usage)

# 3) New constant next to the other *_DEFAULT ones.
old_const = "TOLERANCE_SEC_DEFAULT = None  # None -> inferred as one primary-window's worth (see main())\nCORTISOL_LAG_MIN_DEFAULT = 20.0\n"
assert src.count(old_const) == 1
new_const = old_const + "PRIMARY_GRID_DEFAULT = \"hrv\"  # \"hrv\" keeps existing behavior; \"union\" keeps EDA/skin-temp/\n                                # accel rows even where HRV has no window at all (see module docstring)\n"
src = src.replace(old_const, new_const)

# 4) New build_union_grid() helper, placed right before "Top-level merge".
old_marker = '''# ---------------------------------------------------------------------------
# Top-level merge
# ---------------------------------------------------------------------------'''
assert src.count(old_marker) == 1
new_helper = '''# ---------------------------------------------------------------------------
# Primary grid: either HRV's own windows (old behavior), or the UNION of
# (session_id, window_start_ms) keys across HRV/EDA/skin-temp (see module docstring)
# ---------------------------------------------------------------------------

def _primary_window_sec(*tables):
    """Median window duration (seconds) from the first non-empty table given."""
    for t in tables:
        if t is not None and len(t) > 0:
            return float((t["window_end_ms"] - t["window_start_ms"]).median()) / 1000.0
    raise ValueError("_primary_window_sec: no non-empty table given to infer window_sec from")


def build_union_grid(hrv, eda, skin_temp, window_sec):
    """Builds the primary grid as the union of (session_id, window_start_ms) keys across
    HRV, EDA and skin-temp windows, instead of requiring every output row to already be
    an HRV window. A window that only EDA (or only skin-temp) has a row for still gets a
    row here -- under the old hrv-is-mandatory-primary design that window's EDA/skin-temp/
    accel data would have been silently dropped along with its missing HRV row."""
    window_ms = window_sec * 1000.0
    key_frames, email_frames = [], []
    for t in (hrv, eda, skin_temp):
        if t is not None and len(t) > 0:
            key_frames.append(t[["session_id", "window_start_ms"]])
            if "participant_email" in t.columns:
                email_frames.append(t[["session_id", "participant_email"]].drop_duplicates())
    if not key_frames:
        raise ValueError("build_union_grid: --hrv, --eda and --skin-temp are all empty -- "
                          "nothing to build a union grid from")
    keys = (pd.concat(key_frames, ignore_index=True)
              .drop_duplicates(subset=["session_id", "window_start_ms"]))
    keys["window_end_ms"] = keys["window_start_ms"] + window_ms
    if email_frames:
        emails = pd.concat(email_frames, ignore_index=True).drop_duplicates(subset=["session_id"])
        keys = keys.merge(emails, on="session_id", how="left")
    else:
        keys["participant_email"] = np.nan
    return keys.sort_values(["session_id", "window_start_ms"]).reset_index(drop=True)


# ---------------------------------------------------------------------------
# Top-level merge
# ---------------------------------------------------------------------------'''
src = src.replace(old_marker, new_helper)

# 5) merge_all(): accept primary_grid, build `primary`/`out` from either mode, add the
#    hrv_joined asof path for union mode.
old_merge_all = '''def merge_all(hrv, eda, accel, skin_temp, cortisol=None,
              cortisol_lag_min=CORTISOL_LAG_MIN_DEFAULT, tolerance_sec=TOLERANCE_SEC_DEFAULT):
    if hrv is None or len(hrv) == 0:
        raise ValueError("merge_all: --hrv is the primary grid and cannot be empty")

    primary_window_sec = ((hrv["window_end_ms"] - hrv["window_start_ms"]).median()) / 1000.0
    tolerance_ms = (tolerance_sec if tolerance_sec is not None else primary_window_sec) * 1000.0

    out = hrv.rename(columns={c: f"hrv_{c}" for c in HRV_FEATURE_COLS})

    eda_joined = asof_join(hrv, eda, "eda", EDA_FEATURE_COLS, tolerance_ms)
    skin_joined = asof_join(hrv, skin_temp, "skintemp", SKINTEMP_FEATURE_COLS, tolerance_ms)
    accel_joined = aggregate_accel(hrv, accel)

    out = out.merge(eda_joined, on=["session_id", "window_start_ms"], how="left")
    out = out.merge(skin_joined, on=["session_id", "window_start_ms"], how="left")
    out = out.merge(accel_joined, on=["session_id", "window_start_ms"], how="left")

    if cortisol is not None and len(cortisol) > 0:
        cortisol_joined = attach_cortisol(hrv, cortisol, cortisol_lag_min)
        out = out.merge(cortisol_joined, on=["session_id", "window_start_ms"], how="left")

    return out.sort_values(["session_id", "window_start_ms"]).reset_index(drop=True)'''
assert src.count(old_merge_all) == 1
new_merge_all = '''def merge_all(hrv, eda, accel, skin_temp, cortisol=None,
              cortisol_lag_min=CORTISOL_LAG_MIN_DEFAULT, tolerance_sec=TOLERANCE_SEC_DEFAULT,
              primary_grid=PRIMARY_GRID_DEFAULT):
    if primary_grid not in ("hrv", "union"):
        raise ValueError(f"merge_all: primary_grid must be 'hrv' or 'union', got {primary_grid!r}")

    if primary_grid == "hrv":
        if hrv is None or len(hrv) == 0:
            raise ValueError("merge_all: --primary-grid hrv requires --hrv to be non-empty "
                              "(pass --primary-grid union to build the row grid from whichever "
                              "of HRV/EDA/skin-temp has data instead)")
        primary_window_sec = _primary_window_sec(hrv)
        primary = hrv[["session_id", "window_start_ms", "window_end_ms"]].copy()
        out = hrv.rename(columns={c: f"hrv_{c}" for c in HRV_FEATURE_COLS})
    else:
        primary_window_sec = _primary_window_sec(hrv, eda, skin_temp)
        primary = build_union_grid(hrv, eda, skin_temp, primary_window_sec)
        out = primary.copy()

    tolerance_ms = (tolerance_sec if tolerance_sec is not None else primary_window_sec) * 1000.0

    if primary_grid == "union":
        hrv_joined = asof_join(primary, hrv, "hrv", HRV_FEATURE_COLS, tolerance_ms)
        out = out.merge(hrv_joined, on=["session_id", "window_start_ms"], how="left")

    eda_joined = asof_join(primary, eda, "eda", EDA_FEATURE_COLS, tolerance_ms)
    skin_joined = asof_join(primary, skin_temp, "skintemp", SKINTEMP_FEATURE_COLS, tolerance_ms)
    accel_joined = aggregate_accel(primary, accel)

    out = out.merge(eda_joined, on=["session_id", "window_start_ms"], how="left")
    out = out.merge(skin_joined, on=["session_id", "window_start_ms"], how="left")
    out = out.merge(accel_joined, on=["session_id", "window_start_ms"], how="left")

    if cortisol is not None and len(cortisol) > 0:
        cortisol_joined = attach_cortisol(primary, cortisol, cortisol_lag_min)
        out = out.merge(cortisol_joined, on=["session_id", "window_start_ms"], how="left")

    return out.sort_values(["session_id", "window_start_ms"]).reset_index(drop=True)'''
src = src.replace(old_merge_all, new_merge_all)

# 6) CLI: add --primary-grid, relax the --hrv-required check for union mode.
old_cli_arg = '''    ap.add_argument("--tolerance-sec", type=float, default=None,
                     help="max gap (seconds) for an EDA/skin-temp window to be matched to a "
                          "primary window; default is one primary window's own duration")'''
assert src.count(old_cli_arg) == 1
new_cli_arg = old_cli_arg + '''
    ap.add_argument("--primary-grid", choices=["hrv", "union"], default=PRIMARY_GRID_DEFAULT,
                     help="'hrv' (default, unchanged behavior): every output row is an HRV "
                          "window; EDA/skin-temp/accel data for a window with no HRV row is "
                          "dropped. 'union': output rows are the union of (session_id, "
                          "window_start_ms) keys across HRV/EDA/skin-temp, so a window missing "
                          "only its HRV reading still appears, with hrv_* columns NaN instead "
                          "of the whole row vanishing.")'''
src = src.replace(old_cli_arg, new_cli_arg)

old_required_check = '''        if not args.hrv:
            ap.error("--hrv is required (the primary grid) unless --demo is given")
        hrv_w = pd.read_csv(args.hrv)'''
assert src.count(old_required_check) == 1
new_required_check = '''        if args.primary_grid == "hrv" and not args.hrv:
            ap.error("--hrv is required with --primary-grid hrv (the default) unless --demo is "
                     "given -- pass --primary-grid union if --hrv may be empty/missing")
        if args.primary_grid == "union" and not (args.hrv or args.eda or args.skin_temp):
            ap.error("--primary-grid union needs at least one of --hrv/--eda/--skin-temp")
        hrv_w = pd.read_csv(args.hrv) if args.hrv else None'''
src = src.replace(old_required_check, new_required_check)

old_merge_call = '''    merged = merge_all(hrv_w, eda_w, accel_w, temp_w, cortisol,
                        args.cortisol_lag_min, args.tolerance_sec)'''
assert src.count(old_merge_call) == 1
new_merge_call = '''    merged = merge_all(hrv_w, eda_w, accel_w, temp_w, cortisol,
                        args.cortisol_lag_min, args.tolerance_sec, args.primary_grid)'''
src = src.replace(old_merge_call, new_merge_call)

open(p, "w", encoding="utf-8").write(src)
print("merge_windows.py patched, new size:", len(src.encode()))
