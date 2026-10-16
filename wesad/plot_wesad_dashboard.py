"""
Draws a dashboard figure (heart rate & HRV, EDA, respiration, condition timeline) for one
WESAD subject's already-extracted feature table (wesad/data/features/S*_features.csv).

Cortisol Monitor thesis -- companion to accel_pipeline/plot_dashboard.py, which visualizes
this project's OWN watch-export pipeline output (synthetic so far). This script visualizes
the WESAD dataset instead: real chest ECG, EDA and respiration from real people going
through an actual stress protocol (baseline / stress / amusement / meditation), already
windowed and feature-extracted by whatever process produced wesad_loso.py's input. Because
it's real data, the figure is titled "REAL DATA", not "SYNTHETIC DATA".

Two honest caveats worth knowing, and stamped directly on the figure so they're not missed:
  1. The feature file has no real clock timestamps. The x-axis here is reconstructed as
     window_index * --step-sec (default 30s, matching wesad_loso.py's documented "60s
     windows / 50% overlap"), not the actual recording clock time.
  2. This HRV comes from WESAD's chest ECG (R-peak detection), a different and more
     accurate sensor modality than the wrist PPG this project's own watch pipeline uses --
     so it is NOT a like-for-like comparison with plot_dashboard.py's HR/HRV panel, even
     though the panel looks the same.

There's no accelerometer or skin-temperature data in this feature file (WESAD's raw
dataset has both, but this project's feature-extraction step didn't keep them), and no
cortisol at all (WESAD used self-report surveys, not real hormone draws). So this
dashboard has three signal panels (heart rate & HRV, EDA, respiration) plus a colored
condition timeline using WESAD's own ground-truth labels, instead of
plot_dashboard.py's four signal panels plus cortisol draw markers.

Usage:
    python plot_wesad_dashboard.py wesad/data/features/S2_features.csv
        Saves dashboard_wesad_S2.png next to the input file.

    python plot_wesad_dashboard.py wesad/data/features/S*_features.csv --out-dir figures
        Accepts multiple files (shell-expanded globs work) -- one dashboard per subject.
"""

import argparse
import glob
import os

import matplotlib
matplotlib.use("Agg")  # headless: always save a PNG, never try to pop up a window
import matplotlib.patches as mpatches
import matplotlib.pyplot as plt
import pandas as pd

HR_COLOR = "#E53935"
RMSSD_COLOR = "#1E88E5"
EDA_COLOR = "#43A047"
RESP_COLOR = "#00897B"

# WESAD's own label definitions (readme.pdf): 1=baseline, 2=stress, 3=amusement,
# 4=meditation. 0/5/6/7 are transient/undefined/"should be ignored" per WESAD's docs --
# wesad_loso.py itself only ever trains on 1/2/3, so lumping the rest together here as
# a single "Other/transient" band is consistent with that, not a new assumption.
LABEL_INFO = {
    1: ("Baseline", "#66BB6A"),
    2: ("Stress", "#EF5350"),
    3: ("Amusement", "#42A5F5"),
    4: ("Meditation", "#AB47BC"),
}
OTHER_COLOR = "#E0E0E0"
OTHER_LABEL = "Other/transient"

STEP_SEC_DEFAULT = 30.0  # 60s windows, 50% overlap => 30s step, per wesad_loso.py's docstring


def plot_subject_dashboard(df, subject_id, out_path, step_sec=STEP_SEC_DEFAULT):
    """Builds and saves the 4-panel WESAD dashboard for one subject's feature table.
    Returns out_path, or None if df is empty."""
    sess = df.reset_index(drop=True).copy()
    if sess.empty:
        return None

    sess["t_min"] = sess.index * step_sec / 60.0
    sess["hr_bpm"] = 60.0 / sess["mean_rr"]           # mean_rr is in seconds per beat
    sess["rmssd_ms"] = sess["rmssd"] * 1000.0          # WESAD's rmssd/sdnn are in seconds

    fig, axes = plt.subplots(4, 1, figsize=(11, 11), sharex=True,
                              gridspec_kw={"height_ratios": [3, 3, 3, 1]})
    fig.suptitle(f"REAL DATA (WESAD) -- subject {subject_id}", fontsize=13,
                 fontweight="bold", color="#1B5E20", y=0.995)
    fig.text(0.5, 0.965,
              "Time axis reconstructed from window order (60s windows, 30s step) -- not the "
              "original recording clock. HRV is from chest ECG, not wrist PPG like this "
              "project's own watch pipeline.",
              ha="center", fontsize=8, style="italic", color="#616161")

    # ---- Panel 1: heart rate & HRV -----------------------------------------
    ax = axes[0]
    ax2 = ax.twinx()
    ax.plot(sess["t_min"], sess["hr_bpm"], color=HR_COLOR, linewidth=1.2, label="Heart rate (bpm)")
    ax2.plot(sess["t_min"], sess["rmssd_ms"], color=RMSSD_COLOR, linewidth=1.2, label="RMSSD (ms)")
    ax.set_ylabel("Heart rate (bpm)", color=HR_COLOR)
    ax2.set_ylabel("RMSSD (ms)", color=RMSSD_COLOR)
    ax.tick_params(axis="y", labelcolor=HR_COLOR)
    ax2.tick_params(axis="y", labelcolor=RMSSD_COLOR)
    ax.set_title("Heart rate & HRV (chest ECG) -- higher HR + lower RMSSD together = stress-like")
    lines1, labels1 = ax.get_legend_handles_labels()
    lines2, labels2 = ax2.get_legend_handles_labels()
    ax.legend(lines1 + lines2, labels1 + labels2, loc="upper right", fontsize=8)

    # ---- Panel 2: EDA --------------------------------------------------------
    ax = axes[1]
    ax.plot(sess["t_min"], sess["eda_mean"], color=EDA_COLOR, linewidth=1.2, label="EDA mean (µS)")
    ax.set_ylabel("EDA (µS)")
    ax.set_title("Sweat response (EDA, chest) -- higher = more sympathetic arousal")
    ax.legend(loc="upper right", fontsize=8)

    # ---- Panel 3: respiration --------------------------------------------------
    ax = axes[2]
    ax.plot(sess["t_min"], sess["resp_mean"], color=RESP_COLOR, linewidth=1.2, label="Respiration mean")
    ax.set_ylabel("Respiration (a.u.)")
    ax.set_title("Respiration -- a signal this project's watch pipeline doesn't have, shown for context")
    ax.legend(loc="upper right", fontsize=8)

    # ---- Panel 4: condition timeline (WESAD ground-truth label) --------------
    ax = axes[3]
    for _, row in sess.iterrows():
        name, color = LABEL_INFO.get(int(row["label"]), (OTHER_LABEL, OTHER_COLOR))
        ax.axvspan(row["t_min"], row["t_min"] + step_sec / 60.0, color=color)
    ax.set_yticks([])
    ax.set_xlabel("Time (minutes, reconstructed from window order)")
    ax.set_title("Condition (WESAD ground-truth label)", fontsize=10)

    handles = [mpatches.Patch(color=c, label=n) for n, c in LABEL_INFO.values()]
    handles.append(mpatches.Patch(color=OTHER_COLOR, label=OTHER_LABEL))
    ax.legend(handles=handles, loc="upper center", bbox_to_anchor=(0.5, -0.55),
              ncol=5, fontsize=8, frameon=False)

    for a in axes[:3]:
        a.grid(alpha=0.3)

    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    return out_path


def save_dashboard_for_file(feature_csv_path, out_dir=None, step_sec=STEP_SEC_DEFAULT):
    df = pd.read_csv(feature_csv_path)
    subject_id = os.path.basename(feature_csv_path).split("_")[0]  # e.g. "S2_features.csv" -> "S2"
    resolved_out_dir = out_dir or os.path.dirname(os.path.abspath(feature_csv_path)) or "."
    os.makedirs(resolved_out_dir, exist_ok=True)
    out_path = os.path.join(resolved_out_dir, f"dashboard_wesad_{subject_id}.png")
    return plot_subject_dashboard(df, subject_id, out_path, step_sec=step_sec)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", nargs="+",
                     help="one or more S*_features.csv files to plot (e.g. "
                          "data/features/S2_features.csv, or a glob like "
                          "'data/features/S*_features.csv' -- quote it so this script "
                          "expands it, not the shell, if you want one figure per subject "
                          "in a single run)")
    ap.add_argument("--out-dir", default=None,
                     help="folder to save PNGs into (default: same folder as each input file)")
    ap.add_argument("--step-sec", type=float, default=STEP_SEC_DEFAULT,
                     help="seconds between consecutive windows, for reconstructing the time "
                          "axis (default 30s = 60s windows at 50%% overlap, per wesad_loso.py)")
    args = ap.parse_args()

    # Expand any glob patterns that reached us unexpanded (e.g. quoted on the command line).
    files = []
    for pattern in args.input:
        matches = sorted(glob.glob(pattern))
        files.extend(matches if matches else [pattern])

    if not files:
        print("No input files matched -- nothing plotted.")
        return

    for f in files:
        out_path = save_dashboard_for_file(f, args.out_dir, args.step_sec)
        if out_path:
            print(f"Wrote {out_path}")
        else:
            print(f"Skipped {f} (no rows)")


if __name__ == "__main__":
    main()
