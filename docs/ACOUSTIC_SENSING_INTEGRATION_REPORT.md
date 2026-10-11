# EdgePPG — Acoustic FMCW Sensing Implementation & ML Audit Report

**Date:** 10 October 2026  
**Target Platform:** Android (iQOO 15 / Snapdragon 8 Elite, API 37)  
**Reference Document:** [`docs/EdgePPG_Acoustic_Sensing_Research_and_Implementation_Guide.md`](file:///E:/EdgePPG/docs/EdgePPG_Acoustic_Sensing_Research_and_Implementation_Guide.md)  
**Status:** Implemented, Tested, and Verified (`BUILD SUCCESSFUL`)  

---

## 1. Executive Summary & Architectural Compliance

In strict adherence to the **EdgePPG Acoustic Sensing Research and Implementation Guide (§1, §8, §10)**:
1. **Core Biometric Contract Preserved:** The production `edgeppg-1.0` 28-feature biometric schema remains **100% frozen**. Acoustic signals are **not** injected into `FeatureSchema.kt` or `RowAssembler.kt` and do **not** override `DecisionEngine.kt` gates.
2. **Observability Surface Only:** Acoustic measurements are exposed exclusively via a dedicated diagnostics telemetry panel (`AcousticSnapshot`) for real-time validation without altering KYC decision outputs.
3. **Hardware & Acoustic Safety:** Chirp transmissions operate strictly in the near-ultrasonic spectrum ($18\text{ kHz} - 21\text{ kHz}$) with conservative gain clamped to $\le 0.30$ amplitude, preventing speaker saturation and ear fatigue.
4. **Full-Duplex Async DSP:** Playback and recording run asynchronously on a dedicated daemon thread (`EdgePPG-AcousticFmcwThread`), eliminating frame starvation on CameraX and rPPG pipelines.

---

## 2. Implemented Architecture & Components

The acoustic subsystem is cleanly packaged under [`com.edgeppg.app.acoustic`](file:///E:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/acoustic):

### 2.1 Chirp Generation (`ChirpGenerator.kt`)
- **Parameters:** $f_0 = 18\text{ kHz}$, $f_1 = 21\text{ kHz}$ ($B = 3000\text{ Hz}$), $T = 40\text{ ms}$, $f_s = 48\text{ kHz}$ ($N_c = 1920$ samples).
- **Windowing:** Tukey window ($\alpha = 0.15$) smoothly attenuates boundaries to zero, preventing audible clicks.
- **Reference Phasors:** Precomputes in-phase $\cos(\phi(t))$ and quadrature $\sin(\phi(t))$ reference arrays for baseband mixing.
- **Playback Buffers:** Generates multi-chirp concatenated 16-bit PCM buffers (`generateLoopedPlaybackPcm16`) for seamless loop playback via `AudioTrack`.

### 2.2 Classical FMCW Signal Processing (`AcousticDsp.kt`)
- **Clipping Detection:** Flags buffers where saturated samples ($|sample| \ge 32000$) exceed 1%.
- **Cross-Correlation Alignment:** Locates chirp arrival offset via template matching:
  $$\text{Offset}^* = \arg\max_k \sum_n r[k+n] \cdot s[n]$$
- **Baseband IQ Dechirp:** Multiplies received signal by complex reference $e^{+j \phi_{\text{ref}}(t)}$ and applies a 16-sample moving average low-pass filter to eliminate the $\approx 40\text{ kHz}$ carrier sum $(A+B)$ while preserving $< 1\text{ kHz}$ beat frequencies.
- **2048-Point Radix-2 Range FFT:** In-place decimation-in-time Cooley-Tukey algorithm.
- **Range Resolution:**
  $$\Delta R = \frac{c \cdot f_s \cdot T}{2 \cdot B \cdot N_{\text{fft}}} = \frac{34300 \cdot 48000 \cdot 0.040}{2 \cdot 3000 \cdot 2048} \approx 5.359\text{ cm / bin}$$
- **Target Selection & SNR:** Searches KYC applicant corridor ($15\text{ cm} - 85\text{ cm}$, bins $3 - 16$); calculates SNR against adjacent baseband noise floor. Requires $\ge 6.0\text{ dB}$ SNR for valid detection.
- **Phase Micro-Motion:** Evaluates consecutive chirp phase deltas $\Delta \theta = \angle Z_t - \angle Z_{t-1}$ at the peak bin.

### 2.3 Acoustic Snapshot Telemetry (`AcousticSnapshot.kt`)
Structured telemetry record emitting:
- `isValid: Boolean`
- `targetRangeCm: Float?` (explicit `null` when SNR is insufficient)
- `motionScore: Float` ($[0, 1]$ phase motion score)
- `snrDb: Float` (logarithmic target SNR)
- `isClipping: Boolean` (hardware saturation flag)
- `qualityStatus: String` (`MEASURED`, `LOW_SNR`, `CLIPPING`, `PERMISSION_DENIED`, `STOPPED`)
- `latencyMs: Long` (end-to-end processing latency)

### 2.4 Lifecycle Controller (`AcousticFmcwEngine.kt`)
- Controls full-duplex `AudioTrack` (static looped playback) and `AudioRecord` (`MediaRecorder.AudioSource.MIC`).
- Inspects `Manifest.permission.RECORD_AUDIO` prior to hardware binding; gracefully degrades to `PERMISSION_DENIED` telemetry without crashing if denied.
- Runs on background daemon thread with rate-limiting ($15-20\text{ Hz}$) to minimize CPU/battery overhead.

### 2.5 UI & Diagnostics Integration (`MainActivity.kt`)
- **Diagnostics Monitor:** Appends `|ACOUSTIC FMCW: ACTIVE (32.2cm, SNR 18.5dB)` to the hardware compute panel.
- **Interactive Radar Card:** Displays live target range, carrier band, micro-motion score, amplitude status, and an interactive `[START/STOP FMCW SONAR SENSING]` toggle button.
- **Permissions Flow:** Hooks into `ActivityResultContracts.RequestPermission()` for runtime audio authorization.

---

## 3. Machine Learning (ML) Inspection & Parity Audit

Per the user request to inspect the attached ML changes:
1. **`ml/src/orpad_engine.py`**:
   - Single-applicant KYC evaluation: Multi-person features ($11 - 21$) are kept as `math.nan` (as initialized by `empty_row()`) rather than synthetic placeholders, matching actual applicant constraints.
2. **`ml/src/train_rf.py`**:
   - Includes native decision tree exporter generating `edgeppg_rf_v1.json` directly into `android/app/src/main/assets/`.
   - Exports 300 trees, Platt scaler parameters ($a = 17.7023$, $b = -8.6535$).
3. **Embedded Inference Parity**:
   - `NativeTreeMlClient.kt` executes the trained trees in pure Kotlin on Android CPU without PC dependencies.
   - `NativeTreeMlParityTest.kt` verifies 100% numerical parity with Python Scikit-Learn outputs across all golden vectors.

---

## 4. Verification & Test Outcomes

| Test Suite | Tests Run | Result | Duration |
|---|---|---|---|
| **Android Unit Tests** (`testDebugUnitTest`) | **94** | **100% Passed** | 23s |
| - `AcousticFmcwEngineTest` | 7 | Passed | < 1s |
| - `EyeTrackerTest` | 5 | Passed | < 1s |
| - `GestureTrackerTest` & `GestureAndBehaviorRegressionTest` | 21 | Passed | < 1s |
| - `NativeTreeMlParityTest` & `LocalMlClientTest` | 13 | Passed | < 1s |
| - `DecisionEnginePhoneTest` & `SessionResetTest` | 48 | Passed | < 1s |
| **Python ML Tests** (`python -m unittest discover tests`) | **257** | **100% Passed** | 0.11s |
| **Debug APK Assembly** (`assembleDebug`) | - | **BUILD SUCCESSFUL** | 11s |

**Generated Artifact:**
- Path: `android/app/build/outputs/apk/debug/app-debug.apk`
- Size: **79,772,932 bytes (79.7 MB)**
- Timestamp: 10 October 2026, 23:42:25 IST
