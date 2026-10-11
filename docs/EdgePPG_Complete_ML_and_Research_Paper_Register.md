# EdgePPG --- Complete ML and Research Paper Register (Expanded)

**Purpose:** Corrected research reading list for EdgePPG, including ROI
selection and the research extensions discussed for rPPG, PAD,
environmental forensics, physics-based augmentation, lighting, PRNU,
acoustic sensing, CLIP/VLM, and edge deployment.

**Use:** Give this document to Antigravity as a source-discovery and
experiment-planning contract. It is not proof that any method works in
EdgePPG. Antigravity must verify each paper, official repository,
checkpoint, dataset, protocol, and license before implementation.

------------------------------------------------------------------------

## 1. Architecture constraints

Preserve:

``` text
Camera → Face/ROI → rPPG → Behaviour/Optical Challenge
→ Frozen 28 Features → Schema Validation/Preprocessing
→ Frozen ML → P(LIVE) → Deterministic Security Gates
→ LIVE/SPOOF/UNCERTAIN → Signed Transcript → PC Verifier
```

-   ML estimates `P(LIVE)`; deterministic gates decide the final
    outcome.
-   Do not silently alter the frozen 28-feature schema or zero-fill
    missing rPPG.
-   Keep research features outside the frozen production schema
    initially.
-   Do not claim a paper has been reproduced unless the implementation,
    checkpoint, preprocessing, data split, and evaluation protocol have
    been verified.
-   Keep published metrics, reproduced metrics, and EdgePPG/iQOO 15
    metrics separate.
-   Dataset access is not automatically commercial-use permission.
-   No research heuristic (green/blue boundary, texture variance, PRNU
    score, acoustic respiration, etc.) should directly force `SPOOF`
    without validated evidence and an approved schema/gate change.

## 2. ROI selection and skin-region extraction --- missing from the previous document

### 2.1 Primary ROI paper

**Kim, Lee, and Sohn, "Assessment of ROI Selection for Facial
Video-Based rPPG." Sensors, 2021.**

-   Paper: https://www.mdpi.com/1424-8220/21/23/7923
-   DOI: https://doi.org/10.3390/s21237923
-   Full text mirror: https://pmc.ncbi.nlm.nih.gov/articles/PMC8659899/
-   Why it matters: Evaluates 39 anatomically divided facial regions
    using several rPPG algorithms and datasets; directly relevant to
    deciding which facial areas to sample.
-   EdgePPG use: Compare forehead, left/right cheek, combined
    cheek/forehead, and broader face regions under the same
    capture/evaluation conditions.
-   Important limitation: The best region depends on the data and
    algorithm. Do not assume one ROI is universally optimal.

### 2.2 ROI systematic review

**"The role of face regions in remote photoplethysmography for
contactless heart rate monitoring." npj Digital Medicine, 2025.**

-   Paper: https://www.nature.com/articles/s41746-025-01814-9
-   PubMed Central: https://pmc.ncbi.nlm.nih.gov/articles/PMC12297079/
-   Why it matters: Reviews 70 studies and compares common face-region
    choices and ROI approaches.
-   EdgePPG use: Use as a review/map of ROI approaches and evidence, not
    as a model checkpoint or guarantee of target-device performance.

### 2.3 ROI implementation options to compare

These are engineering candidates, not all separate ML models:

1.  Face-landmark-guided forehead and cheek polygons.
2.  Landmark-guided multiple patches with per-patch quality scores.
3.  Skin-pixel masking inside valid face regions.
4.  Patch-based ROI selection.
5.  Superpixel/segmentation approaches if justified by measured gains.

Potential implementation resources: - MediaPipe:
https://github.com/google-ai-edge/mediapipe - OpenCV:
https://github.com/opencv/opencv

These repositories are implementation tools, not proof that a specific
ROI strategy is superior.

### 2.4 ROI experiment protocol

-   Keep the same video windows, pulse reference, signal processing, and
    evaluation splits when comparing ROIs.
-   Compare POS and CHROM on each candidate ROI.
-   Record valid-skin fraction, ROI area, tracking stability, motion
    sensitivity, and per-ROI signal quality.
-   Test glasses, facial hair, different skin tones, head motion, low
    light, and partial occlusion.
-   Prevent participant/session leakage.
-   Select the ROI strategy using validation data only; evaluate the
    final selection on held-out data.
-   Do not change the 28-feature schema merely to expose new ROI
    diagnostics. Store extra diagnostics in a research-only record until
    schema versioning is approved.

------------------------------------------------------------------------

## 3. rPPG signal extraction

### Papers

**Wang et al., "Algorithmic Principles of Remote PPG." IEEE Transactions
on Biomedical Engineering, 2017.** - DOI:
https://doi.org/10.1109/TBME.2016.2609282 - Role: POS/rPPG foundations;
deterministic baseline.

**de Haan & Jeanne, "Robust Pulse Rate from Chrominance-Based rPPG."
IEEE Transactions on Biomedical Engineering, 2013.** - DOI:
https://doi.org/10.1109/TBME.2013.2266196 - Record:
https://pubmed.ncbi.nlm.nih.gov/23744659/ - Role: CHROM baseline.

**Liu et al., "EfficientPhys: Enabling Simple, Fast and Accurate
Camera-Based Cardiac Measurement." WACV 2023.** - Paper:
https://openaccess.thecvf.com/content/WACV2023/html/Liu_EfficientPhys_Enabling_Simple_Fast_and_Accurate_Camera-Based_Cardiac_Measurement_WACV_2023_paper.html -
Role: First learned rPPG challenger.

**Liu et al., "Multi-Task Temporal Shift Attention Networks for
On-Device Contactless Vitals Measurement."** - Paper:
https://arxiv.org/abs/2006.03790 - Role: Optional temporal-shift
challenger.

**Chen & McDuff, "DeepPhys: Video-Based Physiological Measurement Using
Convolutional Attention Networks."** - Paper:
https://arxiv.org/abs/1805.07888 - Role: Optional learned challenger.

**Yu et al., "Remote Photoplethysmograph Signal Measurement from Facial
Videos Using Spatio-Temporal Networks" (PhysNet).** - Paper:
https://arxiv.org/abs/1905.02419 - Role: Optional spatiotemporal
benchmark.

**Yu et al., "PhysFormer: Facial Video-Based Physiological Measurement
With Temporal Difference Transformer." CVPR 2022.** - Paper:
https://openaccess.thecvf.com/content/CVPR2022/html/Yu_PhysFormer_Facial_Video-Based_Physiological_Measurement_With_Temporal_Difference_Transformer_CVPR_2022_paper.html -
Role: Defer until simpler methods are benchmarked.

### Repositories/frameworks

-   rPPG-Toolbox: https://github.com/ubicomplab/rPPG-Toolbox
-   Remote Biosensing rPPG: https://github.com/remotebiosensing/rppg
-   PhysBench_Archived:
    https://github.com/KegangWangCCNU/PhysBench_Archived

### Dataset candidates

Investigate UBFC-rPPG, PURE, COHFACE, and VIPL-HR. Locate the
authoritative dataset pages and verify data terms, reference signals,
frame rates, split protocols, and commercial-use restrictions. Do not
assume that all dataset protocols are interchangeable.

### Order

POS baseline → CHROM → EfficientPhys → optional
MTTS-CAN/DeepPhys/PhysNet → defer PhysFormer until justified.

Metrics should include HR MAE/RMSE against reference, valid-window
coverage, failure rate, correlation where appropriate, and performance
by motion/lighting/window length. rPPG accuracy is not liveness
accuracy.

------------------------------------------------------------------------

## 4. Face presentation-attack detection (PAD)

### Baseline

**Luevano et al., "Assessing the Performance of Efficient Face
Anti-Spoofing Detection Against Physical and Digital Presentation
Attacks." CVPR 2024 Workshops.** - Paper:
https://openaccess.thecvf.com/content/CVPR2024W/FAS2024/html/Luevano_Assessing_the_Performance_of_Efficient_Face_Anti-Spoofing_Detection_Against_Physical_CVPRW_2024_paper.html -
Repository:
https://github.com/Inria-CENATAV-Tec/Assessing-Efficient-FAS-CVPR2024 -
Role: First lightweight PAD reproduction candidate.

### Physical/digital attack augmentation

**He et al., "Joint Physical-Digital Facial Attack Detection Via
Simulating Spoofing Clues." CVPR 2024 Workshops.** - Paper:
https://openaccess.thecvf.com/content/CVPR2024W/FAS2024/html/He_Joint_Physical-Digital_Facial_Attack_Detection_Via_Simulating_Spoofing_Clues_CVPRW_2024_paper.html -
Repository:
https://github.com/Xianhua-He/cvpr2024-face-anti-spoofing-challenge -
Role: Research on simulated spoofing clues; audit code and protocol
before use.

### Physics-based recapture augmentation

**Cai et al., "Towards Data-Centric Face Anti-Spoofing: Improving
Cross-domain Generalization via Physics-based Data Synthesis." 2024.** -
Paper: https://arxiv.org/abs/2409.03501 - Repository:
https://github.com/RizhaoCai/FAS_Aug - Role: Investigate
recapture-inspired artifacts such as printing noise, colour distortion,
and moiré patterns. - Caveat: The authors discuss that a model can
over-rely on synthetic artifacts. Do not treat augmentation as
automatically beneficial; run ablations and test unseen domains.

### Domain generalization

**Zhou et al., "Test-Time Domain Generalization for Face Anti-Spoofing."
CVPR 2024.** - Paper:
https://openaccess.thecvf.com/content/CVPR2024/html/Zhou_Test-Time_Domain_Generalization_for_Face_Anti-Spoofing_CVPR_2024_paper.html

**Le et al., "Gradient Alignment for Cross-Domain Face Anti-Spoofing."
CVPR 2024.** - Paper:
https://openaccess.thecvf.com/content/CVPR2024/html/Le_Gradient_Alignment_for_Cross-Domain_Face_Anti-Spoofing_CVPR_2024_paper.html

**Costa-Pazo et al., "Face presentation attack detection. A
comprehensive evaluation of the generalisation problem." IET Biometrics,
2021.** - Paper:
https://ietresearch.onlinelibrary.wiley.com/doi/full/10.1049/bme2.12049 -
Role: Important protocol/generalization reference.

### Unified physical-digital attack work (newer research)

**Ma et al., "UniAttackData+: Unified Physical-Digital Attack Detection+
Challenge." ICCV Workshops 2025.** - Paper:
https://openaccess.thecvf.com/content/ICCV2025W/FAS2025/html/Ma_UniAttackData_Unified_Physical-Digital_Attack_Detection_Challenge_ICCVW_2025_paper.html -
Challenge page:
https://sites.google.com/view/face-anti-spoofing-challenge/welcome/challengeiccv2025?authuser=0 -
Role: Newer dataset/challenge reference covering many physical and
digital attack variants. Verify dataset access and terms.

**Balykin et al., "Paired-Sampling Contrastive Framework for Joint
Physical-Digital Face Attack Detection." ICCV Workshops 2025.** - Paper:
https://openaccess.thecvf.com/content/ICCV2025W/FAS2025/html/Balykin_Paired-Sampling_Contrastive_Framework_for_Joint_Physical-Digital_Face_Attack_Detection_ICCVW_2025_paper.html -
Repository with code/checkpoints as described by the paper:
https://github.com/xPONYx/iccv2025_deepfake_challenge - Role: Later
candidate for a unified physical/digital model. Audit its exact
requirements and license; do not assume it is lighter or better on iQOO
15 without measurement.

**Chen et al., "CrossGAP: Unified Face Anti-Spoofing via Cross-Modal
Global-Aware Prompting." ICCV Workshops 2025.** - Paper:
https://openaccess.thecvf.com/content/ICCV2025W/FAS2025/html/Chen_CrossGAP_Unified_Face_Anti-Spoofing_via_Cross-Modal_Global-Aware_Prompting_ICCVW_2025_paper.html -
Role: CLIP-based unified PAD research; offline/PC research candidate,
not the first phone model.

**Guo et al., "Spoofing-aware Prompt Learning for Unified
Physical-Digital Facial Attack Detection." ICCV Workshops 2025.** -
Paper:
https://openaccess.thecvf.com/content/ICCV2025W/FAS2025/html/Guo_Spoofing-aware_Prompt_Learning_for_Unified_Physical-Digital_Facial_Attack_Detection_ICCVW_2025_paper.html -
Role: Another CLIP/prompt-learning approach; compare with simpler PAD
baselines only after they are established.

### Datasets/challenges

-   CelebA-Spoof paper: https://arxiv.org/abs/2007.12342
-   CelebA-Spoof project:
    https://mmlab.ie.cuhk.edu.hk/projects/CelebA/CelebA_Spoof.html
-   CVPR 2024 challenge:
    https://sites.google.com/view/face-anti-spoofing-challenge/welcome/challengecvpr2024
-   ICCV 2025 challenge:
    https://sites.google.com/view/face-anti-spoofing-challenge/welcome/challengeiccv2025?authuser=0

For PAD, report APCER/BPCER/ACER with a stated protocol and threshold
method, plus per-attack and cross-domain results. Do not tune on the
final test set. Check commercial use rights before using any dataset for
KYC.

------------------------------------------------------------------------

## 5. Environmental forensics: virtual backgrounds, segmentation, chroma spill

### What this research track is

Investigate whether segmentation boundaries and foreground/background
statistics provide useful *additional evidence* for detecting virtual
backgrounds, compositing, or recapture artifacts. This is not a
validated detector merely because MediaPipe Selfie Segmentation and
OpenCV are available.

### Relevant foundations and sources

**Survey: "A Survey on Anti-Spoofing Methods for Facial Recognition with
RGB Cameras of Generic Consumer Devices." Journal of Imaging, 2020.** -
Paper: https://www.mdpi.com/2313-433X/6/12/139 - Full text:
https://pmc.ncbi.nlm.nih.gov/articles/PMC8321190/ - Role: Background
survey of RGB PAD methods and attack scenarios.

**Luevano et al., efficient PAD paper (physical and digital attacks),
CVPRW 2024.** - Paper:
https://openaccess.thecvf.com/content/CVPR2024W/FAS2024/html/Luevano_Assessing_the_Performance_of_Efficient_Face_Anti-Spoofing_Detection_Against_Physical_CVPRW_2024_paper.html -
Role: Use as a baseline against which to evaluate whether environmental
cues add measurable value.

**UniAttackData+ challenge paper, ICCVW 2025.** - Paper:
https://openaccess.thecvf.com/content/ICCV2025W/FAS2025/html/Ma_UniAttackData_Unified_Physical-Digital_Attack_Detection_Challenge_ICCVW_2025_paper.html -
Role: Dataset/challenge context for physical and digital attack
diversity.

### Implementation tools (not papers)

-   MediaPipe: https://github.com/google-ai-edge/mediapipe
-   OpenCV: https://github.com/opencv/opencv

### Candidate measurements to test

-   Foreground/background texture and high-frequency statistics.
-   Temporal background stability.
-   Boundary chroma concentration and spill-like cues.
-   Inner-face versus boundary colour differences.
-   Compression/recapture indicators.

**Do not** treat Laplacian variance as a direct sensor-noise
measurement. It is affected by texture, focus, edges, and scene content.
Do not auto-classify a person as spoofed merely because a green/blue hue
appears at a boundary; genuine clothing, walls, plants, LEDs, and
lighting can cause it.

### Dataset plan

Create a controlled, consented dataset with bona fide sessions and
documented virtual-background, green-screen, blue-screen, replay,
compression, low-light, coloured-lighting, flat-background, and
textured-background conditions. Use separate participants/sessions for
train and test. Public PAD datasets may not contain the exact
environmental-forensics labels needed; do not pretend that general PAD
labels directly supervise a boundary-spill detector.

Keep this track research-only and outside the frozen 28 features
initially.

------------------------------------------------------------------------

## 6. Lighting, reflection, and physical-cue research

This is a research family, not a single off-the-shelf model. The first
experiment should be to determine whether the phone's RGB camera and
capture conditions provide enough stable evidence.

### Relevant starting sources

**"Monocular Facial Presentation--Attack--Detection: Classifying
Near-Infrared Reflectance Patterns." Applied Sciences, 2023.** - Paper:
https://www.mdpi.com/2076-3417/13/3/1987 - Role: Example of a
physics/reflectance-based approach to PAD. - Limitation: It concerns
near-infrared reflectance. EdgePPG's ordinary RGB camera cannot be
assumed to reproduce its sensing conditions. Use it for physical-model
ideas, not as a drop-in RGB method.

**"Presentation attack detection for face recognition using light field
camera." IEEE Transactions on Image Processing, 2015.** - DOI:
https://doi.org/10.1109/TIP.2015.2395951 - Record:
https://pubmed.ncbi.nlm.nih.gov/25622320/ - Role: Demonstrates how
richer light-field/focus information can reveal presentation attacks. -
Limitation: A standard phone RGB camera does not provide the same
plenoptic measurements.

### Proposed EdgePPG experiments

-   Test illumination consistency across face regions and across
    controlled challenge motions.
-   Test whether specular highlights move consistently with head motion
    and illumination.
-   Compare genuine, print, replay, virtual background, and recapture
    examples.
-   Record lighting, camera exposure, white balance, and motion
    confounders.
-   Do not call a simple colour/brightness mismatch a spoof verdict.
-   Do not claim reproduction of NIR/light-field techniques using
    RGB-only data.

A specific, validated RGB-only lighting-forensics paper/method must be
identified and verified before coding a faithful reproduction. Until
then, label this a hypothesis-driven experiment rather than a
paper-reproduced module.

------------------------------------------------------------------------

## 7. PRNU and sensor provenance

### Papers

**"PRNU Based Source Camera Identification for Webcam Videos."** -
Paper: https://arxiv.org/abs/2107.01885 - Role: Investigates sensor
fingerprinting for webcam video.

**"PRNU Based Source Camera Identification for Webcam and Smartphone
Videos."** - Paper: https://arxiv.org/abs/2201.11737 - Role:
Investigates PRNU-based source-camera identification for webcam and
smartphone video.

**"Factors that Influence PRNU-Based Camera-Identification via Videos."
Journal of Imaging, 2021.** - Paper:
https://www.mdpi.com/2313-433X/7/1/8 - DOI:
https://doi.org/10.3390/jimaging7010008 - Role: Studies factors that
influence video PRNU reliability.

**"Video source camera identification using fusion of texture features
and noise fingerprint." Forensic Science International: Digital
Investigation, 2024.** - Paper:
https://www.sciencedirect.com/science/article/abs/pii/S2666281724000659 -
DOI: https://doi.org/10.1016/j.fsidi.2024.301746 - Role: Studies
source-camera identification using PRNU plus texture cues.

### EdgePPG interpretation and constraints

PRNU primarily concerns camera-source attribution/forensic consistency.
It is not automatically a live-person detector, and PRNU can be weakened
or altered by stabilization, denoising, resizing, compression, and other
camera-pipeline operations.

For research: - Collect reference footage/images from the target iQOO 15
under multiple exposure, lighting, and camera settings. - Test genuine
camera output against replay/recapture and processed video. - Measure
false mismatch on genuine videos. - Test stability across resolutions,
frame rates, and compression. - Keep results as forensic evidence; do
not treat a PRNU mismatch alone as conclusive spoof proof. - Do not make
per-frame signing or PRNU a production dependency before measuring cost
and reliability.

------------------------------------------------------------------------

## 8. Acoustic physiological sensing --- separate modality

**Important:** Traditional rPPG is optical. A phone microphone does not
directly measure optical rPPG. Acoustic sensing is a separate research
modality.

### Papers

**Chara et al., "Respiratory biofeedback using acoustic sensing with
smartphones." Smart Health, 2023.** - Paper:
https://www.sciencedirect.com/science/article/pii/S2352648323000156 -
DOI: https://doi.org/10.1016/j.smhl.2023.100387 - Role: Single-phone
FMCW acoustic sonar for respiratory measurements. Uses the phone speaker
and microphone to transmit/receive acoustic reflections. - Reported
outputs include breathing rate/pattern, apnea-related events, and
inhalation/exhalation timing. Verify details in the full paper.

**"Smartphone-Based Acoustic Sensing for Breathing and Heartbeat
Detection via AoA Clustering in Indoor Environments." Sensors, 2026.** -
Paper: https://www.mdpi.com/1424-8220/26/14/4591 - PubMed:
https://pubmed.ncbi.nlm.nih.gov/42515474/ - Role: Newer FMCW acoustic
approach using range/angle/time processing for breathing and heartbeat
estimation. - Caveat: It uses a specific speaker/microphone
configuration and signal-processing pipeline. It is not a drop-in
ordinary-microphone model.

**"Smartphone Acoustic Sensing for Contactless Respiration Monitoring
and Gesture-Based Authentication." Sensors, 2026.** - Paper:
https://www.mdpi.com/1424-8220/26/17/5512 - DOI:
https://doi.org/10.3390/s26175512 - Role: Acoustic respiration sensing
and gesture-related research.

### EdgePPG constraints

-   Keep acoustic sensing out of the initial production path.
-   First test speaker/microphone access, frequency response, audio
    routing, echo cancellation, and device restrictions on the actual
    phone.
-   Treat respiration/heartbeat estimates as auxiliary research
    evidence, not proof of liveness.
-   A replay can contain plausible breathing sounds; the method must be
    evaluated against replay and other adversarial cases.
-   Do not add acoustic values to the frozen 28-feature schema without a
    versioned design and measured benefit.

------------------------------------------------------------------------

## 9. CLIP / vision-language models for unified physical-digital PAD

These papers are relevant to your proposed CLIP/VLM research, but larger
vision-language models are not the first on-device choice.

**Chen et al., "CrossGAP: Unified Face Anti-Spoofing via Cross-Modal
Global-Aware Prompting." ICCV Workshops 2025.** - Paper:
https://openaccess.thecvf.com/content/ICCV2025W/FAS2025/html/Chen_CrossGAP_Unified_Face_Anti-Spoofing_via_Cross-Modal_Global-Aware_Prompting_ICCVW_2025_paper.html -
Role: CLIP-based unified face anti-spoofing research.

**Guo et al., "Spoofing-aware Prompt Learning for Unified
Physical-Digital Facial Attack Detection." ICCV Workshops 2025.** -
Paper:
https://openaccess.thecvf.com/content/ICCV2025W/FAS2025/html/Guo_Spoofing-aware_Prompt_Learning_for_Unified_Physical-Digital_Facial_Attack_Detection_ICCVW_2025_paper.html -
Role: Prompt-learning approach for physical and digital attacks.

**Zhang et al., "A Generalizable Face Security Detection Method via
Unified Texture and Semantic Feature Framework." ICCV Workshops
2025.** - Paper:
https://openaccess.thecvf.com/content/ICCV2025W/FAS2025/html/Zhang_A_Generalizable_Face_Security_Detection_Method_via_Unified_Texture_and_ICCVW_2025_paper.html -
Role: Combines texture and semantic features; uses CLIP-derived
semantics as part of a unified approach.

**OpenAI CLIP repository:** https://github.com/openai/CLIP\
**OpenCLIP repository:** https://github.com/mlfoundations/open_clip

### Recommended use

-   First use these as offline research challengers.
-   Check exact model weights, licenses, input resolution,
    compute/memory, and reproducibility.
-   Compare with lightweight PAD on identical splits and attack
    categories.
-   Do not use a VLM's natural-language answer as the final security
    decision.
-   Do not add a large VLM to the phone merely because a paper reports
    strong results.

------------------------------------------------------------------------

## 10. Edge inference and NPU acceleration

This is primarily a deployment/engineering track, not a dataset-training
track.

### Official resources

-   LiteRT overview: https://developers.google.com/edge/litert
-   LiteRT NPU delegates: https://ai.google.dev/edge/litert/android/npu
-   Qualcomm AI Engine Direct/LiteRT delegate:
    https://developers.google.cn/edge/litert/android/npu/qualcomm
-   LiteRT Qualcomm integration:
    https://github.com/google-ai-edge/LiteRT/tree/main/litert/vendors/qualcomm

### Required experiments

-   Establish CPU inference as the reference.
-   Verify whether the actual iQOO 15 chipset/runtime is supported by
    the selected delegate.
-   Compare CPU, GPU, and supported NPU execution using the same
    checkpoint and inputs.
-   Check numerical agreement, unsupported operators, initialization
    cost, steady-state latency, memory, energy/thermal behavior, and
    fallback behavior.
-   Record actual backend logs; do not infer NPU use from a dependency
    being installed.
-   Do not force POS/FFT/DSP operations onto the NPU; use acceleration
    where the workload and API fit.

------------------------------------------------------------------------

## 11. Classical fusion model on the frozen 28 features

**Breiman, "Random Forests." Machine Learning, 2001.** - DOI:
https://doi.org/10.1023/A:1010933404324 - scikit-learn:
https://scikit-learn.org/stable/modules/ensemble.html#forest

**Chen & Guestrin, "XGBoost: A Scalable Tree Boosting System." KDD
2016.** - Paper: https://arxiv.org/abs/1603.02754 - Documentation:
https://xgboost.readthedocs.io/

**Guo et al., "On Calibration of Modern Neural Networks." ICML 2017.** -
Paper: https://arxiv.org/abs/1706.04599 - Use as background; evaluate
calibration on the actual selected classifier.

**Lundberg & Lee, "A Unified Approach to Interpreting Model
Predictions." NeurIPS 2017.** - Paper:
https://arxiv.org/abs/1705.07874 - SHAP: https://github.com/shap/shap

Training flow:

``` text
Labelled recordings + trustworthy labels
→ Real EdgePPG pipeline
→ Versioned validated 28-feature records
→ Participant/session/attack-aware splits
→ Random Forest baseline
→ XGBoost challenger
→ Validation/calibration
→ Frozen model artifact
```

Do not fabricate features or labels. Do not randomly split overlapping
windows from one recording across train and test. Audit legacy
trusted/cross-person fields before training, but do not silently change
the frozen schema.

------------------------------------------------------------------------

## 12. Dataset and leakage checklist

For each dataset, document: - authoritative page and paper; - version
and access date; - license and intended-use permission; - labels and
ground-truth source; - participants, sessions, devices, and attack
metadata; - official split protocol; - preprocessing and frame rate; -
whether the dataset supports the exact target feature/task; -
subject/session overlap with other datasets; - any access restrictions
or limitations.

Avoid leakage from: - the same person across train/test when
subject-independent evaluation is intended; - the same recording or
overlapping windows across splits; - duplicate or near-duplicate
videos; - crops derived from the same source video; - threshold/model
selection on the final test set.

For KYC use, explicitly verify commercial rights. A research-only
license is not sufficient.

------------------------------------------------------------------------

## 13. Research module priority and status

  -------------------------------------------------------------------------------
  Module                          Initial priority        Initial treatment
  ------------------------------- ----------------------- -----------------------
  Face/ROI selection              P0                      Evaluate
                                                          landmark-guided
                                                          forehead/cheek regions
                                                          against alternatives

  POS/CHROM                       P0                      Reproducible
                                                          deterministic baseline

  EfficientPhys                   P1                      First learned rPPG
                                                          challenger

  MobileNetV3-Spoof               P1                      First lightweight PAD
                                                          reproduction candidate,
                                                          subject to data/license

  Random Forest / XGBoost         P1                      Train on actual
                                                          labelled EdgePPG
                                                          feature records

  FAS-Aug physics-based recapture P2                      Controlled ablation
  augmentation                                            after baseline PAD

  Cross-domain PAD                P2                      Evaluate on held-out
                                                          domains/attacks

  Environmental                   P2                      Research-only feature
  segmentation/chroma/texture                             investigation
  forensics                                               

  Lighting/specular/reflectance   P2                      Hypothesis-driven
  cues                                                    controlled experiment

  PRNU provenance                 P2/P3                   Offline forensic
                                                          research; high
                                                          capture-pipeline
                                                          sensitivity

  CLIP/VLM PAD                    P3                      Offline challenger;
                                                          deployment cost must be
                                                          justified

  Acoustic respiration/heartbeat  P3                      Separate modality;
                                                          hardware and spoof
                                                          tests required

  NPU acceleration                After model selection   Deployment benchmark,
                                                          not a reason to choose
                                                          a model by itself

  NIR/light-field-specific        Deferred                Hardware/sensor
  methods                                                 mismatch unless
                                                          compatible sensor data
                                                          exists
  -------------------------------------------------------------------------------

P0/P1/P2/P3 are proposed project priorities, not claims about scientific
importance.

------------------------------------------------------------------------

## 14. Required Antigravity workflow

For each paper/model:

1.  Verify exact title, authors, year, official paper URL, repository
    owner, and repository commit.
2.  Verify dataset, labels, split protocol, access terms, and
    intended-use license.
3.  Verify whether official training code and checkpoints actually
    exist.
4.  Inspect the current EdgePPG code before replacing anything.
5.  Reproduce the paper's method as closely as available materials
    allow; record deviations.
6.  Keep a baseline and run controlled ablations.
7.  Separate published metrics, reproduced metrics, and EdgePPG/iQOO 15
    metrics.
8.  Use participant/session/attack-aware splits where possible.
9.  Test the target phone and record latency, memory, CPU/GPU/NPU
    backend, frame drops, and thermal effects.
10. Preserve the production schema and deterministic security gates.
11. Mark each item UNVERIFIED, SOURCE-AUDITED, REPRODUCED, ADAPTED,
    EDGE-VALIDATED, or REJECTED.
12. Do not fabricate results or report a module as complete until the
    required tests have run.

### Required records

Create: - `RESEARCH_SOURCE_REGISTER.md` - `DATASET_PROVENANCE.md` -
`ROI_EXPERIMENT_PLAN.md` - `RPPG_EXPERIMENT_PLAN.md` -
`PAD_EXPERIMENT_PLAN.md` - `ENVIRONMENTAL_FORENSICS_PLAN.md` -
`LIGHTING_AND_REFLECTANCE_PLAN.md` - `PRNU_PROVENANCE_PLAN.md` -
`ACOUSTIC_SENSING_PLAN.md` - `CLIP_VLM_EXPERIMENT_PLAN.md` -
`EDGE_DEPLOYMENT_VALIDATION.md` - `EXPERIMENT_RESULTS.md`

**Start with ROI + POS/CHROM evaluation, then EfficientPhys,
MobileNetV3-Spoof, and Random Forest/XGBoost. Do not implement every
research extension at once.**
