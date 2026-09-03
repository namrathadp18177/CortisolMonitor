"""
WESAD binary stress detection — leave-one-subject-out (LOSO) pipeline.

Cortisol Monitor thesis, Stage-1 prototype on the public WESAD dataset.
Mirrors the planned approach: window-level physiological features ->
Random Forest -> leave-one-subject-out cross-validation -> F1.

Data: per-subject WESAD feature CSVs (S2-S17, excl S12), 60s windows / 50% overlap.
Columns: HRV (mean_rr, sdnn, rmssd, ecg_peak_count),
         EDA (eda_mean, eda_std, eda_peak_count, eda_slope),
         Respiration (resp_mean, resp_std, resp_zero_crossings), label.
WESAD labels: 1=Baseline, 2=Stress, 3=Amusement (others excluded).
Task: binary  ->  Stress (2)  vs  Non-stress (Baseline 1 + Amusement 3).
"""

import glob, os, sys
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import f1_score, accuracy_score, classification_report

DATA_DIR = sys.argv[1] if len(sys.argv) > 1 else "data/features"

HRV = ["mean_rr", "sdnn", "rmssd", "ecg_peak_count"]
EDA = ["eda_mean", "eda_std", "eda_peak_count", "eda_slope"]
RESP = ["resp_mean", "resp_std", "resp_zero_crossings"]

def load():
    rows = []
    for f in sorted(glob.glob(os.path.join(DATA_DIR, "S*_features.csv"))):
        sid = os.path.basename(f).split("_")[0]      # e.g. "S2"
        d = pd.read_csv(f)
        d["subject"] = sid
        rows.append(d)
    df = pd.concat(rows, ignore_index=True)
    # keep the three affective conditions only
    df = df[df["label"].isin([1, 2, 3])].copy()
    # binary target: stress (2) = 1, else 0
    df["y"] = (df["label"] == 2).astype(int)
    return df

def loso(df, feats, name):
    subs = sorted(df["subject"].unique(), key=lambda s: int(s[1:]))
    per = []
    all_true, all_pred = [], []
    importances = np.zeros(len(feats))
    for s in subs:
        tr = df[df["subject"] != s]
        te = df[df["subject"] == s]
        clf = RandomForestClassifier(
            n_estimators=300, class_weight="balanced",
            random_state=42, n_jobs=-1)
        clf.fit(tr[feats], tr["y"])
        pred = clf.predict(te[feats])
        f1 = f1_score(te["y"], pred, zero_division=0)
        acc = accuracy_score(te["y"], pred)
        per.append((s, len(te), int(te["y"].sum()), round(acc, 3), round(f1, 3)))
        all_true += list(te["y"]); all_pred += list(pred)
        importances += clf.feature_importances_
    importances /= len(subs)

    res = pd.DataFrame(per, columns=["subject", "windows", "stress_windows", "accuracy", "f1"])
    print(f"\n================  {name}  ({len(feats)} features)  ================")
    print(f"features: {feats}")
    print(res.to_string(index=False))
    print(f"\nMean accuracy : {res.accuracy.mean():.3f}")
    print(f"Mean F1 (stress class) : {res.f1.mean():.3f}")
    print(f"Pooled accuracy: {accuracy_score(all_true, all_pred):.3f}  "
          f"weighted F1: {f1_score(all_true, all_pred, average='weighted'):.3f}")
    print("\nPooled classification report (0=non-stress, 1=stress):")
    print(classification_report(all_true, all_pred, digits=3, zero_division=0))
    imp = pd.Series(importances, index=feats).sort_values(ascending=False)
    print("Mean feature importance:")
    print(imp.to_string())
    res["config"] = name
    return res, imp

def main():
    df = load()
    print(f"Loaded {len(df)} windows from {df.subject.nunique()} subjects.")
    print(f"Class balance -> non-stress: {(df.y==0).sum()}, stress: {(df.y==1).sum()}")

    r1, imp1 = loso(df, EDA + HRV, "EDA+HRV (our wearable signals)")
    r2, imp2 = loso(df, EDA + HRV + RESP, "EDA+HRV+Respiration (all features)")

    out = pd.concat([r1, r2], ignore_index=True)
    out.to_csv("wesad_loso_results.csv", index=False)
    print("\nSaved per-subject results -> wesad_loso_results.csv")

if __name__ == "__main__":
    main()
