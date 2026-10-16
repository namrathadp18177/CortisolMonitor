"""
Short, visual PDF report for generate_classify_synthetic_v2.py's output -- built to
be shown to an advisor/committee at a glance, not read line by line.

Four pages only, minimal text (no Random Forest / ML comparison -- rule-based
classifier only, for a quick meeting update):
  1. What data we have -- session/window counts, a bar of sessions by type.
  2. Cortisol variation -- how it was generated (plain table): baseline, non-
     responder rates, spike size/timing, curve shape, and the 5-sample-per-session
     saliva schedule. Pulls live values from the generator's own constants.
  3. Results -- overall numbers (plain table), accuracy by session type, cortisol
     by type, for the rule-based motion-gated classifier.
  4. Correct vs. incorrect -- the plain window counts behind page 3's accuracy
     percentages: out of N windows, how many were classified correctly, overall
     and by session type.
  5. External validation on WESAD (real human data, not synthetic) -- leave-one-
     subject-out cross-validation results, if wesad_loso_results.csv is found.

Pulls live threshold/constant values from generate_classify_synthetic_v2 so the
numbers stay in sync with the actual pipeline rather than being hand-typed here.

Colors follow a fixed categorical order (session type -> color) so the same entity
is always the same color across pages.

Same zero-evidentiary-value caveat as the generator itself -- every page describes a
SYNTHETIC demonstration, printed small in the footer of every page.

Usage:
    python generate_v2_report.py v2_full_training_set.csv --out v2_report.pdf
"""

import argparse
import os
import sys
import textwrap
from datetime import datetime

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
from matplotlib.patches import FancyBboxPatch
import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate_classify_synthetic_v2 as gen

# ---- palette (validated categorical + sequential palette, light mode) ----
INK_PRIMARY = "#0b0b0b"
INK_SECONDARY = "#52514e"
INK_MUTED = "#898781"
GRIDLINE = "#e1e0d9"
BASELINE = "#c3c2b7"
SURFACE = "#fcfcfb"
PAGE_PLANE = "#f9f9f7"
TOTAL_ROW_TINT = "#eaf1fb"  # pale tint of categorical slot 1, for the "Overall" row

SESSION_TYPE_ORDER = ["rest", "physical", "psychological", "mixed", "daily_baseline"]
# color follows the entity, fixed order, never reassigned by rank
TYPE_COLOR = {
    "rest": "#2a78d6",            # categorical slot 1 (blue)
    "physical": "#eb6834",        # slot 2 (orange)
    "psychological": "#1baf7a",   # slot 3 (aqua)
    "mixed": "#eda100",           # slot 4 (yellow)
    "daily_baseline": "#e87ba4",  # slot 5 (magenta)
}

FOOTER_NOTE = ("Synthetic demonstration data, for pipeline + classifier methodology validation -- "
               "not evidence about real wearable-to-stress or wearable-to-cortisol relationships.")


def _new_page():
    fig = plt.figure(figsize=(11, 8.5))
    fig.patch.set_facecolor(SURFACE)
    return fig


def _add_header(fig, title, subtitle=None):
    fig.text(0.05, 0.94, title, fontsize=19, fontweight="bold", color=INK_PRIMARY)
    if subtitle:
        fig.text(0.05, 0.895, subtitle, fontsize=10, color=INK_SECONDARY)


def _add_footer(fig):
    fig.text(0.5, 0.015, FOOTER_NOTE, fontsize=7, color=INK_MUTED, ha="center")


def _style_axis(ax):
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(BASELINE)
    ax.spines["bottom"].set_linewidth(0.8)
    ax.tick_params(colors=INK_MUTED, labelsize=9)
    ax.yaxis.grid(True, color=GRIDLINE, linewidth=0.8)
    ax.set_axisbelow(True)


def _stat_tile(fig, x, y, w, h, value, label):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0,rounding_size=0.01",
                          transform=fig.transFigure, facecolor=PAGE_PLANE,
                          edgecolor=BASELINE, linewidth=0.8, clip_on=False)
    fig.add_artist(box)
    fig.text(x + w / 2, y + h * 0.60, value, fontsize=21, fontweight="bold",
              color=INK_PRIMARY, ha="center", va="center")
    fig.text(x + w / 2, y + h * 0.22, label, fontsize=8.3, color=INK_MUTED,
              ha="center", va="center")


def page_data_overview(pdf, df):
    """What data we have -- counts only, no explanatory paragraphs."""
    fig = _new_page()
    n_sessions = df["session_id"].nunique()
    n_windows = len(df)
    _add_header(fig, "CortisolMonitor -- Synthetic Dataset",
                f"{datetime.now().strftime('%Y-%m-%d')}  |  {n_sessions} sessions  |  {n_windows} 30s windows")

    sess_counts = df.drop_duplicates("session_id")["session_type"].value_counts()
    blunted_phys = blunted_psych = 0
    if "physical_cortisol_blunted" in df.columns:
        flags = df.drop_duplicates("session_id")[["session_id", "physical_cortisol_blunted"]].dropna()
        blunted_phys = int(flags["physical_cortisol_blunted"].sum())
    if "psychological_cortisol_blunted" in df.columns:
        flags = df.drop_duplicates("session_id")[["session_id", "psychological_cortisol_blunted"]].dropna()
        blunted_psych = int(flags["psychological_cortisol_blunted"].sum())

    tiles = [
        (str(n_sessions), "Sessions"),
        (str(n_windows), "30s windows"),
        (str(len(SESSION_TYPE_ORDER)), "Session types"),
        (f"{blunted_phys} / {blunted_psych}", "Cortisol non-responders (phys / psych)"),
    ]
    tile_w, gap, x0 = 0.21, 0.025, 0.05
    for i, (value, label) in enumerate(tiles):
        _stat_tile(fig, x0 + i * (tile_w + gap), 0.68, tile_w, 0.14, value, label)

    fig.text(0.06, 0.60, "Sessions by type", fontsize=11.5, fontweight="bold", color=INK_PRIMARY)
    ax = fig.add_axes([0.08, 0.12, 0.86, 0.44])
    counts = sess_counts.reindex(SESSION_TYPE_ORDER).fillna(0).astype(int)
    colors = [TYPE_COLOR[t] for t in counts.index]
    bars = ax.bar(range(len(counts)), counts.values, width=0.5, color=colors)
    ax.set_xticks(range(len(counts)))
    ax.set_xticklabels([t.replace("_", " ") for t in counts.index], fontsize=10, color=INK_SECONDARY)
    ymax = max(counts.values) if len(counts) and max(counts.values) > 0 else 1
    for b, v in zip(bars, counts.values):
        ax.text(b.get_x() + b.get_width() / 2, v + ymax * 0.03, str(v),
                 ha="center", fontsize=10, color=INK_PRIMARY, fontweight="bold")
    ax.set_ylim(0, ymax * 1.25)
    ax.set_ylabel("Sessions", fontsize=9.5, color=INK_SECONDARY)
    _style_axis(ax)

    _add_footer(fig)
    pdf.savefig(fig, facecolor=SURFACE)
    plt.close(fig)


def page_cortisol_variation(pdf, df):
    """How cortisol variation was generated -- the mechanism behind page 3's
    cortisol-by-type chart. Plain table, pulls live values from the generator's own
    module constants so it can't drift out of sync with the actual code."""
    fig = _new_page()
    _add_header(fig, "Cortisol Variation -- How It Was Generated")

    sample_str = ", ".join(f"{m:+d}" for m in gen.SAMPLE_OFFSETS_MIN)
    rows_raw = [
        ("1. Baseline",
         "Each session draws its own resting cortisol level (centered around 5, never "
         "below 2) -- not shared across sessions."),
        ("2. Does it respond?",
         f"{gen.NON_RESPONDER_PROB_PHYS:.0%} of physical and {gen.NON_RESPONDER_PROB_PSYCH:.0%} of "
         "psychological stressors are randomly flagged as 'non-responders' -- cortisol barely rises "
         "even though the stressor genuinely happened (a real, documented phenomenon)."),
        ("3. Spike size",
         f"If it responds: rise = intensity x k, where k = {gen.K_PHYS_CORTISOL:.1f} for physical and "
         f"k = {gen.K_PSYCH_CORTISOL:.1f} for psychological -- psychological spikes are bigger by "
         "design, for the same intensity."),
        ("4. Spike timing",
         f"Peaks at a random point {gen.PEAK_MINUTES_RANGE[0]}-{gen.PEAK_MINUTES_RANGE[1]} minutes "
         "after the stressor starts -- cortisol lags behind HR/EDA, which react within seconds."),
        ("5. Curve shape",
         "Rises toward the peak, then decays (a log-normal-shaped bump) -- a simplified stand-in for "
         "the real HPA-axis response curve, not a clinically validated model."),
        ("6. Multiple stressors",
         "In 'mixed' sessions (physical then psychological), the two bumps simply add together on top "
         "of baseline -- they can overlap if the second starts before the first has fully decayed."),
        ("7. Sampling schedule",
         f"Only {len(gen.SAMPLE_OFFSETS_MIN)} saliva samples per session (real assay budget limit): "
         f"{sample_str} minutes relative to session start."),
    ]

    ax = fig.add_axes([0.05, 0.08, 0.90, 0.78])
    ax.axis("off")

    col_char_width = 92
    rows = [[step, textwrap.fill(detail, width=col_char_width)] for step, detail in rows_raw]

    table = ax.table(cellText=rows, colLabels=["Step", "Detail"], loc="upper center",
                      cellLoc="left", colLoc="left", colWidths=[0.16, 0.84])
    table.auto_set_font_size(False)
    table.set_fontsize(9)

    # Per-row heights sized to actual wrapped-line counts -- a uniform table.scale()
    # either clips the longest cells or wastes space on the shortest ones.
    LINE_FRAC = 0.034
    ROW_PAD = 0.020
    HEADER_FRAC = 0.045
    row_lines = [cell_text.count("\n") + 1 for _, cell_text in rows]
    row_heights = [HEADER_FRAC] + [n * LINE_FRAC + ROW_PAD for n in row_lines]

    for (row, col), cell in table.get_celld().items():
        cell.set_edgecolor(GRIDLINE)
        cell.set_linewidth(0.8)
        cell.set_text_props(va="top")
        cell.PAD = 0.015
        cell.set_height(row_heights[row])
        if row == 0:
            cell.set_text_props(fontweight="bold", va="center", color=INK_PRIMARY)
            cell.set_facecolor(PAGE_PLANE)
        else:
            cell.set_text_props(color=INK_PRIMARY if col == 0 else INK_SECONDARY,
                                 fontweight="bold" if col == 0 else "normal")
            cell.set_facecolor(SURFACE if row % 2 == 1 else PAGE_PLANE)

    _add_footer(fig)
    pdf.savefig(fig, facecolor=SURFACE)
    plt.close(fig)


def page_results_graphs(pdf, df):
    """Overall numbers (plain table), accuracy by type, cortisol by type -- rule-based classifier."""
    fig = _new_page()
    overall_acc = (df["predicted_phase"] == df["ground_truth_phase"]).mean()
    _add_header(fig, "Classifier Results",
                "Rule-based, motion-gated classifier -- rest / physical / psychological")

    # Panel 1: overall numbers as a plain table -- no color coding, just the counts
    n_all = len(df)
    correct_all = int((df["predicted_phase"] == df["ground_truth_phase"]).sum())
    ax1 = fig.add_axes([0.07, 0.40, 0.26, 0.30])
    ax1.axis("off")
    ax1.set_title("Overall", fontsize=10, color=INK_PRIMARY, pad=14, loc="left")
    t1_rows = [
        ["Windows", str(n_all)],
        ["Correct", str(correct_all)],
        ["Incorrect", str(n_all - correct_all)],
        ["Accuracy", f"{overall_acc:.1%}"],
    ]
    t1 = ax1.table(cellText=t1_rows, colLabels=["Metric", "Value"], loc="center",
                    cellLoc="left", colLoc="left", colWidths=[0.55, 0.45])
    t1.auto_set_font_size(False)
    t1.set_fontsize(10)
    t1.scale(1, 2.1)
    for (row, col), cell in t1.get_celld().items():
        cell.set_edgecolor(GRIDLINE)
        cell.set_linewidth(0.8)
        if row == 0:
            cell.set_text_props(fontweight="bold", color=INK_PRIMARY)
            cell.set_facecolor(PAGE_PLANE)
        elif row == len(t1_rows):  # "Accuracy" is always the last row
            cell.set_text_props(fontweight="bold", color=INK_PRIMARY)
            cell.set_facecolor(TOTAL_ROW_TINT)
        else:
            cell.set_text_props(color=INK_PRIMARY if col == 0 else INK_SECONDARY)
            cell.set_facecolor(SURFACE if row % 2 == 1 else PAGE_PLANE)

    # Panel 2: accuracy by session type
    ax2 = fig.add_axes([0.40, 0.20, 0.27, 0.52])
    per_type = df.groupby("session_type").apply(
        lambda g: (g["predicted_phase"] == g["ground_truth_phase"]).mean())
    per_type = per_type.reindex(SESSION_TYPE_ORDER).dropna()
    colors = [TYPE_COLOR[t] for t in per_type.index]
    bars = ax2.bar(range(len(per_type)), per_type.values, width=0.55, color=colors)
    ax2.set_xticks(range(len(per_type)))
    ax2.set_xticklabels([t.replace("_", " ") for t in per_type.index], fontsize=7.8, color=INK_SECONDARY,
                         rotation=32, ha="right", rotation_mode="anchor")
    ax2.set_ylim(0, 1.15)
    for b, v in zip(bars, per_type.values):
        ax2.text(b.get_x() + b.get_width() / 2, v + 0.03, f"{v:.0%}", ha="center",
                  fontsize=8.5, color=INK_PRIMARY, fontweight="bold")
    _style_axis(ax2)
    ax2.set_title("Accuracy by session type", fontsize=10, color=INK_PRIMARY, pad=10)

    # Panel 3: cortisol by session type (min/mean/max)
    ax3 = fig.add_axes([0.72, 0.20, 0.24, 0.52])
    cort = df.groupby("session_type")["cortisol_value"].agg(["min", "mean", "max"])
    cort = cort.reindex(SESSION_TYPE_ORDER).dropna()
    x = np.arange(len(cort))
    colors = [TYPE_COLOR[t] for t in cort.index]
    ax3.bar(x, cort["mean"], width=0.5, color=colors, alpha=0.85)
    ax3.vlines(x, cort["min"], cort["max"], color=INK_SECONDARY, linewidth=1.2)
    ax3.set_xticks(x)
    ax3.set_xticklabels([t.replace("_", " ") for t in cort.index], fontsize=7.8, color=INK_SECONDARY,
                         rotation=32, ha="right", rotation_mode="anchor")
    _style_axis(ax3)
    ax3.set_title("Cortisol by type\n(min / mean / max)", fontsize=10, color=INK_PRIMARY, pad=10)

    _add_footer(fig)
    pdf.savefig(fig, facecolor=SURFACE)
    plt.close(fig)


def page_correctness_table(pdf, df):
    """Plain window counts behind page 2's accuracy percentages: out of N windows,
    how many were classified correctly -- overall and by session type."""
    fig = _new_page()
    _add_header(fig, "Correct vs. Incorrect, by Session Type")

    col_labels = ["Session type", "Windows", "Correct", "Incorrect", "Accuracy"]
    rows = []
    for t in SESSION_TYPE_ORDER:
        g = df[df["session_type"] == t]
        if g.empty:
            continue
        n = len(g)
        correct = int((g["predicted_phase"] == g["ground_truth_phase"]).sum())
        rows.append([t.replace("_", " "), str(n), str(correct), str(n - correct), f"{correct / n:.1%}"])

    n_all = len(df)
    correct_all = int((df["predicted_phase"] == df["ground_truth_phase"]).sum())
    rows.append(["Overall", str(n_all), str(correct_all), str(n_all - correct_all), f"{correct_all / n_all:.1%}"])

    ax = fig.add_axes([0.12, 0.32, 0.76, 0.42])
    ax.axis("off")
    table = ax.table(cellText=rows, colLabels=col_labels, loc="upper center",
                      cellLoc="center", colLoc="center",
                      colWidths=[0.26, 0.17, 0.17, 0.17, 0.17])
    table.auto_set_font_size(False)
    table.set_fontsize(11)
    table.scale(1, 2.3)

    n_rows = len(rows)
    for (row, col), cell in table.get_celld().items():
        cell.set_edgecolor(GRIDLINE)
        cell.set_linewidth(0.8)
        if row == 0:
            cell.set_text_props(fontweight="bold", color=INK_PRIMARY)
            cell.set_facecolor(PAGE_PLANE)
        elif row == n_rows:  # "Overall" is always the last data row
            cell.set_text_props(fontweight="bold", color=INK_PRIMARY)
            cell.set_facecolor(TOTAL_ROW_TINT)
        else:
            cell.set_text_props(color=INK_PRIMARY if col == 0 else INK_SECONDARY,
                                 fontweight="bold" if col == 0 else "normal")
            cell.set_facecolor(SURFACE if row % 2 == 1 else PAGE_PLANE)

    _add_footer(fig)
    pdf.savefig(fig, facecolor=SURFACE)
    plt.close(fig)


WESAD_FOOTER_NOTE = ("WESAD (Schmidt et al. 2018) is a public, real-human-subject affective-computing "
                      "dataset -- this page is real data, not synthetic. Leave-one-subject-out means each "
                      "subject's result comes from a model that never saw that subject during training.")


def page_wesad_validation(pdf, wesad_df):
    """External validation on a REAL public dataset (not synthetic) -- how well a
    Random Forest trained on WESAD's physiological features detects stress in a
    subject it never trained on (leave-one-subject-out cross-validation)."""
    fig = _new_page()
    _add_header(fig, "External Validation -- WESAD (Real Data)",
                "Leave-one-subject-out cross-validation, Random Forest, binary stress vs. non-stress")

    summary = wesad_df.groupby("config").agg(
        subjects=("subject", "nunique"), windows=("windows", "sum"),
        mean_accuracy=("accuracy", "mean"), mean_f1=("f1", "mean")).reset_index()
    # "EDA+HRV" (matches what our wearable collects) before the full-feature config
    summary["_order"] = summary["config"].str.startswith("EDA+HRV (").map({True: 0, False: 1})
    summary = summary.sort_values("_order")

    n_subjects = int(wesad_df["subject"].nunique())
    n_windows = int(wesad_df.groupby("config")["windows"].sum().iloc[0])
    tiles = [
        (str(n_subjects), "Real subjects (WESAD)"),
        (str(n_windows), "60s windows per config"),
        ("LOSO", "Cross-validation"),
        (f"{summary['mean_f1'].max():.3f}", "Best mean F1 (stress class)"),
    ]
    tile_w, gap, x0 = 0.21, 0.025, 0.05
    for i, (value, label) in enumerate(tiles):
        _stat_tile(fig, x0 + i * (tile_w + gap), 0.68, tile_w, 0.14, value, label)

    fig.text(0.06, 0.58, "Results by feature set", fontsize=11.5, fontweight="bold", color=INK_PRIMARY)
    ax = fig.add_axes([0.12, 0.30, 0.76, 0.24])
    ax.axis("off")
    col_labels = ["Feature set", "Subjects", "Windows", "Mean accuracy", "Mean F1"]
    rows = [[row.config, str(int(row.subjects)), str(int(row.windows)),
             f"{row.mean_accuracy:.1%}", f"{row.mean_f1:.3f}"] for row in summary.itertuples()]
    table = ax.table(cellText=rows, colLabels=col_labels, loc="upper center",
                      cellLoc="center", colLoc="center",
                      colWidths=[0.40, 0.14, 0.14, 0.17, 0.15])
    table.auto_set_font_size(False)
    table.set_fontsize(10.5)
    table.scale(1, 2.3)
    for (row, col), cell in table.get_celld().items():
        cell.set_edgecolor(GRIDLINE)
        cell.set_linewidth(0.8)
        if row == 0:
            cell.set_text_props(fontweight="bold", color=INK_PRIMARY)
            cell.set_facecolor(PAGE_PLANE)
        else:
            cell.set_text_props(color=INK_PRIMARY if col == 0 else INK_SECONDARY)
            cell.set_facecolor(TOTAL_ROW_TINT if row == len(rows) else
                                (SURFACE if row % 2 == 1 else PAGE_PLANE))

    fig.text(0.5, 0.015, WESAD_FOOTER_NOTE, fontsize=7, color=INK_MUTED, ha="center", wrap=True)
    pdf.savefig(fig, facecolor=SURFACE)
    plt.close(fig)


def build_report(df, out_path, wesad_df=None):
    with PdfPages(out_path) as pdf:
        page_data_overview(pdf, df)
        page_cortisol_variation(pdf, df)
        page_results_graphs(pdf, df)
        page_correctness_table(pdf, df)
        if wesad_df is not None:
            page_wesad_validation(pdf, wesad_df)
    return out_path


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("merged_csv", help="v2_full_training_set.csv from generate_classify_synthetic_v2.py")
    ap.add_argument("--out", default="v2_report.pdf")
    ap.add_argument("--wesad-csv", default="../wesad/wesad_loso_results.csv",
                     help="wesad_loso_results.csv from wesad_loso.py -- adds the real-data validation "
                          "page if found; the report still builds fine without it.")
    args = ap.parse_args()

    df = pd.read_csv(args.merged_csv)
    wesad_df = None
    if os.path.exists(args.wesad_csv):
        wesad_df = pd.read_csv(args.wesad_csv)
    else:
        print(f"Note: WESAD results not found at {args.wesad_csv} -- skipping that page "
              f"(pass --wesad-csv to point at it).")
    result = build_report(df, args.out, wesad_df)
    print(f"Wrote {result}")


if __name__ == "__main__":
    main()
