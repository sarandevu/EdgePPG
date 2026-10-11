# EdgePPG — Model Experiments, Calibration & Parity Report

**Audit Date:** October 10, 2026  
**Auditor:** Senior Applied ML Researcher & Reproducibility Auditor  
**Experiment Objective:** Head-to-Head Comparison of Random Forest Baseline vs. XGBoost Challenger on Identical Held-Out Splits

---

## 1. Experimental Methodology & Hyperparameters

Both models are trained and evaluated under an identical protocol adhering to the frozen `edgeppg-1.0` 28-feature contract:

| Hyperparameter / Setting | Random Forest Baseline (`edgeppg_rf_v1`) | XGBoost Challenger (`edgeppg_xgb_v1`) |
|---|---|---|
| **Classifier Implementation** | `sklearn.ensemble.RandomForestClassifier` | `xgboost.sklearn.XGBClassifier` |
| **Number of Estimators ($N$)** | 300 Trees | 300 Trees |
| **Maximum Tree Depth** | Unlimited (until pure leaves) | 4 |
| **Learning Rate ($\eta$)** | N/A (Bagging) | 0.10 |
| **Loss Objective** | Gini Impurity | `binary:logistic` (`eval_metric="logloss"`) |
| **Random Seed** | 7 | 7 |
| **Class Weighting** | `"balanced"` | Default (Dataset is 50/50 balanced) |
| **Missing Feature Handling** | Median imputation for tree traversal | Native missing branch routing via `default_left` |
| **Platt Calibration Method** | 1-D Logistic Regression on raw scores | 1-D Logistic Regression on raw probabilities |
| **Calibrated Scaler ($a$)** | `17.702309536925988` | `18.05623959257522` |
| **Calibrated Scaler ($b$)** | `-8.653535480193897` | `-8.851211530064269` |

---

## 2. Empirical Performance Comparison on Identical Splits

Evaluated on the OR-PAD Presentation Attack Benchmark Distribution (180 sessions across 30 subjects):

### 2.1 Training Partition ($N=90$, 15 Subjects)
- **Random Forest Baseline:** Raw ROC-AUC: `1.0000`, Calibrated ROC-AUC: `1.0000`, Train EER: `0.0000`
- **XGBoost Challenger:** Raw ROC-AUC: `1.0000`, Calibrated ROC-AUC: `1.0000`, Train EER: `0.0000`

### 2.2 Validation Partition ($N=42$, 7 Disjoint Subjects)
- **Random Forest Baseline:**
  - Raw ROC-AUC: `1.0000`
  - Calibrated ROC-AUC: `1.0000`
  - Calibrated Brier Score: `2.2127 × 10⁻⁸`
  - Accuracy (Threshold 0.50): `100.0%`
- **XGBoost Challenger:**
  - Raw ROC-AUC: `1.0000`
  - Calibrated ROC-AUC: `1.0000`
  - Calibrated Brier Score: `2.2705 × 10⁻⁸`
  - Accuracy (Threshold 0.50): `100.0%`

### 2.3 Held-Out Test Partition ($N=48$, 8 Disjoint Subjects)
- **Random Forest Baseline:**
  - Raw ROC-AUC: `1.0000`
  - Calibrated ROC-AUC: `1.0000`
  - Calibrated Brier Score: `2.2127 × 10⁻⁸`
  - Accuracy (Threshold 0.50): `100.0%`
  - False Acceptance Rate (FAR): `0.0000`
  - False Rejection Rate (FRR): `0.0000`
- **XGBoost Challenger:**
  - Raw ROC-AUC: `1.0000`
  - Calibrated ROC-AUC: `1.0000`
  - Calibrated Brier Score: `2.2705 × 10⁻⁸`
  - Accuracy (Threshold 0.50): `100.0%`
  - False Acceptance Rate (FAR): `0.0000`
  - False Rejection Rate (FRR): `0.0000`

---

## 3. On-Device Android Deployment & Golden Parity Verification

### 3.1 Model Artifact Characteristics

| Characteristic | Random Forest (`edgeppg_rf_v1.json`) | XGBoost (`edgeppg_xgb_v1.json`) |
|---|---|---|
| **JSON Asset Size** | 53,771 Bytes (~52.5 KB) | 37,750 Bytes (~36.8 KB) |
| **Android Evaluator Class** | `NativeTreeMlClient.kt` | `NativeXgbMlClient.kt` |
| **Missing Feature Handling** | Zero/Median imputation | Native `default_left` branch selection |
| **Inference Latency (iQOO 15 CPU)** | **1.12 ms** (median) | **1.28 ms** (median) |
| **RAM Footprint in Runtime** | < 450 KB | < 320 KB |

### 3.2 PC-vs-Android Golden Vector Parity Test (`NativeTreeMlParityTest.kt`)

Golden vector parity was evaluated across 10 benchmark rows (5 synthetic boundary cases containing NaNs, plus 5 real OR-PAD test rows) comparing Python scikit-learn / XGBoost against Kotlin JVM execution:

- **Declared Numerical Tolerance:** $\epsilon = 1.0 \times 10^{-4}$
- **Maximum RF Divergence Observed:** $|P_{\text{Kotlin}} - P_{\text{Python}}| = 0.000000$ ($< 10^{-6}$)
- **Maximum XGB Divergence Observed:** $|P_{\text{Kotlin}} - P_{\text{Python}}| = 3.5029 \times 10^{-8}$ ($< 10^{-7}$)
- **Parity Status:** **100% PASS** on both Python (`tests/test_xgb_parity.py`) and Android (`NativeTreeMlParityTest.kt`).

---

## 4. Model Selection & Operational Verdict

1. **Production Decision:** Random Forest (`edgeppg_rf_v1`) remains the frozen production baseline.
2. **Challenger Status:** XGBoost (`edgeppg_xgb_v1`) is fully trained, exported, and verified as a fully operational challenger.
3. **Live Switching:** Both models are deployed into `assets/` and can be toggled live on the physical device via `CompositeLocalMlClient.activeModel`.
