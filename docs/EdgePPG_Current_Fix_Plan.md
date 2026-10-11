EdgePPG — Current Fix Plan

Purpose: Concrete implementation checklist based on the uploaded End-to-End Integration Audit.

Current status: MOSTLY INTEGRATED. Core Camera → Face → rPPG → 28 features → ML → security → signing → PC verification is connected; Hand Landmarker and optical closed-loop measurement are not fully connected.

1. Fix Order

Security correctness fixes

Session reset/state correctness

Camera performance and frame-rate stability

Hand Landmarker → Behaviour → Challenge integration

Optical stimulus → camera response → optical score integration

Full physical-device regression testing

2. P0 — Security-Critical Fixes

2.1 Multi-face rejection

Current issue: RoiTracker uses meshes[0], so multiple faces can silently result in selecting the first face.

0 faces → no-face state / UNCERTAIN.

1 face → normal processing.

>1 faces → multiple-faces state and UNCERTAIN.

Never select meshes[0] when multiple faces exist.

Expose faceCount/multipleFaces to the decision layer.

Add tests for 0, 1 and 2+ faces.

2.2 Fix the no-face quality-gate override

Current issue: the audit reports that a quality override can potentially allow a high ML score to reach LIVE even when no face exists.

No valid face must never produce LIVE.

No-face must deterministically route to UNCERTAIN.

This condition must be independent of ML probability.

Add a regression test proving P(LIVE)=0.99 cannot override no-face.

2.3 Remove fabricated feature fallbacks

Current issue: missing measurements are replaced with constants such as face confidence 0.95 and face quality 0.80.

Remove fabricated security-relevant constants.

Use validated measurements when available.

Represent unavailable measurements using the existing NaN/invalid contract.

Never silently turn missing evidence into positive evidence.

Preserve the frozen 28-feature ordering and model preprocessing.

2.4 Session reset leakage

Current issue: RoiTracker state can survive a session restart.

Add RoiTracker.reset().

Clear lastRois.

Clear smoothing/EMA state.

Clear face-present state.

Call reset() at every new verification session.

Test that no previous-session ROI/evidence is reused.

3. P1 — Camera Performance

The audit reports synchronous FaceMesh and Image Labeler work on the same analyzer thread, potentially blocking for ~550 ms and reducing processing to roughly 2–4 FPS.

Measure actual camera arrival FPS and analyzer processing FPS on the iQOO.

Identify whether Image Labeler is required on every frame.

Avoid blocking the CameraX analyzer with long synchronous waits.

Keep one unified camera stream.

Do not weaken security gates to hide performance problems.

After optimization, verify rPPG timestamp/sample quality and FPS/drop-rate gates.

4. P1 — Complete Hand Challenge Integration

Current state: hand challenge generation/UI/TTS exists, but CameraSession never invokes a Hand Landmarker. The observed gesture is null and hand challenges time out.

Required runtime path:

Camera frame → Hand Landmarker → hand landmarks → action detector → ObservedAction → ChallengeVerifier → PASS/FAIL/TIMEOUT → behaviour evidence → feature/security path.

Use the existing MediaPipe Hand Landmarker if already present; do not duplicate it.

Feed real camera frames into it.

Keep hand observations separate from face/rPPG ROI data.

Generate structured observed actions.

Verify gesture type, hand side, duration and confidence.

Use temporal confirmation rather than one-frame PASS.

Respect challenge timeout.

Bind results to sessionId/sessionNonce/challengeId/challengeNonce.

Record evidence for debugging/transcript.

Minimum hand tests

Correct gesture → PASS.

Wrong gesture → not PASS.

No gesture → TIMEOUT.

Correct gesture with wrong hand → not PASS.

Gesture before challenge → cannot satisfy the new challenge.

Temporary landmark loss → cannot create false PASS.

Two hands present → deterministic behavior.

5. P1 — Complete Optical Closed-Loop Integration

Current state: the flash overlay works, but camera-measured skin reflectance response is absent; liveOpticalResponseScore remains NaN.

Required runtime path:

Fresh optical challenge → display stimulus → capture camera response → validated facial skin ROI → measure temporal reflectance change → quality/validity checks → optical response score → feature/security evidence.

Do not treat the screen flash itself as a successful optical response.

Measure camera-observed response from the current facial skin ROI.

Bind measurement to the current face and session.

Reject invalid/no-face measurements.

Account for exposure/auto-control effects and document the chosen handling.

Use session/challenge-specific stimulus.

Produce defined validity/confidence.

Never fabricate an optical score.

6. rPPG — Keep It, Verify It

The audit confirms actual facial ROI pixels feed POS/CHROM → bandpass → FFT. Do not redesign this without evidence.

Only validated facial ROIs feed rPPG.

Never use hand/generic-frame ROI as a fallback.

No-face must invalidate rPPG rather than reuse stale data.

Eliminate session-boundary stale ROI state.

Verify actual extracted pixels, not just overlays.

After performance changes, re-measure SNR, HR stability and ROI agreement.

7. 28-Feature Contract — Freeze It

Keep exact feature order.

Keep model preprocessing compatible.

Remove fabricated positive fallbacks.

Populate hand features only from real hand observations.

Do not claim optical feature validity until closed-loop measurement exists.

Prevent stale values from crossing sessions.

Keep NaN/missing-value handling compatible with the trained model.

8. ML — Do Not Change the Model Yet

Keep the current 300-tree model and inference path.

Do not retrain merely because upstream evidence is currently incomplete.

Fix evidence generation first.

Verify the same 28-feature contract reaches the model.

Use P(LIVE) as evidence, not as the sole security decision.

9. Security Decision Invariants

No face → never LIVE.

Multiple faces → never LIVE.

Invalid integrity/session → never LIVE.

Incomplete mandatory challenge → never LIVE.

Missing evidence must not become positive evidence.

ML probability cannot override mandatory security failures.

UNCERTAIN must remain available for insufficient-quality/incomplete evidence.

10. Transcript / PC Verifier

Keep the existing canonical transcript/signing path.

Ensure challenge results belong to the current session.

Ensure evidence fields represent what was actually measured.

Never sign a transcript claiming an optical/hand result that was not measured.

Re-run replay/freshness tests after session-state changes.

11. Required End-to-End Tests

Test

Action

Required Result

Normal live user

Complete correct verification

All required evidence valid

No face

Remove face

Never LIVE

Multiple faces

Add second face

UNCERTAIN / multiple-faces

Wrong hand

Wrong gesture/side

Challenge not passed

Correct hand

Requested gesture

Challenge PASS

Hand timeout

Do nothing

TIMEOUT

Optical response

Run optical challenge

Actual camera-derived response

Optical invalid

No valid face during flash

No fabricated optical PASS

Session restart

Run A then B

No stale state from A

Low FPS

Stress processing

Correct quality gate

Replay/stale challenge

Reuse old evidence

Rejected

High ML + security failure

Force high P(LIVE)

Never LIVE

12. Do NOT Do These Yet

Do not add another ML model.

Do not redesign the 28-feature schema.

Do not replace rPPG without evidence of a defect.

Do not add more biometric primitives before fixing integration.

Do not hide missing hand/optical evidence with constants.

Do not mark a challenge PASS merely because it was displayed.

Do not treat a screen flash as optical PAD measurement.

Do not optimize by disabling mandatory security gates.

13. Definition of Done

No-face and multi-face states are correctly enforced.

No stale ROI/session state crosses sessions.

No fabricated security feature values remain.

Camera processing has an acceptable measured frame rate.

Hand Landmarker receives real camera frames.

Hand challenge results are genuinely observed and verified.

Optical challenge has genuine camera-measured response, or is explicitly marked unavailable and excluded from claims.

28-feature vector remains schema-compatible.

ML remains connected to the same vector.

Security gates correctly dominate ML when mandatory evidence is missing.

Transcript accurately represents actual measurements.

PC verifier accepts valid sessions and rejects replay/invalid evidence.

Existing tests plus new integration tests pass.

At least one physical-device session is traced from camera frame to signed verdict.

14. Exact Execution Sequence

Fix multi-face rejection.

Fix no-face security-gate override.

Add RoiTracker.reset() and invoke it at session start.

Remove fabricated face-confidence/quality fallbacks.

Measure and fix camera analyzer blocking/FPS.

Integrate MediaPipe Hand Landmarker into the existing CameraSession.

Connect hand observations to the existing ChallengeVerifier/BehaviourRunner.

Test all hand challenge states.

Implement optical closed-loop response measurement.

Connect optical score only after its measurement is validated.

Run the complete 28-feature/model/security regression suite.

Run physical iQOO end-to-end tests.

Re-run the full integration audit.

Source

Based on the uploaded EdgePPG Full End-to-End Integration Audit. The audit reports the system as MOSTLY INTEGRATED and identifies Hand Landmarker and optical closed-loop measurement as disconnected, plus multi-face, no-face gate, session-reset and fabricated-fallback issues.