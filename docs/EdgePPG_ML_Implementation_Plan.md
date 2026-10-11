# EdgePPG — ML Implementation Plan

> **Purpose:** Implementation and experiment plan for the ML-related components of EdgePPG.
>
> This document converts the research/source register into an actionable ML workflow. It does **not** replace `Architecture.md`, and it does not redefine the frozen 28-feature schema.
>
> **Important:** The source register identifies candidate methods and their roles, but does not provide enough verified information to invent final EdgePPG hyperparameters. Parameters that are not established by the source material are explicitly marked **TBD / SOURCE-AUDIT REQUIRED**.

---

# 1. Relationship to the EdgePPG Architecture

The production architecture remains:

```text
Camera
  ↓
Face / ROI
  ↓
rPPG
  ↓
Behaviour / Optical Challenge
  ↓
Frozen 28 Features
  ↓
Schema Validation / Preprocessing
  ↓
ML
  ↓
P(LIVE)
  ↓
Deterministic Security Gates
  ↓
LIVE / SPOOF / UNCERTAIN
  ↓
Signed Transcript
  ↓
PC Verifier
```

The ML implementation plan concerns the stages that generate/evaluate ML-related evidence.

The architectural rule remains:

```text
ML → P(LIVE)
```

The deterministic security gates remain responsible for the final:

```text
LIVE / SPOOF / UNCERTAIN
```

The ML implementation must not silently change that boundary.

---

# 2. ML Workstreams

The project should not train every candidate simultaneously.

The source register proposes the following progression:

```text
P0:
    ROI evaluation
    POS
    CHROM

P1:
    EfficientPhys
    Lightweight PAD
    Random Forest
    XGBoost

P2:
    Physics-based augmentation
    Cross-domain PAD
    Environmental forensics
    Lighting / reflectance

P2/P3:
    PRNU

P3:
    CLIP/VLM
    Acoustic sensing

After model selection:
    NPU / edge deployment benchmarking
```

This ordering is a project priority, not a claim about scientific importance.

---

# 3. ML Role Map

| Component | Role | Input | Output | Initial Status |
|---|---|---|---|---|
| ROI selection | Signal-quality / region experiment | Face frames/landmarks | ROI | P0 |
| POS | Deterministic rPPG baseline | ROI video | rPPG signal/features | P0 |
| CHROM | Deterministic rPPG baseline | ROI video | rPPG signal/features | P0 |
| EfficientPhys | Learned rPPG challenger | Facial video | Physiological estimate/features | P1 |
| Lightweight PAD | Face attack detection candidate | Face/frame/video representation | PAD evidence/score | P1 |
| Random Forest | Frozen-28-feature baseline | 28 features | Liveness score/probability | P1 |
| XGBoost | Frozen-28-feature challenger | 28 features | Liveness score/probability | P1 |
| Calibration | Probability calibration | Validation predictions | Calibrated score | P1 |
| Physics augmentation | PAD training experiment | Training data | Augmented training set | P2 |
| Cross-domain PAD | Generalization experiment | PAD data | PAD score | P2 |
| Environmental forensics | Research evidence | Camera/environment data | Research features | P2 |
| Lighting/reflectance | Research evidence | RGB capture | Research features | P2 |
| PRNU | Camera-source forensic evidence | Camera video | Provenance evidence | P2/P3 |
| CLIP/VLM | Offline PAD challenger | Face/image/video | Research PAD evidence | P3 |
| Acoustic sensing | Separate modality | Audio/sensor signal | Respiration/heartbeat evidence | P3 |
| NPU | Deployment optimization | Selected model | Runtime backend | Post-selection |

The source register explicitly identifies POS/CHROM as the initial deterministic baseline, EfficientPhys as the first learned rPPG challenger, MobileNetV3-Spoof as the first lightweight PAD reproduction candidate, and RF/XGBoost as models to train on actual labelled EdgePPG feature records. fileciteturn40file0L609-L620

---

# 4. Global Experiment Rules

Every ML experiment must follow these rules.

## 4.1 No fabricated data

Do not fabricate:

- Labels
- Features
- Missing rPPG values
- Ground-truth measurements
- Test results

The source register explicitly prohibits fabricated features/labels. fileciteturn40file0L571-L574

## 4.2 Prevent data leakage

Do not randomly split overlapping windows from the same recording across train/test.

Avoid:

- Same participant across train/test when subject-independent evaluation is intended
- Same recording across splits
- Overlapping windows across splits
- Duplicate/near-duplicate videos
- Crops from the same source video across splits
- Test-set threshold/model selection

These leakage controls are explicitly required by the source register. fileciteturn40file0L578-L592

## 4.3 Keep metrics separate

Record separately:

```text
Published paper metrics
Reproduced metrics
EdgePPG metrics
iQOO 15 runtime metrics
```

Do not present one as another. fileciteturn40file0L32-L36

## 4.4 Preserve the production schema

Do not silently change the frozen 28-feature schema to accommodate a research experiment.

Research-only diagnostics remain outside the production schema until an explicit schema change is approved. fileciteturn40file0L28-L31

---

# 5. Workstream A — ROI Selection

## Objective

Determine which facial regions provide suitable input for the rPPG pipeline.

## Candidate ROI strategies

The source register lists:

1. Landmark-guided forehead and cheek polygons
2. Multiple landmark-guided patches with quality scores
3. Skin-pixel masking
4. Patch-based ROI selection
5. Superpixel/segmentation approaches where justified

These are engineering candidates rather than separate ML models. fileciteturn40file0L73-L87

## Initial experiment

Compare:

```text
Forehead
Left cheek
Right cheek
Combined cheeks
Combined forehead + cheeks
Broader face regions
```

using the same:

- Video windows
- Pulse reference
- Signal processing
- Evaluation splits

Then compare POS and CHROM on each ROI. fileciteturn40file0L90-L101

## Record

- Valid-skin fraction
- ROI area
- Tracking stability
- Motion sensitivity
- Signal quality
- HR error
- Failure rate
- Behaviour under glasses
- Facial hair
- Skin-tone variation
- Head motion
- Low light
- Partial occlusion

## Selection rule

Select the ROI strategy using validation data.

Final evaluation must use held-out data.

Do not modify the 28-feature schema merely to expose additional ROI diagnostics. fileciteturn40file0L95-L103

---

# 6. Workstream B — POS rPPG Baseline

## Purpose

Provide the first deterministic rPPG baseline.

Source role:

```text
POS = rPPG foundation / deterministic baseline
```

The source register identifies Wang et al., “Algorithmic Principles of Remote PPG” as the relevant foundation. fileciteturn40file0L112-L115

## Input

```text
Face ROI video
```

## Processing

The exact implementation should be audited against the source method.

Do not invent deviations and call them a reproduction.

## Output

```text
rPPG signal
↓
Downstream physiological features
```

## Parameters

The final EdgePPG parameters must be recorded explicitly.

Required parameter record:

```text
Sampling/frame-rate assumptions: TBD / source-audit
Window length: TBD / source-audit
Window overlap/stride: TBD / source-audit
Preprocessing: TBD / source-audit
Detrending/filtering: TBD / source-audit
Frequency range: TBD / source-audit
Normalization: TBD / source-audit
Signal-quality criteria: TBD
```

These values must be established from the verified implementation/source and actual EdgePPG experiment rather than invented.

---

# 7. Workstream C — CHROM rPPG Baseline

## Purpose

Provide a second deterministic chrominance-based rPPG baseline.

The source register identifies de Haan & Jeanne as the CHROM baseline. fileciteturn40file0L117-L120

## Input

```text
Face ROI video
```

## Output

```text
rPPG signal
↓
Downstream physiological features
```

## Experiment

Run CHROM under the same ROI, recordings, windows, reference signals, and splits used for POS.

This makes the comparison meaningful.

## Parameters

Record and source-audit:

```text
Window length: TBD
Overlap/stride: TBD
Chrominance transformation: source-audit
Normalization: source-audit
Frequency band: TBD / source-audit
Filtering: TBD / source-audit
Quality criteria: TBD
```

---

# 8. Workstream D — EfficientPhys

## Purpose

EfficientPhys is the first learned rPPG challenger.

The source register identifies it as the first learned rPPG candidate. fileciteturn40file0L122-L125

## Input

Facial video / ROI representation as required by the verified implementation.

## Output

A learned physiological estimate that can be evaluated against the deterministic POS/CHROM baselines.

## Implementation procedure

1. Verify the paper.
2. Verify the official repository.
3. Verify checkpoint availability.
4. Verify preprocessing.
5. Verify input dimensions.
6. Verify dataset/split protocol.
7. Reproduce the source method as closely as available materials allow.
8. Record every deviation.
9. Compare against POS and CHROM.
10. Only then investigate EdgePPG adaptation.

## Parameters

Do not invent final values.

Record:

```text
Input resolution: SOURCE-AUDIT REQUIRED
Temporal length: SOURCE-AUDIT REQUIRED
Batch size: SOURCE-AUDIT REQUIRED
Optimizer: SOURCE-AUDIT REQUIRED
Learning rate: SOURCE-AUDIT REQUIRED
Scheduler: SOURCE-AUDIT REQUIRED
Weight decay: SOURCE-AUDIT REQUIRED
Epochs: SOURCE-AUDIT REQUIRED
Loss: SOURCE-AUDIT REQUIRED
Augmentation: SOURCE-AUDIT REQUIRED
Checkpoint: SOURCE-AUDIT REQUIRED
```

## Important

rPPG accuracy is not equivalent to liveness accuracy.

The source register explicitly separates these metrics. fileciteturn40file0L165-L167

---

# 9. Workstream E — Lightweight PAD

## Purpose

Provide the first learned face presentation-attack detection candidate.

The source register identifies the Luevano et al. CVPRW 2024 work as the first lightweight PAD reproduction candidate. fileciteturn40file0L176-L182

## Input

To be established from the verified reproduction:

```text
Face image / face sequence / required representation
```

## Output

PAD evidence/score.

The exact output interpretation must be verified from the source implementation before integration.

## Training procedure

1. Verify paper.
2. Verify repository.
3. Verify checkpoint.
4. Verify datasets and labels.
5. Verify attack categories.
6. Verify preprocessing.
7. Verify split protocol.
8. Reproduce baseline.
9. Evaluate cross-domain/attack behavior.
10. Only then consider EdgePPG adaptation.

## Required evaluation

Record:

- APCER
- BPCER
- ACER
- Per-attack results
- Cross-domain results
- Threshold-selection procedure

The source register explicitly calls for these PAD metrics with a stated protocol. fileciteturn40file0L263-L265

## Parameters

Final values:

```text
Input size: SOURCE-AUDIT REQUIRED
Frame sampling: SOURCE-AUDIT REQUIRED
Architecture configuration: SOURCE-AUDIT REQUIRED
Optimizer: SOURCE-AUDIT REQUIRED
Learning rate: SOURCE-AUDIT REQUIRED
Batch size: SOURCE-AUDIT REQUIRED
Epochs: SOURCE-AUDIT REQUIRED
Augmentation: SOURCE-AUDIT REQUIRED
Loss: SOURCE-AUDIT REQUIRED
Checkpoint: SOURCE-AUDIT REQUIRED
```

Do not claim these are paper values until verified.

---

# 10. Workstream F — Random Forest

## Purpose

Provide a classical baseline on the frozen 28-feature representation.

The source register identifies Random Forest as the baseline for actual labelled EdgePPG feature records. fileciteturn40file0L619-L620

## Input

```text
Validated 28-feature record
```

## Training flow

```text
Labelled recordings
       ↓
Real EdgePPG pipeline
       ↓
Versioned validated 28-feature records
       ↓
Participant/session/attack-aware splits
       ↓
Random Forest
       ↓
Validation
       ↓
Calibration if required
       ↓
Frozen model artifact
```

This follows the training flow in the source register. fileciteturn40file0L558-L568

## Parameters

The final EdgePPG configuration must be explicitly recorded:

```text
n_estimators: TBD
max_depth: TBD
min_samples_split: TBD
min_samples_leaf: TBD
max_features: TBD
class_weight: TBD
random_state: TBD
criterion: TBD
```

These are configuration fields to record, not invented final values.

## Output

```text
P(LIVE)
```

or the model score that is subsequently calibrated/converted according to the selected implementation.

---

# 11. Workstream G — XGBoost

## Purpose

XGBoost is the challenger to the Random Forest baseline on the same frozen 28-feature records.

The source register identifies XGBoost as the challenger. fileciteturn40file0L619-L620

## Input

```text
Validated 28-feature record
```

## Training flow

```text
Same records
Same split protocol
Same feature schema
        ↓
XGBoost
        ↓
Validation
        ↓
Calibration if required
        ↓
Frozen artifact
```

Do not give XGBoost an easier/different dataset than Random Forest.

## Parameters to record

```text
n_estimators: TBD
max_depth: TBD
learning_rate: TBD
subsample: TBD
colsample_bytree: TBD
min_child_weight: TBD
gamma: TBD
reg_alpha: TBD
reg_lambda: TBD
random_state: TBD
early_stopping configuration: TBD
```

Again, these are required configuration records, not fabricated recommended values.

---

# 12. Workstream H — Calibration

The source register includes calibration as part of the RF/XGBoost workflow and references Guo et al. as background. fileciteturn40file0L550-L552

The implementation must establish whether the selected model's raw output should be calibrated before being represented as `P(LIVE)`.

Record:

```text
Calibration method: TBD
Calibration dataset: validation only
Calibration parameters: TBD
Calibration version: TBD
```

Do not calibrate on the final test set.

---

# 13. Workstream I — Physics-Based PAD Augmentation

## Purpose

Investigate whether recapture-inspired synthetic effects improve PAD generalization.

The source register identifies Cai et al. as the relevant physics-based synthesis candidate and specifically warns that models may over-rely on synthetic artifacts. fileciteturn40file0L194-L203

## Procedure

```text
Baseline PAD
     ↓
Controlled augmentation
     ↓
Ablation
     ↓
Unseen-domain evaluation
```

Compare:

```text
Baseline
vs
Baseline + augmentation
```

Keep everything else fixed.

Do not automatically incorporate the augmentation into production training.

---

# 14. Workstream J — Cross-Domain PAD

The project should evaluate whether a PAD model generalizes beyond its training domain.

Candidate research includes:

- Test-Time Domain Generalization
- Gradient Alignment
- Generalization evaluation

These are research candidates listed in the source register. fileciteturn40file0L205-L218

Required evaluation:

```text
Train domain
     ↓
Held-out domain
     ↓
Per-attack evaluation
```

Do not claim generalization based only on an in-domain test split.

---

# 15. Workstream K — Environmental Forensics

This remains research-only initially.

Candidate evidence includes:

- Foreground/background texture
- High-frequency statistics
- Temporal background stability
- Boundary chroma/spill cues
- Inner-face vs boundary colour
- Compression/recapture indicators

The source explicitly says these are candidate measurements, not a validated detector. fileciteturn40file0L270-L310

Do not:

```text
green/blue boundary → SPOOF
```

and do not treat Laplacian variance as direct sensor-noise measurement. fileciteturn40file0L312-L316

Keep these features outside the frozen 28-feature production path initially.

---

# 16. Workstream L — Lighting / Reflectance

This is a hypothesis-driven research experiment rather than an established production model.

Test:

- Illumination consistency
- Specular highlight behavior
- Genuine vs print
- Genuine vs replay
- Genuine vs virtual background
- Genuine vs recapture

Record camera exposure, white balance, and motion confounders.

The source explicitly says not to claim reproduction of NIR/light-field methods using ordinary RGB phone data. fileciteturn40file0L333-L373

---

# 17. Workstream M — PRNU

PRNU is a source-camera attribution/forensic experiment, not automatically a liveness model.

The source register describes its sensitivity to stabilization, denoising, resizing, compression, and other camera-pipeline operations. fileciteturn40file0L404-L417

Keep it:

```text
Offline / research
```

until reliability and cost are established.

Do not allow a PRNU mismatch by itself to force:

```text
SPOOF
```

---

# 18. Workstream N — CLIP / VLM

Candidate research includes:

- CrossGAP
- Spoofing-aware Prompt Learning
- Unified texture/semantic approaches

The source register recommends these initially as offline challengers. fileciteturn40file0L469-L503

Requirements:

- Verify exact weights
- Verify license
- Verify input resolution
- Measure compute/memory
- Establish reproducibility
- Compare against lightweight PAD
- Do not use natural-language output as the final security decision

---

# 19. Workstream O — Acoustic Sensing

Acoustic sensing is separate from optical rPPG.

It requires its own:

```text
Sensor
→ Signal processing
→ Feature extraction
→ Research evaluation
```

The source register explicitly recommends keeping acoustic sensing outside the initial production path and testing speaker/microphone access, frequency response, routing, echo cancellation, and device restrictions on the actual phone. fileciteturn40file0L422-L465

---

# 20. Workstream P — Edge / NPU Deployment

NPU work occurs after model selection.

Required sequence:

```text
Selected model
      ↓
CPU reference
      ↓
GPU where applicable
      ↓
Supported NPU
      ↓
Compare
```

Measure:

- Numerical agreement
- Unsupported operators
- Initialization cost
- Steady-state latency
- Memory
- Energy/thermal behavior
- Fallback behavior
- Actual backend logs

The source register explicitly states that installed dependencies do not prove NPU execution. fileciteturn40file0L509-L535

---

# 21. Training Dataset Contract

Every training dataset must have a record containing:

```text
Dataset name
Authoritative source
Version
Access date
License
Intended-use permission
Labels
Ground-truth source
Participants
Sessions
Devices
Attack metadata
Official split protocol
Preprocessing
Frame rate
Target task compatibility
Subject/session overlap
Restrictions
```

This follows the dataset checklist in the source register. fileciteturn40file0L578-L595

For KYC use, commercial rights must be explicitly verified.

---

# 22. Experiment Record

Every candidate model must have a reproducible experiment record.

```text
Experiment ID:
Component:
Paper/source:
Repository:
Commit:
Checkpoint:
Dataset:
Dataset version:
License:
Feature/schema version:
Preprocessing:
Training split:
Validation split:
Test split:
Hyperparameters:
Random seed:
Training environment:
Model artifact:
Evaluation metrics:
EdgePPG metrics:
iQOO 15 metrics:
Latency:
Memory:
Thermal:
Deviations:
Status:
```

---

# 23. Model Status Lifecycle

Every candidate should move through:

```text
UNVERIFIED
    ↓
SOURCE-AUDITED
    ↓
REPRODUCED
    ↓
ADAPTED
    ↓
EDGE-VALIDATED
    ↓
SELECTED / FROZEN
```

A candidate may instead become:

```text
REJECTED
```

at any stage.

The source register explicitly requires these status categories. fileciteturn40file0L668-L687

---

# 24. Model Selection Procedure

Do not choose a model solely from the paper's reported number.

For each candidate:

```text
Paper result
     ↓
Reproduction
     ↓
Controlled EdgePPG experiment
     ↓
Cross-domain / attack evaluation
     ↓
Runtime validation
     ↓
iQOO 15 measurement
     ↓
Decision
```

Keep:

```text
Published result
≠
Reproduced result
≠
EdgePPG result
≠
iQOO 15 result
```

---

# 25. Initial Implementation Order

The recommended implementation order from the source register is:

### Phase 0 — Signal baseline

```text
ROI
 ↓
POS
 ↓
CHROM
```

### Phase 1 — Learned models

```text
EfficientPhys
 ↓
Lightweight PAD
 ↓
Random Forest
 ↓
XGBoost
```

### Phase 2 — Research improvements

```text
Physics-based augmentation
Cross-domain PAD
Environmental forensics
Lighting / reflectance
```

### Phase 3 — Advanced research

```text
PRNU
CLIP/VLM
Acoustic sensing
```

### Phase 4 — Deployment

```text
Selected model
 ↓
CPU
 ↓
GPU
 ↓
NPU where supported
```

The source register explicitly recommends beginning with ROI + POS/CHROM, followed by EfficientPhys, MobileNetV3-Spoof, and RF/XGBoost rather than implementing every research extension simultaneously. fileciteturn40file0L698-L700

---

# 26. Critical Rule About Parameters

This document intentionally does **not** invent final hyperparameter values.

For every parameter, there are three possible sources:

```text
1. Paper/source value
2. EdgePPG experimental value
3. Final frozen production value
```

They must be recorded separately.

Example:

```text
Parameter:
    learning_rate

Paper:
    SOURCE-AUDIT REQUIRED

EdgePPG experiment:
    TBD

Final selected model:
    TBD
```

Once the experiment is actually run, replace `TBD` with the measured/selected value and record the experiment ID.

---

# 27. Final ML-to-Architecture Integration

After a model is selected:

```text
Frozen 28 Features
        ↓
Schema Validation
        ↓
Preprocessing
        ↓
Frozen ML Artifact
        ↓
P(LIVE)
        ↓
Deterministic Security Gates
        ↓
LIVE / SPOOF / UNCERTAIN
```

The model selection process must not change the architectural boundary.

The selected model is an implementation of:

```text
ML → P(LIVE)
```

not a replacement for the deterministic security-gate layer.

---

# 28. Completion Criteria

An ML component is **not complete** merely because its code exists.

Minimum completion state:

```text
Source verified
      ↓
Implementation audited
      ↓
Dataset/protocol verified
      ↓
Training/reproduction completed
      ↓
Controlled evaluation completed
      ↓
Artifact versioned
      ↓
EdgePPG integration tested
      ↓
iQOO 15 tested where applicable
      ↓
Runtime metrics recorded
      ↓
Regression test completed
      ↓
Status recorded
```

No module should be reported as complete before the required tests have actually run.

---

# 29. Required Supporting Files

The source register recommends maintaining separate experiment records:

```text
RESEARCH_SOURCE_REGISTER.md
DATASET_PROVENANCE.md
ROI_EXPERIMENT_PLAN.md
RPPG_EXPERIMENT_PLAN.md
PAD_EXPERIMENT_PLAN.md
ENVIRONMENTAL_FORENSICS_PLAN.md
LIGHTING_AND_REFLECTANCE_PLAN.md
PRNU_PROVENANCE_PLAN.md
ACOUSTIC_SENSING_PLAN.md
CLIP_VLM_EXPERIMENT_PLAN.md
EDGE_DEPLOYMENT_VALIDATION.md
EXPERIMENT_RESULTS.md
```

This ML implementation plan acts as the central index connecting those experiments to the EdgePPG ML pipeline.
