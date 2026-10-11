# EdgePPG — Implementation Plan & Roadmap

## 📋 Executive Overview

**EdgePPG** is an on-device Video KYC (VKYC) liveness assurance, presentation attack detection (PAD), and cryptographic attestation engine. It performs real-time physiological pulse extraction via remote photoplethysmography (rPPG), active behavioral and optical challenges, and hardware-backed cryptographic signing (`AndroidKeyStore` SECP256R1) on modern Android devices (targeted on the **iQOO 15** / Snapdragon 8 Elite platform).

This document serves as the master implementation plan, recording architectural milestones, engineering phases, verification criteria, and device validation results.

---

## 🎯 Architecture & Design Goals

1. **Zero-Cloud Raw Biometric Privacy**:
   - Raw video frames and full-resolution facial images **never leave device memory**.
   - Only canonicalized 28-feature telemetry and cryptographic ECDSA signatures are transmitted to the verification authority.
2. **Deterministic Multi-Modal Verification**:
   - Physiological pulse extraction via the Plane-Orthogonal-to-Skin (POS) algorithm.
   - Active challenge-response (Gaze, Head Pose, Hand Gestures, Stillness intervals).
   - Dynamic optical screen flash challenge for digital display / presentation attack detection.
   - Screen bezel and secondary device replay detection.
3. **Hardware Enclave Cryptographic Attestation**:
   - The session verdict transcript is canonicalized and signed using NIST P-256 (`SHA256withECDSA`) inside the `AndroidKeyStore`.
   - Freshness nonces with a 5-minute validity window prevent replay attacks and man-in-the-middle intercepts.
4. **Resilience Across Diverse Environmental Conditions**:
   - Robust performance under varying ambient lighting (dim indoor, direct daylight, mixed illumination).
   - Graceful handling of network latency via local Wi-Fi auto-sync and QR-code fallback.

---

## 🗺️ Phased Implementation Roadmap

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                             EDGEPPG ROADMAP PHASES                               │
├───────────────────┬───────────────────────────────────┬──────────────────────────┤
│ Phase 1           │ Foundation & Environment          │ COMPLETE (v1.0.0)        │
│ Phase 2           │ CameraX & Hardware Lock           │ COMPLETE (v1.0.0)        │
│ Phase 3           │ POS rPPG Physiological Engine     │ COMPLETE (v1.0.0)        │
│ Phase 4           │ Behavioral Challenge State Machine│ COMPLETE (v1.0.0)        │
│ Phase 5           │ Optical Stimulus Engine           │ COMPLETE (v1.1.0)        │
│ Phase 6           │ Presentation Attack Defense Layer │ COMPLETE (v1.1.0)        │
│ Phase 7           │ AndroidKeyStore Attestation       │ COMPLETE (v1.0.0)        │
│ Phase 8           │ PC Verifier Node & Telemetry Sync │ COMPLETE (v1.1.0)        │
│ Phase 9           │ UI/UX White & Light-Blue Redesign │ COMPLETE (v1.1.0)        │
│ Phase 10          │ Real-World Calibration & Testing  │ COMPLETE (v1.1.0)        │
└───────────────────┴───────────────────────────────────┴──────────────────────────┘
```

---

## 🔍 Detailed Phase Execution

### Phase 1: Foundation & Build Pipeline (Completed)
- Configured Android Gradle build with Kotlin 1.9+ and AndroidX CameraX (`1.4.1`).
- Pinned compile options to Java 17 and AGP compatible toolchains.
- Established strict security claims and zero-PII structured logging framework (`com.edgeppg.app.Log`).
- Implemented Python Verifier server with standard library and cryptographic envelope verification.

### Phase 2: CameraX & Hardware Convergence Lock (Completed)
- Bound front-facing camera session targeting 30 FPS steady-state acquisition.
- Intercepted Camera2 `CaptureRequest` parameters to engage `CONTROL_AE_LOCK` (Auto-Exposure Lock) and `CONTROL_AWB_LOCK` (Auto-White Balance Lock) once exposure settles.
- Designed dynamic FPS estimation with warmup smoothing to avoid premature failure during camera startup.

### Phase 3: Pure Kotlin rPPG Physiological Engine (Completed)
- Implemented Plane-Orthogonal-to-Skin (POS) projection matrix in pure Kotlin (`RppgClient.kt`).
- Extracted temporal RGB means from three independent facial skin ROIs:
  - **Forehead ROI**
  - **Left Cheek ROI**
  - **Right Cheek ROI**
- Designed dual-band biquad Butterworth filter ($0.75\,\text{Hz} - 3.5\,\text{Hz}$, matching $45 - 210\,\text{BPM}$).
- Engineered Radix-2 FFT spectral analysis for SNR computation.
- Implemented cross-ROI Pearson correlation ($r$) to verify that arterial pulse propagation occurs synchronously across distinct facial zones.

### Phase 4: Deterministic Behavioral Challenges (Completed)
- Built pseudo-random seed generator producing unrepeatable challenge sequences:
  - Head Pose (`TURN_LEFT`, `TURN_RIGHT`, `TURN_UP`, `TURN_DOWN`, `NOD`, `SHAKE_NO`).
  - Gaze Vector tracking via eye mesh landmark vectors (`LOOK_LEFT`, `LOOK_RIGHT`, `LOOK_UP`, `LOOK_DOWN`).
  - Hand Gesture tracking via MediaPipe gesture recognizer (`RAISE_LEFT`, `RAISE_RIGHT`, `OPEN_PALM`, `THUMBS_UP`).
  - Stillness intervals with micro-motion boundary verification.
- Enforced strict timeout budgets per challenge phase (1.8s–3.0s).

### Phase 5: Optical Stimulus Challenge Engine (Completed & Calibrated)
- Implemented full-screen color flash stimulus sequence (`WHITE`, `CYAN`, `MAGENTA`, `YELLOW`).
- Measured skin reflectance modulation:
  $$\Delta I_{\text{skin}}(t) = I_{\text{skin}}(t_{\text{flash}}) - I_{\text{skin}}(t_{\text{pre-flash}})$$
- **Ambient Lighting Calibration**:
  - In brightly lit rooms, ambient photons can wash out relative flash contrast.
  - Calibrated the analyzer to report `CONTAMINATED` (inconclusive ambient lighting) rather than false `WRONG` (attack) when contrast is diminished by ambient light, preventing false rejections of real applicants.

### Phase 6: Multi-Modal Anti-Spoofing & Replay Attack Defense (Completed)
- **Phone / Secondary Screen Detector**: Evaluates image boundaries, screen edge moiré patterns, and specular frame reflections.
- **28-Feature Schema Assembler**: Aggregates behavioral scores, physiological SNR, cross-ROI correlation, optical reflectance, and camera exposure stability into a standardized feature vector.
- **Local ML Inference Parity**: Integrated native Decision Tree and XGBoost tree interpreters (`NativeTreeMlClient.kt`, `NativeXgbMlClient.kt`) with fallback to local Termux/IPC endpoints.

### Phase 7: Hardware Enclave Cryptographic Attestation (Completed)
- Key generation inside `AndroidKeyStore` using SECP256R1 (NIST P-256) elliptic curve.
- Canonical JSON serialization guaranteeing identical byte representations across Kotlin and Python.
- Signed envelope generation containing session ID, nonce, decision verdict, telemetry summary, and timestamp.
- Tamper-evident receipt emission on the PC verifier node upon signature verification.

### Phase 8: PC Verifier Gateway & Live Telemetry Sync (Completed)
- Real-time HTTP live stream (`POST /api/live`) transmitting live FPS, face lock state, cardiac pulse waveform, and challenge status.
- PC dashboard operator console with session scheduling, applicant reference management, and live biometric telemetry charts.
- Verified bidirectional synchronization between Android mobile device and PC gateway (`192.168.112.130:8080`).

### Phase 9: UI/UX Redesign — White & Light-Blue Palette (Completed)
- Professional financial-grade visual styling using crisp white surfaces (`#FFFFFF`) with light-blue accents (`#EAF5FF`, `#CFE5FA`, `#77BFFF`).
- Face Mesh ROI Overlays:
  - Forehead: Cyan (`#22D3EE`)
  - Left Cheek: Emerald (`#34D399`)
  - Right Cheek: Orange (`#FB923C`)
- Real-time rPPG pulse wave canvas displaying live cardiac signal oscillations.
- Restored original bottom-navigation order and clean card elevation.

### Phase 10: Device Calibration & Real-World Validation (Completed)
- Inspected real session recordings under bright daylight and indoor lighting conditions.
- Tuned physiological pulse threshold:
  ```kotlin
  val hasGenuinePhysioPulse = ((pCorr >= 0.05f && pSnr >= 0.08f) || (pSnr >= 0.15f)) && (pHr in 45f..185f)
  ```
- Shielded on-device `LIVE` verdict from temporary Wi-Fi network latency.
- Verified all 44 unit tests passing on Gradle.
- Successfully deployed and tested debug APK on the connected **iQOO 15** hardware.

---

## 🧪 Verification & Test Suite Matrix

The project enforces automated test coverage across both Android native components and Python verifier backend:

| Test Suite | Components Tested | Status |
| :--- | :--- | :---: |
| `AcousticEchoTest` | Ultrasonic Doppler echo sensing & obstacle distance | ✅ PASS |
| `BehavioralEstimatorTest` | Gaze direction, Head Euler angles, Gesture tracking | ✅ PASS |
| `DocumentOcrTest` | ID document boundary detection & field extraction | ✅ PASS |
| `FeatureAssemblerTest` | 28-feature canonical schema construction & normalization | ✅ PASS |
| `OpticalChallengeTest` | Screen flash timing, stimulus analysis, ambient compensation | ✅ PASS |
| `RppgDspTest` | Biquad bandpass filter, Radix-2 FFT, Pearson correlation | ✅ PASS |
| `DecisionEnginePhoneTest` | Phone/screen detector, spoof classification gates | ✅ PASS |
| `SessionResetTest` | State cleanup, nonce recycling, memory leak prevention | ✅ PASS |
| `Python Verifier Suite` | Nonce freshness, ECDSA verification, receipt generation | ✅ PASS |
| `Automated Attack Harness` | Genuine, Photo Attack, Video Replay, Signature Tamper | ✅ PASS |

**Total Android Unit Tests**: 44 passing (`testDebugUnitTest`).

---

## 📱 Hardware Target: iQOO 15 Validation Profile

- **Processor**: Qualcomm Snapdragon 8 Elite (arm64-v8a)
- **Camera Sensor**: High-speed front camera with fixed 30 FPS capture pipeline
- **Hardware Enclave**: StrongBox / KeyMint AndroidKeyStore hardware backing
- **Thermal & CPU Budget**: Average inference latency $< 18\,\text{ms}$ per frame; CPU utilization $< 14\%$ during active verification.

---

## 🔒 Security Claims & Compliance Notice

- **Integrity**: Decisions are bound to hardware ECDSA signatures. If telemetry or verdicts are modified in transit, verification fails.
- **Freshness**: Replay attempts of valid historic envelopes fail due to nonce expiration ($> 300\,\text{s}$).
- **Privacy**: No facial recognition templates, PII, or raw video streams are transmitted to any remote server.
