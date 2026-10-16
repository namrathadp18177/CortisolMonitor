"""
Results dashboard for generate_classify_synthetic_v2.py's output -- a single PNG
summarizing how well the motion-gated rule-based classifier told rest/physical/
psychological apart, whether the synthetic cortisol behaved as designed (including
non-responders), and how the classifier handles the "EDA spiked even though I was
asleep" case (daily_baseline sessions' spontaneous no-motion EDA bursts).

This is a SUMMARY dashboard across all sessions (confusion matrix, per-type accuracy,
cortisol by type, counts table) -- different from plot_dashboard.py / plot_dashboard_clean.py,
which show one session's physiological traces over time. Those still work unchanged
on v2_full_training_set.csv for a single session_id (same merged-window schema), if
you want to see one session's actual HR/EDA/skin-temp/accel curves.

Usage:
    python plot_v2_summary_dashboard.py v2_full_training_set.csv --out v2_summary_dashboard.png
"""

import argparse
import os
import stat

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

PHASE_ORDER = ["rest", "physical", "psychological"]  # ground-truth labels -- still 3
# Predicted labels can now be 4-valued (see generate_classify_synthetic_v2.py's
# HR_CORROBORATION_FRAC) -- PRED_ORDER is used anywhere the table/heatmap indexes by
# predicted_phase, so "non_stress_arousal" predictions don't get silently reindexed
# away. A prior version of this file used PHASE_ORDER for both axes, which quietly
# dropped every non_stress_arousal count from the confusion matrix and counts table.
PRED_ORDER = PHASE_ORDER + ["non_stress_arousal"]
TYPE_ORDER = ["rest", "physical", "psychological", "mixed", "daily_baseline", "sustained_eda_drift"]

TYPE_COLOR = {
    "rest": "#78909C", "physical": "#E53935", "psychological": "#1E88E5",
    "mixed": "#8E24AA", "daily_baseline": "#00897B", "sustained_eda_drift": "#6D4C41",
}


def _build_counts_table(df):
    rows = []

    gt_counts = df["ground_truth_phase"].value_counts()
    pred_counts = df["predicted_phase"].value_counts()
    rows.append(("Windows -- ground truth / predicted", ""))
    for phase in PHASE_ORDER:
        rows.append((f"  {phase}", f"{int(gt_counts.get(phase, 0))}  /  {int(pred_counts.get(phase, 0))}"))
    if "non_stress_arousal" in pred_counts.index:
        rows.append(("  non_stress_arousal", f"(no ground truth)  /  {int(pred_counts.get('non_stress_arousal', 0))}"))

    sess_counts = df.drop_duplicates("session_id")["session_type"].value_counts()
    rows.append(("Sessions generated, by type", ""))
    for t in TYPE_ORDER:
        rows.append((f"  {t}", int(sess_counts.get(t, 0))))

    rows.append(("Cortisol non-responders (stressor happened, cortisol barely moved)", ""))
    for kind, col in [("physical", "physical_cortisol_blunted"), ("psychological", "psychological_cortisol_blunted")]:
        if col in df.columns:
            sess_flags = df.drop_duplicates("session_id")[["session_id", col]].dropna(subset=[col])
            n_blunted, n_total = int(sess_flags[col].sum()), len(sess_flags)
            rows.append((f"  {kind}", f"{n_blunted} / {n_total} sessions"))

    if "is_spontaneous_eda_burst" in df.columns:
        burst = df[df["is_spontaneous_eda_burst"]]
        rows.append(("Case A -- brief spontaneous no-motion EDA bursts (negative control)", ""))
        if len(burst):
            n_mis = int((burst["predicted_phase"] == "psychological").sum())
            n_nsa = int((burst["predicted_phase"] == "non_stress_arousal").sum())
            rows.append(("  windows with a burst", int(len(burst))))
            rows.append(("  false 'psychological' calls", f"{n_mis} / {len(burst)}"))
            rows.append(("  called 'non_stress_arousal' instead", f"{n_nsa} / {len(burst)}"))
        else:
            rows.append(("  windows with a burst", 0))

    if "is_sustained_eda_drift" in df.columns:
        drift = df[df["is_sustained_eda_drift"]]
        rows.append(("Case B (CRITICAL) -- sustained no-motion EDA-only elevation (negative control)", ""))
        if len(drift):
            n_mis = int((drift["predicted_phase"] == "psychological").sum())
            n_nsa = int((drift["predicted_phase"] == "non_stress_arousal").sum())
            rows.append(("  windows in a sustained drift", int(len(drift))))
            rows.append(("  false 'psychological' calls", f"{n_mis} / {len(drift)}"))
            rows.append(("  called 'non_stress_arousal' instead", f"{n_nsa} / {len(drift)}"))
        else:
            rows.append(("  windows in a sustained drift", 0))

    return rows


def plot_summary_dashboard(df, out_path):
    fig = plt.figure(figsize=(16, 10.5))
    gs = fig.add_gridspec(2, 3, height_ratios=[1, 1])
    fig.suptitle("Synthetic v2: classifier results summary (rule-based motion-gated classifier)",
                 fontsize=13, fontweight="bold", color="#212121")

    # Panel 1: confusion matrix heatmap. Rows (ground truth) stay 3-valued; columns
    # (predicted) use PRED_ORDER so a "non_stress_arousal" column shows up instead of
    # being silently dropped -- it will always be an all-zero ROW (ground truth is
    # never non_stress_arousal) but a real COLUMN whenever the rule-based classifier
    # produces it.
    ax = fig.add_subplot(gs[0, 0])
    confusion = pd.crosstab(df["ground_truth_phase"], df["predicted_phase"])
    confusion = confusion.reindex(index=PHASE_ORDER, columns=PRED_ORDER, fill_value=0)
    ax.imshow(confusion.values, cmap="Blues", vmin=0, aspect="auto")
    ax.set_xticks(range(len(PRED_ORDER)))
    ax.set_yticks(range(len(PHASE_ORDER)))
    ax.set_xticklabels(PRED_ORDER, rotation=25, ha="right")
    ax.set_yticklabels(PHASE_ORDER)
    ax.set_xlabel("Predicted")
    ax.set_ylabel("Actual (ground truth)")
    ax.set_title("Confusion matrix (window count)")
    vmax = confusion.values.max() if confusion.values.size else 1
    for i in range(len(PHASE_ORDER)):
        for j in range(len(PRED_ORDER)):
            val = confusion.values[i, j]
            color = "white" if val > vmax * 0.5 else "#212121"
            ax.text(j, i, str(val), ha="center", va="center", color=color, fontsize=11, fontweight="bold")
    overall_acc = (df["predicted_phase"] == df["ground_truth_phase"]).mean()
    ax.text(0.5, -0.24, f"Overall accuracy: {overall_acc:.1%}  (n={len(df)} windows)",
            transform=ax.transAxes, ha="center", fontsize=10, color="#212121")

    # Panel 2: per-session-type accuracy
    ax = fig.add_subplot(gs[0, 1])
    per_type = df.groupby("session_type").apply(
        lambda g: (g["predicted_phase"] == g["ground_truth_phase"]).mean())
    per_type = per_type.reindex(TYPE_ORDER).dropna()
    colors = [TYPE_COLOR.get(t, "#999999") for t in per_type.index]
    bars = ax.bar(per_type.index, per_type.values, color=colors)
    ax.set_ylim(0, 1.05)
    ax.set_ylabel("Accuracy")
    ax.set_title("Accuracy by session type")
    ax.tick_params(axis="x", rotation=20)
    for bar, val in zip(bars, per_type.values):
        ax.text(bar.get_x() + bar.get_width() / 2, val + 0.02, f"{val:.0%}",
                ha="center", fontsize=9, color="#212121")

    # Panel 3: cortisol value by session type (min-mean-max range)
    ax = fig.add_subplot(gs[0, 2])
    cort = df.groupby("session_type")["cortisol_value"].agg(["min", "mean", "max"])
    cort = cort.reindex(TYPE_ORDER).dropna()
    x = np.arange(len(cort.index))
    colors = [TYPE_COLOR.get(t, "#999999") for t in cort.index]
    ax.bar(x, cort["mean"], color=colors, alpha=0.85)
    ax.vlines(x, cort["min"], cort["max"], color="#212121", linewidth=1.5)
    ax.scatter(x, cort["min"], color="#212121", marker="_", s=120)
    ax.scatter(x, cort["max"], color="#212121", marker="_", s=120)
    ax.set_xticks(x)
    ax.set_xticklabels(cort.index, rotation=20)
    ax.set_ylabel("Cortisol value (synthetic)")
    ax.set_title("Cortisol by session type (min/mean/max)")

    # Panel 4: counts table, spanning the full width
    ax = fig.add_subplot(gs[1, :])
    ax.axis("off")
    rows = _build_counts_table(df)
    cell_text, cell_colors = [], []
    for label, value in rows:
        is_header = (value == "")
        cell_text.append([label, "" if is_header else str(value)])
        cell_colors.append(["#ECEFF1", "#ECEFF1"] if is_header else ["white", "white"])
    table = ax.table(cellText=cell_text, colLabels=["Metric", "Value"], cellColours=cell_colors,
                      colColours=["#CFD8DC", "#CFD8DC"], loc="center", cellLoc="left", colLoc="left")
    table.auto_set_font_size(False)
    table.set_fontsize(9.5)
    table.scale(1, 1.35)
    table.auto_set_column_width([0, 1])
    for (row, col), cell in table.get_celld().items():
        if row == 0:
            cell.set_text_props(fontweight="bold")
        elif cell_text[row - 1][1] == "" and row > 0:
            cell.set_text_props(fontweight="bold")
    ax.set_title("Counts summary", fontsize=11, loc="left")

    fig.tight_layout(rect=[0, 0, 1, 0.96])

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
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("merged_csv", help="v2_full_training_set.csv from generate_classify_synthetic_v2.py")
    ap.add_argument("--out", default="v2_summary_dashboard.png")
    args = ap.parse_args()

    df = pd.read_csv(args.merged_csv)
    result = plot_summary_dashboard(df, args.out)
    print(f"Wrote {result}")


if __name__ == "__main__":
    main()
