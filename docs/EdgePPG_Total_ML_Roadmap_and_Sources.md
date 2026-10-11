# EdgePPG — Total Machine Learning Roadmap & Authoritative Source Register

**Document Version:** 1.0.0  
**Target Device:** vivo / iQOO 15 (Qualcomm Snapdragon 8 Elite, Hexagon NPU, Adreno 830 GPU)  
**Target Environment:** Pure Kotlin On-Device Execution (`com.edgeppg.app`), Zero-Python Runtime, Zero-Termux Production Dependency  
**Frozen Interface:** 28-Feature Vector Schema (`edgeppg-1.0`), Deterministic Security Gates (`DecisionEngine`)

---

## 1. Executive Summary & Core ML Philosophy

EdgePPG operates on a **defense-in-depth, physics-and-geometry-grounded architecture** for remote human liveness and trust verification. Unlike black-box neural networks that take raw camera frames directly to a binary decision, EdgePPG decomposes verification into explicit, verifiable physical layers:

```text
Physical Applicant (Facing Camera)
  │
  ├── [1] Camera Sensor Stream (1080p @ 30 FPS, YUV_420_888)
  │     │
  │     ├── [2] Google ML Kit FaceMesh (468 3D Geometric Landmarks)
  │     │     ├── Spatial Head Pose (Yaw, Pitch, Roll via PnP / Orthographic)
  │     │     ├── Differential Iris Gaze (Vector Angle & Deviation)
  │     │     ├── Eye Aspect Ratio (EAR) for Involuntary Blink Tracking
  │     │     └── Mouth Aspect Ratio (MAR) for Smile & Mouth Open/Close
  │     │
  │     ├── [3] Anatomical Skin ROI Tracker (Forehead + Left Cheek + Right Cheek)
  │     │     └── Multi-Face Detector (Rejects > 1 Face; Halts on 0 Faces)
  │     │
  │     ├── [4] Dual rPPG DSP Pipeline (POS + CHROM Chrominance Projection)
  │     │     └── 256-pt Radix-2 FFT Pulse Extraction (SNR, Perfusion, HR Stability)
  │     │
  │     ├── [5] Active Anti-Spoofing & Screen Edge Planarity Detector
  │     │     ├── 3D Mesh Z-Variance Planar Collapse Detector
  │     │     └── Bezel Edge Contour & ML Kit Image Labeler
  │     │
  │     └── [6] Closed-Loop Optical Reflectance Verification
  │           └── Synchronous Screen Flash (White/Warm/Cool) vs. Facial Skin $\Delta RGB$
  │
  └── [7] Feature Assembler (Frozen 28-Feature Schema: edgeppg-1.0)
        │
        ├── [8] On-Device Calibrated Tree Model (300 Decision Trees + Platt Sigmoid)
        │     └── Outputs P(LIVE) ∈ [0.0, 1.0]
        │
        └── [9] Deterministic Security Gates (DecisionEngine)
              ├── Checks: Hardware Integrity, Face Count, Quality Gates, Challenges
              └── Verdict: LIVE | SPOOF | UNCERTAIN (Security Gates ALWAYS Dominate ML)
```

---

## 2. Master Status Matrix: Done vs. To Be Done

| # | ML / Algorithmic Component | Current Status | Code Location | Key Sources & Reference Papers | Next Required Action |
|---|-----------------------------|----------------|---------------|--------------------------------|----------------------|
| **1** | **FaceMesh 468 3D Landmarks** | **IMPLEMENTED** | `RoiTracker.kt` | Google MediaPipe (Lugaresi 2019, Grishchenko 2020) | Add non-blocking timeout handling |
| **2** | **Multi-Face Rejection** | **TO BE DONE** | `RoiTracker.kt`, `DecisionEngine.kt` | Biometric Single-Applicant Invariant | Reject `meshes.size > 1` → UNCERTAIN |
| **3** | **Head Pose Tracking (Yaw/Pitch/Roll)** | **IMPLEMENTED** | `HeadPoseSolver.kt` | POSIT / Orthographic Landmark Projection | Calibrate degree thresholds for iQOO |
| **4** | **Differential Gaze Tracking** | **IMPLEMENTED** | `GazeEstimator.kt` | Iris Landmark Centers (468–477) | Integrate with random challenge engine |
| **5** | **EAR (Eye Blink Action Tracking)** | **TO BE DONE** | `behavior/FaceActionTracker.kt` | Soukupová & Čech (2016), CVIU | Implement EAR formula on landmarks |
| **6** | **MAR (Mouth / Smile Action Tracking)** | **TO BE DONE** | `behavior/FaceActionTracker.kt` | Kaipeng Zhang et al., MTCNN / Dlib | Implement MAR formula on lips |
| **7** | **Anatomical ROI Extraction** | **IMPLEMENTED** | `RoiTracker.kt` | Kim, Lee, Sohn (Sensors 2021) | Add `reset()` to prevent cross-session leakage |
| **8** | **Deterministic rPPG (POS & CHROM)** | **IMPLEMENTED** | `RppgClient.kt` | Wang et al. (TBME 2017), de Haan (TBME 2013) | Validate on physical iQOO 15 camera |
| **9** | **Screen / Phone Attack Detector** | **IMPLEMENTED** | `PhoneScreenDetector.kt` | ML Kit ImageLabeler + 3D Mesh Planarity | Make ImageLabeler async (0ms analyzer stall) |
| **10**| **Closed-Loop Optical Flash** | **TO BE DONE** | `CameraSession.kt`, `BehaviourRunner.kt` | Tang et al. (TIFS 2018), Chan (CVPR 2018) | Correlate screen flash with facial $\Delta RGB$ |
| **11**| **Frozen 28-Feature Schema** | **IMPLEMENTED** | `RowAssembler.kt` | `EdgePPG_Architecture_Reference.md` | Remove fabricated constant fallbacks |
| **12**| **300-Tree Random Forest + Platt Sigmoid** | **IMPLEMENTED** | `NativeTreeMlClient.kt` | Breiman (2001), Platt (1999), Guo (ICML 2017) | Fully operational on Kotlin bytecode |
| **13**| **XGBoost Classifier Challenger** | **TO BE DONE** | `models/edgeppg_xgb_v1.json` | Chen & Guestrin (KDD 2016) | Export to JSON and add Kotlin inference |
| **14**| **EfficientPhys Learned rPPG** | **TO BE DONE** | `ml/src/efficient_phys/` | Liu et al. (WACV 2023), rPPG-Toolbox | Train on UBFC/PURE, export to LiteRT |
| **15**| **MobileNetV3-Spoof Lightweight PAD** | **TO BE DONE** | `ml/src/fas/` | Luevano et al. (CVPRW 2024) | Benchmark on CelebA-Spoof, quantize INT8 |
| **16**| **FAS-Aug Physics-Based Recapture** | **TO BE DONE** | `ml/src/augmentation/` | Cai et al. (arXiv 2024), FAS_Aug | Synthetic data generation for recapture |
| **17**| **Acoustic FMCW Sonar Sensing** | **RESEARCH** | `termux/scripts/acoustic/` | Chara et al. (Smart Health 2023) | Auxiliary research modality |
| **18**| **CLIP / VLM Zero-Shot PAD** | **RESEARCH** | `verifier/vlm_eval/` | Chen et al. (CrossGAP, ICCVW 2025) | Offline PC research challenger |
| **19**| **Qualcomm Hexagon NPU Acceleration** | **TO BE DONE** | `android/app/src/main/cpp/` | LiteRT Qualcomm QNN Delegate | Profile Snapdragon 8 Elite Hexagon NPU |

---

## 3. Detailed Component Specifications, Mathematics & Sources

### Track 1: Google FaceMesh 468-Landmark Action & Geometry ML

#### 1.1 Purpose & Role in EdgePPG
To replace heavy external gesture models (e.g., 15 MB MediaPipe Hand Landmarker) and eliminate timeout bugs on behavioral challenges, Google ML Kit FaceMesh (already loaded into memory for rPPG ROI extraction) is leveraged to extract fine-grained facial geometry, head movement, eye gaze, involuntary blinks, and mouth actions at 30 FPS.

#### 1.2 Mathematical Formulations

##### A. Eye Aspect Ratio (EAR) — Blink & Micro-Expression Detection
Tracks natural involuntary blinking and requested blink challenges. For each eye:
$$\text{EAR} = \frac{\|p_1 - p_5\|_2 + \|p_2 - p_4\|_2}{2 \cdot \|p_0 - p_3\|_2}$$

*Canonical 468-Mesh Indices:*
- **Left Eye:** $p_0 = 33$ (outer corner), $p_3 = 133$ (inner corner), $p_1 = 160$, $p_5 = 144$, $p_2 = 159$, $p_4 = 145$.
- **Right Eye:** $p_0 = 362$ (inner corner), $p_3 = 263$ (outer corner), $p_1 = 385$, $p_5 = 380$, $p_2 = 387$, $p_4 = 373$.
- **Detection Logic:**
  - $\text{EAR} > 0.25$: Open eye.
  - $\text{EAR} < 0.18$: Closed eye / blink event.
  - Blink duration must span 100 ms – 400 ms (filters out unnatural static cutouts or video loops).

##### B. Mouth Aspect Ratio (MAR) — Smile & Mouth Open/Close
Tracks compliance with active liveness challenges (e.g., "Smile", "Open Mouth"):
$$\text{MAR} = \frac{\|p_{13} - p_{14}\|_2 + \|p_{82} - p_{312}\|_2}{2 \cdot \|p_{61} - p_{291}\|_2}$$
*Key Landmark Indices:*
- Upper Lip Top: 13, Lower Lip Bottom: 14.
- Left Mouth Corner: 61, Right Mouth Corner: 291.
- Open mouth threshold: $\text{MAR} > 0.45$. Closed mouth threshold: $\text{MAR} < 0.15$.

##### C. Head Pose Euler Angles (Yaw, Pitch, Roll)
Derived using Perspective-n-Point (PnP) or orthographic least-squares projection of canonical 3D facial anchors against 2D camera projections:
- Anchors: Nose tip (1), Chin (152), Left eye outer (33), Right eye outer (263), Left mouth (61), Right mouth (291).
- Solves:
  $$\begin{bmatrix} \text{Yaw} \\ \text{Pitch} \\ \text{Roll} \end{bmatrix} = \text{EulerAngles}(\mathbf{R})$$
- Evaluates:
  - Turn Left: $\text{Yaw} > +15^\circ$
  - Turn Right: $\text{Yaw} < -15^\circ$
  - Nod (Look Up / Down): $\text{Pitch} \gtrless \pm 12^\circ$
  - Head Tilt: $\text{Roll} \gtrless \pm 12^\circ$

#### 1.3 Sources & Authoritative References
1. **Soukupová, T. and Čech, J.**, *"Real-Time Eye Blink Detection using Facial Landmarks"*, Computer Vision Winter Workshop (CVWW), 2016.  
   - DOI: [10.5281/zenodo.1199390](https://doi.org/10.5281/zenodo.1199390)
2. **Lugaresi et al.**, *"MediaPipe: A Framework for Building Perception Pipelines"*, arXiv:1906.08172, 2019.  
   - Paper: [https://arxiv.org/abs/1906.08172](https://arxiv.org/abs/1906.08172)
3. **Grishchenko, I. and Bazarevsky, V.**, *"On-device, Real-time Face Detection and Mesh Generation"*, Google AI Blog, 2020.  
   - Blog: [Google AI Blog](https://ai.googleblog.com/2020/09/face-mesh.html)
4. **Google ML Kit Face Mesh Documentation:** [https://developers.google.com/ml-kit/vision/face-mesh-detection](https://developers.google.com/ml-kit/vision/face-mesh-detection)

---

### Track 2: Physiological rPPG Signal Processing & Neural Models

#### 2.1 Deterministic Baselines: POS & CHROM (On-Device Production)
- **POS (Plane-Orthogonal-to-Skin):**
  Projects normalized temporal RGB color signals into two orthogonal chrominance signals:
  $$S_1 = G - B, \quad S_2 = G + B - 2R$$
  Combines them using dynamic projection based on alpha-tuning:
  $$h(t) = S_1 + \frac{\sigma(S_1)}{\sigma(S_2)} S_2$$
- **CHROM (Chrominance-based rPPG):**
  Applies bandpass filtering ($0.7 \text{ Hz} – 3.5 \text{ Hz}$, corresponding to 42–210 BPM) and Radix-2 256-point Fast Fourier Transform (FFT).
- **SNR Calculation:**
  $$\text{SNR}_{\text{rPPG}} = 10 \log_{10} \left( \frac{\int_{f_0 - 0.15}^{f_0 + 0.15} P(f)\,df + \int_{2f_0 - 0.15}^{2f_0 + 0.15} P(f)\,df}{\int_{0.7}^{3.5} P(f)\,df - \text{PeakPower}} \right)$$

#### 2.2 Learned Challenger: EfficientPhys (First Deep Learning rPPG Model)
- **Architecture:** Lightweight spatial-temporal CNN utilizing self-attention and depthwise separable convolutions to extract blood volume pulse (BVP) directly from normalized video frames.
- **Latency Target:** $< 8\text{ ms}$ per 30-frame window on Snapdragon 8 Elite Hexagon NPU.
- **Input:** $30 \times 72 \times 72 \times 3$ normalized facial skin frames.

#### 2.3 Sources & Repositories
1. **Wang, W. et al.**, *"Algorithmic Principles of Remote PPG"*, IEEE Transactions on Biomedical Engineering (TBME), 2017.  
   - DOI: [10.1109/TBME.2016.2609282](https://doi.org/10.1109/TBME.2016.2609282)
2. **de Haan, G. and Jeanne, V.**, *"Robust Pulse Rate from Chrominance-Based rPPG"*, IEEE TBME, 2013.  
   - DOI: [10.1109/TBME.2013.2266196](https://doi.org/10.1109/TBME.2013.2266196)
3. **Liu, X. et al.**, *"EfficientPhys: Enabling Simple, Fast and Accurate Camera-Based Cardiac Measurement"*, WACV 2023.  
   - Paper: [CVF Open Access](https://openaccess.thecvf.com/content/WACV2023/html/Liu_EfficientPhys_Enabling_Simple_Fast_and_Accurate_Camera-Based_Cardiac_Measurement_WACV_2023_paper.html)
4. **rPPG-Toolbox Repository:** [https://github.com/ubicomplab/rPPG-Toolbox](https://github.com/ubicomplab/rPPG-Toolbox)
5. **Authoritative Datasets:**
   - **UBFC-rPPG:** Univ. of Bourgogne Franche-Comté (Bobbia et al., 2019)
   - **PURE:** Pulse Rate from Eye-Tracking (Stricker et al., 2014)
   - **COHFACE:** Idiap Research Institute (Heusch et al., 2017)

---

### Track 3: Presentation Attack Detection (PAD) & Data Augmentation

#### 3.1 On-Device Multi-Layer Screen & Phone Attack Detection
- **Layer 1: 3D Facial Mesh Depth Planarity:**
  Computes the normalized variance of the Z-coordinates of the 468 mesh landmarks relative to the bounding box diagonal:
  $$\text{PlanarityScore} = \exp\left( - \frac{\sigma(Z)^2}{K \cdot (\text{width} \cdot \text{height})} \right)$$
  When a flat screen, iPad, or printed photo is presented to the camera, the 3D depth field collapses to a near-planar surface ($\text{PlanarityScore} > 0.70$).
- **Layer 2: Bezel & Edge Gradient Density:**
  Calculates Sobel edge gradients in the outer perimeter bounding box of the face. Physical phone and tablet frames produce high linear contrast edges.
- **Layer 3: Asynchronous ML Kit Object & Label Classifier:**
  Classifies electronic screens, cellular phones, monitors, and tablets. Runs completely asynchronously to maintain 30 FPS camera throughput.

#### 3.2 Lightweight Neural PAD: MobileNetV3-Spoof & FAS-Aug
- **MobileNetV3-Spoof:** Depthwise separable inverted bottleneck architecture trained on physical presentation attacks (screens, printed paper, cutouts, silicone masks).
- **FAS-Aug Physics-Based Recapture Augmentation:**
  Simulates screen glare, moiré pattern frequency aliasing, color gamut distortion, and printing halftone patterns onto genuine face videos to enable zero-shot generalization across unseen devices.

#### 3.3 Sources & Repositories
1. **Luevano, K. et al.**, *"Assessing the Performance of Efficient Face Anti-Spoofing Detection Against Physical and Digital Presentation Attacks"*, CVPR Workshops 2024.  
   - Paper: [CVPRW 2024](https://openaccess.thecvf.com/content/CVPR2024W/FAS2024/html/Luevano_Assessing_the_Performance_of_Efficient_Face_Anti-Spoofing_Detection_Against_Physical_CVPRW_2024_paper.html)  
   - Repository: [https://github.com/Inria-CENATAV-Tec/Assessing-Efficient-FAS-CVPR2024](https://github.com/Inria-CENATAV-Tec/Assessing-Efficient-FAS-CVPR2024)
2. **Cai, R. et al.**, *"Towards Data-Centric Face Anti-Spoofing: Improving Cross-domain Generalization via Physics-based Data Synthesis"*, arXiv:2409.03501, 2024.  
   - Paper: [https://arxiv.org/abs/2409.03501](https://arxiv.org/abs/2409.03501)  
   - Repository: [https://github.com/RizhaoCai/FAS_Aug](https://github.com/RizhaoCai/FAS_Aug)
3. **UniAttackData+ Challenge:** *"Unified Physical-Digital Attack Detection Challenge"*, ICCV Workshops 2025.  
   - Site: [https://sites.google.com/view/face-anti-spoofing-challenge/welcome/challengeiccv2025](https://sites.google.com/view/face-anti-spoofing-challenge/welcome/challengeiccv2025)
4. **CelebA-Spoof Dataset:** Zhang et al., ECCV 2020.  
   - Project: [https://mmlab.ie.cuhk.edu.hk/projects/CelebA/CelebA_Spoof.html](https://mmlab.ie.cuhk.edu.hk/projects/CelebA/CelebA_Spoof.html)

---

### Track 4: Optical Closed-Loop Reflectance Verification

#### 4.1 Physical Principle
A genuine 3D human face has distinctive sub-surface scattering and skin reflectance properties. When the smartphone display flashes a designated stimulus color (White, Warm Red-Shifted, or Cool Blue-Shifted) for $250\text{ ms} – 400\text{ ms}$:
1. The camera captures the temporal reflectance response $\Delta RGB(t)$ specifically across the validated anatomical skin ROIs (forehead and cheeks).
2. The observed shift must match the spectral signature:
   - Warm stimulus: $\Delta R > \Delta B$.
   - Cool stimulus: $\Delta B > \Delta R$.
3. The response latency must match the display-to-camera rolling shutter delay ($60\text{ ms} – 120\text{ ms}$).
4. Static 2D paper, unreactive remote replay video, or mask attacks fail to produce the synchronous closed-loop reflectance response.

#### 4.2 Sources & References
1. **Tang, D. et al.**, *"Face Presentation Attack Detection Using Flash Against Various Presentation Attacks"*, IEEE Transactions on Information Forensics and Security (TIFS), 2018.  
   - DOI: [10.1109/TIFS.2018.2834167](https://doi.org/10.1109/TIFS.2018.2834167)
2. **Chan, C. et al.**, *"Face Liveness Detection Using a Flash Against 2D and 3D Attacks"*, CVPR, 2018.  
   - Paper: [CVPR Open Access](https://openaccess.thecvf.com/content_cvpr_2018/html/Chan_Face_Liveness_Detection_CVPR_2018_paper.html)

---

### Track 5: Frozen 28-Feature Schema & Production Fusion Classifier

#### 5.1 Frozen 28-Feature Vector Contract (`edgeppg-1.0`)
All physiological, geometric, behavioral, optical, and hardware signals are assembled into an immutable 28-dimensional float array:

| Index | Feature Key | Physical Meaning | Normal Range | Sentinel |
|---|---|---|---|---|
| `[0]` | `face_confidence` | Measured detection confidence of face | $[0.0, 1.0]$ | `Float.NaN` |
| `[1]` | `face_quality` | Geometric illumination / sharpness quality | $[0.0, 1.0]$ | `Float.NaN` |
| `[2]` | `rppg_snr` | Pulse signal-to-noise ratio (POS/CHROM) | $[-10, +25]\text{ dB}$ | `Float.NaN` |
| `[3]` | `rppg_peak_strength` | Spectral peak power in cardiac band | $[0.0, 1.0]$ | `Float.NaN` |
| `[4]` | `rppg_hr_stability` | Temporal heart rate variance | $[0.0, 1.0]$ | `Float.NaN` |
| `[5]` | `rppg_roi_agreement` | Pearson correlation between forehead & cheeks | $[-1.0, 1.0]$ | `Float.NaN` |
| `[6]` | `gaze_accuracy` | Behavioral gaze challenge compliance rate | $[0.0, 1.0]$ | `Float.NaN` |
| `[7]` | `head_accuracy` | Behavioral head orientation compliance rate | $[0.0, 1.0]$ | `Float.NaN` |
| `[8]` | `hand_accuracy` | Auxiliary action compliance rate | $[0.0, 1.0]$ | `Float.NaN` |
| `[9]` | `challenge_timing_error` | Latency delta from instruction to action | $[0.1, 5.0]\text{ s}$ | `Float.NaN` |
| `[10]`| `optical_response_score` | Measured closed-loop flash response | $[0.0, 1.0]$ | `Float.NaN` |
| `[11]`| `camera_quality` | Camera sensor frame throughput score | $[0.0, 1.0]$ | `Float.NaN` |
| `[12]`| `frame_drop_rate` | Frame drop percentage during session | $[0.0, 1.0]$ | `Float.NaN` |
| `[13]`| `exposure_stability` | Camera auto-exposure convergence stability | $[0.0, 1.0]$ | `Float.NaN` |
| `[14]`| `awb_stability` | Camera auto-white-balance convergence stability | $[0.0, 1.0]$ | `Float.NaN` |
| `[15]`| `capture_duration` | Validated session duration | $[5.0, 30.0]\text{ s}$ | `Float.NaN` |
| `[16]`| `device_integrity` | Hardware attestation / Keymaster flag | $1.0\text{ (valid)}, 0.0$ | `0.0` |
| `[17–27]` | Reserved Biometrics | Cross-modal & environmental telemetry | Validated ranges | `Float.NaN` |

#### 5.2 On-Device Calibrated Tree Model (`NativeTreeMlClient.kt`)
- **Structure:** 300 Decision Trees parsed directly from `assets/edgeppg_rf_v1.json`.
- **Inference Runtime:** Pure Kotlin bytecode (Zero C++ JNI overhead, $< 0.1\text{ ms}$ evaluation time on Snapdragon 8 Elite CPU).
- **Sigmoid Calibration:** Converts raw voting fraction $\hat{y}$ to true calibrated Bayesian posterior probability:
  $$P(\text{LIVE} \mid \mathbf{x}) = \frac{1}{1 + \exp\left( - (a \cdot \hat{y} + b) \right)}, \quad a = 17.7023, \; b = -8.6535$$
- **Sources:**
  - Breiman, L., *"Random Forests"*, Machine Learning, 2001. [DOI: 10.1023/A:1010933404324](https://doi.org/10.1023/A:1010933404324)
  - Platt, J., *"Probabilistic Outputs for Support Vector Machines and Comparisons to Regularized Likelihood Methods"*, 1999.
  - Guo, C. et al., *"On Calibration of Modern Neural Networks"*, ICML 2017. [arXiv:1706.04599](https://arxiv.org/abs/1706.04599)

---

### Track 6: Advanced Multi-Modal Research Extensions (Offline / Post-Freeze)

#### 6.1 Environmental Forensics & Chroma Spill
- **Concept:** Detecting virtual background compositing, green-screen chroma keying, and digital edge halos in video streams.
- **Metrics:** Inner-to-outer face boundary chroma concentration, Laplacian texture variance discontinuity, temporal background stillness.
- **Reference:** Survey on Anti-Spoofing Methods with RGB Cameras, Journal of Imaging, 2020. [DOI: 10.3390/jimaging6120139](https://doi.org/10.3390/jimaging6120139)

#### 6.2 Acoustic FMCW Sonar Sensing
- **Concept:** Uses the phone's earpiece/speaker to emit inaudible Frequency-Modulated Continuous Wave (FMCW) chirps ($18\text{ kHz} – 22\text{ kHz}$) and the microphone to capture chest wall movements for breathing and heartbeat estimation.
- **Reference:** Chara, C. et al., *"Respiratory biofeedback using acoustic sensing with smartphones"*, Smart Health, 2023. [DOI: 10.1016/j.smhl.2023.100387](https://doi.org/10.1016/j.smhl.2023.100387)

#### 6.3 Vision-Language Models (VLM / CLIP) for Zero-Shot PAD
- **Concept:** Prompt-driven contrastive learning for detecting open-world physical and digital deepfakes.
- **Reference:** Chen et al., *"CrossGAP: Unified Face Anti-Spoofing via Cross-Modal Global-Aware Prompting"*, ICCV Workshops 2025.

---

## 4. Immediate Concrete Execution Sequence

To complete the ML pipeline and ensure full integration without compromising existing features:

```text
Phase 1: Security & Session Invariants (Immediate)
  ├── 1.1 Invalidate ROIs on multiple faces (meshes.size > 1) in RoiTracker.kt
  ├── 1.2 Decouple !hasFace in DecisionEngine.kt (no-face ALWAYS yields UNCERTAIN)
  ├── 1.3 Add RoiTracker.reset() and invoke at session start
  └── 1.4 Remove fabricated constant fallbacks (0.95f, 0.80f) in SessionController.kt

Phase 2: Camera Performance Optimization
  ├── 2.1 Make ML Kit ImageLabeler asynchronous in PhoneScreenDetector.kt
  └── 2.2 Reduce FaceMesh wait timeout to 35ms (eliminate 550ms analyzer stall)

Phase 3: FaceMesh 468-Landmark Action Tracking Integration
  ├── 3.1 Implement EAR (Blink) and MAR (Smile) in FaceActionTracker.kt
  └── 3.2 Wire FaceActionTracker into BehaviourRunner.kt to satisfy all active challenges

Phase 4: Optical Closed-Loop Reflectance Verification
  ├── 4.1 Record pre-flash baseline facial skin RGB in CameraSession.kt
  ├── 4.2 Compute normalized spectral shift ΔRGB during flash
  └── 4.3 Populate liveOpticalResponseScore with validated camera response

Phase 5: Full Regression Testing & Hardware Deployment
  ├── 5.1 Run Android JVM Unit Tests (./gradlew.bat testDebugUnitTest)
  ├── 5.2 Run Python ML Test Suite (python tests/run_all_tests.py)
  └── 5.3 Install & Verify Debug APK on Physical iQOO 15 (Snapdragon 8 Elite)
```
