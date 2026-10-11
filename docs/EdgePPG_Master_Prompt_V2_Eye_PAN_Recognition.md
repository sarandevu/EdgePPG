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
Camera -> Face/FaceMesh + face-count policy -> Eye/gaze/blink tracking
-> Anatomical ROIs -> POS/CHROM rPPG
-> Behaviour/gesture/optical challenge -> Frozen 28 features
-> schema/preprocessing validation -> Random Forest baseline / XGBoost challenger
-> validated calibrated P(LIVE), if calibration is proven
-> deterministic security gates -> LIVE/SPOOF/UNCERTAIN
-> signed session transcript -> PC verifier

Separate KYC document lane (must not enter the frozen biometric vector):
Camera/document mode -> PAN-card quadrilateral detection and temporal tracking
-> stable capture / perspective correction -> OCR -> format and field checks
-> masked document status and explicitly scoped session record
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
-   Eye tracking, camera-based gaze estimation, blink/eye-state tracking,
    gesture recognition and PAN-card tracking are explicit Round 1 scope;
    they must have real runtime data paths, tests and UI state, not just
    placeholder classes.
-   PAN-card OCR/document status is a separate identity-document lane. It
    must never be inserted into the frozen 28-feature biometric vector or
    silently used as a PAD-model feature. Document readability or PAN text
    format is not proof that the card is genuine or government-verified.
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

### Eye tracking, gaze and blink state — required

Treat eye tracking as a first-class runtime feature, not merely a label in
an architecture diagram. First audit and complete the existing
`GazeEstimator.kt`/FaceMesh path rather than replacing it blindly.

- Track both eye regions frame-to-frame using the active face mesh and
  preserve correct camera rotation, preview mirroring and image-coordinate
  mapping. Surface current validity/confidence and stale/timeout state.
- Implement and test camera-based gaze-direction estimation for the
  supported challenge directions, temporal smoothing, blink/eye-open state
  and challenge response timing. Keep EAR/blink logic separate from gaze
  direction; do not infer gaze solely from head pose.
- Verify exactly which eye/iris landmarks the active ML Kit Face Mesh API
  actually exposes. Its documented 468-point mesh must not be assumed to
  provide dedicated pupil/iris tracking. If its landmarks cannot support
  the required eye tracking, prototype MediaPipe Face Landmarker as a
  separately evaluated alternative (it documents 478 landmarks and
  optional face blendshapes), but do not silently replace the current
  detector. Record accuracy, device latency, API/model versions, license,
  coordinate differences and regression results before choosing.
- Do not claim medical-grade, infrared, hardware eye tracking or precise
  point-of-regard measurement. Label the feature “camera-based gaze
  estimate” unless calibration and ground-truth evaluation justify a more
  specific claim.
- Add unit/replay tests for left/right/up/down targets, neutral gaze,
  blink, head-turn confounding, glasses, partial occlusion, lighting,
  rotated/mirrored frames, face loss, multi-face input, stale output and
  session reset. An invalid/ambiguous response must fail safely or produce
  `UNCERTAIN`, never be guessed.
- Map results only to existing schema fields where their definitions
  match (for example `gaze_accuracy` and `challenge_timing_error`). Do not
  change the frozen 28-feature schema to add new eye fields without a
  separately approved and versioned migration.

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

### Behaviour, gesture model and optical challenge — required

Audit head pose, gaze/eye tracking, EAR/blink, MAR/mouth action, hand
tracking, gesture classification, challenge state transitions and
timeouts. A challenge only passes when an actual measured response
satisfies its criteria. Test wrong, delayed, missing and interrupted
responses, occlusion, multiple faces and reset. Optical response must be
measured from the actual controlled illumination/flash, timestamp and
facial response.

For hand gestures, do not ship a hard-coded gesture name or a mock
classifier. First candidate: audit and adapt the official Google AI Edge
MediaPipe Gesture Recognizer Android sample and its compatible model
bundle:
`https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/gesture_recognizer/android`
with the Android guide at
`https://ai.google.dev/edge/mediapipe/solutions/vision/gesture_recognizer/android`.
The task supports live-stream gesture recognition and returns recognized
gestures plus hand landmarks. Verify the current dependency/API, compatible
model bundle, repository commit, license, model download path, APK size,
threading and iQOO 15 latency before adopting it. Pin a commit/version;
never depend on mutable `main` in the build.

- Choose a small set of gestures that the actual model supports (for
  example open palm or fist only after checking the model's labels), then
  align challenge wording to those labels. If custom gestures are required,
  train/evaluate a compatible custom model and document its dataset,
  classes, splits, metrics and license.
- Run gesture inference asynchronously and avoid blocking CameraX/FaceMesh
  or starving rPPG frame sampling. Synchronize results to frame timestamps;
  reject stale, low-confidence, off-screen or ambiguous gestures.
- Gesture is auxiliary challenge compliance, not proof of liveness by
  itself. Use `hand_accuracy` only if its frozen schema definition and
  measured computation match; never add gesture fields to the 28-vector
  without an approved schema version.
- Add tests for no hand, multiple hands, supported and unsupported
  gestures, confidence threshold, mirrored front camera, left/right hand,
  occlusion, rapid motion, delayed callback, camera interruption, timeout
  and session reset.

If any component is not fully implemented/tested, label it `PARTIAL` or
`UNVERIFIED`, show that honestly and handle missing evidence safely.

### PAN-card detection/tracking and OCR — separate KYC document lane

Implement PAN-card handling as a real, explicit document-capture workflow.
It is not a biometric PAD classifier and must stay separate from the
frozen `edgeppg-1.0` 28-feature vector and `P(LIVE)` calculation.

- Provide a dedicated document mode/camera flow with a visible card
  boundary/quad, corner stability, orientation/blur/glare checks and a
  tracked/stable-capture state across consecutive frames. Use frame-to-frame
  association and temporal stability; a one-shot OCR call does not satisfy
  “tracking.” Audit the existing camera architecture first, and do not run
  front-face liveness and rear-camera document capture concurrently if the
  device cannot support it. Switching camera modes must pause/restart stages
  cleanly and reset stale session state.
- Start with a deterministic quadrilateral/contour and perspective-crop
  baseline if suitable in the existing stack. If that fails on physical
  iQOO 15 conditions, evaluate a documented card/document detector model.
  Record source repository, pinned commit, license, model/checkpoint, size,
  latency and evidence before importing any third-party code or weights.
- For OCR, evaluate Google ML Kit Text Recognition v2:
  `https://developers.google.com/ml-kit/vision/text-recognition/v2`
  and the Android guide/sample. OCR output must include geometry and, where
  available, confidence; use perspective-corrected images and controlled
  processing frequency. Do not assume generic OCR recognizes every PAN
  card layout correctly; test real-looking, consented/test documents and
  difficult capture conditions.
- Implement a separate PAN-field parser/validator that checks extracted
  text and format, but clearly distinguish `DETECTED`, `TRACKING_STABLE`,
  `OCR_PARTIAL`, `FORMAT_CHECK_PASSED`, `USER_CONFIRMED` and
  `VERIFICATION_UNAVAILABLE` (or equivalent explicit states). A valid-looking
  PAN format does not establish document authenticity, cardholder identity,
  government database status or ownership. Only report authoritative
  verification if a real approved verification service is integrated and
  its result is auditable.
- Resolve multi-frame OCR disagreement, reflections, glare, blur, cut-off
  edges, rotated cards, background text, print/replay-on-screen attacks,
  swapped cards and camera interruptions. Never auto-accept an OCR result
  merely because it is syntactically plausible.
- Privacy: mask PAN numbers by default in UI/logs/reports; never put full
  PAN text, card images or OCR payloads in debug logs or analytics. Prefer
  in-memory processing and delete temporary crops after use. If retention
  or transmission is explicitly required, document the legal/business basis,
  user notice/consent, access controls, encryption and retention period.
- Keep document status in a separate typed model/session field. If the
  signed transcript records document status, include the minimum necessary
  status and integrity metadata, not full PAN data by default. The PC
  verifier must verify integrity, not pretend it independently validates
  PAN authenticity.
- Add tests for supported PAN layout, low-resolution OCR, glare, skew,
  blur, partial card, competing text, wrong/unsupported document, unstable
  corners, frame-to-frame identity switching, OCR disagreement, malformed
  PAN format, redaction, log leakage, session reset and camera switching.

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

### Eye tracking and gesture

Report valid eye/gaze result rate, per-direction challenge accuracy and a
confusion matrix when reference labels exist, blink/EAR validity, false
challenge acceptance/rejection, stale-result rate, hand-detection failure
rate, per-class gesture precision/recall, unsupported/ambiguous gesture
rejection, and median/p95 per-stage latency on the physical device when
sample counts support it. A gaze “accuracy” number requires a defined
target protocol and reference labels. Distinguish replay/offline results
from iQOO results.

### PAN-card document lane

Report card detection/tracking success by capture condition, corner/track
stability, OCR field exact-match or character-error rate on consented and
appropriately licensed test samples, format-parser outcomes, false accepts
of malformed/unsupported cards, capture retries and latency. Report sample
counts and document types while masking sensitive values. Keep these
metrics separate from PAD metrics and do not call OCR success “PAN
verification.”

### rPPG

Report HR MAE/RMSE in BPM against synchronized reference, valid-signal/
failure rate, per-subject/condition results where possible, POS versus
CHROM, and sensitivity to motion, illumination, ROI quality, frame drops
and camera settings. Do not convert HR accuracy into PAD accuracy.

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

Implement these around real backend state:

1. Real-time Liveness Health Dashboard.
2. Eye-tracking panel: actual eye-region overlay, camera-based gaze estimate,
   blink/eye-state, confidence/validity, stale state and challenge progress.
3. PAN-card tracking/capture panel: live card boundary, track stability,
   OCR status and masked extracted fields. Never display a fake “verified” badge.
4. Gesture challenge panel: requested gesture, supported model class, actual
   confidence, timestamp and pass/fail/timeout.
5. Evidence panel with real measurements and gate reasons; do not claim
   SHAP/feature attribution unless implemented and validated.
6. Timestamped session timeline.
7. POS/CHROM waveforms, FFT and real rPPG metrics.
8. Actual anatomical ROI overlay.
9. Exactly-one-face security status.
10. Screen/phone evidence with limitations shown.
11. Behaviour/optical challenge progress and actual response.
12. Session nonce and signed transcript.
13. Tamper-verification demo using a modified transcript copy.
14. Deterministic gate inspector.
15. Frozen 28-feature inspector with value/validity/schema version.
16. Performance Monitor LAST, only after the pipeline stabilizes; measured
    values only.
17. Attack Simulator FINAL MAJOR FEATURE, with reproducible genuine,
    multiple-face, poor-quality, static-image/replay, failed gesture/challenge,
    unstable/invalid PAN capture and transcript-tampering scenarios. It reports
    actual outcomes and must not force verdicts.

Performance Monitor and Attack Simulator are presentation/testing layers,
not stages in the biometric inference pipeline.

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
3.  Complete camera-based eye tracking/gaze/blink path and its challenge
    tests; evaluate Face Landmarker only if current landmarks are
    insufficient.
4.  Integrate a verified MediaPipe Gesture Recognizer sample/model (or
    documented challenger), pin its provenance and prove async iQOO runtime
    behaviour without starving face/rPPG processing.
5.  Build the separate PAN-card detection/tracking/OCR lane, including
    privacy/redaction, capture stability, explicit status states and
    non-verification caveats.
6.  Verify RF baseline and data sources; train/compare/calibrate XGBoost;
    export and test parity.
7.  Complete optical challenge only if genuinely measurable and testable.
8.  Connect UI to real values: health dashboard, eye tracking, PAN tracking,
    gesture status, ROI, signal charts, feature inspector, evidence and timeline.
9.  Verify nonce, signed transcript, PC verifier and tamper tests.
10. Build/install/test on physical iQOO 15; regression after every fix.
11. Add Performance Monitor.
12. Add Attack Simulator last and rerun end-to-end regression.

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
comparison is reproducible; eye tracking/gaze/blink has real tested outputs;
gesture recognition consumes a verified model with physical-device evidence;
PAN-card tracking/OCR is a separate tested and privacy-safe lane with no
unsupported authenticity claims; metrics use suitable labels/protocols;
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

## Eye tracking and face landmarks

-   ML Kit Face Mesh Android (468 3D points; verify current beta/API limits):
    https://developers.google.com/ml-kit/vision/face-mesh-detection/android
-   MediaPipe Face Landmarker Android guide (candidate only if current
    ML Kit landmarks are insufficient; verify current outputs/version):
    https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android
-   Official MediaPipe Android sample:
    https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/face_landmarker/android

## Hand gesture recognition

-   MediaPipe Gesture Recognizer Android guide:
    https://ai.google.dev/edge/mediapipe/solutions/vision/gesture_recognizer/android
-   Official Android sample and model-bundle wiring:
    https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/gesture_recognizer/android

## PAN-card detection and OCR

-   ML Kit Text Recognition v2:
    https://developers.google.com/ml-kit/vision/text-recognition/v2
-   ML Kit Text Recognition v2 Android guide:
    https://developers.google.com/ml-kit/vision/text-recognition/v2/android
-   Optional explicit document-capture UX (not a real-time tracking system by
    itself): ML Kit Document Scanner
    https://developers.google.com/ml-kit/vision/doc-scanner/android
-   Google ML Kit Android sample repository (inspect the relevant sample,
    pin commit and verify license before adapting):
    https://github.com/googlesamples/mlkit

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
-   ML Kit Face Mesh exposes a documented 468-point mesh; do not assume
    dedicated iris/pupil landmarks or call its output precise eye tracking
    unless the chosen API and tests support that claim.
-   ML Kit Document Scanner helps capture/crop a document but does not by
    itself implement continuous PAN-card tracking or authoritative PAN
    verification.
-   MediaPipe Gesture Recognizer is a candidate implementation/model, not
    proof that the selected gesture set is supported or accurate in EdgePPG.
-   Listed repositories/datasets are candidates, not proof of successful
    import or suitability.
-   Dataset terms may restrict redistribution or commercial use.
-   Published metrics are not EdgePPG results.
