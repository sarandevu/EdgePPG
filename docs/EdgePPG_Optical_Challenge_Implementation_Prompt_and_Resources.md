# EdgePPG — Optical Challenge-Response Implementation Prompt & Research Resource Guide

**Purpose:** Give Antigravity a precise, implementation-oriented plan to audit, build, test and document the missing optical challenge-response pipeline in the existing EdgePPG Android application.

**Target runtime:** Kotlin-first Android application on the iQOO 15, using the existing front-camera, face/ROI, session, feature-schema, decision-gate and signed-transcript architecture.

**Status discipline:** This document describes the intended implementation. It does not claim the feature currently works. A UI label such as `OPTICAL: ACTIVE` or an existing method/class is not evidence that a real challenge was emitted, camera response was measured, or the result was consumed by the production pipeline.

---

## 1. What the optical challenge is supposed to do

EdgePPG's runtime architecture defines Optical Challenge Analysis as:

> Input: randomized screen brightness/color/timing → expected vs observed correlation + delay → `optical_response_score`, `optical_timing_error`, `optical_sequence_score`.

It also states that screen-induced RGB variation must not be confused with physiological rPPG, and proposes multi-ROI analysis, temporal separation, normalization and quality gating. The current frozen feature contract contains `optical_response_score`; timing and sequence measurements remain intermediate unless the schema is formally versioned.

The optical challenge is an **active, time-linked visual stimulus-and-measurement step**:

1. The app generates a fresh challenge sequence for the current session.
2. The phone display presents a controlled sequence of colors or brightness levels.
3. The front camera captures the participant during the sequence.
4. The app extracts actual RGB/color measurements from tracked anatomical facial ROIs.
5. The analyser compares when and how the observed facial measurements change with the known stimulus sequence.
6. The module returns an optical-response score, timing/sequence diagnostics, validity status and reason codes.
7. The validated result reaches the feature assembler and, according to the existing specification, the optical feature position in the frozen schema.
8. The deterministic decision layer handles the overall session; the optical module must not independently force a final `LIVE` or `SPOOF` verdict.

This is not simply an animation that changes the screen colour. The key implementation requirement is a traceable connection from **actual displayed stimulus → actual camera frames → ROI measurements → correlation/response calculation → validated result → intended consumer**.

### 1.1 What this proves—and what it does not

A measurable response means the captured facial regions changed in temporal relation to the stimulus. It does **not**, by itself, prove identity or liveness. A printed image or replay displayed on another device can also reflect light, and camera auto-exposure/white-balance can create correlated changes. Optical challenge is one evidence channel and must be evaluated against real presentation attacks.

Do not describe optical correlation as a biological response unless the method specifically measures a validated biological mechanism. A screen-colour challenge that analyses facial RGB is not the same as an eye-pupil reflex test, and it is not the same as CFrPPG.

---

## 2. How it differs from rPPG and CFrPPG

| Component | Input / operation | Main output | Purpose |
|---|---|---|---|
| POS/CHROM rPPG | Facial RGB sampled over time; signal extraction and spectral analysis | Pulse-related signal, HR estimate if valid, SNR/quality | Estimate physiological pulse-related evidence |
| CFrPPG | Multiple local rPPG signals and a learned spectral correspondence method | Correspondence feature for a PAD classifier | Research-based presentation-attack evidence, particularly for 3D-mask attacks |
| Optical challenge | Randomized display color/brightness/timing + camera response | Optical response, timing and sequence measurements | Check a time-linked visual stimulus response and add a distinct challenge channel |
| Passive RGB PAD | Camera frames analysed without actively emitted challenge | PAD score | Look for texture/appearance/video evidence of attacks |
| Acoustic FMCW | Known acoustic probe + microphone echoes | Acoustic range/motion measurements | A separate experimental sensing modality |

The optical branch can share the same CameraX frames, face track and anatomical ROI tracker as rPPG, but it must keep separate state and avoid treating stimulus-induced color modulation as pulse.

---

## 3. Research and implementation resources

These resources have different roles. A reference repository is not automatically a production-ready Android module. Inspect the source, licence, dependencies, model provenance, and data flow before borrowing code.

### 3.1 Closest open-source reference: active screen-light checks

**OpenFace Liveness**  
Repository: <https://github.com/abhigyaan-jha/open-face-liveness>  
README: <https://github.com/abhigyaan-jha/open-face-liveness#readme>  
Licence shown by repository: MIT; bundled model assets and third-party components may retain separate licences, so inspect its notices.

Why it is relevant:
- The project describes active screen-light checks.
- Its README refers to facial skin regions, HSV sampling, sequence correlation and response magnitude.
- Its light results include baseline sampling, per-colour steps, sequence score, correlation and response magnitude.
- It is a browser/TypeScript-oriented implementation using web video/model technology, not a native Kotlin Android library.

How to use it:
- Treat it as a conceptual and algorithmic reference for diagnostic fields, not a drop-in dependency.
- Inspect its light-response code and tests to understand how it samples skin regions and scores response.
- Port only the parts that are technically appropriate and license-compatible.
- Do not copy its verdict thresholds or claim its implementation is validated on the iQOO 15.
- The repository currently has a small public footprint; independently audit the implementation before relying on it.

### 3.2 Official Google face-region source

**Google ML Kit Face Mesh for Android**  
Android guide: <https://developers.google.com/ml-kit/vision/face-mesh-detection/android>  
Concepts: <https://developers.google.com/ml-kit/vision/face-mesh-detection/concepts>  
API reference: <https://developers.google.com/android/reference/com/google/mlkit/vision/facemesh/FaceMeshDetector>

Relevance:
- Provides a face mesh with 468 3D points and triangle information in mesh mode.
- EdgePPG already uses a Face Mesh/ROI concept; reuse the current ROI tracker if it is genuinely integrated.
- These landmarks can help keep forehead and cheek sampling locations associated with the face as it moves.

Caution: Google documents Face Mesh as beta. Its availability does not make optical challenge analysis complete, and its landmark output does not prove liveness.

### 3.3 Official Android camera-frame analysis

**CameraX ImageAnalysis**  
Documentation: <https://developer.android.com/media/camera/camerax/analyze>

Relevance:
- Documents access to camera frames for image processing.
- Explains the analysis pipeline, YUV/RGBA formats, frame dropping/backpressure and `ImageProxy` lifecycle.
- Optical analysis should use the existing camera pipeline rather than opening a competing camera session without a documented need.

Implementation note: `ImageProxy` must be closed on every code path. If `STRATEGY_KEEP_ONLY_LATEST` is used, record that frames may be dropped; do not assume every displayed colour step has a captured frame.

### 3.4 Screen brightness control (optional)

**Android `WindowManager.LayoutParams` reference**  
Documentation: <https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness>

The app can draw a controlled full-screen colour view for the stimulus. A temporary window-specific screen-brightness override is another option, but do not change system-wide settings unnecessarily. Prefer a clearly scoped stimulus view that the application can restore after completion or cancellation. Test HDR, adaptive brightness, display colour settings and device-specific behaviour rather than assuming rendered RGB maps linearly to emitted light.

### 3.5 Research paper: mobile lighting system for face anti-spoofing

**Aurora Guard: Reliable Face Anti-Spoofing via Mobile Lighting System**  
Paper/preprint: <https://arxiv.org/abs/2102.00713>

Why it matters:
- Studies lighting-based cues and a light CAPTCHA mechanism for replay/photo presentation attacks.
- Provides context for using controlled illumination and analysing the resulting facial image.
- It is a research method and should not be treated as an implementation specification for EdgePPG without studying its actual assumptions and experiments.

### 3.6 Research paper: display patterns and corneal reflection

**Apple in My Eyes (AIME): Liveness Detection for Mobile Security Using Corneal Specular Reflections**  
Publication: <https://ieeexplore.ieee.org/document/9924234/>

Why it matters:
- Uses screen patterns as authentication challenges and analyses corneal specular reflections.
- Helps clarify a different optical approach: eye/cornea reflection analysis rather than only sampling RGB changes over forehead and cheeks.

Important distinction: AIME is not interchangeable with EdgePPG's specified `optical_response_score` pipeline. Do not silently turn this project into a pupil/cornea-reflection system. Treat it as a research alternative only if a deliberate design decision is made.

### 3.7 Attack/evaluation resources

**OR-PAD — Oulu Remote-photoplethysmography Presentation Attacks Database**  
Repository and access information: <https://github.com/marukosan93/OR-PAD>

Why it matters:
- Focuses on rPPG-based presentation attacks and includes attack scenarios involving illumination, motion and manipulated/replayed signals.
- Helps test the broader danger of confusing stimulus-induced or adversarial colour changes with physiological evidence.
- Dataset access is restricted; its repository describes academic/non-commercial terms and a data-transfer process. Follow the current licence/terms before using it.

**ORPDAD — Oulu Remote-photoplethysmography Physical Domain Attacks Database**  
Repository: <https://github.com/marukosan93/ORPDAD/>

Why it matters:
- Includes physical-domain presentation-attack scenarios and controlled illumination manipulations.
- Useful as research context for testing whether the optical channel or rPPG channel can be manipulated by external light.
- Verify dataset access, terms, labels and baseline instructions. Do not assume the data directly evaluates the exact EdgePPG screen-light protocol.

**Digital and Physical-World Attacks on Remote Pulse Detection (WACV 2022)**  
Paper: <https://openaccess.thecvf.com/content/WACV2022/html/Speth_Digital_and_Physical-World_Attacks_on_Remote_Pulse_Detection_WACV_2022_paper.html>

Why it matters:
- Demonstrates that digital changes or external visible-light illumination can create misleading pulse-like signals.
- This is a key reason to separate optical stimulus-induced RGB changes from rPPG and to test attacks that manipulate illumination.

### 3.8 Official service example: colour-sequence challenge

**Amazon Rekognition Face Liveness — movement and light challenge API**  
Documentation: <https://docs.aws.amazon.com/rekognition/latest/APIReference/API_rekognitionstreaming_FaceMovementAndLightServerChallenge.html>

This documents colour-sequence configuration as part of a managed liveness service. Use it only as a design reference for challenge sequencing and output concepts. It is not a reason to add an AWS dependency: EdgePPG's baseline is intended to run its core pipeline on-device and use its own PC verifier.

---

## 4. Required EdgePPG data flow

The implemented flow should fit the current architecture:

```text
Session Start
  → Fresh session ID / nonce / challenge seed
  → Camera Acquisition
  → Existing Face / ROI Tracking
  → Optical Baseline Sampling
  → Randomized Screen Colour/Brightness Sequence
  → Camera Frames Captured During Stimulus
  → Per-ROI RGB/colour sampling + timestamps
  → Validity checks and normalization
  → Compare expected stimulus with observed response
  → optical_response_score + intermediate diagnostics + validity/reason
  → Existing Feature Vector Assembly (frozen edgeppg-1.0)
  → Schema Validation / Preprocessing
  → ML Fusion → P(LIVE)
  → Deterministic Security Gates
  → LIVE / SPOOF / UNCERTAIN
  → Signed Transcript
```

The optical analyser can run concurrently with other sensing branches, but its records must belong to the current session/challenge. Stale frames or delayed callbacks from a prior step/session must never be used to finish a current challenge.

### Frozen feature-schema rule

The current `Architecture.md` lists `optical_response_score` in the frozen 28-feature schema. It also states that values such as `optical_timing_error` and `optical_sequence_score` remain intermediate unless a versioned schema change formally adopts them.

Therefore:
- Verify the actual schema implementation before editing.
- Feed the intended optical feature only into the existing, documented position if that contract is real and consistent with the trained model.
- Keep timing, correlation, amplitude, per-region response and sequence diagnostics available for observability, tests and reason codes as intermediate values.
- Do not append features, reorder fields, silently rename the field or change preprocessing.
- If source code/training artefacts disagree on the schema, stop and report the conflict before wiring this feature into ML.

---

## 5. Optical challenge algorithm specification

### 5.1 Preconditions

Before starting a challenge, check:
- Camera permission and camera stream are active.
- The expected participant face is tracked.
- Required anatomical ROIs are valid and stable.
- Face count matches the current session mode (single-person versus multi-person policy).
- There are enough usable frames for a baseline.
- Capture conditions are not obviously saturated, severely blurred or otherwise invalid.
- The user has been informed that a colour/brightness challenge is about to occur and has an accessible alternative path if needed.

If preconditions fail, do not emit a fabricated response or claim the challenge passed.

### 5.2 Baseline

Collect a brief stable baseline before the first stimulus step. Record per-ROI RGB or a well-defined colour representation, frame timestamps, ROI validity, exposure/white-balance metadata where available, and the baseline variation.

Use robust statistics (for example, a trimmed mean or median) rather than trusting a single pixel. Sample from skin areas tracked by the existing ROI geometry and exclude invalid pixels, eyes, eyebrows, hair, lips, background and obvious specular highlights as far as practical.

### 5.3 Stimulus generation

Generate a fresh challenge sequence associated with the current `session_id`, nonce and challenge seed. Use a bounded palette and restrained luminance/colour changes. Record the exact stimulus state and monotonic timestamp of every state transition.

The sequence can contain colour/brightness steps such as neutral baseline and low-contrast warm/cool/white states, but the actual palette, dwell time and ordering must be configurable and documented. Avoid a fixed reusable sequence that can trivially be prerecorded.

**Safety requirement:** Do not implement rapid, high-contrast strobing. Warn the user before the optical test, provide a cancel option and offer a non-light alternative for users with photosensitive epilepsy, migraine/light sensitivity or accessibility needs. Use low-discomfort transitions and evaluate the intended stimulus timing on the actual device. If a research parameter such as 250–400 ms is present in earlier notes, treat it as a parameter to validate—not permission to create unsafe flashing.

### 5.4 Capture alignment

Use the existing CameraX/camera pipeline wherever possible. Each analysed frame should carry the best available camera timestamp and ROI/quality data. Log stimulus transition times using a monotonic clock.

Do not assume display rendering time, camera sensor timestamp and callback-arrival time are identical. Establish how they can be aligned in the current camera implementation, estimate/measure the offset or delay, and report timing uncertainty. If reliable alignment is not possible, mark timing diagnostics inconclusive rather than returning a confident score.

Avoid blocking camera analysis while updating the screen. Keep the analysis callback fast, and release every `ImageProxy` on all code paths.

### 5.5 Response extraction

For each valid frame, obtain robust colour samples from the current forehead and left/right cheek ROIs. Preserve per-region sample series and timestamps. Do not use hand/background pixels as facial response evidence.

The implementation should document:
- Input colour representation (for example, YUV converted to RGB, or direct RGBA output).
- Whether values are gamma-encoded or transformed.
- Normalization and baseline subtraction.
- Handling of exposure and auto-white-balance changes.
- ROI and face-track validity rules.
- Minimum useful frame count and sequence coverage.
- Outlier, saturation and motion rejection.

The UI's selected display RGB values are not guaranteed to equal the physical emitted light or the RGB values measured from skin. Screen rendering, display calibration, tone mapping, camera auto controls and ambient light affect the observed data.

### 5.6 Scoring

Create an explicit, tested analyser that compares the observed response series with the actual challenge waveform/step sequence. Candidate diagnostics include:
- Response magnitude relative to baseline variation.
- Correlation or sequence agreement between expected stimulus and observed facial response.
- Estimated response lag and timing uncertainty.
- Agreement across valid facial ROIs.
- Signal/ROI quality and challenge coverage.

Do not reuse a copied numeric threshold without evaluating it against EdgePPG recordings. Normalization and correlation should account for slow drift and global exposure changes, but must not remove the very response the challenge is meant to measure. Avoid choosing a threshold that passes synthetic unit tests yet fails physical capture.

`optical_response_score` must have a documented range and meaning consistent with the current trained model. If its current definition is unknown, do not redefine it silently; trace the existing contract first.

### 5.7 Result object

Use or adapt the existing project conventions. Do not create a parallel framework if equivalent interfaces already exist. A result should conceptually contain:

```kotlin
// Illustrative contract only. Adapt names/types to the existing codebase.
data class OpticalChallengeResult(
    val sessionId: String,
    val valid: Boolean,
    val opticalResponseScore: Float?,
    val timingErrorMs: Long?,
    val sequenceScore: Float?,
    val roiCoverage: Map<String, Float>,
    val correlation: Float?,
    val reasonCodes: List<String>,
    val challengeVersion: String
)
```

Do not store fabricated numbers for invalid output. Use the established missing-value contract for the frozen feature vector; keep `null`/unavailable or typed invalid state in intermediate objects where supported.

---

## 6. State machine and lifecycle

Implement or reuse an explicit state machine similar to:

```text
DISABLED
  → READY
  → BASELINE
  → STIMULUS_SEQUENCE
  → ANALYSING
  → COMPLETE_VALID

Any active state
  → INCONCLUSIVE / CANCELLED / ERROR
```

Expected behavior:
- The sequence can only run once per challenge attempt.
- A new session resets baseline buffers, ROI histories, callbacks and scores.
- Each challenge event is associated with a session/challenge ID and version.
- Late callbacks from older challenge IDs are discarded.
- A pause, camera loss, face-track loss or user cancellation follows a documented policy.
- The display is always restored to the regular UI after success, timeout, failure or cancellation.
- Camera and background tasks are stopped/released according to lifecycle rules.
- Timeouts produce a specific inconclusive/failure reason; timeout is not automatically proof of spoofing.

---

## 7. Security-policy integration

The optical module is a measurement producer—not the final decision engine.

The existing EdgePPG gate design states, in principle:

```text
if critical_integrity_failure:          → SPOOF
elif insufficient_quality:              → UNCERTAIN
elif challenge_incomplete:              → UNCERTAIN
elif live_probability >= LIVE_THRESHOLD: → LIVE
elif live_probability <= SPOOF_THRESHOLD:→ SPOOF
else:                                   → UNCERTAIN
```

Use the real authoritative gate implementation and policy rather than copying this pseudocode over the current code without review.

Required safeguards:
- Poor sensing or uncertain optical alignment should lead to the documented retry/`UNCERTAIN` behavior, not automatically to `SPOOF`.
- A high optical correlation is not alone sufficient to declare `LIVE`.
- An anomalous optical response must not be labelled a confirmed 3D mask unless the validated PAD evidence and policy support it.
- The optical output must not bypass the ML and security-gate boundaries.
- The dashboard must show the actual optical result separately from `P(LIVE)` and the final system verdict.
- Preserve transcript/signature behavior. Only include optical details in the signed payload if the authoritative transcript contract already includes them or a deliberate protocol version change has been approved.

---

# 8. Master prompt for Antigravity

Copy the prompt below into Antigravity with the EdgePPG project open. Attach the current screenshots and this guide if they are available in the workspace.

```text
PROJECT: EdgePPG — Implement the missing Optical Challenge-Response module
TARGET: Existing Kotlin-first Android application, tested on the physical iQOO 15

ROLE
Act as a senior Android/Kotlin engineer, camera/DSP engineer, and biometric presentation-attack detection researcher. Implement the optical challenge defined in the existing EdgePPG Architecture.md. Do not redesign the overall architecture.

PRIMARY GOAL
Build an actual end-to-end optical challenge, not just a UI card or placeholder telemetry. The current architectural contract is:
randomized screen brightness/color/timing → camera-derived facial ROI response → expected-versus-observed correlation and delay → optical_response_score plus intermediate diagnostics → existing feature assembly → schema validation → ML P(LIVE) → deterministic security gates.

PHASE 1 — SOURCE AUDIT BEFORE MODIFICATION
1. Inspect Git status; preserve all unrelated user changes.
2. Read Architecture.md, Requirements.md, TechStack.md, Workflow.md, the ML roadmap, and the current frozen feature-schema specification. If a named document is absent, record that fact rather than inventing its contents.
3. Find the real camera acquisition, face/ROI tracker, session/challenge generator, rPPG processor, feature assembler, schema validator/preprocessor, ML client, DecisionEngine, dashboard and transcript signer/verifier.
4. Find every optical/light/reflection class, function, field, UI label, asset and test. Determine whether it is active runtime code, an unused prototype, a heuristic, placeholder or merely a UI display.
5. Trace the actual data path with file/function references. Pay special attention to UI claims such as OPTICAL ACTIVE and CLOSED-LOOP REFLECTANCE: labels do not prove runtime implementation.
6. Confirm the exact optical_response_score position and preprocessing contract in the frozen edgeppg-1.0 schema. Do not change the 28-feature count/order or model input. If source and model contracts conflict, stop before integrating the new score and report the conflict.
7. Write a brief audit report and then continue with the implementation if the contract is clear. Stop for user input only if a material architecture/schema/security conflict prevents safe work.

PHASE 2 — DESIGN AND IMPLEMENT
Reuse existing components and idioms. Avoid duplicate camera sessions and parallel ROI trackers.

A. SESSION BINDING
- Generate a fresh challenge sequence for each session using the existing session ID, nonce/challenge seed and protocol conventions.
- Record the sequence version and monotonic timestamps for state changes.
- Ensure delayed results from old sessions/challenges are ignored.

B. STIMULUS RENDERING
- Implement a controlled full-screen or appropriately sized, scoped display stimulus with bounded colour/brightness states.
- Use a configurable sequence and dwell-time policy; do not hard-code many repeated delays through the UI code.
- Record when each state is requested and when it is actually applied as far as Android permits.
- Always restore the regular screen state on success, timeout, exception, lifecycle interruption or cancellation.
- Do not use rapid high-contrast strobing. Show a warning and provide cancel/non-light fallback for light-sensitive users. Validate the earlier proposed 250–400 ms timing rather than treating it as an unconditional requirement.

C. BASELINE AND CAMERA OBSERVATIONS
- Reuse the current CameraX/front-camera pipeline and existing anatomical forehead/left-cheek/right-cheek ROI tracking.
- Collect a stable pre-challenge baseline and frame timestamps.
- During each stimulus step, sample actual camera-derived facial ROI colour values and validity, not screen-colour constants.
- Record camera frame timestamps, ROI validity, sample count, dropped-frame indicators and exposure/white-balance metadata if available.
- Keep the camera analyzer fast and close ImageProxy in all paths.
- Document timestamp alignment between display events, camera sensor frames and callback arrival; do not assume they share an identical clock.

D. RESPONSE ANALYSER
- Separate optical stimulus analysis from rPPG. A display colour transition must not be automatically treated as pulse evidence.
- Use robust multi-pixel statistics within valid skin ROIs; reject invalid/moving/off-face regions.
- Implement documented baseline subtraction/normalization, stimulus-response correlation or sequence agreement, response magnitude, lag/timing estimate and ROI agreement where justified.
- Consider the effects of auto-exposure, auto-white-balance, display tone mapping, ambient light, camera sampling rate and movement.
- Return a typed result containing validity, optical_response_score when valid, intermediate timing/sequence/correlation diagnostics, reason codes and challenge version.
- Use the current model's expected score range/scaling. Do not invent numeric thresholds; derive candidate thresholds from documented requirements and offline/device evaluation.
- If input quality or time alignment is insufficient, produce an invalid/inconclusive result, not a made-up score.

E. STATE MACHINE AND FAILURE HANDLING
- Implement explicit states for ready, baseline, stimulus sequence, analysis, completion, inconclusive, cancelled and error (or map cleanly onto the existing challenge state machine).
- Never let one frame advance multiple steps.
- Reject stale asynchronous callbacks, reset buffers at session boundaries and handle camera/face loss, missing frames, timeout, app pause and user cancellation.
- Challenge timeout or poor optical quality must follow the existing safe policy; it must not automatically be declared a spoof unless the authoritative policy explicitly says so.

F. EDGEPPG INTEGRATION
- Pass the measured optical result into the existing feature assembler, using only the already specified optical_response_score field and its actual contract.
- Keep optical_timing_error, optical_sequence_score, correlation, response magnitude and per-region details as intermediate diagnostics unless a versioned schema migration is explicitly approved.
- Do not alter the frozen feature count/order, training preprocessing, Random Forest model artifact, ML probability semantics, deterministic gates, Android Keystore signing or PC-verification protocol.
- Optical code must not directly set the final LIVE/SPOOF verdict or bypass DecisionEngine.
- Ensure the final transcript represents the actual gate result under the current protocol contract.

G. DASHBOARD
Expose actual values and statuses:
- challenge state and current step;
- actual stimulus state and timestamps;
- baseline/sample count and valid ROI coverage;
- face/ROI quality and frame gaps;
- observed per-region response (with no invented values);
- correlation/sequence score/lag only when calculable;
- optical_response_score and validity/reason code;
- measured analysis latency where instrumented;
- clear boundary between optical diagnostics, P(LIVE), gate reasons and final result.
Use UNAVAILABLE/INVALID/INCONCLUSIVE for missing measurements rather than displaying zero as a fake measurement.

PHASE 3 — SOURCES AND RESOURCE AUDIT
Use these as references, not automatic dependencies:
1. OpenFace Liveness active screen-light reference:
   https://github.com/abhigyaan-jha/open-face-liveness
2. Google ML Kit Face Mesh Android:
   https://developers.google.com/ml-kit/vision/face-mesh-detection/android
3. Google ML Kit Face Mesh concepts:
   https://developers.google.com/ml-kit/vision/face-mesh-detection/concepts
4. Android CameraX ImageAnalysis:
   https://developer.android.com/media/camera/camerax/analyze
5. Android window screenBrightness reference (optional):
   https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness
6. Aurora Guard paper:
   https://arxiv.org/abs/2102.00713
7. Apple in My Eyes (corneal reflection, distinct method):
   https://ieeexplore.ieee.org/document/9924234/
8. OR-PAD dataset/research:
   https://github.com/marukosan93/OR-PAD
9. ORPDAD dataset/research:
   https://github.com/marukosan93/ORPDAD/
10. Digital and Physical-World Attacks on Remote Pulse Detection:
    https://openaccess.thecvf.com/content/WACV2022/html/Speth_Digital_and_Physical-World_Attacks_on_Remote_Pulse_Detection_WACV_2022_paper.html
11. Amazon colour-sequence challenge docs (concept reference only):
    https://docs.aws.amazon.com/rekognition/latest/APIReference/API_rekognitionstreaming_FaceMovementAndLightServerChallenge.html

Inspect licenses and source code before adopting code. OpenFace Liveness is browser-oriented and is not a native Android drop-in. The OR-PAD/ORPDAD dataset terms may restrict use. Never claim that a resource was integrated without actually integrating and testing it.

PHASE 4 — TESTS
Add tests for:
- challenge sequence uniqueness/session binding at the intended interface;
- baseline and response calculation on deterministic synthetic series;
- correct response alignment and known injected delays;
- no response, flat input, saturated data, noisy input, missing ROI and poor frame coverage;
- ROI disagreement and face tracking loss;
- stale callbacks, back-to-back sessions and cancellation;
- timeout and app lifecycle interruption;
- frozen feature count/order and preprocessing parity;
- a valid optical result reaches the feature assembler;
- invalid optical results never become fabricated scores;
- no direct path from optical score to final verdict bypassing DecisionEngine;
- regression tests for UI state vs actual backend state.

Use synthetic tests to validate maths, but do not claim that they prove physical-device optical performance.

PHASE 5 — BUILD AND PHYSICAL DEVICE VALIDATION
- Run relevant Gradle unit tests, lint and assembleDebug.
- Install and open the actual debug APK on the authorized iQOO 15 if connected.
- Run a test matrix: stable genuine participant; changing face distance; head motion; poor ambient light; high ambient light; eyeglasses; temporary ROI loss; printed image; video on another phone/tablet; camera exposure/white-balance changes; interrupted/cancelled challenge.
- Collect measured camera FPS/frame gaps and per-stage latency where instrumented.
- Determine whether the display stimulus causes a measurable and repeatable response in the actual facial ROIs on the target phone.
- Compare the result with known stimulus times and report response quality. If the device cannot provide reliable separation, report a blocker; do not hardcode a passing score.
- Observe accessibility precautions and provide a non-light alternative.

PHASE 6 — FINAL REPORT
Provide:
1. Audit findings and actual source files changed.
2. Before/after runtime data-flow map.
3. Signal-processing formula and score definition.
4. Confirmed schema mapping.
5. Test commands and actual results.
6. Build/APK/install status.
7. Physical iQOO 15 observations and limitations.
8. Reference repositories/papers actually inspected and any licence constraints.
9. Features still unverified.

Never claim that optical liveness is secure against all attacks. Never fabricate values, bypass the gates, change schema/model data without approval, or mark a feature complete because a UI card exists.

BEGIN by auditing the repository and trace the optical runtime path. Then implement the smallest complete vertical slice and validate it.
```

---

## 9. Suggested implementation order

### Phase 0 — Verify what already exists
1. Search the source for the current optical state, score and `optical_response_score`.
2. Follow the call graph from the screen card to real stimulus rendering and from CameraX frames to response analysis.
3. Identify placeholders and report them.
4. Confirm the exact 28-feature input order and whether the training data/model already expects this feature.

### Phase 1 — Build a measurement-only proof of concept
1. Create the session-bound stimulus state machine.
2. Collect baseline and actual per-ROI observations.
3. Log timestamps, sample coverage and validity.
4. Render a diagnostic plot/table of expected stimulus and observed per-ROI signals.
5. Run controlled repeated tests on the iQOO 15 before connecting the output to a verdict or model.

### Phase 2 — Validate the response calculation
1. Use a deterministic synthetic signal to test normalization/correlation/lag code.
2. Test noisy, missing, delayed and saturated data.
3. Record physical-device captures under controlled conditions.
4. Measure within-person repeatability and variation across conditions.
5. Establish documented acceptance thresholds based on data rather than convenient arbitrary values.

### Phase 3 — Integrate into EdgePPG
1. Feed valid `optical_response_score` into the existing, verified feature slot.
2. Preserve intermediate diagnostics separately.
3. Add explicit invalid/inconclusive handling.
4. Test model/preprocessing parity and gate behavior.
5. Verify transcript/result consistency.

### Phase 4 — Evaluate security contribution
Compare baselines with optical evidence added in a controlled evaluation. Include genuine participants, printed photos, replay videos, different display devices and illumination manipulation. Use subject-separated splits where data is sufficient. Report false accepts and false rejects by attack type, along with sample counts and confidence intervals where feasible.

Only use the phrase “improves security” if a fair evaluation demonstrates a meaningful improvement over the baseline under the tested conditions.

---

## 10. Acceptance checklist

The optical challenge is not considered integrated until all applicable items are evidenced:

- [ ] The display renders a session-specific challenge, not merely a decorative animation.
- [ ] Each stimulus transition has an event record/timestamp.
- [ ] Actual camera frames and camera timestamps feed the analyser.
- [ ] Existing anatomical ROIs are used and validity is tracked.
- [ ] Observed facial colour measurements originate from frame pixels.
- [ ] Baseline, normalization, correlation/sequence comparison and delay logic are documented and tested.
- [ ] Low quality/missing data returns invalid/inconclusive, not a fabricated score.
- [ ] No confusion between optical stimulus modulation and the rPPG pulse signal.
- [ ] Existing `optical_response_score` contract is verified and preserved.
- [ ] The frozen 28-feature schema remains unchanged unless a documented migration has been approved.
- [ ] The optical module cannot bypass ML or deterministic security gates.
- [ ] Final transcript uses the real final outcome and preserves the current signing contract.
- [ ] Screen state and resources are restored on success, error, lifecycle interruption and cancellation.
- [ ] Accessible non-light alternative and safety warning exist.
- [ ] Relevant unit tests pass.
- [ ] APK builds successfully.
- [ ] Physical iQOO 15 test results are recorded, or explicitly marked NOT RUN.
- [ ] Dashboard values are traced to real state and measurements.
- [ ] All security-performance claims are bounded by the actual tested attacks and conditions.

---

## 11. References and source notes

1. OpenFace Liveness, repository README and code: <https://github.com/abhigyaan-jha/open-face-liveness>
2. Google ML Kit Face Mesh, Android: <https://developers.google.com/ml-kit/vision/face-mesh-detection/android>
3. Google ML Kit Face Mesh concepts: <https://developers.google.com/ml-kit/vision/face-mesh-detection/concepts>
4. Android CameraX image analysis: <https://developer.android.com/media/camera/camerax/analyze>
5. Android `WindowManager.LayoutParams.screenBrightness`: <https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness>
6. Aurora Guard: Reliable Face Anti-Spoofing via Mobile Lighting System: <https://arxiv.org/abs/2102.00713>
7. Apple in My Eyes (AIME): Liveness Detection for Mobile Security Using Corneal Specular Reflections: <https://ieeexplore.ieee.org/document/9924234/>
8. OR-PAD: <https://github.com/marukosan93/OR-PAD>
9. ORPDAD: <https://github.com/marukosan93/ORPDAD/>
10. Digital and Physical-World Attacks on Remote Pulse Detection, WACV 2022: <https://openaccess.thecvf.com/content/WACV2022/html/Speth_Digital_and_Physical-World_Attacks_on_Remote_Pulse_Detection_WACV_2022_paper.html>
11. Amazon Rekognition Face Liveness colour-sequence challenge API (design reference only): <https://docs.aws.amazon.com/rekognition/latest/APIReference/API_rekognitionstreaming_FaceMovementAndLightServerChallenge.html>

**Resource selection principle:** Start with the native EdgePPG code and official Android/Google documentation. Use the browser-oriented open-source project and research papers as references. Don't add an external dependency unless it materially improves the implementation and its licence, runtime and architecture fit have been confirmed.
