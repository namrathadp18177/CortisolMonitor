"""
Draws a one-page-per-session "dashboard" figure (movement, heart rate/HRV, EDA/sweat,
skin temperature -- stacked, sharing a time axis) from a merged_windows.csv produced by
merge_windows.py. A 5th "condition timeline" panel (styled like
wesad/plot_wesad_dashboard.py's own condition panel) is added automatically whenever the
merged data has a "phase" column -- currently only hrv_eda_windowing.py's --demo
generator tags its rows this way (baseline/stress/recovery), so real watch exports and
the make_stress_test_fixture.py robustness fixture both simply get the plain 4-panel
figure, exactly as before.

Cortisol Monitor thesis -- a visualization step on top of Stage 3/4/5 of
docs/accelerometer_stress_research_and_plan.md, so a run of the pipeline (real or
synthetic) produces something you can actually look at, not just a CSV of numbers.

Every figure is stamped "SYNTHETIC DATA" in the title unless --real is passed -- this
script has no way to know on its own whether the input came from a real watch or one of
the pipeline's own --demo/make_stress_test_fixture.py generators, so it defaults to the
cautious label rather than risk a synthetic figure being mistaken for a real result.

Usage:
    python plot_dashboard.py merged_windows.csv
        Saves one dashboard_<session_id>.png per session next to merged_windows.csv.

    python plot_dashboard.py merged_windows.csv --out-dir figures --real
        Custom output folder; drops the "SYNTHETIC DATA" stamp (use only once the input
        is an actual watch export, not one of the pipeline's synthetic generators).

Also importable: merge_windows.py calls save_dashboards() directly when run with --plot,
so `python merge_windows.py --demo --plot` or `python run_stress_test.py` (which passes
--plot through) produce these figures automatically as part of that run.
"""

import argparse
import os
import stat

import matplotlib
matplotlib.use("Agg")  # headless: always save a PNG, never try to pop up a window
import matplotlib.patches as mpatches
import matplotlib.pyplot as plt
import pandas as pd

MOTION_COLOR = "#5C6BC0"
HR_COLOR = "#E53935"
RMSSD_COLOR = "#1E88E5"
EDA_COLOR = "#43A047"
SKIN_COLOR = "#FB8C00"
AMBIENT_COLOR = "#BDBDBD"
CORTISOL_COLOR = "#8E24AA"
LOW_CONF_EDGE = "#212121"

# Condition-timeline colors for the optional 5th panel (only drawn when the merged data
# has a "phase" column -- see module docstring). Baseline/stress colors deliberately echo
# wesad/plot_wesad_dashboard.py's own Baseline/Stress colors so the two dashboards read
# the same way at a glance; "recovery" has no WESAD equivalent (WESAD's protocol doesn't
# have one), so it gets its own color rather than reusing WESAD's Amusement/Meditation.
PHASE_INFO = {
    "baseline": ("Baseline", "#66BB6A"),
    "stress": ("Stress", "#EF5350"),
    "recovery": ("Recovery", "#26A69A"),
}
OTHER_PHASE_COLOR = "#E0E0E0"
OTHER_PHASE_LABEL = "Other"


def _minutes_from(series_ms, t0_ms):
    return (series_ms - t0_ms) / 60000.0


def plot_session_dashboard(df, session_id, out_path, synthetic=True):
    """Builds and saves the 4-panel dashboard for one session_id in df. Returns out_path,
    or None if that session has no rows (so callers can skip it without erroring)."""
    sess = df[df["session_id"] == session_id].sort_values("window_start_ms").copy()
    if sess.empty:
        return None

    t0 = sess["window_start_ms"].min()
    sess["t_min"] = _minutes_from(sess["window_start_ms"], t0)
    sess["t_min_end"] = _minutes_from(sess["window_end_ms"], t0)

    has_phase = "phase" in sess.columns and sess["phase"].notna().any()
    n_panels = 5 if has_phase else 4
    height_ratios = [3, 3, 3, 3, 1] if has_phase else [3, 3, 3, 3]
    fig, axes = plt.subplots(n_panels, 1, figsize=(10, 12 if has_phase else 11), sharex=True,
                              gridspec_kw={"height_ratios": height_ratios})
    title = f"Session dashboard -- {session_id}"
    if synthetic:
        title = "SYNTHETIC DATA -- " + title
    fig.suptitle(title, fontsize=13, fontweight="bold",
                 color="#B00020" if synthetic else "#212121")

    # ---- Panel 1: movement -------------------------------------------------
    ax = axes[0]
    if "accel_mean_mag_g" in sess.columns:
        ax.plot(sess["t_min"], sess["accel_mean_mag_g"], marker="o", color=MOTION_COLOR,
                 label="mean |accel| (g)")
    if "accel_any_motion_flagged" in sess.columns:
        flagged = sess[sess["accel_any_motion_flagged"] == True]  # noqa: E712 (pandas bool col)
        for _, row in flagged.iterrows():
            ax.axvspan(row["t_min"] - 0.05, row["t_min"] + 0.05, color="#F57F17", alpha=0.25)
    ax.set_ylabel("Movement (g)")
    ax.set_title("Movement (accelerometer) -- shaded = motion flagged")
    if ax.get_legend_handles_labels()[0]:
        ax.legend(loc="upper right", fontsize=8)

    # ---- Panel 2: heart rate & HRV -----------------------------------------
    ax = axes[1]
    ax2 = ax.twinx()
    if "hrv_mean_hr_bpm" in sess.columns:
        ax.plot(sess["t_min"], sess["hrv_mean_hr_bpm"], marker="o", color=HR_COLOR,
                 label="Heart rate (bpm)")
    if "hrv_rmssd" in sess.columns:
        ax2.plot(sess["t_min"], sess["hrv_rmssd"], marker="s", color=RMSSD_COLOR,
                  label="RMSSD (ms)")
    if "hrv_low_confidence" in sess.columns and "hrv_mean_hr_bpm" in sess.columns:
        low = sess[sess["hrv_low_confidence"] == True]  # noqa: E712
        if len(low):
            ax.scatter(low["t_min"], low["hrv_mean_hr_bpm"], facecolors="none",
                       edgecolors=LOW_CONF_EDGE, s=90, linewidths=1.5, label="low confidence",
                       zorder=5)
    ax.set_ylabel("Heart rate (bpm)", color=HR_COLOR)
    ax2.set_ylabel("RMSSD (ms)", color=RMSSD_COLOR)
    ax.tick_params(axis="y", labelcolor=HR_COLOR)
    ax2.tick_params(axis="y", labelcolor=RMSSD_COLOR)
    ax.set_title("Heart rate & HRV -- higher HR + lower RMSSD together = stress-like")
    lines1, labels1 = ax.get_legend_handles_labels()
    lines2, labels2 = ax2.get_legend_handles_labels()
    if lines1 or lines2:
        ax.legend(lines1 + lines2, labels1 + labels2, loc="upper right", fontsize=8)

    # ---- Panel 3: EDA (sweat) ----------------------------------------------
    ax = axes[2]
    if "eda_mean" in sess.columns:
        ax.plot(sess["t_min"], sess["eda_mean"], marker="o", color=EDA_COLOR,
                 label="EDA mean (µS)")
    ax.set_ylabel("EDA (µS)")
    ax.set_title("Sweat response (EDA) -- higher = more sympathetic arousal")
    if ax.get_legend_handles_labels()[0]:
        ax.legend(loc="upper right", fontsize=8)

    # ---- Panel 4: skin temperature ------------------------------------------
    ax = axes[3]
    if "skintemp_skin_mean_c" in sess.columns:
        ax.plot(sess["t_min"], sess["skintemp_skin_mean_c"], marker="o", color=SKIN_COLOR,
                 label="Skin temp (°C)")
    if "skintemp_ambient_mean_c" in sess.columns:
        ax.plot(sess["t_min"], sess["skintemp_ambient_mean_c"], linestyle="--",
                 color=AMBIENT_COLOR, label="Ambient temp (°C)")
    ax.set_ylabel("Temp (°C)")
    if not has_phase:
        ax.set_xlabel("Time since session start (minutes)")
    ax.set_title("Skin temperature")
    if ax.get_legend_handles_labels()[0]:
        ax.legend(loc="upper right", fontsize=8)

    # ---- Panel 5 (optional): condition timeline -----------------------------
    # Only present for sessions whose windows carry a "phase" column -- as of this
    # writing that's just hrv_eda_windowing.py's --demo generator (baseline/stress/
    # recovery). Styled after wesad/plot_wesad_dashboard.py's own condition panel.
    if has_phase:
        ax = axes[4]
        for _, row in sess.iterrows():
            phase = row.get("phase")
            name, color = PHASE_INFO.get(phase, (OTHER_PHASE_LABEL, OTHER_PHASE_COLOR))
            ax.axvspan(row["t_min"], row["t_min_end"], color=color)
        ax.set_yticks([])
        ax.set_xlabel("Time since session start (minutes)")
        ax.set_title("Condition (synthetic demo phase)", fontsize=10)
        handles = [mpatches.Patch(color=c, label=n) for n, c in PHASE_INFO.values()]
        if sess["phase"].isna().any() or (~sess["phase"].isin(PHASE_INFO.keys())).any():
            handles.append(mpatches.Patch(color=OTHER_PHASE_COLOR, label=OTHER_PHASE_LABEL))
        ax.legend(handles=handles, loc="upper center", bbox_to_anchor=(0.5, -0.6),
                  ncol=len(handles), fontsize=8, frameon=False)

    # ---- Cortisol draw markers, across all panels --------------------------
    if "cortisol_value" in sess.columns and "cortisol_sample_time_ms" in sess.columns:
        cort = sess.dropna(subset=["cortisol_sample_time_ms", "cortisol_value"])
        cort = cort.drop_duplicates(subset=["cortisol_sample_time_ms"])
        for _, row in cort.iterrows():
            ct_min = (row["cortisol_sample_time_ms"] - t0) / 60000.0
            for a in axes:
                a.axvline(ct_min, color=CORTISOL_COLOR, linestyle=":", alpha=0.6)
            axes[0].annotate(f" cortisol {row['cortisol_value']:.1f} ng/mL",
                              xy=(ct_min, 1), xycoords=("data", "axes fraction"),
                              rotation=90, va="top", ha="left", fontsize=7, color=CORTISOL_COLOR)

    for a in (axes[:4] if has_phase else axes):
        a.grid(alpha=0.3)

    fig.tight_layout(rect=(0, 0, 1, 0.96))
    try:
        # If a PNG already sits at this exact path (e.g. left over from a previous run,
        # or written by a different tool/process), some setups won't let a plain
        # open()-for-write overwrite it cleanly -- on Windows this has been seen to
        # surface as a confusing "OSError: [Errno 22] Invalid argument" from Pillow
        # rather than a clearer permission error, if the existing file is read-only,
        # currently open in another program (an image viewer, etc.), or still being
        # synced by a cloud-storage client. Removing it first sidesteps that whole
        # class of failure; if the removal itself fails (e.g. still genuinely locked),
        # fall through and let the save attempt raise its own, still-useful error.
        if os.path.exists(out_path):
            try:
                os.chmod(out_path, stat.S_IWRITE)  # clear a read-only flag, if any (Windows)
            except OSError:
                pass
            try:
                os.remove(out_path)
            except OSError:
                pass
        fig.savefig(out_path, dpi=150)
    except OSError as e:
        plt.close(fig)
        raise OSError(
            f"Could not write dashboard PNG to {out_path} ({e}). This usually means the "
            f"file is open in another program (an image viewer, etc.), marked read-only, "
            f"or -- if this folder is synced via OneDrive/Dropbox/etc. -- still syncing. "
            f"Close/unlock the file (or pause sync briefly) and re-run."
        ) from e
    plt.close(fig)
    return out_path


def save_dashboards(df, out_dir, synthetic=True):
    """One dashboard PNG per distinct session_id in df. Returns the list of paths written."""
    os.makedirs(out_dir, exist_ok=True)
    paths = []
    for session_id in sorted(df["session_id"].dropna().unique()):
        out_path = os.path.join(out_dir, f"dashboard_{session_id}.png")
        result = plot_session_dashboard(df, session_id, out_path, synthetic=synthetic)
        if result:
            paths.append(result)
    return paths


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", help="merged_windows.csv (from merge_windows.py) to plot")
    ap.add_argument("--out-dir", default=None,
                     help="folder to save PNGs into (default: same folder as input)")
    ap.add_argument("--real", action="store_true",
                     help="drop the 'SYNTHETIC DATA' title stamp -- pass this only once the "
                          "input is an actual watch export, not a --demo/fixture generator")
    args = ap.parse_args()

    df = pd.read_csv(args.input)
    out_dir = args.out_dir or os.path.dirname(os.path.abspath(args.input)) or "."
    paths = save_dashboards(df, out_dir, synthetic=not args.real)

    if not paths:
        print("No sessions found in the input -- nothing plotted.")
        return
    for p in paths:
        print(f"Wrote {p}")


if __name__ == "__main__":
    main()
