"""
Compares the hand-built rule-based classifier (in generate_classify_synthetic_v2.py)
against a Random Forest trained on the same synthetic data, to answer Nami's
question: "is there any other ML algorithm that can get better results?"

IMPORTANT METHODOLOGY NOTES -- read before trusting any number this prints:

1. CLASS IMBALANCE: ~88% of windows are "rest" (session baselines + recovery
   dominate). A classifier that always guesses "rest" would already score ~88%
   accuracy without learning anything. That's why this script reports macro-F1 and
   per-class recall/precision alongside accuracy, not accuracy alone -- accuracy by
   itself would make a lazy model look almost as good as a real one. Pay attention to
   PHYSICAL and PSYCHOLOGICAL recall specifically: that's "of the real stress/activity
   events, how many did it actually catch."

2. LEAKAGE / fair evaluation: the rule-based classifier isn't "trained" on anything,
   so there's no leakage risk -- its number is just its number. A Random Forest IS
   fit to data, so if it were evaluated on the same rows it was trained on, the
   accuracy would be inflated (it can partly memorize). To keep the comparison fair,
   the random forest is evaluated with GroupKFold cross-validation GROUPED BY
   session_id: whole sessions are held out together, never split across train/test,
   so the model is always scored on sessions it has never seen. All reported RF
   numbers are these out-of-fold (held-out) predictions, not training-set performance.
   An earlier version of this script still had a narrower leak: the median imputer
   (point 3 below) was fit on the WHOLE dataset before cross-validation split it,
   so each fold's held-out rows had already influenced the fill values used on them
   -- caught by Nami in review. The imputer is now fit fresh inside each fold, on
   that fold's training rows only (see _cross_val_predict_grouped).

3. Two random forests are trained, not one:
   - "RF (same features)" uses EXACTLY the 3 inputs the rule-based classifier uses
     (hr_dev_from_baseline, eda_dev_from_baseline, is_moving) -- isolates "does
     learning the thresholds from data beat hand-picking them," with identical
     information.
   - "RF (full features)" also gets accel_mean_mag_g, accel_pct_motion_flagged,
     skintemp_skin_slope_c_per_s, hrv_rmssd, eda_slope, eda_std -- shows what's
     possible when the model gets to use signals the rule-based classifier ignores
     (like skin temp direction, which Nami specifically asked about defending).
   Missing values in the extra features (e.g. hrv_rmssd is NaN when too few beats
   were detected in a window) are median-imputed -- a real missing-data strategy
   would need more care than this script gives it.

4. Small-data caveat for the thesis: with the default --n-per-type, only a few dozen
   synthetic sessions exist (session count = n_per_type x number of session types in
   generate_classify_synthetic_v2.py -- 6 types as of 2026-10, up from 5). 5-fold
   GroupKFold cross-validation on that few sessions is already stretched thin (only a
   handful held out per fold); any number here should be treated as directional, not
   precise, and re-run once real session counts grow (especially once real TSST data
   exists -- see the chat discussion on why a complex model trained on N=6 real
   participants would generalize worse than something simple).

5. "non_stress_arousal": as of 2026-10, the rule-based classifier can predict a 4th
   label (see generate_classify_synthetic_v2.py's HR_CORROBORATION_FRAC) that never
   appears in ground_truth_phase (there are only 3 ground-truth phases: rest/physical/
   psychological). PHASE_ORDER below intentionally stays 3-valued -- the Random Forest
   is trained on ground_truth_phase and never needs to predict a 4th class, and
   precision_recall_fscore_support(..., labels=PHASE_ORDER) already scores a
   rule-based "non_stress_arousal" prediction as simply wrong for whatever the true
   label was (correctly dragging down recall for any true psychological window that
   got this label), which is what you want. What labels=PHASE_ORDER does NOT show on
   its own is where those predictions actually went -- _print_report below adds an
   explicit count of them so that's visible rather than hidden inside a recall number.

Usage:
    python compare_classifiers.py v2_full_training_set.csv --out comparison.png
"""

import argparse
import os
import stat

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import GroupKFold
from sklearn.metrics import accuracy_score, f1_score, precision_recall_fscore_support, confusion_matrix
from sklearn.impute import SimpleImputer

PHASE_ORDER = ["rest", "physical", "psychological"]

FEATURES_SAME = ["hr_dev_from_baseline", "eda_dev_from_baseline", "is_moving"]
FEATURES_EXTRA = ["accel_mean_mag_g", "accel_pct_motion_flagged",
                   "skintemp_skin_slope_c_per_s", "hrv_rmssd", "eda_slope", "eda_std"]
FEATURES_FULL = FEATURES_SAME + FEATURES_EXTRA

N_SPLITS = 5
RF_PARAMS = dict(n_estimators=300, max_depth=6, min_samples_leaf=3,
                  class_weight="balanced", random_state=42)


def _prep_features(df, feature_cols):
    """Only the deterministic, row-independent conversion (bool -> int) happens here.
    Median imputation is NOT done here -- it has to be fit per-fold, inside the
    cross-validation loop, using only that fold's training rows (see
    _cross_val_predict_grouped). Imputing before the split would let the held-out
    test rows influence the fill values used on them -- a real leak, caught by Nami
    in review: an earlier version of this function imputed on the whole dataset
    before any split existed."""
    X = df[feature_cols].copy()
    if "is_moving" in X.columns:
        X["is_moving"] = X["is_moving"].astype(bool).astype(int)
    return X


def _cross_val_predict_grouped(df, feature_cols, y, groups):
    """GroupKFold out-of-fold predictions, grouped by session_id -- no session ever
    appears in both train and test for the same fold. The median imputer is fit
    fresh inside each fold on that fold's TRAINING rows only, then applied to both
    train and test -- so no statistic derived from a held-out session ever touches
    that session's own prediction, or any other fold's."""
    X_raw = _prep_features(df, feature_cols)
    missing_cols = [c for c in feature_cols if c != "is_moving" and X_raw[c].isna().any()]

    gkf = GroupKFold(n_splits=N_SPLITS)
    oof_pred = pd.Series(index=df.index, dtype=object)
    importances = []
    for train_idx, test_idx in gkf.split(X_raw, y, groups):
        X_train = X_raw.iloc[train_idx].copy()
        X_test = X_raw.iloc[test_idx].copy()
        if missing_cols:
            imputer = SimpleImputer(strategy="median")
            X_train[missing_cols] = imputer.fit_transform(X_train[missing_cols])
            X_test[missing_cols] = imputer.transform(X_test[missing_cols])
        clf = RandomForestClassifier(**RF_PARAMS)
        clf.fit(X_train, y.iloc[train_idx])
        oof_pred.iloc[test_idx] = clf.predict(X_test)
        importances.append(clf.feature_importances_)
    mean_importance = pd.Series(np.mean(importances, axis=0), index=feature_cols).sort_values(ascending=False)
    return oof_pred, mean_importance


def _metrics_for(y_true, y_pred, label):
    acc = accuracy_score(y_true, y_pred)
    macro_f1 = f1_score(y_true, y_pred, labels=PHASE_ORDER, average="macro", zero_division=0)
    precision, recall, f1, support = precision_recall_fscore_support(
        y_true, y_pred, labels=PHASE_ORDER, zero_division=0)
    per_class = {phase: {"precision": p, "recall": r, "f1": f, "support": s}
                 for phase, p, r, f, s in zip(PHASE_ORDER, precision, recall, f1, support)}
    cm = confusion_matrix(y_true, y_pred, labels=PHASE_ORDER)
    return {"label": label, "accuracy": acc, "macro_f1": macro_f1, "per_class": per_class, "confusion": cm}


def run_comparison(df):
    y = df["ground_truth_phase"]
    groups = df["session_id"]

    results = []
    results.append(_metrics_for(y, df["predicted_phase"], "Rule-based (hand-tuned)"))

    rf_same_pred, rf_same_importance = _cross_val_predict_grouped(df, FEATURES_SAME, y, groups)
    results.append(_metrics_for(y, rf_same_pred, "Random Forest (same 3 features)"))

    rf_full_pred, rf_full_importance = _cross_val_predict_grouped(df, FEATURES_FULL, y, groups)
    results.append(_metrics_for(y, rf_full_pred, "Random Forest (full features)"))

    return results, rf_full_importance


def _print_report(results, rf_full_importance, df=None):
    print("\n" + "=" * 72)
    print("CLASSIFIER COMPARISON  (Random Forest numbers are 5-fold GroupKFold")
    print("out-of-fold / held-out predictions -- not training-set accuracy)")
    print("=" * 72)
    for r in results:
        print(f"\n--- {r['label']} ---")
        print(f"  Overall accuracy: {r['accuracy']:.1%}   Macro-F1: {r['macro_f1']:.3f}")
        print(f"  {'phase':<15}{'precision':>10}{'recall':>10}{'f1':>10}{'support':>10}")
        for phase in PHASE_ORDER:
            pc = r["per_class"][phase]
            print(f"  {phase:<15}{pc['precision']:>10.1%}{pc['recall']:>10.1%}{pc['f1']:>10.3f}{pc['support']:>10.0f}")

    print("\nFeature importance (Random Forest, full feature set, averaged across folds):")
    for feat, imp in rf_full_importance.items():
        print(f"  {feat:<30}{imp:.3f}")

    # Rule-based "non_stress_arousal" predictions never match a PHASE_ORDER label, so
    # they already count against recall above (point 5 in the module docstring) --
    # this just makes visible WHERE they actually went, instead of leaving that hidden
    # inside a reduced recall number.
    if df is not None and "non_stress_arousal" in df["predicted_phase"].unique():
        print("\n'non_stress_arousal' predictions (rule-based only) by true ground-truth label:")
        nsa = df[df["predicted_phase"] == "non_stress_arousal"]
        print(f"  {nsa['ground_truth_phase'].value_counts().to_string()}")


def plot_comparison(results, rf_full_importance, out_path):
    fig, axes = plt.subplots(1, 3, figsize=(17, 5.5))
    fig.suptitle("Rule-based classifier vs. Random Forest (held-out, GroupKFold by session)",
                 fontsize=13, fontweight="bold")

    labels = [r["label"] for r in results]
    short_labels = ["Rule-based", "RF\n(same feat.)", "RF\n(full feat.)"]
    colors = ["#78909C", "#1E88E5", "#8E24AA"]

    ax = axes[0]
    accs = [r["accuracy"] for r in results]
    f1s = [r["macro_f1"] for r in results]
    x = np.arange(len(results))
    width = 0.35
    ax.bar(x - width / 2, accs, width, label="Accuracy", color=colors)
    ax.bar(x + width / 2, f1s, width, label="Macro-F1", color=colors, alpha=0.5)
    ax.set_xticks(x)
    ax.set_xticklabels(short_labels, fontsize=9)
    ax.set_ylim(0, 1.05)
    ax.set_title("Accuracy vs. Macro-F1\n(macro-F1 isn't fooled by the 88% 'rest' majority class)")
    ax.legend(fontsize=8)
    for i, (a, f) in enumerate(zip(accs, f1s)):
        ax.text(i - width / 2, a + 0.02, f"{a:.0%}", ha="center", fontsize=8)
        ax.text(i + width / 2, f + 0.02, f"{f:.2f}", ha="center", fontsize=8)

    ax = axes[1]
    width = 0.25
    x = np.arange(len(PHASE_ORDER))
    for i, r in enumerate(results):
        recalls = [r["per_class"][p]["recall"] for p in PHASE_ORDER]
        ax.bar(x + (i - 1) * width, recalls, width, label=short_labels[i].replace("\n", " "), color=colors[i])
    ax.set_xticks(x)
    ax.set_xticklabels(PHASE_ORDER)
    ax.set_ylim(0, 1.05)
    ax.set_ylabel("Recall")
    ax.set_title('Recall by class\n("of the real events, how many did it catch")')
    ax.legend(fontsize=7.5)

    ax = axes[2]
    imp = rf_full_importance.sort_values()
    ax.barh(imp.index, imp.values, color="#8E24AA")
    ax.set_title("Feature importance\n(Random Forest, full feature set)")
    ax.set_xlabel("Mean decrease in impurity (avg. across folds)")

    fig.tight_layout(rect=[0, 0, 1, 0.92])

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
        raise OSError(f"Could not write comparison PNG to {out_path} ({e})") from e
    plt.close(fig)
    return out_path


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("merged_csv", help="v2_full_training_set.csv from generate_classify_synthetic_v2.py")
    ap.add_argument("--out", default="classifier_comparison.png")
    args = ap.parse_args()

    df = pd.read_csv(args.merged_csv)
    results, rf_full_importance = run_comparison(df)
    _print_report(results, rf_full_importance, df)
    out = plot_comparison(results, rf_full_importance, args.out)
    print(f"\nWrote {out}")


if __name__ == "__main__":
    main()
