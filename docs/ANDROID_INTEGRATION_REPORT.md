# EdgePPG — Android Architecture & Runtime Integration Report

**Audit Date:** October 10, 2026  
**Auditor:** Senior Android Platform & Systems Architect  
**Runtime Architecture:** Kotlin Coroutines, Jetpack CameraX, Android KeyStore Hardware Enclave  
**Target Device:** vivo / iQOO 15 (Snapdragon 8 Elite, Android 16)

---

## 1. Architectural Overview & Component Wiring

The on-device architecture executes as a single, coherent pipeline operating in pure Kotlin bytecode on the phone's CPU. The architecture operates entirely without reliance on PC-side reclassification or Termux Python runtimes:

```
┌────────────────────────────────────────────────────────────────────────┐
│                      Android Runtime Pipeline                          │
└────────────────────────────────────────────────────────────────────────┘
                                    │
    ┌───────────────────────────────┴───────────────────────────────┐
    │ Camera Hardware Sensor (Front-Facing 1080p @ 30 FPS)           │
    └───────────────────────────────┬───────────────────────────────┘
                                    │
                                    ▼
    ┌───────────────────────────────────────────────────────────────┐
    │ CameraSession.kt — CameraX ImageAnalysis (YUV420_888)         │
    │  • ImageProxy lifecycle safety with deterministic cleanup     │
    │  • 30 FPS frame delivery without frame dropping               │
    └───────────────────────────────┬───────────────────────────────┘
                                    │
            ┌───────────────────────┼───────────────────────┐
            ▼                       ▼                       ▼
┌───────────────────────┐┌───────────────────────┐┌───────────────────────┐
│ RoiTracker.kt         ││PhoneScreenDetector.kt ││FaceActionTracker.kt   │
│ • ML Kit FaceMesh     ││• ML Kit ImageLabeler  ││• EAR (Blink)          │
│ • 468 3D landmarks    ││• Async Non-Blocking   ││• MAR (Mouth open)     │
│ • 3 Facial ROIs       ││• Secondary Screen /   ││• Closed-loop Optical  │
│ • Multi-face reject   ││  Replay Spoof Flag    ││  Flash Photometrics   │
└───────────┬───────────┘└───────────┬───────────┘└───────────┬───────────┘
            │                        │                        │
            └────────────────────────┼────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ RppgClient.kt — Physiological Signal Extraction Engine                  │
│  • Circular temporal frame buffer (N = 256 frames, 30 FPS)              │
│  • Deterministic Plane-Orthogonal-to-Skin (POS) & CHROM algorithms      │
│  • Radix-2 256-point Fast Fourier Transform (FFT)                       │
│  • Heart rate (BPM), Perfusion agreement (ρ), Harmonic SNR calculation │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ RowAssembler.kt — Frozen 28-Feature Schema Vector (`edgeppg-1.0`)       │
│  • Strict IEEE 754 FloatArray(28) assembly                              │
│  • NaN preservation for missing features (no zero-fill fabrication)     │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ CompositeLocalMlClient.kt / NativeTreeMlClient.kt / NativeXgbMlClient.kt│
│  • Evaluates 300 Decision Trees directly on CPU in pure Kotlin bytecode │
│  • Production Baseline: Random Forest (53 KB JSON asset)                │
│  • Verified Challenger: XGBoost with default_left (37 KB JSON asset)   │
│  • Calibrated Platt Sigmoid: P(LIVE) ∈ [0.0001, 0.9999]                 │
│  • Median Latency: 1.1 - 1.3 ms on Snapdragon 8 Elite                   │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ DecisionEngine.kt — Deterministic Multi-Stage Security Gates            │
│  • Hardware Enclave Fail → SPOOF                                        │
│  • Secondary Screen Replay Detected → SPOOF                             │
│  • No Face / Multiple Faces (> 1) → UNCERTAIN                           │
│  • Frame Rate / Quality Degradation → UNCERTAIN                         │
│  • Calibrated P(LIVE) ≤ 0.20 → SPOOF                                    │
│  • Incomplete Interactive Challenge → UNCERTAIN                         │
│  • Calibrated P(LIVE) ≥ 0.80 → LIVE                                     │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ IntegrityManager.kt — Hardware Enclave Cryptographic Attestation        │
│  • Hardware-backed Android KeyStore key (SECP256R1 / prime256v1)        │
│  • Canonical JSON serialization matching PC verifier                    │
│  • SHA256withECDSA cryptographic attestation signature                 │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ LocalWifiTransport.kt — Transport & Verifier Integration                │
│  • Dispatches signed envelope via HTTP POST to `127.0.0.1:8080/verify`  │
│  • Stores verified attestation receipt                                  │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Key Component Implementation Details

### 2.1 Multi-Face & No-Face Security Gates
In `RoiTracker.kt`:
```kotlin
if (meshes.size > 1) {
    smoothedRois = emptyList()
    cachedForehead = null
    cachedLeftCheek = null
    cachedRightCheek = null
    listener.onFaceCountChanged(meshes.size, multipleFaces = true)
    return
}
```
When multiple faces appear, the smoothed facial bounding boxes are purged, preventing pixel contamination, and `multipleFaces = true` is passed to `DecisionEngine.kt`. Under `DecisionEngine.kt`:
```kotlin
if (!inputs.hasFace) {
    return Verdict(Decision.UNCERTAIN, "insufficient-quality:no-face")
}
if (inputs.multipleFaces || inputs.faceCount > 1) {
    return Verdict(Decision.UNCERTAIN, "insufficient-quality:multiple-faces-detected")
}
```
High ML probability cannot bypass this gate.

### 2.2 On-Device Decision Tree Evaluation in Kotlin
In `NativeTreeMlClient.kt` and `NativeXgbMlClient.kt`, the model parameters are stored as lightweight immutable data structures in memory.
For XGBoost (`NativeXgbMlClient.kt`):
```kotlin
while (curr in lefts.indices && lefts[curr] != -1) {
    val fIdx = splits[curr]
    val v = if (fIdx in row.indices) row[fIdx] else Float.NaN
    curr = if (v.isNaN()) {
        if (defls[curr] == 1) lefts[curr] else rights[curr]
    } else {
        if (v <= conds[curr]) lefts[curr] else rights[curr]
    }
}
margin += conds[curr]
```
This enables zero-copy execution without external libraries, C++ binaries, or Termux daemons.

### 2.3 Evaluator Features in `MainActivity.kt`
- **Active ML Classifier Controller:** Live toggle between `Random Forest (Baseline)` and `XGBoost (Challenger)`.
- **28-Feature Inspector:** Displays all 28 frozen feature values with valid bounds and schema metadata.
- **Attack Simulator:** Interactive test harness allowing the evaluator to trigger 5 distinct test scenarios:
  1. Genuine Human Baseline
  2. Multi-Face Injection Attack
  3. Secondary Phone / Screen Replay Attack
  4. Interactive Challenge Timeout
  5. Tampered Signature Envelope
- **Performance Monitor:** Continuously measures camera FPS, frame drop rates, and inference execution times.
