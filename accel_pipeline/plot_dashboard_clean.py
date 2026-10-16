"""
One-off "clean" dashboard: same 4-panel layout as plot_dashboard.py (movement, HR/HRV,
EDA, skin temp) but WITHOUT the low-confidence circle markers on the HR panel -- every
window is just plotted and joined like a normal line, low-confidence included. Requested
2026-09-28 by Nami: "use low confidence for now, next time wear it properly ... don't
mark it as low confidence, just join each point like a proper graph."

Does not touch plot_dashboard.py itself -- that script's low-confidence marking stays as
the default/rigorous view. This is a supplementary, more optimistic view of the same
data for the same session.

Usage:
    python plot_dashboard_clean.py merged_real.csv <session_id> --out dashboard_clean.png
"""

import argparse
import os
import stat

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter, MultipleLocator
import pandas as pd

MOTION_COLOR = "#5C6BC0"
HR_COLOR = "#E53935"
RMSSD_COLOR = "#1E88E5"
EDA_COLOR = "#43A047"
SKIN_COLOR = "#FB8C00"
AMBIENT_COLOR = "#BDBDBD"


def _minutes_from(series_ms, t0_ms):
    return (series_ms - t0_ms) / 60000.0


def plot_clean_dashboard(df, session_id, out_path):
    sess = df[df["session_id"] == session_id].sort_values("window_start_ms").copy()
    if sess.empty:
        return None

    t0 = sess["window_start_ms"].min()
    sess["t_min"] = _minutes_from(sess["window_start_ms"], t0)

    fig, axes = plt.subplots(4, 1, figsize=(10, 11), sharex=True,
                              gridspec_kw={"height_ratios": [3, 3, 3, 3]})
    fig.suptitle(f"Session dashboard (all points, no low-confidence marking) -- {session_id}",
                 fontsize=12, fontweight="bold", color="#212121")

    # Panel 1: movement
    ax = axes[0]
    ax.plot(sess["t_min"], sess["accel_mean_mag_g"], marker="o", markersize=3,
            color=MOTION_COLOR, label="mean |accel| (g)")
    ax.set_ylabel("Movement (g)")
    ax.set_title("Movement (accelerometer)")
    ax.legend(loc="upper right", fontsize=8)

    # Panel 2: HR & RMSSD -- no low-confidence overlay, just the lines.
    # Many windows have 0 detected beats (NaN HR/RMSSD), which breaks the line at every
    # gap -- drop those rows before plotting so the line joins straight across to the
    # next window that actually has a value, same look as the other panels (which don't
    # have gaps). Nothing is interpolated/invented, windows with no beats are just
    # skipped rather than shown as a break.
    ax = axes[1]
    ax2 = ax.twinx()
    hr_pts = sess.dropna(subset=["hrv_mean_hr_bpm"])
    rmssd_pts = sess.dropna(subset=["hrv_rmssd"])
    ax.plot(hr_pts["t_min"], hr_pts["hrv_mean_hr_bpm"], marker="o", markersize=3,
            color=HR_COLOR, label="Heart rate (bpm)")
    ax2.plot(rmssd_pts["t_min"], rmssd_pts["hrv_rmssd"], marker="s", markersize=3,
             color=RMSSD_COLOR, label="RMSSD (ms)")
    ax.set_ylabel("Heart rate (bpm)", color=HR_COLOR)
    ax2.set_ylabel("RMSSD (ms)", color=RMSSD_COLOR)
    ax.tick_params(axis="y", labelcolor=HR_COLOR)
    ax2.tick_params(axis="y", labelcolor=RMSSD_COLOR)
    ax.set_title("Heart rate & HRV")
    lines1, labels1 = ax.get_legend_handles_labels()
    lines2, labels2 = ax2.get_legend_handles_labels()
    ax.legend(lines1 + lines2, labels1 + labels2, loc="upper right", fontsize=8)

    # Panel 3: EDA
    ax = axes[2]
    ax.plot(sess["t_min"], sess["eda_mean"], marker="o", markersize=3,
            color=EDA_COLOR, label="EDA mean (µS)")
    ax.set_ylabel("EDA (µS)")
    ax.set_title("Sweat response (EDA)")
    ax.legend(loc="upper right", fontsize=8)

    # Panel 4: skin temp
    ax = axes[3]
    ax.plot(sess["t_min"], sess["skintemp_skin_mean_c"], marker="o", markersize=3,
            color=SKIN_COLOR, label="Skin temp (°C)")
    if "skintemp_ambient_mean_c" in sess.columns:
        ax.plot(sess["t_min"], sess["skintemp_ambient_mean_c"], linestyle="--",
                color=AMBIENT_COLOR, label="Ambient temp (°C)")
    ax.set_ylabel("Temp (°C)")
    ax.set_xlabel("Time since session start (minutes)")
    ax.set_title("Skin temperature")
    ax.legend(loc="upper right", fontsize=8)

    # Second x-axis below the minutes axis, showing actual wall-clock time (ET) every
    # 25 minutes -- same t0 used for t_min above, so a tick at t_min=25 maps to
    # clock_time(t0 + 25 min).
    def _min_to_clock_label(t_min_value, _pos):
        ts = pd.to_datetime(t0 + t_min_value * 60000.0, unit="ms", utc=True)
        ts = ts.tz_convert("America/New_York")
        return ts.strftime("%I:%M %p").lstrip("0")

    secax = ax.secondary_xaxis(-0.28, functions=(lambda x: x, lambda x: x))
    secax.xaxis.set_major_locator(MultipleLocator(25))
    secax.xaxis.set_major_formatter(FuncFormatter(_min_to_clock_label))
    secax.set_xlabel("Clock time (ET)")

    fig.tight_layout(rect=[0, 0, 1, 0.97])
    fig.subplots_adjust(bottom=0.14)

    try:
        if os.path.exists(out_path):
            try:
                os.chmod(out_path, stat.S_IWRITE)
            except OSError:
                pass
            try:
                os.remove(out_path)
            except OSError:
                pass
        fig.savefig(out_path, dpi=150)
    except OSError as e:
        plt.close(fig)
        raise OSError(f"Could not write dashboard PNG to {out_path} ({e})") from e
    plt.close(fig)
    return out_path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("merged_csv")
    ap.add_argument("session_id")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    df = pd.read_csv(args.merged_csv)
    out_path = args.out or f"dashboard_{args.session_id}_clean.png"
    result = plot_clean_dashboard(df, args.session_id, out_path)
    if result:
        print(f"Wrote {result}")
    else:
        print(f"No rows found for session {args.session_id}")


if __name__ == "__main__":
    main()
