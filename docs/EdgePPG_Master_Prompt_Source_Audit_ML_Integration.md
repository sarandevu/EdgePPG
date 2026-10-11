# EdgePPG Master Prompt --- Source Audit, ML Validation, Integration & Evaluation

Use this as the master instruction for Antigravity/OpenCode. Work on the
existing EdgePPG repository as a senior Kotlin/Android engineer, applied
ML researcher, PAD engineer, and reproducibility auditor.

## Objective

Deliver a coherent, evidence-based Evaluation Round 1 product. Audit
first, then fix the smallest proven problems, validate models and data,
integrate the selected model, and build the evaluator-facing features.
Never pretend a component works because its class, repository, checkbox
or UI exists.

## 1. Architecture invariants

Preserve the existing architecture and frozen `edgeppg-1.0` 28-feature
schema unless a documented, approved, versioned change is necessary.

``` text
Camera -> Face/FaceMesh + face-count policy -> Anatomical ROIs
-> POS/CHROM rPPG -> Behaviour/optical challenge
-> Frozen 28 features -> schema/preprocessing validation
-> Random Forest baseline / XGBoost challenger
-> validated calibrated P(LIVE), if calibration is proven
-> deterministic security gates -> LIVE/SPOOF/UNCERTAIN
-> signed session transcript -> PC verifier
```

-   Phone performs sensing, feature extraction, inference, gates and
    signing.
-   PC verifies signature, session, schema and transcript integrity; it
    must not silently reclassify or override the phone verdict.
-   `P(LIVE)` is not the final verdict. Security gates can block
    acceptance.
-   Exactly three biometric outcomes: `LIVE`, `SPOOF`, `UNCERTAIN`.
-   Never fabricate features, labels, metrics, HR, SNR, FPS, latency,
    resource usage or probability values.
-   Never replace missing values with plausible constants without a
    schema-approved meaning. Follow the existing missing-value contract
    and test it.
-   rPPG must use intended forehead/cheek facial ROIs, never accidental
    hand/background/display pixels.
-   Zero faces and multiple faces must block the single-person KYC path;
    use `UNCERTAIN` unless a documented, tested policy requires
    otherwise.
-   Keep production inference Kotlin/Android-first. PC/Python is for
    training and experiments. Do not add Python/Termux to production.
-   Existing Java may remain if correct; do not rewrite for style alone.
-   Separate source-derived facts, repository observations, proposed
    changes and unverified assumptions.

## 2. Phase 0: audit before editing

Inspect the actual repository, build and tests first. Trace the full
data path from camera frame to verdict and signed transcript; trace
model artifact from training/export to Android inference; trace each
feature to its exact frozen-vector index and model input.

Create: - `AUDIT_REPORT.md` - `SOURCE_AND_ARTIFACT_REGISTER.md` -
`DATASET_MANIFEST.md` - `TEST_AND_METRIC_PLAN.md` -
`FEATURE_STATUS.md` - `KNOWN_LIMITATIONS.md`

For every component record: source URL, exact version/commit, license,
local paths/classes/functions, input/output, downstream consumer, tests,
device evidence, status (`IMPLEMENTED`, `PARTIAL`, `MISSING`,
`DEVIATION`, `UNVERIFIED`, `BLOCKED`), evidence, risk and smallest
correction. Do not edit before reporting the audit. If
repository/device/data access is missing, state the blocker instead of
guessing.

## 3. Source and import verification

For every GitHub repository, SDK, paper, dataset, checkpoint and
model: 1. Prefer official documentation, original paper, authors'
repository or dataset host. 2. Pin commit/tag/version; do not rely on
mutable `main` for reproducibility. 3. Review software, dataset and
checkpoint licenses separately. 4. Record imported files, dependencies,
artifact paths, hashes, commands and expected outputs. 5. Confirm
whether code, datasets and pretrained weights were actually downloaded
separately. 6. Validate sample counts, labels, missing/corrupt/duplicate
samples and split integrity against source documentation. 7. SHA-256
proves file identity, not label correctness. 8. A component is verified
only when provenance is known, licensing is reviewed, a reference test
passes, and the real EdgePPG runtime consumes its output.

## 4. In-scope components

### FaceMesh and geometry

The documented component is Google ML Kit Face Mesh; do not silently
replace it with MediaPipe or another product. Check dependency/version,
camera rotation/crop, coordinate mapping, timeouts, stale results, face
count, mesh stability and actual iQOO 15 behaviour. Verify current API
constraints and beta status. Do not call FaceMesh a PAD classifier.

### ROIs

Use the existing forehead and left/right cheek ROI definitions. Overlay
actual sampled ROIs on the preview. Verify coordinate consistency and
frame alignment; reset tracker state between sessions; confirm that
sampled pixels exclude hands/background. Record quality metrics only if
really computed and schema-compatible.

### POS/CHROM/FFT/rPPG

POS and CHROM are deterministic signal-processing algorithms, not AI
models. Compare implementations with original papers and trusted
reference implementations. Audit frame order/timestamps, FPS, RGB
channels, ROI aggregation, normalization, filtering, FFT frequency
bins/window and HR conversion. Test synthetic known-frequency signals
and real videos with synchronized reference measurements. Report POS and
CHROM separately. HR MAE/RMSE, valid-signal rate, failure rate and
condition-wise results are required where data supports them. A
plausible pulse estimate or SNR does not prove liveness.

### Behaviour and optical challenge

Audit head pose, gaze, EAR/blink, MAR/mouth action, challenge state
transitions and timeouts. A challenge only passes when an actual
measured response satisfies its criteria. Test wrong, delayed, missing
and interrupted responses, occlusion, multiple faces and reset. Optical
response must be measured from the actual controlled illumination/flash,
timestamp and facial response. If not fully implemented/tested, label
`PARTIAL` or `UNVERIFIED`, show it honestly and handle missing evidence
safely.

### Frozen 28 features

Do not reorder, rename, reinterpret or silently add features. Record
index/name/unit/type/range/source/validity/missing convention. Confirm
training and Android ordering and preprocessing are identical. Test NaN,
unavailable, out-of-range, stale, incomplete and wrong-schema inputs. Do
not invent values for reserved indices.

### Random Forest baseline

Audit the actual 300-tree artifact, hash, provenance, class order,
feature order, preprocessing, missing-value handling and calibration.
Reproduce golden predictions and compare Android inference with a
trusted reference. Existing calibration constants are not proof of
accuracy or calibration.

### XGBoost challenger --- required

Train XGBoost using the same frozen 28-feature contract, legitimate PAD
labels and same subject/session-aware evaluation protocol as RF. Compare
both on the same held-out test set. Record preprocessing,
hyperparameters, seeds, package versions, labels, splits, logs, model
artifact and metrics. Keep final test data untouched during model
selection.

Calibrate on a separate calibration set or with leakage-safe
cross-validation; never calibrate on the final test set. Report raw and
calibrated metrics separately. Export a genuine XGBoost model in
supported JSON/UBJSON format, not just a tree visualization. Decide
Android runtime after examining model/runtime compatibility. If using a
custom evaluator or conversion, test all relevant tree operators,
missing-value branches, objective/base score and feature ordering. Run
PC-versus-Android golden-vector parity tests with a declared tolerance.
Choose production model on evidence and deployability: XGBoost wins only
if results, calibration, latency and runtime correctness support it;
otherwise RF remains production and XGBoost is documented as challenger.
Never label RF output as XGBoost.

### Image Labeler/screen-phone evidence

Verify the exact API/model. Do not assume the default generic ML Kit
Image Labeler is a dedicated phone-spoof classifier. Treat generic
object labels as supporting evidence only. Audit async/throttling,
runtime integration and actual iQOO results. Combine evidence without
overstating certainty.

### Security/session/transcript

Test single-face gates, quality gates, nonce/session freshness, session
reset, transcript schema, signature and PC verification. Test valid and
tampered transcripts, modified verdict/features, wrong schema/session,
replay, duplicate/stale nonce, missing fields and invalid signatures.
Signature proves integrity/authenticity of signed content, not truth of
the biometric conclusion.

## 5. Datasets: keep tasks separate

Use rPPG datasets such as UBFC-rPPG, PURE and COHFACE to evaluate pulse
extraction only when their synchronized reference signals and terms
support the task. They are not automatically PAD training datasets. For
PAD use data with genuine and spoof labels and documented attack types,
e.g. CelebA-Spoof or other appropriately sourced PAD datasets. Verify
access, terms and license before use.

Maintain dataset manifests: source, version, terms, files/counts,
subjects/sessions, labels, attack types, missing/corrupt/duplicate
samples, hashes, split assignments and preprocessing. Separate
subjects/sessions where possible; prevent near-duplicate frame leakage.
Document class imbalance and label mapping. Evaluate
cross-dataset/domain generalization where feasible. Separate published
results, independently reproduced results, EdgePPG offline results and
iQOO 15 results. Never present published numbers as EdgePPG results.

## 6. Required metrics

### PAD classifiers

Report confusion matrix with explicit class definitions, accuracy,
precision, recall/sensitivity, specificity, F1, ROC-AUC, PR-AUC where
useful, false acceptance/rejection rates with exact definitions,
APCER/BPCER/ACER where the protocol supports them, per-attack-type
results, per-domain results, sample counts, split protocol, threshold
and confidence intervals/uncertainty where feasible. State whether a
sample means frame, video, subject or session. Do not rely on accuracy
alone.

### Probability calibration

Report raw versus calibrated results, calibration/reliability curve,
Brier score and log loss where applicable. Calibration data must be
disjoint from training and final testing or use documented leakage-safe
CV. If calibration is not validated, label the output an uncalibrated
score, not calibrated `P(LIVE)`.

### rPPG

Report HR MAE/RMSE in BPM against synchronized reference,
valid-signal/failure rate, per-subject/condition results where possible,
POS versus CHROM, and sensitivity to motion, illumination, ROI quality,
frame drops and camera settings. Do not convert HR accuracy into PAD
accuracy.

### Geometry/challenge

Report face-count/multi-face enforcement, ROI/landmark stability,
invalid-frame rate, challenge pass/fail/timeout, false challenge
acceptance/rejection and optical-response repeatability under controlled
conditions. Document limits; do not infer broad attack resistance from a
few demos.

### Android/device performance

On physical iQOO 15 measure camera/processed FPS, dropped-frame rate,
per-stage latency, end-to-end latency (median and p95 if enough
samples), cold/warm startup, inference latency,
CPU/memory/battery/thermal if measurable, timeouts/errors, crash-free
repeated sessions, model-load failures, PC/Android parity, APK
hash/version, device/OS. Distinguish proposed targets from measured
results.

### End-to-end tests

Test genuine person, no face, multiple faces, low quality/occlusion,
motion/lighting degradation, printed image, second-phone replay,
failed/delayed challenge, unavailable rPPG, malformed/missing features,
stale state, session reset, tampered transcript, wrong schema, replayed
session, camera interruption and detector timeout. Define expected
outcomes before tests but never hardcode a desired verdict. Record
failures honestly.

## 7. Product features

Implement these around real backend state: 1. Real-time Liveness Health
Dashboard. 2. Evidence panel showing real measurements and gate reasons;
do not claim SHAP/feature attribution unless implemented and validated.
3. Timestamped session timeline. 4. POS/CHROM waveforms, FFT and real
rPPG metrics. 5. Actual ROI overlay. 6. Exactly-one-face security. 7.
Screen/phone evidence with limitations shown. 8. Behaviour/optical
challenge progress and actual response. 9. Session nonce and signed
transcript. 10. Tamper-verification demo using a modified transcript
copy. 11. Deterministic gate inspector. 12. Frozen 28-feature inspector
with value/validity/schema version. 13. Performance Monitor LAST, only
after pipeline stabilizes; real measured values only. 14. Attack
Simulator FINAL MAJOR FEATURE, with reproducible genuine, multiple-face,
poor-quality, static image/replay, failed challenge and
transcript-tampering scenarios. It reports actual outcomes and must not
force verdicts.

Performance Monitor and Attack Simulator are presentation/testing
layers, not stages in the biometric inference pipeline.

## 8. Future research only

Do not let these block the Round 1 core: - EfficientPhys learned rPPG; -
verified dedicated lightweight PAD model such as a MobileNetV3-based
implementation; - FAS-Aug/domain-generalization augmentation; - CLIP/VLM
PAD; - acoustic FMCW as a separate modality; - PRNU/source-camera
provenance; - environmental forensics and lighting/reflection
research; - Qualcomm NPU optimization.

For each, record paper, authors' repository, license,
dataset/checkpoint, reproducibility, metrics, integration cost and
go/no-go criteria. PRNU alone is not liveness proof; acoustic is a
separate modality; library presence does not prove NPU execution.

## 9. Implementation order

1.  Audit and baseline build/tests.
2.  Fix face count, no-face, ROI reset, fabricated fallbacks,
    missing-feature handling and data provenance.
3.  Verify RF baseline and data sources; train/compare/calibrate
    XGBoost; export and test parity.
4.  Complete behaviour/optical only if genuinely measurable and
    testable.
5.  Connect UI to real values: dashboard, ROI, signal charts, feature
    inspector, evidence and timeline.
6.  Verify nonce, signed transcript, PC verifier and tamper tests.
7.  Build/install/test on physical iQOO 15; regression after every fix.
8.  Add Performance Monitor.
9.  Add Attack Simulator last and rerun end-to-end regression.

For every change: state evidence and acceptance criteria; add/fix test;
implement smallest change; run tests; build APK; install/test on iQOO
when camera/runtime is involved; inspect logs/UI; run regression; update
docs. If physical device testing is unavailable, mark it
`BLOCKED`/`UNVERIFIED`.

## 10. Required deliverables

Maintain `AUDIT_REPORT.md`, `SOURCE_AND_ARTIFACT_REGISTER.md`,
`DATASET_MANIFEST.md`, `MODEL_EXPERIMENTS.md`, `METRICS_REPORT.md`,
`ANDROID_INTEGRATION_REPORT.md`, `DEVICE_TEST_REPORT.md`,
`FEATURE_STATUS.md`, and `KNOWN_LIMITATIONS.md`, plus model hashes,
golden vectors, expected PC/Android outputs and reproducible commands.

For every reported number, distinguish published, reproduced, EdgePPG
offline, physical iQOO 15, proposed target or unknown/unverified.

## 11. Final acceptance gates

Do not claim completion until architecture/schema are preserved or
versioned; provenance and licenses are recorded; production does not
fabricate evidence; active model/artifact is known; RF/XGBoost
comparison is reproducible; metrics use suitable labels/protocols;
calibration is evaluated; Android parity passes; deterministic gates
pass tests; signature/tamper tests pass; physical iQOO results are
recorded or explicitly blocked; UI uses real data; simulator reports
real outcomes; performance is measured; limitations are documented.

Begin with Phase 0. Do not rewrite the project blindly. Report
repository/build/test access, audit the real code path, and produce the
audit documents before making changes.

------------------------------------------------------------------------

# Source register (starting points; verify current versions/terms)

## Android vision

-   Google ML Kit Face Mesh Android:
    https://developers.google.com/ml-kit/vision/face-mesh-detection/android
-   Face Mesh concepts/coordinates:
    https://developers.google.com/ml-kit/vision/face-mesh-detection/concepts
-   Google ML Kit face detection Android:
    https://developers.google.com/ml-kit/vision/face-detection/android
-   Google ML Kit Image Labeling Android:
    https://developers.google.com/ml-kit/vision/image-labeling/android

## rPPG

-   Wang et al., *Algorithmic Principles of Remote PPG* (2017; DOI
    10.1109/TBME.2016.2609282):
    https://pubmed.ncbi.nlm.nih.gov/28113245/
-   de Haan & Jeanne, *Robust Pulse Rate From Chrominance-Based rPPG*
    (2013; DOI 10.1109/TBME.2013.2266196):
    https://doi.org/10.1109/TBME.2013.2266196
-   rPPG-Toolbox: https://github.com/ubicomplab/rPPG-Toolbox
-   EfficientPhys (WACV 2023 proceedings):
    https://openaccess.thecvf.com/WACV2023
-   COHFACE official dataset page:
    https://www.idiap.ch/en/scientific-research/data/cohface
-   For UBFC-rPPG and PURE, find the original dataset/paper host and
    verify terms directly before downloading; do not rely only on
    mirrors.

## PAD

-   CelebA-Spoof official dataset page/terms:
    https://mmlab.ie.cuhk.edu.hk/projects/CelebA/CelebA_Spoof.html
-   CelebA-Spoof authors' GitHub:
    https://github.com/ZhangYuanhan-AI/CelebA-Spoof
-   Awesome-FAS discovery index (not a substitute for original sources):
    https://github.com/RizhaoCai/Awesome-FAS
-   FAS-Aug authors' GitHub: https://github.com/CeceliaSoh/FAS-Aug
-   ISO/IEC 30107-3:2023 overview:
    https://www.iso.org/standard/79520.html
-   NIST PAD terminology:
    https://csrc.nist.gov/glossary/term/Presentation_Attack_Detection

## ML and calibration

-   XGBoost model saving/loading:
    https://xgboost.readthedocs.io/en/stable/tutorials/saving_model.html
-   XGBoost Python API:
    https://xgboost.readthedocs.io/en/stable/python/python_api.html
-   scikit-learn probability calibration:
    https://scikit-learn.org/stable/modules/calibration.html
-   `CalibratedClassifierCV`:
    https://scikit-learn.org/stable/modules/generated/sklearn.calibration.CalibratedClassifierCV.html

## Source caveats

-   Google ML Kit Face Mesh is documented as beta; check current
    version/constraints.
-   Default ML Kit Image Labeler is a generic image-labeling component,
    not automatically a dedicated PAD classifier.
-   Listed repositories/datasets are candidates, not proof of successful
    import or suitability.
-   Dataset terms may restrict redistribution or commercial use.
-   Published metrics are not EdgePPG results.
