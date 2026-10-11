# EdgePPG — Reference Architecture

> **Purpose:** Reference architecture for auditing the existing EdgePPG implementation.
>
> **Important:** This document does **not** prescribe a particular ML model, model architecture, accuracy target, dataset, or training method. ML is treated as a black-box component with a defined responsibility and interface.
>
> **Audit rule:** Do not redesign the existing system merely to make it match this document. First compare the implementation against this reference and record deviations.

---

## 1. Canonical Runtime Architecture

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
Signed Session Transcript
  ↓
PC Verifier
```

This is the primary flow to cross-check against the actual code.

---

## 2. Architectural Responsibilities

### Camera

Responsible for obtaining the actual session imagery.

**Expected output:**
- Camera frames
- Camera/session state

The production pipeline must consume actual camera data rather than simulated or hardcoded frames.

### Face / ROI

Responsible for locating/selecting the facial region required by downstream processing.

**Expected output:**
- Valid ROI
- ROI validity/state information

The ROI must actually feed downstream processing; merely displaying a detected face is not sufficient.

### rPPG

Responsible for extracting physiological evidence/features from the camera-derived facial signal.

**Expected output:**
- rPPG signal/features
- Validity information where required

Invalid or unavailable rPPG must not silently become fabricated evidence.

### Behaviour / Optical Challenge

Responsible for extracting the behavioural/challenge evidence required by the system.

Examples may include the defined gaze, head, hand, or optical challenge processing.

The exact implementation is not prescribed by this architecture.

### Frozen 28 Features

The production feature boundary is the defined 28-feature schema.

```text
Measurements
    ↓
Feature extraction
    ↓
28-feature vector
```

Required architectural properties:

- Fixed expected dimensionality
- Deterministic feature ordering
- Consistent feature identifiers
- Defined data representation
- No silent schema changes
- No fabricated values for unavailable measurements

The exact definitions of the 28 features belong to the feature/schema specification.

### Schema Validation / Preprocessing

Responsible for ensuring the feature representation is valid for ML inference.

```text
28 Features
    ↓
Schema Validation
    ↓
Preprocessing
    ↓
ML Input
```

This stage must not silently change the feature contract.

---

# 3. ML Boundary

The ML component is intentionally unspecified.

It may use whichever model has been selected for the project.

This document does **not** specify:

- Model family
- Model architecture
- Accuracy
- Training method
- Hyperparameters
- Dataset
- Framework

The architectural requirement is only:

```text
Validated Feature Representation
            ↓
           ML
            ↓
         P(LIVE)
```

### Required ML responsibility

The ML component must:

1. Receive the defined feature representation.
2. Perform the project's selected liveness inference.
3. Produce the liveness probability/score represented as `P(LIVE)`.
4. Pass that result to the deterministic security-gate layer.

### Important separation

```text
P(LIVE)
    =
ML output

LIVE / SPOOF / UNCERTAIN
    =
Final system outcome
```

These must not be treated as the same architectural object.

---

# 4. Deterministic Security Gates

The security-gate layer is after ML.

```text
P(LIVE)
   +
Required security / validity evidence
   ↓
Deterministic Security Gates
   ↓
Final Outcome
```

The exact thresholds and gate conditions belong to the security-gate specification.

The architecture requires exactly three final outcomes:

```text
LIVE
SPOOF
UNCERTAIN
```

### LIVE

The defined requirements for accepting a live session are satisfied.

### SPOOF

The defined evidence is sufficient to classify the session as an attack/spoof.

### UNCERTAIN

The system cannot safely establish either final result.

For example, uncertainty may arise from invalid, unavailable, or insufficient required evidence.

---

# 5. Critical P(LIVE) Boundary

The intended architecture is:

```text
Features
   ↓
ML
   ↓
P(LIVE)
   ↓
Security Gates
   ↓
LIVE / SPOOF / UNCERTAIN
```

Therefore, an implementation should be specifically audited for cases where it instead does:

```text
Features
   ↓
ML
   ↓
if P(LIVE) > threshold
       ↓
     LIVE
else
       ↓
     SPOOF
```

with no required deterministic security/validity layer.

That is an architectural deviation **unless the actual project specification explicitly defines the ML threshold itself as the complete decision mechanism**.

The purpose of this document is to determine what the existing implementation actually does.

---

# 6. Session / Challenge Flow

The conceptual session flow is:

```text
Session Start
     ↓
Nonce / Challenge
     ↓
Camera
     ↓
Face / Identity
     ↓
Gaze / Head / Hand Behaviour
     ↓
rPPG
     ↓
Optical Challenge
     ↓
Feature Construction
     ↓
Schema Validation / Preprocessing
     ↓
ML
     ↓
P(LIVE)
     ↓
Security Gates
     ↓
Final Outcome
     ↓
Transcript
     ↓
Signature
```

The exact internal order of sensing operations can differ where explicitly specified, but the following boundary must remain traceable:

```text
Feature representation
        ↓
       ML
        ↓
    P(LIVE)
        ↓
Security decision
```

---

# 7. Transcript and Signing

After the final security result is determined, the phone creates the session transcript.

Conceptually:

```text
Session information
       +
Required evidence/results
       +
Schema information
       +
P(LIVE)
       +
Gate/result information
       +
Final outcome
       ↓
Session Transcript
       ↓
Signature
       ↓
Signed Transcript
```

The exact cryptographic algorithm is outside this architecture document.

The important architectural requirement is that the signed transcript represents the actual session result produced by the phone.

---

# 8. PC Verifier

The PC is a **verifier**, not a second independent liveness decision engine.

```text
Phone
  │
  │ Signed Transcript
  ▼
PC Verifier
  │
  ├── Verify signature
  ├── Verify session information
  ├── Verify schema/version information
  ├── Verify transcript integrity
  │
  ▼
Display verified result
```

The PC should not independently replace the phone's final security decision with another liveness model/decision engine.

---

# 9. UI / Dashboard

The UI is an observability layer.

It should display actual runtime state, such as:

- Session state
- Camera state
- Face/ROI state
- rPPG state
- Feature validity
- `P(LIVE)`
- Security-gate state
- Final outcome
- Transcript state
- Signature verification state

The UI must not manufacture values merely to make the dashboard appear complete.

Every displayed security value should be traceable to the actual runtime pipeline.

---

# 10. Production vs Research

Research experiments do not automatically become production security signals.

Examples of research-only candidates can include:

- Alternative ROI methods
- Alternative rPPG methods
- Additional PAD models
- Physics-based augmentation
- Environmental forensics
- Lighting/reflection analysis
- PRNU
- Acoustic sensing
- CLIP/VLM challengers
- NPU deployment experiments

Do not silently add experimental signals to the frozen 28-feature production interface.

```text
Research experiment
        ≠
Production security signal
```

A research component becomes production only after it is explicitly selected, validated, and incorporated through the appropriate architecture/schema change.

---

# 11. Data Validity Principle

The implementation must distinguish between:

```text
Valid measurement
Invalid measurement
Unavailable measurement
Processing failure
Valid zero-valued measurement
```

These are not automatically equivalent.

For example:

```text
rPPG unavailable
```

must not automatically become:

```text
rPPG = 0
```

unless the defined feature specification explicitly requires that representation.

When required evidence cannot be established, the system should follow its validity/gating logic rather than fabricate evidence.

---

# 12. Architectural Invariants

### INV-01 — ML output

```text
ML → P(LIVE)
```

### INV-02 — Decision boundary

```text
P(LIVE) → Deterministic Security Gates → Final Outcome
```

### INV-03 — Final outcomes

Exactly:

```text
LIVE
SPOOF
UNCERTAIN
```

### INV-04 — Frozen feature interface

Production ML input follows the defined 28-feature contract.

### INV-05 — No silent fabrication

Unavailable/invalid measurements must not silently become fabricated feature values.

### INV-06 — Phone is the decision origin

The phone performs the production inference and security decision.

### INV-07 — PC is a verifier

The PC verifies the phone's signed result rather than becoming a second decision engine.

### INV-08 — Transcript follows the decision

The transcript represents the actual session result and is signed over the defined representation.

### INV-09 — UI reflects runtime state

Dashboard values originate from actual runtime state.

### INV-10 — Research isolation

Experimental research signals do not silently enter the production feature schema or security decision.

---

# 13. Implementation Audit Checklist

## Camera

- [ ] Camera capture exists
- [ ] Actual frames reach processing
- [ ] Runtime camera state is observable
- [ ] No hardcoded/demo frames are used in production

## Face / ROI

- [ ] Face/ROI processing exists
- [ ] ROI feeds downstream processing
- [ ] Invalid ROI is handled
- [ ] ROI is not only a UI feature

## rPPG

- [ ] rPPG processing exists
- [ ] Camera-derived data reaches rPPG
- [ ] rPPG output reaches feature extraction
- [ ] Invalid/unavailable rPPG is handled correctly

## Behaviour / Optical Challenge

- [ ] Behaviour/challenge processing exists
- [ ] Real session input reaches it
- [ ] Its output reaches feature construction
- [ ] Production path is not merely simulated

## 28 Features

- [ ] Feature vector is constructed
- [ ] Expected schema is used
- [ ] Feature ordering is deterministic
- [ ] Schema is validated
- [ ] No silent zero-fill/fabrication
- [ ] Feature vector reaching ML is traceable

## ML

- [ ] ML component exists
- [ ] Defined feature representation reaches ML
- [ ] ML produces `P(LIVE)`
- [ ] `P(LIVE)` can be observed/traced at runtime
- [ ] `P(LIVE)` is passed to the security-gate layer
- [ ] ML is not incorrectly responsible for the complete final security decision

## Security Gates

- [ ] Deterministic security/validity gates exist
- [ ] Gates receive required evidence
- [ ] Gates can produce `LIVE`
- [ ] Gates can produce `SPOOF`
- [ ] Gates can produce `UNCERTAIN`
- [ ] UI cannot bypass the gate logic

## Transcript

- [ ] Session result is represented
- [ ] Required session/schema information is represented
- [ ] Relevant ML/gate result is represented
- [ ] Transcript uses actual runtime data

## Signing

- [ ] Transcript is signed
- [ ] Signature covers the defined transcript
- [ ] Signing uses the real session result
- [ ] No static/demo transcript is used

## PC Verifier

- [ ] PC receives signed transcript
- [ ] Signature is verified
- [ ] Session/schema information is verified
- [ ] Transcript integrity is checked
- [ ] PC displays verified result
- [ ] PC does not replace the phone's security decision

## UI

- [ ] UI displays actual runtime state
- [ ] `P(LIVE)` shown is the actual ML output
- [ ] Gate state shown is the actual gate state
- [ ] Final outcome shown is the actual final outcome
- [ ] No placeholder values are presented as real values

---

# 14. Audit Classification

For every component, use exactly one status:

```text
IMPLEMENTED
PARTIAL
MISSING
DEVIATION
UNVERIFIED
```

### IMPLEMENTED

The component exists, follows the reference responsibility/data flow, and has sufficient evidence.

### PARTIAL

Some required behavior exists but the complete architectural path is not established.

### MISSING

The required component/responsibility is absent.

### DEVIATION

The implementation materially differs from the reference architecture.

### UNVERIFIED

The code appears to contain the component, but real runtime behavior has not yet been established.

---

# 15. Most Important Audit Method

Do not audit by looking only for filenames or classes.

Trace one real session's data.

Start with:

```text
Camera frame
```

Then follow:

```text
Camera
 ↓
ROI
 ↓
rPPG / Behaviour / Challenge
 ↓
28 Features
 ↓
Schema Validation / Preprocessing
 ↓
ML
 ↓
P(LIVE)
 ↓
Security Gates
 ↓
LIVE / SPOOF / UNCERTAIN
 ↓
Transcript
 ↓
Signature
 ↓
PC Verification
```

For every arrow, answer:

> Where is the actual output of the previous stage consumed by the next stage?

This is the key architectural audit.

A class/function existing in the repository is not proof that the runtime pipeline uses it.

---

# 16. Antigravity Audit Instruction

Use this prompt when asking an implementation agent to audit the project:

```text
Treat Architecture.md as the reference architecture.

DO NOT redesign the architecture.

DO NOT replace the ML model.

DO NOT judge conformance based on ML accuracy.

Audit the existing implementation against the responsibilities, data-flow boundaries, and invariants defined in Architecture.md.

Trace whether the actual runtime follows:

Camera
→ Face/ROI
→ rPPG
→ Behaviour/Optical Challenge
→ Frozen 28 Features
→ Schema Validation/Preprocessing
→ ML
→ P(LIVE)
→ Deterministic Security Gates
→ LIVE/SPOOF/UNCERTAIN
→ Signed Transcript
→ PC Verifier

For every stage report:

1. Source files/classes/functions involved
2. Actual input
3. Actual output
4. Where that output is consumed
5. Whether the runtime data path is proven
6. Status:
   IMPLEMENTED / PARTIAL / MISSING / DEVIATION / UNVERIFIED
7. Evidence
8. Required correction, if any

Do not modify the implementation during the first audit.

Do not assume that a UI element, class, function, or existing checkmark proves that the underlying pipeline is operational.

First produce a deviation/audit report.
Only after the audit should implementation corrections be proposed.
```

---

# 17. Definition of Conformance

The implementation is architecturally conformant when the real runtime demonstrates the intended boundary:

```text
                         ML
                          │
                          ▼
                    ┌──────────┐
Features ──────────►│ P(LIVE)  │
                    └────┬─────┘
                         │
                         ▼
                ┌─────────────────┐
                │ Security Gates  │
                └───────┬─────────┘
                        │
             ┌──────────┼──────────┐
             ▼          ▼          ▼
           LIVE       SPOOF     UNCERTAIN
             │          │          │
             └──────────┼──────────┘
                        ▼
                 Signed Transcript
                        │
                        ▼
                  PC Verification
```

**The purpose of this document is architectural cross-checking. It does not prescribe the ML model or evaluate ML accuracy.**
