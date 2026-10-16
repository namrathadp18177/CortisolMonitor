"""
HRV & EDA windowing pipeline.

Cortisol Monitor thesis -- Stage 3 of docs/accelerometer_stress_research_and_plan.md
(the accelerometer half of Stage 3/4 lives in accel_windowing.py, right next to this
file -- this script does the HRV and EDA half of the same stage).

Turns raw exports from VitalsCsvExporter into fixed-length windows with the HRV and
EDA feature summaries Stage 3 calls for:

    "HRV: RMSSD, SDNN, pNN50, mean HR, mean IBI (recompute from the raw per-second
    CSV, same formulas as your on-device HrvCalculator, so the two stay consistent
    as a sanity check)
    EDA: mean skin conductance, and ideally a tonic/phasic decomposition if your
    timeline allows -- even just mean + peak count is a reasonable starting feature
    set"

Inputs (from VitalsCsvExporter.java):
  --hr  hr_*.csv / hr_session_*.csv
        columns: session_id, participant_email, timestamp_ms, heart_rate_bpm,
                 hr_status, ibi_list_ms, ibi_status_list, received_at_ms
        ibi_list_ms / ibi_status_list are JSON-array strings, e.g. "[812,798,845]" --
        Samsung's HEART_RATE_CONTINUOUS tracker batches up to 4 IBI values per
        1-second sample. Only entries whose matching ibi_status_list value is 0 are
        valid (see HrvCalculator.java's class doc) -- everything else is dropped
        here, mirroring VitalsDatabaseHelper's IBI-series query.

  --eda eda_*.csv / eda_session_*.csv
        columns: session_id, participant_email, timestamp_ms, conductance_uS,
                 status, received_at_ms
        EDA_CONTINUOUS samples at 1 Hz.

HRV formulas are copied from HrvCalculator.java so a window computed here and the
on-device HRV card agree on the same beats:
    mean_rr (mean IBI, ms) = mean(IBI)
    mean_hr_bpm            = 60000 / mean_rr
    sdnn                   = population std of IBI (ddof=0)
    rmssd                  = sqrt(mean(successive_diff^2))        over n-1 diffs
    pnn50_percent          = 100 * count(|successive_diff| > 50ms) / (n-1)
    "enough data" gate     = beat_count >= 30   (HrvCalculator.hasEnoughData())

Column names for the two feature sets deliberately match wesad/wesad_loso.py's
existing HRV/EDA columns (mean_rr, sdnn, rmssd, eda_mean, eda_std, eda_peak_count,
eda_slope) plus a couple of device-specific extras (mean_hr_bpm, pnn50_percent,
beat_count) -- so once a real session table exists it can be fed straight into that
same LOSO training code (or used to fine-tune it) without a renaming pass.
`ecg_peak_count` in the WESAD model is analogous to `beat_count` here (WESAD counted
R-peaks in raw ECG; the watch gives beats directly via IBI).

EDA also gets a real tonic (slow skin-conductance-level baseline)/phasic (fast SCR
burst) decomposition when neurokit2 is installed, added as eda_tonic_mean,
eda_tonic_slope, eda_phasic_mean, eda_scr_count, eda_scr_amplitude_sum/_mean, and
eda_decomposition_available columns ALONGSIDE (never replacing) eda_mean/eda_std/
eda_peak_count/eda_slope -- see the "Tonic/phasic EDA decomposition" section below for
how and why. This finally answers the module docstring's own "ideally a tonic/phasic
decomposition if your timeline allows" line above with a real implementation rather
than just the placeholder mean+peak-count feature set.

Like accel_windowing.py, this does NOT try to align HRV windows with EDA windows,
with accelerometer windows, or with cortisol samples -- per VitalsCsvExporter's own
design note, that alignment (session_id + nearest window/timestamp) is meant to
happen in a later pandas merge_asof step (Stage 3's "one row per analysis window"
table), once accel + HRV + EDA + skin-temp windows all exist. This script produces
its half of that eventual join.

IMPORTANT: no real watch session has been exported yet (as of 2026-09-08). --demo
runs a small SYNTHETIC session (same session_id/epoch/segment lengths as
accel_windowing.py's --demo) to sanity-check the parsing/windowing/formulas -- it is
not real HRV/EDA data and proves nothing about real stress detection. Don't treat
its output as anything but a logic check.

Usage:
    python hrv_eda_windowing.py --hr hr_export.csv --eda eda_export.csv
        [--window-sec 30] [--min-coverage 0.5] [--hrv-min-beats 30]
        [--out-hrv hrv_windows.csv] [--out-eda eda_windows.csv]

    python hrv_eda_windowing.py --demo [--window-sec 15]
        Defaults to 15s windows for --demo specifically, so windows line up
        cleanly with the demo's 5min/2min/3min baseline/stress/recovery segments
        (10 minutes total).
"""

import argparse
import glob
import json
import os

import numpy as np
import pandas as pd

try:
    import neurokit2 as nk
    _HAS_NEUROKIT = True
except ImportError:  # optional dependency -- see EDA_DECOMPOSITION_METHOD_DEFAULT below
    nk = None
    _HAS_NEUROKIT = False

HR_EDA_NOMINAL_RATE_HZ = 1.0     # both HEART_RATE_CONTINUOUS and EDA_CONTINUOUS sample at 1Hz

WINDOW_SEC_DEFAULT = 30.0        # long enough to plausibly clear the 30-beat HRV floor at a
                                  # typical resting HR (see HRV_MIN_BEATS_DEFAULT) -- adjust
                                  # once real pilot data shows your actual beat rate; shorter
                                  # windows will show up as low_confidence more often
DEMO_WINDOW_SEC_DEFAULT = 15.0   # aligns cleanly with the demo's 30/15/15s segments
MIN_COVERAGE_DEFAULT = 0.5
HRV_MIN_BEATS_DEFAULT = 30        # HrvCalculator.hasEnoughData()
PNN50_THRESHOLD_MS = 50.0         # HrvCalculator's fixed NN50 threshold
IBI_MIN_PLAUSIBLE_MS = 300   # 200 bpm -- above even maximal exertion; faster IBIs are sensor
                              # noise/double-counted beats, not real heartbeats
IBI_MAX_PLAUSIBLE_MS = 2000  # 30 bpm -- below resting bradycardia/deep sleep; slower IBIs are
                              # almost always a missed-beat artifact, not a real heartbeat
IBI_MAX_RELATIVE_JUMP = 0.25  # reject a beat that differs from the previous ACCEPTED beat by
                               # more than 25% (a simple ectopic-beat/artifact despike, in the
                               # same spirit as the fixed-threshold rules used by Kubios/Malik's
                               # rule -- not a substitute for a real arrhythmia detector, just a
                               # guard against single corrupted samples skewing RMSSD/SDNN)
EDA_MIN_PEAK_PROMINENCE_US = 0.05 # Simple local-max detector behind eda_peak_count below -- not
                                   # a real SCR/tonic-phasic decomposition on its own, but kept
                                   # as-is (existing dashboards and the rule-based classifier's
                                   # baseline computation already depend on eda_mean/eda_std/
                                   # eda_slope/eda_peak_count unchanged). The real tonic/phasic/
                                   # SCR decomposition is the eda_tonic_*/eda_phasic_*/eda_scr_*
                                   # columns added below, alongside these, not in place of them.

# ---------------------------------------------------------------------------
# Tonic/phasic EDA decomposition (neurokit2) -- optional, additive
# ---------------------------------------------------------------------------
# compute_eda_windows() below always produces eda_mean/eda_std/eda_peak_count/eda_slope
# (the simple placeholder feature set). When neurokit2 is installed, it ALSO runs a real
# tonic (slow skin-conductance-level baseline) / phasic (fast SCR burst) decomposition and
# adds eda_tonic_mean, eda_tonic_slope, eda_phasic_mean, eda_scr_count,
# eda_scr_amplitude_sum/_mean, eda_decomposition_available columns -- see
# EDA_FEATURE_COLS in merge_windows.py, which must list a column here for it to survive
# the merge step.
#
# cvxEDA (Greco et al. 2016, via neurokit2.eda_phasic(method="cvxEDA")) assumes an evenly
# -sampled input signal -- its tonic/phasic split and SCR peak detection both reason in
# units of samples-per-second. A real watch export is NOT always evenly sampled end to end
# (the wearable can lose Bluetooth, come off-wrist, or the participant can remove it for
# hours) -- decomposing straight across a multi-hour gap as if it were one second of fast
# EDA movement would plant a spurious, large-amplitude artifact right at the gap boundary.
# So decomposition runs per contiguous run of samples (see _contiguous_segments), never
# across a gap -- windows that fall in a too-short or skipped segment simply get NaN here,
# same spirit as the "low_confidence instead of a guess" rule used for HRV elsewhere in
# this pipeline.
EDA_DECOMPOSITION_METHOD_DEFAULT = "cvxEDA"  # alternatives neurokit2 supports: "highpass",
                                               # "smoothmedian", "sparseEDA" -- cvxEDA is the
                                               # one validated here (real Oct-6 session: full
                                               # 14.3h / 51,542-sample stream decomposes in
                                               # ~1.6s; also checked robust down to very short
                                               # (10-sample) segments)
EDA_DECOMPOSITION_MIN_SAMPLES = 10  # below this, decomposition is untested/unreliable here --
                                      # skip it and leave the new columns NaN rather than guess
EDA_GAP_TOLERANCE_SEC = 5.0 / HR_EDA_NOMINAL_RATE_HZ  # consecutive EDA samples more than this
    # many seconds apart are treated as crossing a real sensor dropout, not a couple of fast
    # seconds -- decomposition is run separately on either side, never bridged across the gap

HR_REQUIRED_COLUMNS = ["session_id", "participant_email", "timestamp_ms",
                        "ibi_list_ms", "ibi_status_list"]
EDA_REQUIRED_COLUMNS = ["session_id", "participant_email", "timestamp_ms", "conductance_uS"]


# ---------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------

def _load_one(path, required_columns):
    df = pd.read_csv(path)
    missing = [c for c in required_columns if c not in df.columns]
    if missing:
        raise ValueError(f"{path}: missing expected column(s) {missing} -- not a matching export?")
    return df


def _load_csv(path, required_columns, glob_prefix):
    """Accepts either a single CSV file, or a directory containing several (as
    produced by repeated VitalsCsvExporter exports), and concatenates them --
    same convention as accel_windowing.py's load_accel()."""
    if os.path.isdir(path):
        files = sorted(glob.glob(os.path.join(path, glob_prefix + "*.csv")))
        if not files:
            raise FileNotFoundError(f"No {glob_prefix}*.csv files found in {path}")
        return pd.concat([_load_one(f, required_columns) for f in files], ignore_index=True)
    return _load_one(path, required_columns)


def load_hr(path):
    return _load_csv(path, HR_REQUIRED_COLUMNS, "hr")


def load_eda(path):
    return _load_csv(path, EDA_REQUIRED_COLUMNS, "eda")


# ---------------------------------------------------------------------------
# HRV
# ---------------------------------------------------------------------------

def _parse_valid_ibis(ibi_list_str, ibi_status_str):
    """One HR sample's ibi_list_ms/ibi_status_list (JSON-array strings) -> the
    subset of IBI values (ms, ints) whose matching status is 0 (valid), in the
    same arrival order Samsung's SDK delivered them -- mirrors the filter
    VitalsDatabaseHelper applies before HrvCalculator ever sees the data."""
    if pd.isna(ibi_list_str) or pd.isna(ibi_status_str):
        return []
    try:
        ibis = json.loads(ibi_list_str)
        statuses = json.loads(ibi_status_str)
    except (TypeError, ValueError):
        return []
    if len(ibis) != len(statuses):
        # Malformed row (shouldn't happen from a real export) -- skip rather than
        # guess which entries line up.
        return []
    return [ibi for ibi, status in zip(ibis, statuses) if status == 0]


def _filter_plausible_ibis(ibi_ms):
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
    return filtered, n_in - len(filtered)


def _hrv_metrics(ibi_ms):
    """Same formulas as HrvCalculator.compute() -- see module docstring. Returns
    NaN metrics (with beat_count = len(ibi_ms)) if there are fewer than 2 valid IBI
    values, matching HrvCalculator's "returns null" case."""
    n = len(ibi_ms)
    if n < 2:
        return {"beat_count": n, "mean_rr": np.nan, "mean_hr_bpm": np.nan,
                "sdnn": np.nan, "rmssd": np.nan, "pnn50_percent": np.nan}

    arr = np.array(ibi_ms, dtype=float)
    mean_rr = arr.mean()
    sdnn = arr.std(ddof=0)

    diffs = np.diff(arr)
    rmssd = np.sqrt(np.mean(diffs ** 2))
    pnn50 = 100.0 * np.sum(np.abs(diffs) > PNN50_THRESHOLD_MS) / (n - 1)
    mean_hr = 60000.0 / mean_rr if mean_rr > 0 else 0.0

    return {"beat_count": n, "mean_rr": round(mean_rr, 2), "mean_hr_bpm": round(mean_hr, 2),
            "sdnn": round(sdnn, 2), "rmssd": round(rmssd, 2), "pnn50_percent": round(pnn50, 2)}


def compute_hrv_windows(df, window_sec=WINDOW_SEC_DEFAULT,
                         min_coverage=MIN_COVERAGE_DEFAULT,
                         hrv_min_beats=HRV_MIN_BEATS_DEFAULT):
    """Bins HR samples into fixed window_sec windows per session (windows never
    cross a session boundary, same convention as accel_windowing.py), concatenates
    each window's valid IBI values in timestamp order, and computes HRV metrics
    per window."""
    df = df.copy()
    df["_valid_ibis"] = [
        _parse_valid_ibis(a, b) for a, b in zip(df["ibi_list_ms"], df["ibi_status_list"])
    ]

    window_ms = window_sec * 1000.0
    expected_samples = window_sec * HR_EDA_NOMINAL_RATE_HZ

    rows = []
    for session_id, g in df.groupby("session_id", sort=False):
        g = g.sort_values("timestamp_ms").drop_duplicates(subset=["timestamp_ms"])
        session_start_ms = g["timestamp_ms"].iloc[0]
        participant_email = g["participant_email"].iloc[0]
        window_idx = ((g["timestamp_ms"] - session_start_ms) // window_ms).astype(int)
        for idx, wdf in g.groupby(window_idx):
            n_hr_samples = len(wdf)
            coverage = n_hr_samples / expected_samples
            ibi_window_raw = [v for lst in wdf["_valid_ibis"] for v in lst]  # flatten, in order
            ibi_window, n_rejected = _filter_plausible_ibis(ibi_window_raw)
            metrics = _hrv_metrics(ibi_window)
            low_confidence = bool(metrics["beat_count"] < hrv_min_beats or coverage < min_coverage)
            row = {
                "session_id": session_id,
                "participant_email": participant_email,
                "window_start_ms": session_start_ms + idx * window_ms,
                "window_end_ms": session_start_ms + (idx + 1) * window_ms,
                "n_hr_samples": n_hr_samples,
                "expected_hr_samples": round(expected_samples),
                "sample_coverage": round(coverage, 3),
                **metrics,
                "ibi_rejected_implausible": n_rejected,
                "low_confidence": low_confidence,
            }
            # Only present when the input carries a "phase" column (currently just
            # make_synthetic_demo() below, tagging its baseline/stress/recovery segments) --
            # a real hr_*.csv export never has this column, so real windows simply won't get
            # a "phase" value, same as every other windowing function in this file staying
            # backward-compatible with real data. Majority vote handles the rare window that
            # straddles a phase boundary.
            if "phase" in wdf.columns:
                row["phase"] = wdf["phase"].mode().iloc[0]
            rows.append(row)
    return pd.DataFrame(rows).sort_values(["session_id", "window_start_ms"]).reset_index(drop=True)


# ---------------------------------------------------------------------------
# EDA
# ---------------------------------------------------------------------------

def _eda_peak_count(values, min_prominence=EDA_MIN_PEAK_PROMINENCE_US):
    """Counts simple local maxima whose rise above the lower of their two
    neighbours clears min_prominence. This is a placeholder peak-finder, not a
    real SCR detector or tonic/phasic decomposition -- good enough for the "even
    just mean + peak count" starting feature set Stage 3 calls for. Swap in a
    proper decomposition (e.g. cvxEDA) later if the thesis needs one."""
    n = len(values)
    if n < 3:
        return 0
    count = 0
    for i in range(1, n - 1):
        if values[i] > values[i - 1] and values[i] > values[i + 1]:
            prominence = values[i] - min(values[i - 1], values[i + 1])
            if prominence >= min_prominence:
                count += 1
    return count


def _contiguous_segments(timestamps_ms, gap_tolerance_sec=EDA_GAP_TOLERANCE_SEC):
    """Splits one session's sorted EDA timestamp array into (start, end) index ranges
    (end exclusive) wherever two consecutive samples are farther apart than
    gap_tolerance_sec -- see the module-level note above compute_eda_windows for why
    decomposition must never bridge a real sensor dropout."""
    n = len(timestamps_ms)
    if n == 0:
        return []
    gap_ms = gap_tolerance_sec * 1000.0
    diffs = np.diff(np.asarray(timestamps_ms, dtype=float))
    breaks = (np.where(diffs > gap_ms)[0] + 1).tolist()
    bounds = [0] + breaks + [n]
    return [(bounds[i], bounds[i + 1]) for i in range(len(bounds) - 1)]


def _decompose_eda_segment(conductance, sampling_rate=HR_EDA_NOMINAL_RATE_HZ,
                            method=EDA_DECOMPOSITION_METHOD_DEFAULT):
    """Tonic/phasic split + SCR peak detection for ONE contiguous, evenly-sampled EDA
    segment (neurokit2.eda_phasic + neurokit2.eda_peaks). Returns
    (tonic, phasic, scr_peak_idx, scr_amplitude) -- all local to this segment's own
    index -- or None if neurokit2 isn't installed, the segment is shorter than
    EDA_DECOMPOSITION_MIN_SAMPLES, or neurokit2/cvxopt raises on this particular
    segment (logged, not fatal -- the caller leaves that segment's windows as NaN
    rather than crashing the whole windowing run over one bad stretch of signal)."""
    n = len(conductance)
    if not _HAS_NEUROKIT or n < EDA_DECOMPOSITION_MIN_SAMPLES:
        return None
    try:
        decomposed = nk.eda_phasic(np.asarray(conductance, dtype=float),
                                    sampling_rate=sampling_rate, method=method)
        tonic = decomposed["EDA_Tonic"].to_numpy(dtype=float)
        phasic = decomposed["EDA_Phasic"].to_numpy(dtype=float)
        _, info = nk.eda_peaks(phasic, sampling_rate=sampling_rate, method="neurokit")
        scr_peak_idx = np.asarray(info.get("SCR_Peaks", []), dtype=int)
        scr_amplitude = np.asarray(info.get("SCR_Amplitude", []), dtype=float)
    except Exception as exc:  # pragma: no cover -- defensive: cvxEDA can fail to converge
        print(f"  [warn] EDA tonic/phasic decomposition failed on a {n}-sample segment "
              f"({exc!r}); leaving eda_tonic_*/eda_phasic_*/eda_scr_* as NaN for its windows.")
        return None
    return tonic, phasic, scr_peak_idx, scr_amplitude


IRREGULAR_SPACING_TOLERANCE = 0.2  # a contiguous segment's median inter-sample gap deviating
    # from the nominal rate by more than this fraction is treated as irregularly sampled --
    # cvxEDA/eda_peaks reason in samples-per-second, so handing them unevenly spaced samples
    # as if they were uniform would distort the tonic/phasic split and SCR timing


def _decompose_eda_session(timestamps_ms, conductance, sampling_rate=HR_EDA_NOMINAL_RATE_HZ,
                            method=EDA_DECOMPOSITION_METHOD_DEFAULT):
    """Runs _decompose_eda_segment() once per contiguous run in one session's full raw
    EDA stream, then stitches the per-segment results back into full-session-length
    tonic/phasic arrays (NaN wherever a segment was too short, skipped, or failed) plus
    a flat list of (session-local index, amplitude) SCR events. Called ONCE per session
    on the complete raw stream -- not per window -- same grouping convention
    compute_hrv_windows/compute_eda_windows already use.

    Each contiguous segment's ACTUAL sample spacing is checked against the nominal rate
    before decomposing (see IRREGULAR_SPACING_TOLERANCE above) -- a segment sampled close
    enough to nominal is decomposed as before; one that drifts (batched samples, a few
    dropped ticks that didn't open a big-enough gap to split the segment) is first
    resampled onto a uniform sampling_rate grid (linear interpolation) so cvxEDA/eda_peaks
    see what they assume, then the result is interpolated back onto this segment's own
    original timestamps so every downstream window-aggregation step is unaffected."""
    n = len(conductance)
    tonic = np.full(n, np.nan)
    phasic = np.full(n, np.nan)
    scr_events = []  # list of (session-local index, amplitude)
    ts = np.asarray(timestamps_ms, dtype=float)
    irregular_segments = 0
    total_segments = 0

    for start, end in _contiguous_segments(timestamps_ms):
        total_segments += 1
        seg_ts = ts[start:end]
        seg_conductance = conductance[start:end]
        seg_n = len(seg_conductance)

        is_irregular = False
        if seg_n >= 3:
            nominal_dt_ms = 1000.0 / sampling_rate
            median_dt_ms = float(np.median(np.diff(seg_ts)))
            is_irregular = abs(median_dt_ms - nominal_dt_ms) > IRREGULAR_SPACING_TOLERANCE * nominal_dt_ms

        if not is_irregular:
            result = _decompose_eda_segment(seg_conductance, sampling_rate, method)
            if result is None:
                continue
            seg_tonic, seg_phasic, seg_scr_idx, seg_scr_amp = result
            tonic[start:end] = seg_tonic
            phasic[start:end] = seg_phasic
            for local_idx, amp in zip(seg_scr_idx, seg_scr_amp):
                scr_events.append((start + int(local_idx), float(amp)))
            continue

        irregular_segments += 1
        nominal_dt_ms = 1000.0 / sampling_rate
        grid_ts = np.arange(seg_ts[0], seg_ts[-1] + nominal_dt_ms / 2.0, nominal_dt_ms)
        if len(grid_ts) < EDA_DECOMPOSITION_MIN_SAMPLES:
            continue  # too short even after resampling -- leave this segment's windows NaN
        resampled = np.interp(grid_ts, seg_ts, seg_conductance)
        result = _decompose_eda_segment(resampled, sampling_rate, method)
        if result is None:
            continue
        grid_tonic, grid_phasic, grid_scr_idx, grid_scr_amp = result
        # map the uniform-grid result back onto this segment's own original timestamps
        seg_tonic = np.interp(seg_ts, grid_ts, grid_tonic)
        seg_phasic = np.interp(seg_ts, grid_ts, grid_phasic)
        tonic[start:end] = seg_tonic
        phasic[start:end] = seg_phasic
        for grid_idx, amp in zip(grid_scr_idx, grid_scr_amp):
            peak_t = grid_ts[grid_idx]
            nearest_local = int(np.argmin(np.abs(seg_ts - peak_t)))
            scr_events.append((start + nearest_local, float(amp)))

    if total_segments:
        print(f"  [info] EDA decomposition: {irregular_segments}/{total_segments} contiguous "
              f"segment(s) had irregular sample spacing and were resampled to a uniform "
              f"{sampling_rate:.2f} Hz grid before decomposition.")

    return tonic, phasic, scr_events


def compute_eda_windows(df, window_sec=WINDOW_SEC_DEFAULT, min_coverage=MIN_COVERAGE_DEFAULT,
                         decompose=True, decomposition_method=EDA_DECOMPOSITION_METHOD_DEFAULT):
    """Bins EDA samples into fixed window_sec windows per session and computes
    mean/std/peak-count/slope per window -- same windowing convention as
    accel_windowing.py and compute_hrv_windows above.

    When decompose=True (default) and neurokit2 is installed, also runs a real
    tonic/phasic decomposition once per session (see _decompose_eda_session) and adds
    eda_tonic_mean/eda_tonic_slope/eda_phasic_mean/eda_scr_count/
    eda_scr_amplitude_sum/eda_scr_amplitude_mean/eda_decomposition_available columns,
    alongside (never replacing) eda_mean/eda_std/eda_peak_count/eda_slope above. If
    neurokit2 isn't installed, those new columns come back all-NaN/0 and everything
    else is unaffected -- pass decompose=False to skip the attempt (and its one-line
    warning) entirely, e.g. for a quick run where the extra columns aren't needed."""
    window_ms = window_sec * 1000.0
    expected_samples = window_sec * HR_EDA_NOMINAL_RATE_HZ

    if decompose and not _HAS_NEUROKIT:
        print("  [warn] neurokit2 is not installed -- eda_tonic_*/eda_phasic_*/eda_scr_* "
              "columns will be left as NaN/0. `pip install neurokit2 cvxopt` to enable "
              "tonic/phasic decomposition.")
        decompose = False

    rows = []
    for session_id, g in df.groupby("session_id", sort=False):
        g = g.sort_values("timestamp_ms").drop_duplicates(subset=["timestamp_ms"]).reset_index(drop=True)
        session_start_ms = g["timestamp_ms"].iloc[0]
        participant_email = g["participant_email"].iloc[0]
        window_idx = ((g["timestamp_ms"] - session_start_ms) // window_ms).astype(int)

        if decompose:
            tonic_full, phasic_full, scr_events = _decompose_eda_session(
                g["timestamp_ms"].to_numpy(dtype=float),
                g["conductance_uS"].to_numpy(dtype=float),
                HR_EDA_NOMINAL_RATE_HZ, decomposition_method)
        else:
            tonic_full = np.full(len(g), np.nan)
            phasic_full = np.full(len(g), np.nan)
            scr_events = []
        scr_by_position = {}
        for pos, amp in scr_events:
            scr_by_position.setdefault(pos, []).append(amp)

        for idx, wdf in g.groupby(window_idx):
            n = len(wdf)
            coverage = n / expected_samples
            conductance = wdf["conductance_uS"].to_numpy(dtype=float)
            t_sec = (wdf["timestamp_ms"].to_numpy(dtype=float) - wdf["timestamp_ms"].iloc[0]) / 1000.0

            eda_mean = conductance.mean()
            eda_std = conductance.std(ddof=0) if n > 1 else 0.0
            eda_peak_count = _eda_peak_count(conductance)
            eda_slope = np.polyfit(t_sec, conductance, 1)[0] if n >= 2 else np.nan

            # Tonic/phasic/SCR: wdf.index is positional (g was reset_index'd above), so it
            # lines up directly with tonic_full/phasic_full/scr_by_position's own indexing.
            window_positions = wdf.index.to_numpy()
            tonic_w = tonic_full[window_positions]
            phasic_w = phasic_full[window_positions]
            tonic_mask = ~np.isnan(tonic_w)
            phasic_mask = ~np.isnan(phasic_w)
            n_decomposed = int(tonic_mask.sum())

            eda_tonic_mean = tonic_w[tonic_mask].mean() if n_decomposed else np.nan
            eda_phasic_mean = phasic_w[phasic_mask].mean() if phasic_mask.sum() else np.nan
            eda_tonic_slope = (np.polyfit(t_sec[tonic_mask], tonic_w[tonic_mask], 1)[0]
                                if n_decomposed >= 2 else np.nan)

            window_amps = [a for pos in window_positions for a in scr_by_position.get(pos, [])]
            eda_scr_count = len(window_amps)
            eda_scr_amplitude_sum = float(np.sum(window_amps)) if window_amps else 0.0
            eda_scr_amplitude_mean = float(np.mean(window_amps)) if window_amps else np.nan

            rows.append({
                "session_id": session_id,
                "participant_email": participant_email,
                "window_start_ms": session_start_ms + idx * window_ms,
                "window_end_ms": session_start_ms + (idx + 1) * window_ms,
                "n_samples": n,
                "expected_samples": round(expected_samples),
                "coverage": round(coverage, 3),
                "eda_mean": round(eda_mean, 4),
                "eda_std": round(eda_std, 4),
                "eda_peak_count": eda_peak_count,
                "eda_slope": round(eda_slope, 5) if not np.isnan(eda_slope) else np.nan,
                "eda_tonic_mean": round(eda_tonic_mean, 4) if not np.isnan(eda_tonic_mean) else np.nan,
                "eda_tonic_slope": (round(eda_tonic_slope, 5)
                                      if not np.isnan(eda_tonic_slope) else np.nan),
                "eda_phasic_mean": (round(eda_phasic_mean, 4)
                                      if not np.isnan(eda_phasic_mean) else np.nan),
                "eda_scr_count": eda_scr_count,
                "eda_scr_amplitude_sum": round(eda_scr_amplitude_sum, 4),
                "eda_scr_amplitude_mean": (round(eda_scr_amplitude_mean, 4)
                                             if not np.isnan(eda_scr_amplitude_mean) else np.nan),
                "eda_decomposition_available": n_decomposed == n,
                "low_confidence": bool(coverage < min_coverage),
            })
    return pd.DataFrame(rows).sort_values(["session_id", "window_start_ms"]).reset_index(drop=True)


# ---------------------------------------------------------------------------
# Summaries
# ---------------------------------------------------------------------------

def summarize_hrv(windows, hrv_min_beats=HRV_MIN_BEATS_DEFAULT):
    total = len(windows)
    if total == 0:
        print("No HRV windows produced.")
        return
    n_low_conf = int(windows["low_confidence"].sum())
    print(f"\n{total} HRV window(s) across {windows['session_id'].nunique()} session(s)")
    print(f"  low-confidence (< {hrv_min_beats} beats or low sample coverage): "
          f"{n_low_conf} ({100 * n_low_conf / total:.1f}%)")
    print("\nPer-session breakdown:")
    per = windows.groupby("session_id").agg(
        windows=("window_start_ms", "count"),
        mean_beat_count=("beat_count", "mean"),
        mean_rmssd=("rmssd", "mean"),
        mean_hr_bpm=("mean_hr_bpm", "mean"),
        low_confidence=("low_confidence", "sum"),
    )
    print(per.to_string())


def summarize_eda(windows):
    total = len(windows)
    if total == 0:
        print("No EDA windows produced.")
        return
    n_low_conf = int(windows["low_confidence"].sum())
    print(f"\n{total} EDA window(s) across {windows['session_id'].nunique()} session(s)")
    print(f"  low-confidence (low sample coverage): {n_low_conf} ({100 * n_low_conf / total:.1f}%)")
    if "eda_decomposition_available" in windows.columns:
        n_decomp = int(windows["eda_decomposition_available"].sum())
        print(f"  tonic/phasic decomposition available: {n_decomp} ({100 * n_decomp / total:.1f}%)"
              f" -- rest left eda_tonic_*/eda_phasic_*/eda_scr_* as NaN/0 (neurokit2 missing, "
              f"segment too short, or a sensor-dropout gap)")
    print("\nPer-session breakdown:")
    agg_kwargs = dict(
        windows=("window_start_ms", "count"),
        mean_eda=("eda_mean", "mean"),
        mean_peak_count=("eda_peak_count", "mean"),
        low_confidence=("low_confidence", "sum"),
    )
    if "eda_scr_count" in windows.columns:
        agg_kwargs["mean_scr_count"] = ("eda_scr_count", "mean")
        agg_kwargs["mean_tonic"] = ("eda_tonic_mean", "mean")
    per = windows.groupby("session_id").agg(**agg_kwargs)
    print(per.to_string())


# ---------------------------------------------------------------------------
# Synthetic demo (same session_id/epoch/segment lengths as accel_windowing.py's
# --demo, so the demo outputs from both scripts line up if you want to see a
# full synthetic Stage-3 row end to end)
# ---------------------------------------------------------------------------

def make_synthetic_demo():
    """SYNTHETIC session, not real watch data: 5min calm baseline, 2min stress-like
    segment (faster/less-variable heart rate, rising EDA with a couple of
    SCR-like peaks), 3min back toward baseline (10 minutes total -- extended from
    an earlier 1-minute version so the demo/dashboards have enough elapsed time to
    look like an actual session) -- at 1Hz, matching HEART_RATE_CONTINUOUS/
    EDA_CONTINUOUS's real sample rate. Exists only to exercise the parsing/
    windowing/formula code end to end; the physiology is illustrative, not
    modeled from real data.

    Each second is tagged with which segment it belongs to ("baseline"/"stress"/
    "recovery") in hr_rows only, as a "phase" column -- compute_hrv_windows() above
    carries that through (majority vote per window) so a dashboard can draw a
    condition timeline, the same idea as the real WESAD dataset's own ground-truth
    label column (see wesad/plot_wesad_dashboard.py). Not added to eda_rows: EDA
    windows aren't the primary grid merge_windows.py builds around, so a phase
    column there would never reach the merged output anyway (see merge_windows.py's
    module docstring for how the HRV/EDA/accel/skin-temp join works)."""
    rng = np.random.default_rng(7)
    t0 = 1_756_000_000_000  # same arbitrary epoch as accel_windowing.py's demo
    session_id = "demo_session_1"
    participant_email = "demo@example.com"

    hr_rows, eda_rows = [], []
    state = {"t": t0, "eda_level": 2.0}  # baseline microsiemens

    def add_second(mean_ibi_ms, ibi_jitter_ms, eda_target, eda_noise, phase, force_peak=False):
        t = state["t"]
        n_ibi = int(rng.integers(1, 4))
        ibis = [int(max(300, round(rng.normal(mean_ibi_ms, ibi_jitter_ms)))) for _ in range(n_ibi)]
        statuses = [0] * n_ibi
        hr_bpm = round(60000.0 / np.mean(ibis))
        hr_rows.append({
            "session_id": session_id, "participant_email": participant_email,
            "timestamp_ms": t, "heart_rate_bpm": hr_bpm, "hr_status": 0,
            "ibi_list_ms": json.dumps(ibis), "ibi_status_list": json.dumps(statuses),
            "received_at_ms": t, "phase": phase,
        })

        state["eda_level"] += (eda_target - state["eda_level"]) * 0.3
        conductance = state["eda_level"] + rng.normal(0, eda_noise)
        if force_peak:
            conductance += 0.8
        eda_rows.append({
            "session_id": session_id, "participant_email": participant_email,
            "timestamp_ms": t, "conductance_uS": round(max(0.0, conductance), 4),
            "status": 0, "received_at_ms": t,
        })
        state["t"] += 1000

    for _ in range(300):
        add_second(mean_ibi_ms=850, ibi_jitter_ms=35, eda_target=2.0, eda_noise=0.03,
                    phase="baseline")
    for i in range(120):
        add_second(mean_ibi_ms=650, ibi_jitter_ms=12, eda_target=4.5, eda_noise=0.05,
                    phase="stress", force_peak=(i % 20 in (3, 9)))
    for _ in range(180):
        add_second(mean_ibi_ms=850, ibi_jitter_ms=35, eda_target=2.5, eda_noise=0.03,
                    phase="recovery")

    return pd.DataFrame(hr_rows), pd.DataFrame(eda_rows)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _default_out(input_path, suffix):
    base = input_path.rstrip("/\\") if os.path.isdir(input_path) else os.path.splitext(input_path)[0]
    return base + suffix


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hr", help="hr_*.csv file, or a directory of them")
    ap.add_argument("--eda", help="eda_*.csv file, or a directory of them")
    ap.add_argument("--window-sec", type=float, default=None,
                     help=f"default {WINDOW_SEC_DEFAULT}s normally, {DEMO_WINDOW_SEC_DEFAULT}s "
                          f"for --demo (so demo windows line up with its 30/15/15s segments)")
    ap.add_argument("--min-coverage", type=float, default=MIN_COVERAGE_DEFAULT)
    ap.add_argument("--hrv-min-beats", type=int, default=HRV_MIN_BEATS_DEFAULT,
                     help="HrvCalculator.hasEnoughData()'s threshold -- windows with fewer "
                          "valid beats than this are low_confidence")
    ap.add_argument("--out-hrv", default=None)
    ap.add_argument("--out-eda", default=None)
    ap.add_argument("--demo", action="store_true",
                     help="run on a small synthetic baseline/stress-like/baseline session "
                          "instead of real hr_*.csv / eda_*.csv exports")
    ap.add_argument("--no-eda-decomposition", action="store_true",
                     help="skip the neurokit2 tonic/phasic/SCR decomposition and leave "
                          "eda_tonic_*/eda_phasic_*/eda_scr_* columns as NaN/0 -- useful for "
                          "a quick run, or if neurokit2 isn't installed")
    ap.add_argument("--eda-decomposition-method", default=EDA_DECOMPOSITION_METHOD_DEFAULT,
                     choices=["cvxEDA", "highpass", "smoothmedian", "sparseEDA"],
                     help=f"tonic/phasic split method passed to neurokit2.eda_phasic() "
                          f"(default {EDA_DECOMPOSITION_METHOD_DEFAULT})")
    args = ap.parse_args()

    window_sec = args.window_sec
    if window_sec is None:
        window_sec = DEMO_WINDOW_SEC_DEFAULT if args.demo else WINDOW_SEC_DEFAULT

    if args.demo:
        print("Running on SYNTHETIC demo data (no real watch export exists yet) -- "
              "for sanity-checking the pipeline only, not for any analysis conclusions.\n")
        hr_df, eda_df = make_synthetic_demo()
        out_hrv = args.out_hrv or "hr_demo_hrv_windows.csv"
        out_eda = args.out_eda or "eda_demo_eda_windows.csv"
    else:
        if not args.hr and not args.eda:
            ap.error("at least one of --hr / --eda is required unless --demo is given")
        hr_df = load_hr(args.hr) if args.hr else None
        eda_df = load_eda(args.eda) if args.eda else None
        out_hrv = args.out_hrv or (_default_out(args.hr, "_hrv_windows.csv") if args.hr else None)
        out_eda = args.out_eda or (_default_out(args.eda, "_eda_windows.csv") if args.eda else None)

    if hr_df is not None:
        hrv_windows = compute_hrv_windows(hr_df, window_sec, args.min_coverage, args.hrv_min_beats)
        hrv_windows.to_csv(out_hrv, index=False)
        print(f"Wrote {len(hrv_windows)} HRV window(s) -> {out_hrv}")
        summarize_hrv(hrv_windows, args.hrv_min_beats)

    if eda_df is not None:
        eda_windows = compute_eda_windows(eda_df, window_sec, args.min_coverage,
                                           decompose=not args.no_eda_decomposition,
                                           decomposition_method=args.eda_decomposition_method)
        eda_windows.to_csv(out_eda, index=False)
        print(f"\nWrote {len(eda_windows)} EDA window(s) -> {out_eda}")
        summarize_eda(eda_windows)


if __name__ == "__main__":
    main()
