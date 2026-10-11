# EdgePPG — VKYC Assurance & Verification Gateway

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029%2B)-green.svg)](android/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple.svg)](android/)
[![Python](https://img.shields.io/badge/Python-3.10%2B-yellow.svg)](verifier/)
[![Target Hardware](https://img.shields.io/badge/Target-iQOO%2015%20%7C%20Snapdragon%208%20Elite-red.svg)](IMPLEMENTATION_PLAN.md)
[![Tests](https://img.shields.io/badge/Unit%20Tests-44%20Passing-brightgreen.svg)](tests/)

**EdgePPG** is an on-device Video KYC (VKYC) liveness assurance, presentation attack detection (PAD), and hardware-backed cryptographic attestation system. It combines real-time physiological remote photoplethysmography (rPPG), active behavioral challenges, dynamic optical flash probing, and `AndroidKeyStore` SECP256R1 signatures to provide tamper-evident verification receipts without streaming raw biometric video to the cloud.

---

## 📚 Key Project Documentation

* 🏛️ **[ARCHITECTURE.md](ARCHITECTURE.md)** — In-depth architectural blueprint, mathematical models (POS DSP, FFT, Pearson correlation), 28-feature schema, and enclave security.
* 📋 **[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)** — Master phase roadmap, test matrices, hardware validation on iQOO 15, and real-world illumination calibration log.
* 📜 **[LICENSE](LICENSE)** — Apache License 2.0.
* 🤝 **[CONTRIBUTING.md](CONTRIBUTING.md)** — Contribution guidelines, local development workflows, and testing expectations.
* 🛡️ **[SECURITY.md](SECURITY.md)** — Threat model, security boundaries, zero-cloud privacy architecture, and vulnerability reporting.
* 📝 **[CHANGELOG.md](CHANGELOG.md)** — Release history and recent updates.

---

## 🏗️ System Architecture

```
                                  EDGEPPG PIPELINE
   ┌─────────────────────────────────────────────────────────────────────────────┐
   │                                                                             │
   │  ┌────────────────┐     ┌────────────────┐     ┌─────────────────────────┐  │
   │  │ Camera Capture │ ──> │ Face ROI &     │ ──> │ rPPG POS DSP            │  │
   │  │ (30 FPS Lock)  │     │ Mesh Tracking  │     │ (Biquad + FFT + Corr)   │  │
   │  └────────────────┘     └────────────────┘     └─────────────────────────┘  │
   │                                                             │               │
   │  ┌────────────────┐     ┌────────────────┐                  ▼               │
   │  │ Sensor Quality │     │ Active Gaze &  │     ┌─────────────────────────┐  │
   │  │ Gate (AE/AWB)  │     │ Head Challenge │ ──> │ Multi-Modal Decision     │  │
   │  └────────────────┘     └────────────────┘     │ Engine (LIVE/SPOOF/UNC) │  │
   │                                                └─────────────────────────┘  │
   │                                                             │               │
   │  ┌────────────────────────────────────────────────────────┐ │               │
   │  │ AndroidKeyStore (SECP256R1 Hardware-Signed Envelope)   │ ◄               │
   │  └────────────────────────────────────────────────────────┘                 │
   │                               │                                             │
   └───────────────────────────────┼─────────────────────────────────────────────┘
                                   │ HTTP POST /api/result & /api/live
                                   ▼
   ┌─────────────────────────────────────────────────────────────────────────────┐
   │                          PC VERIFIER & GATEWAY NODE                         │
   │  - Cryptographic Signature & Nonce Verification (300s freshness window)     │
   │  - Real-Time Live Queue & Operator Console (Session / Call ID Routing)      │
   │  - 28-Feature Schema Compliance & Audit Ledger                              │
   │  - Offline Tamper-Evident SHA-256 Verification Receipts                     │
   └─────────────────────────────────────────────────────────────────────────────┘
```

---

## ⚡ Key Capabilities & Scientific Foundations

### 1. Physiological rPPG Pulse Extraction
* **Plane-Orthogonal-to-Skin (POS)**: Isolates microvascular hemoglobin absorption pulses from temporal RGB averages across 3 facial ROIs (Forehead, Left Cheek, Right Cheek).
* **Dual-Band Bandpass Filtering**: Biquad Butterworth filter tuned to $0.75\,\text{Hz} - 3.5\,\text{Hz}$ ($45 - 210\,\text{BPM}$).
* **Radix-2 FFT Spectral SNR**: Extracts heart rate peak frequency and calculates pulsatile Signal-to-Noise Ratio (dB).
* **Multi-ROI Cross-Correlation**: Validates physiological phase synchrony across separate skin regions ($r > 0.05 - 0.35$). Synthetic screens and printed photos lack genuine cross-ROI vascular pulse propagation.

### 2. Active Behavioral Challenges
* **Deterministic Pseudo-Random Generation**: Seed-derived unpredictable challenge sequences prevent pre-recorded video replays.
* **Modalities**:
  * Gaze Vector tracking (up/down/left/right).
  * 3D Head Pose tracking via Euler angles (yaw/pitch/roll).
  * Hand Gesture detection via MediaPipe (`RAISE_LEFT`, `RAISE_RIGHT`, `OPEN_PALM`, `THUMBS_UP`).
  * Micro-motion stillness verification.

### 3. Active Optical Stimulus & Ambient Light Calibration
* **Screen Flash Probe**: Emits millisecond color flashes to verify temporal reflectance modulation on human skin vs digital display panels.
* **Illumination Resilience**: In brightly lit environments where natural sunlight washes out flash contrast, the engine designates the trial as `CONTAMINATED` (inconclusive lighting condition) rather than flagging the user as `WRONG` (presentation attack), preventing genuine applicants in good lighting from false rejections.

### 4. Hardware-Enclave Attestation
* **AndroidKeyStore Signing**: Hardware-backed NIST P-256 (`SHA256withECDSA`) signing of canonical verification transcripts.
* **Nonce Freshness**: Cryptographic nonces bound to 300-second freshness windows guarantee replay immunity.

### 5. Zero-Cloud Privacy Guarantee
* Raw video frames and facial biometric data never leave device memory. Only canonicalized feature vectors and cryptographic signatures are transmitted over local Wi-Fi or QR fallback.

---

## 🎨 Visual Design System

The application features a modern, professional, financial-grade user experience:
* **Color Palette**: Dominant clean white surfaces (`#FFFFFF`) with subtle soft-blue cards (`#EAF5FF`, `#CFE5FA`) and vivid brand accents (`#77BFFF`).
* **Face Mesh ROI Overlay**:
  * Forehead: Vibrant Cyan (`#22D3EE`)
  * Left Cheek: Emerald Green (`#34D399`)
  * Right Cheek: Tangerine Orange (`#FB923C`)
* **Live Cardiac Canvas**: Real-time oscillatory waveform rendering microvascular pulse waves dynamically at 30+ FPS.

---

## 🚀 Quick Start Guide

### Prerequisites
* **Android Device**: Android 10+ (API 29+), tested and calibrated on **iQOO 15** (Qualcomm Snapdragon 8 Elite).
* **Development Machine**: Windows 11 / Linux / macOS with Python 3.10+ and JDK 17+.

---

### Step 1: Start the PC Verifier Gateway

```powershell
# In repository root:
.\.venv\Scripts\python.exe -m verifier.server --port 8080
```
Open **`http://localhost:8080/`** (or your machine's Wi-Fi IP e.g. `http://192.168.112.130:8080/`) to view the Verification Operator Dashboard.

---

### Step 2: Build & Install Android Application

```powershell
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

### Step 3: Run Live Verification

1. **On the PC Operator Dashboard**:
   * Click **⚡ Autofill Sample** (schedules Applicant `User 1`, Session `001`).
   * Click **+ Schedule Session**.
2. **On the Mobile Device**:
   * Open **EdgePPG VKYC**.
   * Tap **`START / JOIN VERIFICATION ➔`**.
   * Align your face in the camera preview frame.
   * Follow the prompt challenges (e.g. gaze shift, head tilt, stillness).
   * Upon completion, the screen confirms **VERIFIED LIVE APPLICANT** and streams the signed envelope to the PC gateway.
   * The PC dashboard writes an immutable receipt to `verifier/receipts/`.

---

## 🧪 Automated Attack Test Harness

The PC Verifier features a built-in automated test suite that simulates adversarial presentation attacks:

```powershell
.\.venv\Scripts\python.exe -m unittest discover verifier/tests
```

Evaluated Attack Vectors:
* **Genuine Live Subject**: Valid rPPG pulse ($SNR \ge 0.42$, $ROI \ge 0.52$), passes all gates $\rightarrow$ `LIVE`.
* **Printed Photo Attack**: Static reflection ($SNR = 0.04$, $ROI = 0.02$), no pulse $\rightarrow$ `SPOOF`.
* **Screen Video Replay**: Display flicker ($SNR = 0.08$, $ROI = 0.06$) $\rightarrow$ `SPOOF`.
* **Signature Tampering**: Corrupted envelope signature $\rightarrow$ `INVALID`.
* **Nonce Replay**: Replayed historic session nonce past 300s window $\rightarrow$ `REPLAY REJECTED`.

---

## 📁 Repository Directory Structure

```
EdgePPG/
├── android/                         # Android native project (Kotlin)
│   └── app/src/main/kotlin/com/edgeppg/app/
│       ├── acoustic/                # Ultrasonic Doppler distance estimator
│       ├── behavior/                # Gaze, head pose, and hand gesture solvers
│       ├── capture/                 # CameraX session, 3A lock, phone screen detector
│       ├── challenge/               # Deterministic challenge sequence engine
│       ├── document/                # ID document OCR and framing assist
│       ├── features/                # 28-feature canonical vector assembler & native ML
│       ├── gates/                   # DecisionEngine & threshold evaluator
│       ├── integrity/               # AndroidKeyStore ECDSA signer & transcript builder
│       ├── optical/                 # Screen flash challenge stimulus & analyzer
│       ├── quality/                 # Exposure, AWB, and motion contamination gates
│       ├── rppg/                    # Pure Kotlin POS DSP, biquad filter, FFT SNR
│       ├── session/                 # SessionController & CallController
│       └── transport/               # Local Wi-Fi transport & QR code fallback
├── verifier/                        # Python PC verifier gateway node
│   ├── calls.py                     # Session scheduling & join handling
│   ├── canonical.py                 # Canonical JSON serialization
│   ├── receipt.py                   # Tamper-evident receipt generator
│   ├── server.py                    # HTTP dashboard & verification gateway
│   ├── test_harness.py              # Automated attack scenario engine
│   └── verify.py                    # ECDSA signature & freshness validator
├── models/                          # ML models (Random Forest, XGBoost, MediaPipe)
├── docs/                            # Research blueprints, audits, and guides
├── tests/                           # Python parity and end-to-end test suite
├── ARCHITECTURE.md                  # Comprehensive system & runtime architecture
├── IMPLEMENTATION_PLAN.md           # Implementation roadmap & validation log
├── CHANGELOG.md                     # Version history & release notes
├── CONTRIBUTING.md                  # Developer workflow & PR guidelines
├── SECURITY.md                      # Security policy & threat model
├── LICENSE                          # Apache License 2.0
└── requirements.txt                 # Python dependencies
```

---

## 📜 License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for details.
