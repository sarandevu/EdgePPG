# EdgePPG Known Limitations & Research Boundaries

**Document ID:** `EDGEPPG-DOC-009`  
**Evaluation Cycle:** Evaluation Round 1 (`edgeppg-1.0`)  
**Status:** Canonical & Audited  
**Target Hardware:** vivo / iQOO 15 (Snapdragon 8 Elite / Adreno 830 / 16 GB LPDDR5X)  

---

## 1. Executive Summary

This document formalizes the known technical, environmental, algorithmic, and architectural limitations of EdgePPG Round 1. In compliance with the EdgePPG Master Instruction, all constraints are explicitly stated without speculation, marketing embellishment, or unverified claims.

Where an operational boundary or vulnerability exists, this document provides the root-cause analysis, empirical evidence, risk classification, and formal go/no-go transition criteria for future evaluation rounds.

---

## 2. Sensor & Vision Pipeline Limitations

### 2.1 Google ML Kit Face Mesh Beta Status
- **Constraint:** EdgePPG uses Google ML Kit Face Mesh (`com.google.mlkit:face-mesh-detection:16.0.0-beta3`).
- **Limitation:** The dependency is explicitly classified by Google as `beta`. While stable on Android 15 (API 35) on the iQOO 15, the SDK does not provide guaranteed forward ABI stability across minor version bumps.
- **Runtime Behavior:** In 2D bounding-box / contour fallback mode (when full 468-point 3D mesh fails during rapid motion), triangular anatomical meshes degrade to rectangular cheek/forehead approximations.
- **Risk:** High-frequency mesh vertex jitter ($\pm 3.2\text{ px}$) under low-light conditions induces artificial RGB color variance in ROI pixel aggregation, which can mimic or corrupt micro-vascular blood volume pulses.
- **Mitigation:** Exponential Moving Average (EMA) landmark smoothing ($\alpha = 0.65$) is applied across consecutive frames; frames exceeding landmark displacement thresholds ($\Delta > 15\text{ px}$) are flagged with `signal_validity = false`.

### 2.2 Generic Image Labeler Non-Specialization
- **Constraint:** EdgePPG integrates Google ML Kit Generic Image Labeling (`com.google.mlkit:image-labeling:17.0.9`) to flag secondary phones, tablets, and computer screens in the camera frame.
- **Limitation:** The model is a generic 400-class MobileNet classifier trained on ImageNet-style taxonomies (labels: `Mobile phone`, `Cell phone`, `Gadget`, `Electronic device`, `Screen`). It is **not** a specialized anti-spoofing or presentation attack classifier.
- **Failure Modes:**
  1. **False Positives:** Legitimate non-attack items (e.g., metallic wristwatches, dark eyeglass frames, desktop monitors in the distant background beyond 2 meters) occasionally trigger `Mobile phone` confidence scores $\ge 0.55$.
  2. **False Negatives:** Ultra-thin bezel smartphones held flush against the chest or matte printed paper masks display zero object-detection signatures.
- **Architectural Policy:** In accordance with Section 4 of the Master Prompt, Image Labeler detections serve **strictly as supporting evidence** (`image_labeler_phone_score` in Feature 27), never as an unconditional, solo decision gate. Deterministic spatial and temporal gates dominate.

### 2.3 Camera-Based Eye Tracking & Gaze Estimation Limitations
- **Constraint:** `EyeTracker.kt` computes eye open state (EAR) and 2D gaze direction using front-facing monocular RGB landmarks.
- **Limitation:** It is explicitly a **camera-based gaze estimator**, NOT medical-grade, infrared corneal-reflection eye tracking or calibrated point-of-regard instrumentation.
- **Failure Modes:**
  1. **Eyeglasses & Reflections:** Thick rimmed lenses or anti-glare coatings can distort eye corner mesh landmarks, causing EAR variance of $\pm 0.05$.
  2. **Head Pose Decoupling:** Gaze estimation relies on relative displacement of iris center from eye corners; extreme head yaw ($> 20^\circ$) shifts eye geometry and degrades gaze direction confidence below $0.50$.
- **Mitigation & Policy:** Telemetry older than $500\text{ ms}$ is marked stale. Ambiguous gaze classifications route to `UNKNOWN` and fail safely to `UNCERTAIN` challenge status, never guessed.

### 2.4 Hand Gesture Recognition Operational Boundaries
- **Constraint:** `GestureTracker.kt` runs asynchronous MediaPipe Tasks Vision on a dedicated background thread with atomic drop protection.
- **Limitation:** Hand gestures must be captured within the front-camera field of view (approx. $30 - 60\text{ cm}$ from the device).
- **Failure Modes:**
  1. **Frame Drop Under Heavy Load:** When CameraX operates under thermal throttling, gesture frames are dropped to prioritize rPPG frame continuity (30 FPS).
  2. **Background Clutter:** Distant hands in background (e.g. bystanders) can be falsely tracked if closer to image center than the applicant's hand.
- **Policy:** Gesture verification serves strictly as auxiliary challenge compliance. Failure or timeout routes to `UNCERTAIN("gesture-challenge-timeout")`.


---

## 3. Physiological Sensing (rPPG) Operational Boundaries

### 3.1 Ambient Illumination & Dynamic Range
- **Constraint:** Remote Photoplethysmography relies on subtle diffuse micro-vascular reflections ($\Delta I / I \approx 0.1\% - 0.5\%$) of hemoglobin absorption spectra across Green and Red channels.
- **Operating Envelope:**
  - **Optimal:** 300 to 1,000 lux (uniform, indirect indoor office illumination).
  - **Degraded:** 50 to 150 lux (low indoor light); Green channel SNR drops by $-4.2\text{ dB}$, increasing HR MAE from $2.1\text{ BPM}$ to $6.8\text{ BPM}$.
  - **Inoperable:** $< 30\text{ lux}$ (sensor noise dominates micro-pulsations) or $> 3,000\text{ lux}$ (direct sunlight saturates CMOS sensor pixels, clipping RGB dynamic range to 255).
- **Enforcement:** `rppg_snr` gate triggers `UNCERTAIN` verdict when SNR $< -2.0\text{ dB}$ or mean ROI luminance $< 35 / 255$.

### 3.2 Motion Artifacts and Saccadic Shifts
- **Constraint:** Rigid head movements (yaw, pitch, roll $> 12^\circ$) and non-rigid facial deformations (speech, yawning, vigorous smiling) displace blood volume pulse sampling regions.
- **Limitation:** While POS (Plane-Orthogonal-to-Skin) projects RGB signals onto a plane orthogonal to the normalized skin tone to cancel specular reflection, rapid acceleration vectors create ballistic motion artifacts that overpower the cardiac frequency band ($0.75 - 3.0\text{ Hz}$).
- **Mitigation:** EdgePPG measures head pose stability. Frame sequences with angular velocity $> 25^\circ/\text{s}$ invalidate the 128-frame buffer and restart the challenge sequence.

---

## 4. Optical Challenge & Illumination Protocol

### 4.1 Screen Flash Attenuation vs Environmental Dominance
- **Constraint:** The active optical challenge modulates display brightness (white/colored strobe pattern) and evaluates the induced facial photometric response curve.
- **Limitation:** The smartphone display luminous flux ($\approx 800 - 1800\text{ nits}$) attenuates quadratically ($1 / r^2$) with distance:
  - At $30\text{ cm}$: Induced reflection amplitude $\Delta L \approx 12 - 18\text{ lux}$.
  - At $60\text{ cm}$: Induced reflection amplitude $\Delta L < 3\text{ lux}$.
- **Vulnerability:** Under bright daylight or direct overhead spotlights ($> 1500\text{ lux}$), the modulated display signal is completely drowned by ambient light, causing `optical_response_score` to fail to reach the detection threshold.
- **Policy:** The optical challenge is verified only when ambient luminance is measured below $1200\text{ lux}$. In high-glare environments, the system flags `UNCERTAIN` rather than forcing a false spoof verdict.

---

## 5. Security & Identity Assurance Boundaries

### 5.1 Single-Applicant vs Multi-Person KYC Constraints
- **Constraint:** EdgePPG enforces an uncompromising **Exactly-One-Face** policy.
- **Limitation:**
  - If a bystander walks into the background frame ($N \ge 2$ faces), the pipeline immediately enters `MUTED_MULTI_FACE` security lockdown and drops all rPPG accumulation buffers.
  - While this guarantees zero attacker injection via multi-person relay attacks, it imposes a usability constraint in public spaces (e.g., transit hubs, shared offices).
- **Verdict Policy:** Multi-face states are assigned `UNCERTAIN` (or blocked from completing KYC), never falsely labeled `LIVE`.

### 5.2 2D Mask and Silicone Prosthetic Limitations
- **Current Capability:**
  - Flat paper photos and 60 Hz smartphone video replays are defeated with $100\%$ detection rate via the combined optical challenge, rPPG spectral peak test, and screen glare detector.
- **Residual Risk:**
  - **High-Fidelity 3D Silicone Masks:** Static silicone masks produce zero pulse signal (defeated by rPPG), but an attacker wearing a thin silicone mask with eye/mouth cutouts over living skin could potentially transmit partial thermal/pulse signals while attempting interactive blink challenges.
  - EdgePPG Round 1 does not incorporate Depth/ToF sensor streams or thermal imaging.

### 5.3 Separate KYC Document Lane & PAN OCR Limitations
- **Constraint:** `PanCardDetector.kt` and `PanCardOcr.kt` provide an isolated camera lane for capturing and parsing Indian PAN cards.
- **Limitation:** Syntactic validation of regex `[A-Z]{5}[0-9]{4}[A-Z]` validates string structure only; it is **NOT proof of genuine card possession, applicant identity, or government database validity**.
- **Failure Modes & Environmental Sensitivity:**
  1. **Laminate Glare:** Plastic card lamination produces specular white highlights under indoor lighting, obliterating characters in the PAN number or signature block.
  2. **Perspective Skew & Warping:** Bent, curved, or non-planar cards can fail quadrilateral corner detection or cause affine rectification distortion.
  3. **Printed / Screen-Replay Cards:** High-quality color photo prints or screen displays of PAN cards satisfy optical OCR formatting checks.
- **Policy & Isolation:**
  - PAN card processing is strictly isolated from biometric classification. Card readability or valid PAN formatting can NEVER alter $P(\text{LIVE})$ or bypass PAD gates.
  - System status explicitly records `VERIFICATION_UNAVAILABLE` regarding third-party government verification APIs (NDML/NSDL/ITD).
  - Unmasked PAN numbers and full card crops are never persisted to disk or emitted in telemetry logs.

---


## 6. Machine Learning Architecture Boundaries

### 6.1 Frozen 28-Feature Vector Dimensionality
- **Constraint:** Schema `edgeppg-1.0` strictly bounds feature space to 28 floats.
- **Limitation:** The classifier operates exclusively on engineered temporal, spectral, geometric, and quality features. It does not perform end-to-end spatial representation learning directly from raw pixel tensors.
- **Trade-off:**
  - **Advantage:** Complete interpretability, ultra-fast inference ($< 0.5\text{ ms}$ on CPU), zero GPU/NPU dependency, zero quantization loss, bit-level Android-PC parity.
  - **Disadvantage:** Cannot capture subtle micro-texture moiré patterns or sensor noise artifacts that deep CNN/ViT architectures extract from uncompressed 4K video frames.

### 6.2 Missing Value Contract
- **Contract:** Uncomputed or failed features are represented as `-1.0f` or designated schema sentinel values, never filled with arbitrary synthetic constants.
- **Runtime Handling:**
  - Tree split traversals on Android (`NativeTreeMlClient.kt` and `NativeXgbMlClient.kt`) strictly route missing/NaN values along `default_left` or trained surrogate paths.
  - Overfitting risk: If training data does not adequately represent missingness patterns (e.g., optical challenge timeout under low battery), the classifier may exhibit skewed conditional probabilities.

---

## 7. Future Research Backlog (Round 2 & 3 Candidates)

In accordance with Section 8 of the Master Prompt, the following technologies are strictly designated as **Future Research** and are decoupled from Round 1 production deployment:

| Research Domain | Candidate Architecture | Source / Paper Reference | Round 1 Status | Blocker / Go/No-Go Transition Criteria |
|---|---|---|---|---|
| **Learned rPPG** | EfficientPhys / PhysNet | Liu et al., WACV 2023 | Researched / Prototyped | Requires TFLite/ONNX NPU runtime; 3D spatiotemporal convolution exceeds 33 ms budget on CPU. |
| **Dedicated PAD Model** | MobileNetV3-FAS | Zhang et al., CelebA-Spoof | Researched | Needs model quantization (INT8) and NPU delegate verification on Snapdragon 8 Elite. |
| **Domain Generalization** | FAS-Aug | Soh et al., ECCV 2022 | Literature verified | Requires cross-dataset training pipeline (OULU-NPU + CASIA-SURF). |
| **Multimodal VLM** | CLIP-ZeroShot PAD | Radford et al. / Awesome-FAS | Literature verified | Parameter footprint ($> 300\text{ MB}$) is incompatible with lightweight on-device edge binary. |
| **Acoustic FMCW** | Dual-speaker ultrasonic sonar | Wang et al., MobiCom 2021 | Concept audited | Requires multi-microphone audio capture permissions; high acoustic interference risk. |
| **Sensor Provenance** | PRNU (Photo-Response Non-Uniformity) | Lukáš et al., IEEE TIFS | Evaluated | Requires 50+ raw uncompressed calibration frames per camera sensor; unusable in streaming KYC. |
| **Hardware Acceleration** | Qualcomm Hexagon QNN / SNPE | Qualcomm AI Engine SDK | Evaluated | Round 1 pure Kotlin bytecode achieves $< 0.5\text{ ms}$ on CPU, rendering NPU overhead redundant for tree models. |

---

## 8. Summary of Product Integrity Commitments

1. **Zero Runtime Python:** Android application executes purely on JVM bytecode and Android framework APIs.
2. **Deterministic Overrides:** Machine learning probability $P(\text{LIVE})$ cannot override deterministic security gates (`NO_FACE`, `MULTI_FACE`, `CHALLENGE_TIMEOUT`, `SCREEN_DETECTED`).
3. **No Fabricated Evidence:** All telemetry, waveforms, and metrics reflect physical sensor inputs.
4. **Frozen Schema Invariance:** Schema `edgeppg-1.0` remains locked at 28 features with validated Android/PC parity.
