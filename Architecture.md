# EdgePPG — System & Runtime Architecture

## 1. System Vision & Zero-Trust Principles

**EdgePPG** provides cryptographic assurance of human liveness during Video KYC (VKYC) sessions directly on consumer edge hardware. The system enforces strict zero-trust principles:

1. **Edge Biometric Confinement**: High-resolution video feeds and biometric templates never leave device RAM. No facial embeddings or biometric records are stored on disk or sent across the network.
2. **Cryptographic Attestation**: The verification verdict and aggregated 28-feature telemetry vector are cryptographically signed inside the `AndroidKeyStore` using a hardware-isolated NIST P-256 (`SHA256withECDSA`) keypair.
3. **Multi-Modal Verification**: Liveness cannot be assured by a single signal. EdgePPG combines physiological microvascular pulse detection (rPPG), active behavioral challenge-response, optical screen flash reflection analysis, and secondary device / phone bezel detection.
4. **Freshness & Anti-Replay**: Each verification session is bound to a cryptographically generated nonce and timestamp, enforcing a 300-second freshness window.

---

## 2. End-to-End System Pipeline

```
                                  EDGEPPG RUNTIME PIPELINE
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                                 ANDROID CLIENT SUBSYSTEM                               │
 │                                                                                        │
 │  ┌─────────────────────┐      ┌─────────────────────────┐     ┌─────────────────────┐  │
 │  │ CameraX Capture     │ ───> │ Convergence Lock        │ ──> │ Face ROI Tracker    │  │
 │  │ (30 FPS Stream)     │      │ (AE Lock / AWB Lock)    │     │ (Forehead & Cheeks) │  │
 │  └─────────────────────┘      └─────────────────────────┘     └──────────┬──────────┘  │
 │                                                                          │             │
 │                 ┌────────────────────────────────────────────────────────┼──────────┐  │
 │                 ▼                                                        ▼          ▼  │
 │  ┌──────────────────────────────┐                         ┌───────────────────────┐ │  │
 │  │ rPPG POS Signal Engine       │                         │ Behavioral & Optical  │ │  │
 │  │ - Temporal RGB extraction    │                         │ Stimulus Engine       │ │  │
 │  │ - Biquad Butterworth Bandpass│                         │ - Gaze / Head Pose    │ │  │
 │  │ - Radix-2 FFT (SNR & BPM)    │                         │ - Hand Gesture Recogn │ │  │
 │  │ - Cross-ROI Pearson Corr (r) │                         │ - Screen Flash Probe  │ │  │
 │  └──────────────┬───────────────┘                         └───────────┬───────────┘ │  │
 │                 │                                                     │             │  │
 │                 └────────────────────────┬────────────────────────────┘             │  │
 │                                          ▼                                          │  │
 │                         ┌─────────────────────────────────┐                         │  │
 │                         │ 28-Feature Schema Assembler     │                         │  │
 │                         └────────────────┬────────────────┘                         │  │
 │                                          ▼                                          │  │
 │                         ┌─────────────────────────────────┐                         │  │
 │                         │ Multi-Modal ML & Decision Gates │                         │  │
 │                         │ (DecisionEngine: LIVE/SPOOF/UNC)│                         │  │
 │                         └────────────────┬────────────────┘                         │  │
 │                                          ▼                                          │  │
 │                         ┌─────────────────────────────────┐                         │  │
 │                         │ AndroidKeyStore Hardware Enclave│                         │  │
 │                         │ (ECDSA SECP256R1 Signed Envelope│                         │  │
 │                         └────────────────┬────────────────┘                         │  │
 └──────────────────────────────────────────┼──────────────────────────────────────────┘
                                            │ HTTP POST /api/result & /api/live
                                            ▼
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                              PC VERIFIER GATEWAY NODE                                  │
 │                                                                                        │
 │  - Session Orchestrator & Live Telemetry Ingestion (/api/live)                         │
 │  - Public Key Cryptographic Attestation Validator (/api/result)                       │
 │  - Nonce Replay Protection & 300s Freshness Gate                                       │
 │  - Tamper-Evident SHA-256 Receipt Generator & Audit Ledger                             │
 │  - Operator Console & Automated Presentation Attack Lab                                │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Subsystem Breakdown

### 3.1 Camera Capture & Hardware Convergence Lock
- **Package**: `com.edgeppg.app.capture`
- **Component**: [CameraSession.kt](file:///e:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/capture/CameraSession.kt), [ConvergenceLockController.kt](file:///e:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/capture/ConvergenceLockController.kt)
- **Mechanism**:
  - Utilizes CameraX `ImageAnalysis` bound to the front-facing camera.
  - Automatically assesses 3A convergence (Auto-Exposure and Auto-White Balance).
  - Once luminance variance settles within standard margins, locks `CONTROL_AE_LOCK = true` and `CONTROL_AWB_LOCK = true`. This prevents automatic camera gain oscillations from artificially injecting false photoplethysmographic harmonics.

### 3.2 Pure Kotlin rPPG POS Engine
- **Package**: `com.edgeppg.app.rppg`
- **Component**: [RppgClient.kt](file:///e:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/rppg/RppgClient.kt), [RppgGraphView.kt](file:///e:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/rppg/RppgGraphView.kt)
- **Algorithm**: Plane-Orthogonal-to-Skin (POS):
  1. Temporal RGB means $C(t) = [R(t), G(t), B(t)]^T$ are extracted across 3 facial ROIs:
     - Forehead ROI ($ROI_0$)
     - Left Cheek ROI ($ROI_1$)
     - Right Cheek ROI ($ROI_2$)
  2. Normalization:
     $$c_n(t) = \frac{C(t)}{\mu_C(t)}$$
  3. Orthogonal projection:
     $$S_1(t) = G(t) - B(t), \quad S_2(t) = G(t) + B(t) - 2R(t)$$
     $$h(t) = S_1(t) + \alpha(t) S_2(t), \quad \alpha(t) = \frac{\sigma(S_1)}{\sigma(S_2)}$$
  4. Filtering: Dual-pass 2nd-order biquad Butterworth bandpass ($0.75\,\text{Hz} - 3.5\,\text{Hz}$, representing $45 - 210\,\text{BPM}$).
  5. Spectral SNR & Peak Estimation: 512-point Radix-2 FFT computes signal-to-noise ratio:
     $$\text{SNR} = 10 \log_{10} \left( \frac{\int_{f_0 - \Delta f}^{f_0 + \Delta f} P(f) df}{\int_{\text{band}} P(f) df - \int_{f_0 - \Delta f}^{f_0 + \Delta f} P(f) df} \right)$$
  6. Phase Agreement: Pearson correlation $r(ROI_0, ROI_1)$ and $r(ROI_0, ROI_2)$ evaluates biological pulsatile synchronization. Real skin exhibits $r > 0.05 - 0.35$ with positive SNR; synthetic screens and printed photos exhibit zero microvascular sync.

### 3.3 Behavioral Challenge Engine
- **Package**: `com.edgeppg.app.behavior`, `com.edgeppg.app.challenge`
- **Components**: [GazeEstimator.kt](file:///e:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/behavior/GazeEstimator.kt), [HeadPoseSolver.kt](file:///e:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/behavior/HeadPoseSolver.kt), [GestureTracker.kt](file:///e:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/behavior/GestureTracker.kt), [ChallengeEngine.kt](file:///e:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/challenge/ChallengeEngine.kt)
- **Capabilities**:
  - **Gaze**: Computes relative iris-to-eye-corner displacement vectors (`LOOK_LEFT`, `LOOK_RIGHT`, `LOOK_UP`, `LOOK_DOWN`).
  - **Head Pose**: Solves 3D Perspective-n-Point Euler angles (`YAW`, `PITCH`, `ROLL`) tracking intentional head nods and turns.
  - **Hand Gesture**: MediaPipe-based hand tracking detecting distinct commands (`RAISE_LEFT`, `RAISE_RIGHT`, `OPEN_PALM`, `THUMBS_UP`).
  - **Stillness**: Verifies micro-motion boundaries during physiological rPPG sampling windows.

### 3.4 Active Optical Stimulus Challenge
- **Package**: `com.edgeppg.app.optical`
- **Components**: [OpticalChallengeEngine.kt](file:///e:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/optical/OpticalChallengeEngine.kt), [OpticalResponseAnalyzer.kt](file:///e:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/optical/OpticalResponseAnalyzer.kt)
- **Principle**:
  - Displays randomized screen color flashes (e.g. Cyan, Magenta, Yellow) at calibrated millisecond intervals.
  - Measures specular and diffuse reflection response on facial skin:
    $$\Delta R = R_{\text{stimulus}} - R_{\text{baseline}}$$
  - **Illumination Resilience**: In high ambient lighting where natural sunlight or bright lamps dilute flash contrast, the analyzer designates the observation as `CONTAMINATED` (inconclusive lighting) rather than false `WRONG` (presentation attack), preventing genuine applicants from being falsely rejected.

### 3.5 Phone & Secondary Screen Detector
- **Package**: `com.edgeppg.app.capture`
- **Component**: [PhoneScreenDetector.kt](file:///e:/EdgePPG/android/app/src/main/kotlin/com/edgeppg/app/capture/PhoneScreenDetector.kt)
- **Principle**:
  - Analyzes high-frequency Fourier components and edge gradients to detect rectangular bezel outlines, specular glare typical of mobile screens/tablets, and display moiré patterns.
  - Instantly transitions the decision engine to `SPOOF` if a secondary display frame is identified in the capture preview.

### 3.6 Hardware-Enclave Integrity (`AndroidKeyStore`)
- **Package**: `com.edgeppg.app.integrity`
- **Component**: `IntegrityManager.kt`
- **Specifications**:
  - Key Alias: `edgeppg_attestation_key`
  - Algorithm: NIST P-256 (`EC`, `secp256r1`)
  - Signature: `SHA256withECDSA`
  - Hardware Protection: Key generated with `PURPOSE_SIGN` and backed by Android KeyMint / StrongBox when available.
  - Signed Envelope:
    ```json
    {
      "keyAlias": "edgeppg_attestation_key",
      "alg": "SHA256withECDSA",
      "data": "{\"session_id\":\"...\",\"nonce\":\"...\",\"decision\":\"LIVE\",...}",
      "sig": "MEQCICvG...3D=="
    }
    ```

---

## 4. Normative 28-Feature Schema

Every verification transcript generates a canonical 28-feature floating-point vector:

| # | Feature Name | Range | Description |
| :- | :--- | :--- | :--- |
| 1 | `face_confidence` | $[0.0, 1.0]$ | Face detector confidence |
| 2 | `face_quality` | $[0.0, 1.0]$ | Landmark sharpness & illumination balance |
| 3 | `rppg_snr` | $[-5.0, 25.0]$ | rPPG cardiac spectral signal-to-noise ratio (dB) |
| 4 | `rppg_peak_strength` | $[0.0, 1.0]$ | Fundamental heart rate FFT peak prominence |
| 5 | `rppg_hr_stability` | $[0.0, 1.0]$ | Heart rate temporal consistency over time |
| 6 | `rppg_roi_agreement` | $[-1.0, 1.0]$ | Cross-ROI Pearson correlation ($r$) |
| 7 | `gaze_accuracy` | $[0.0, 1.0]$ | Accuracy of gaze direction challenge responses |
| 8 | `head_accuracy` | $[0.0, 1.0]$ | Accuracy of head pose challenge movements |
| 9 | `hand_accuracy` | $[0.0, 1.0]$ | Hand gesture recognition confidence |
| 10 | `challenge_timing_error` | $[0.0, 5.0]$ | Mean latency deviation from challenge cues (s) |
| 11 | `optical_response_score` | $[0.0, 1.0]$ | Facial skin reflectance modulation correlation |
| 12 | `trusted_face_confidence`| $[0.0, 1.0]$ | Co-presence participant face confidence |
| 13 | `trusted_face_quality` | $[0.0, 1.0]$ | Co-presence participant landmark quality |
| 14 | `trusted_gaze_accuracy` | $[0.0, 1.0]$ | Co-presence participant gaze compliance |
| 15 | `trusted_head_accuracy` | $[0.0, 1.0]$ | Co-presence participant head pose compliance |
| 16 | `trusted_hand_accuracy` | $[0.0, 1.0]$ | Co-presence participant hand gesture compliance |
| 17 | `trusted_timing_error` | $[0.0, 5.0]$ | Co-presence participant response latency (s) |
| 18 | `cross_person_timing` | $[0.0, 1.0]$ | Multi-party interaction temporal synchrony |
| 19 | `cross_person_interact` | $[0.0, 1.0]$ | Mutual gaze / gesture alignment score |
| 20 | `motion_consistency` | $[0.0, 1.0]$ | Relative head motion physics consistency |
| 21 | `presence_consistency` | $[0.0, 1.0]$ | Simultaneous presence stability |
| 22 | `sequence_consistency` | $[0.0, 1.0]$ | Strict challenge sequence order fidelity |
| 23 | `camera_quality` | $[0.0, 1.0]$ | Sensor sharpness, noise level, and resolution |
| 24 | `frame_drop_rate` | $[0.0, 1.0]$ | Dropped frame fraction (target 30 FPS) |
| 25 | `exposure_stability` | $[0.0, 1.0]$ | Exposure convergence & lock stability |
| 26 | `awb_stability` | $[0.0, 1.0]$ | Auto-white balance gain variance |
| 27 | `capture_duration` | $[0.0, 120.0]$| Duration of active acquisition session (s) |
| 28 | `device_integrity` | $[0.0, 1.0]$ | Hardware attestation & KeyStore integrity status |

---

## 5. PC Verifier Gateway Architecture

- **Path**: `verifier/`
- **Modules**:
  - `server.py`: Standard library HTTP server hosting operator UI and REST endpoints.
  - `verify.py`: Cryptographic verification of ECDSA signatures against known public keys (`pubkey.b64`).
  - `calls.py`: Manages verification sessions (`SCHEDULED`, `WAITING`, `CONNECTED`, `COMPLETED`).
  - `receipt.py`: Generates immutable JSON receipts with SHA-256 digests in `verifier/receipts/`.
  - `test_harness.py`: Automated adversarial evaluation harness testing 5 attack modalities.

### REST Endpoints
- `GET /`: Serves operator dashboard.
- `GET /api/calls`: Retrieves list of scheduled calls and active live session telemetry.
- `POST /api/calls`: Creates or schedules a new verification session.
- `POST /api/live`: Real-time streaming telemetry ingestion from the mobile client.
- `POST /api/result`: Ingests and verifies hardware-signed attestation envelopes.
- `POST /api/lab/evaluate`: Runs automated attack test scenarios.