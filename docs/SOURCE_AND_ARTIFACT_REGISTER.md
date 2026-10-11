# EdgePPG — Source, License & Artifact Register

**Audit Date:** October 10, 2026  
**Auditor:** Reproducibility Auditor & Applied ML Researcher  
**Scope:** Dependencies, Checkpoints, Licenses, SHA-256 Artifact Hashes

---

## 1. Upstream Sources & Pinned Versions

### 1.1 Android Vision & On-Device Processing
- **Google ML Kit Face Mesh Detection (Android):**
  - Canonical URL: `https://developers.google.com/ml-kit/vision/face-mesh-detection/android`
  - Artifact: `com.google.mlkit:face-mesh-detection:16.0.0-beta3`
  - License: Google APIs Terms of Service / Apache-2.0 runtime wrapper
  - Note: Documented as Beta API; 468 3D landmarks used strictly for geometrical ROI projection and micro-action tracking. NOT a PAD classifier.
- **Google ML Kit Image Labeling (Android):**
  - Canonical URL: `https://developers.google.com/ml-kit/vision/image-labeling/android`
  - Artifact: `com.google.mlkit:image-labeling:17.0.9`
  - License: Google APIs Terms of Service / Apache-2.0 runtime wrapper
  - Note: Generic on-device classifier used as supplementary heuristic evidence for secondary screen presence; not a standalone biometric PAD detector.
- **Google ML Kit Text Recognition v2 (Android):**
  - Canonical URL: `https://developers.google.com/ml-kit/vision/text-recognition/v2`
  - Artifact: `com.google.mlkit:text-recognition:16.0.1`
  - License: Google APIs Terms of Service / Apache-2.0
  - Note: Dedicated KYC document lane for PAN Card text extraction and regex format verification.
- **Google AI Edge MediaPipe Tasks Vision (Android):**
  - Canonical URL: `https://ai.google.dev/edge/mediapipe/solutions/vision/gesture_recognizer/android`
  - Artifact: `com.google.mediapipe:tasks-vision:0.10.14`
  - License: Apache-2.0
  - Note: Hand Gesture Recognizer and hand tracking engine for interactive behavioral challenges.
- **AndroidX CameraX:**
  - Artifacts: `androidx.camera:camera-core:1.4.1`, `camera-camera2:1.4.1`, `camera-lifecycle:1.4.1`, `camera-view:1.4.1`
  - License: Apache-2.0
  - Execution: Front-facing 1080p preview @ 30 FPS.

### 1.2 rPPG Signal Processing & Algorithms
- **Algorithmic Principles of Remote PPG (POS):**
  - Authors: Wang et al. (2017), IEEE Transactions on Biomedical Engineering.
  - DOI: `10.1109/TBME.2016.2609282`
  - Implementation: Deterministic plane-orthogonal-to-skin (POS) projection in `rppg/RppgClient.kt`. No black-box AI model.
- **Robust Pulse Rate From Chrominance-Based rPPG (CHROM):**
  - Authors: de Haan & Jeanne (2013), IEEE Transactions on Biomedical Engineering.
  - DOI: `10.1109/TBME.2013.2266196`
  - Implementation: Deterministic chrominance difference combination in `rppg/RppgClient.kt`.
- **rPPG-Toolbox Reference:**
  - Authors: Liu et al., Ubicomp Lab, University of Washington.
  - URL: `https://github.com/ubicomplab/rPPG-Toolbox`
  - License: MIT License

### 1.3 Machine Learning & Calibration Frameworks
- **XGBoost:**
  - Version: `2.0.3` (Python), Custom pure-Kotlin evaluator on Android.
  - URL: `https://xgboost.readthedocs.io/en/stable/`
  - License: Apache-2.0
- **scikit-learn:**
  - Version: `1.8.0` / `1.9.1` (Training environment)
  - URL: `https://scikit-learn.org/stable/`
  - License: BSD-3-Clause
- **Probability Calibration (Platt Sigmoid):**
  - Canonical Reference: Platt (1999) / Niculescu-Mizil & Caruana (2005)
  - Formula: $P(\text{LIVE}) = \frac{1}{1 + \exp(-(a \cdot \text{score} + b))}$
  - Implementation: `ml/src/calibration.py` (PC) & `NativeTreeMlClient.kt` / `NativeXgbMlClient.kt` (Android).

---

## 2. Checkpoints, Models & Binary Artifact Hashes

| Artifact Path | Description | Format | Size (Bytes) | SHA-256 Checksum |
|---|---|---|---|---|
| `models/edgeppg_rf_v1.joblib` | Production Baseline Random Forest (300 Trees) | Joblib | 191,075 | `b8ea8afee77ff9bec1d5266362ab3837b933ef0717c68efd6fed8535e50260e1` |
| `models/edgeppg_xgb_v1.joblib` | Challenger XGBoost Classifier (300 Trees) | Joblib | 215,809 | `5628e6763693cc17dad946809975efa81ea137ca8050af4d28e3ebb65005fda2` |
| `models/edgeppg_xgb_v1.json` | Exported XGBoost Challenger Tree Array & Scaler | JSON | 37,750 | `f67a4200ed11a426e19c54397db65a5409fee31aae003941ca7b37608cb9f5a8` |
| `android/app/src/main/assets/edgeppg_rf_v1.json` | Embedded Android RF Model (Evaluated in Kotlin) | JSON | 53,771 | `70a91ba6029680a26dcf1ac0048fa8e6929b893442cbdc5ae6a8461142fc8c87` |
| `android/app/src/main/assets/edgeppg_xgb_v1.json` | Embedded Android XGB Model (Evaluated in Kotlin) | JSON | 37,750 | `f67a4200ed11a426e19c54397db65a5409fee31aae003941ca7b37608cb9f5a8` |
| `data/golden_vectors.json` | PC-Generated Golden Parity Vectors (10 records) | JSON | 7,106 | `8604c3cddff5e9309434a8e7a2ba9402da2549d35df8d75657c1811f90be8e07` |
| `android/app/build/outputs/apk/debug/app-debug.apk` | Verified iQOO 15 Debug Build APK | Android APK | 79,772,932 | `113c437b26a483aa36d54bbf6cae9eee10e27416b9a21d43f2fc54008b8b4065` |

---

## 3. Reproducible Verification Commands

### 3.1 Python Pipeline & Model Parity Tests
```bash
# Execute all Python unit and integration tests (324 tests)
python tests/run_all_tests.py

# Execute ML training, schema, and calibration tests (26 tests)
python ml/tests/run_tests.py

# Verify PC vs Golden Vector XGBoost Parity
python -m unittest tests.test_xgb_parity
```

### 3.2 Android On-Device JVM Unit & Parity Tests
```bash
# Execute full Android unit test suite
cd android
.\gradlew.bat testDebugUnitTest

# Execute targeted ML Parity verification on Android JVM
.\gradlew.bat testDebugUnitTest --tests com.edgeppg.app.features.NativeTreeMlParityTest
```

### 3.3 Target APK Compilation & Installation
```bash
# Compile Debug APK
cd android
.\gradlew.bat assembleDebug

# Deploy to connected physical iQOO 15 hardware
adb -s 10BFAT1U9X000XP install -r app/build/outputs/apk/debug/app-debug.apk
```
