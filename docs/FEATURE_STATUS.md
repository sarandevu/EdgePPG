# EdgePPG — Frozen 28-Feature Schema Status (`edgeppg-1.0`)

**Audit Date:** October 10, 2026  
**Auditor:** Senior Feature Engineer & PAD Systems Architect  
**Schema Identifier:** `edgeppg-1.0` (Frozen Vector Contract, 28 Elements)

---

## 1. Schema Invariants & Missing Value Policy

1. **Ordering & Index Invariance:** The vector order is frozen across training, export, runtime inference, and verification. No features may be added, removed, reordered, or re-scaled.
2. **Missing Value Contract:** Any feature unavailable at runtime strictly emits `Float.NaN`.
3. **No Synthetic Fabrication:** Substituting missing measurements with arbitrary plausible constants (e.g. `0.95f`, `0.80f`) is strictly prohibited. Missing values route safely to `UNCERTAIN` per `Requirements §FR-GATE-4`.

---

## 2. Itemized 28-Feature Audit Table

| Index | Feature Name | Unit | Type | Valid Range | Source Module | Runtime Consumer | Missing Convention | Verification Status |
|---|---|---|---|---|---|---|---|---|
| **0** | `face_confidence` | Ratio | Float | $[0.0, 1.0]$ | `RoiTracker.kt` (ML Kit FaceMesh) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **1** | `face_quality` | Score | Float | $[0.0, 1.0]$ | `QualityGate.kt` / `AwbStability` | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **2** | `rppg_snr` | Decibels (dB) | Float | $[-10.0, 35.0]$ | `RppgClient.kt` (256-pt FFT) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **3** | `rppg_peak_strength` | Ratio | Float | $[0.0, 1.0]$ | `RppgClient.kt` (FFT spectral power) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **4** | `rppg_hr_stability` | Score | Float | $[0.0, 1.0]$ | `RppgClient.kt` (Sliding variance) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **5** | `rppg_roi_agreement` | Correlation | Float | $[-1.0, 1.0]$ | `RppgClient.kt` (Pearson $\rho$ Forehead/Cheek) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **6** | `gaze_accuracy` | Score | Float | $[0.0, 1.0]$ | `FaceActionTracker.kt` / `GazeEstimator` | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **7** | `head_accuracy` | Score | Float | $[0.0, 1.0]$ | `FaceActionTracker.kt` (Head Pose) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **8** | `hand_accuracy` | Score | Float | $[0.0, 1.0]$ | `FaceActionTracker.kt` (Hand Gesture) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **9** | `challenge_timing_error`| Seconds | Float | $[0.0, 10.0]$ | `BehaviourRunner.kt` (Response latency) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **10** | `optical_response_score`| Score | Float | $[0.0, 1.0]$ | `CameraSession.kt` (Screen flash $\Delta RGB$) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **11** | `trusted_face_confidence`| Ratio | Float | $[0.0, 1.0]$ | Single-user mirror of Index 0 | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **12** | `trusted_face_quality` | Score | Float | $[0.0, 1.0]$ | Single-user mirror of Index 1 | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **13** | `trusted_gaze_accuracy` | Score | Float | $[0.0, 1.0]$ | Single-user mirror of Index 6 | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **14** | `trusted_head_accuracy` | Score | Float | $[0.0, 1.0]$ | Single-user mirror of Index 7 | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **15** | `trusted_hand_accuracy` | Score | Float | $[0.0, 1.0]$ | Single-user mirror of Index 8 | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **16** | `trusted_challenge_timing_error` | Seconds | Float | $[0.0, 10.0]$ | Single-user mirror of Index 9 | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **17** | `cross_person_timing` | Score | Float | $[0.0, 1.0]$ | Multi-party KYC agent channel | `RowAssembler.kt` / ML Model | `Float.NaN` (Single applicant) | `IMPLEMENTED & VERIFIED` |
| **18** | `cross_person_interaction` | Score | Float | $[0.0, 1.0]$ | Multi-party KYC agent channel | `RowAssembler.kt` / ML Model | `Float.NaN` (Single applicant) | `IMPLEMENTED & VERIFIED` |
| **19** | `relative_motion_consistency` | Score | Float | $[0.0, 1.0]$ | Multi-party KYC agent channel | `RowAssembler.kt` / ML Model | `Float.NaN` (Single applicant) | `IMPLEMENTED & VERIFIED` |
| **20** | `participant_presence_consistency` | Score | Float | $[0.0, 1.0]$ | Multi-party KYC agent channel | `RowAssembler.kt` / ML Model | `Float.NaN` (Single applicant) | `IMPLEMENTED & VERIFIED` |
| **21** | `challenge_sequence_consistency` | Score | Float | $[0.0, 1.0]$ | Multi-party KYC agent channel | `RowAssembler.kt` / ML Model | `Float.NaN` (Single applicant) | `IMPLEMENTED & VERIFIED` |
| **22** | `camera_quality` | Score | Float | $[0.0, 1.0]$ | `CameraSession.kt` (FPS / 30.0f) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **23** | `frame_drop_rate` | Ratio | Float | $[0.0, 1.0]$ | `QualityGate.kt` (Dropped / Analyzed) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **24** | `exposure_stability` | Score | Float | $[0.0, 1.0]$ | `CameraSession.kt` (CaptureResult Exposure) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **25** | `awb_stability` | Score | Float | $[0.0, 1.0]$ | `CameraSession.kt` (Color correction gains) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **26** | `capture_duration` | Seconds | Float | $[1.0, 60.0]$ | `SessionController.kt` (Session timer) | `RowAssembler.kt` / ML Model | `Float.NaN` | `IMPLEMENTED & VERIFIED` |
| **27** | `device_integrity` | Binary Flag | Float | $\{0.0, 1.0\}$ | `IntegrityManager.kt` (Hardware KeyStore) | `RowAssembler.kt` / ML Model | `0.0f` on failure | `IMPLEMENTED & VERIFIED` |

---

## 3. Parity & Validation Evidence

1. **Schema Definition Tests:** `ml/tests/test_ml_pipeline.py` verifies all 28 feature names and orders against `Architecture.md`.
2. **Schema Validator on Android:** `android/app/src/main/kotlin/com/edgeppg/app/features/FeatureSchema.kt` checks exact array size (28) and rejects uncalibrated versions.
3. **Golden Vector Parity:** `tests/test_xgb_parity.py` and `android/app/src/test/kotlin/com/edgeppg/app/features/NativeTreeMlParityTest.kt` evaluate all 28 features simultaneously on PC and Kotlin JVM, passing within $\epsilon = 1.0 \times 10^{-4}$.
4. **Behavioral Feature Real-Time Sourcing:**
   - Feature 6 (`gaze_accuracy`) and Feature 9 (`challenge_timing_error`) are continuously driven by `behavior/EyeTracker.kt` and `behavior/FaceActionTracker.kt`.
   - Feature 8 (`hand_accuracy`) is driven by `behavior/GestureTracker.kt` from asynchronous live posture inference.

---

## 4. Biometric Isolation Contract: Separate KYC Document Lane

In strict compliance with Architecture Invariant §1 of the Master Prompt:
- **No Biometric Vector Pollution:** The PAN document capture and OCR pipeline (`com.edgeppg.app.document.*`) is an isolated application lane.
- **Excluded From Vector:** `PanCardState`, `CardQuad`, `PanCardResult`, extracted PAN string, applicant name, and card validity are **NEVER** injected into the frozen 28-feature schema (`edgeppg-1.0`).
- **No Impact on $P(\text{LIVE})$:** The Random Forest baseline and XGBoost challenger models operate exclusively on biometric, optical, and sensor stability features. A valid PAN card or OCR text string can never alter $P(\text{LIVE})$ or override deterministic anti-spoofing security gates.
- **Privacy Masking Contract:**
  - Raw PAN characters $[5..8]$ are permanently masked as asterisks (`ABCDE****F`).
  - Extracted applicant names are redacted to initial and asterisks (`R***** S****`).
  - Rectified card bitmaps are ephemeral and recycled immediately; zero raw card images or full unmasked PANs are persisted to local storage or signed transcript records.
- **Authoritative Disclaimer:** Format validation (`[A-Z]{5}[0-9]{4}[A-Z]`) verifies syntactic structure only. Document status records explicitly emit `VERIFICATION_UNAVAILABLE` regarding government database verification (NDML/NSDL/ITD), precluding any false claims of official validation.

