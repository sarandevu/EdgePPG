# EdgePPG — Acoustic Sensing Research, GitHub Resources, ML Plan, and Android Implementation Guide

**Document type:** Research and implementation guide  
**Project:** EdgePPG — on-device multimodal active liveness / Video KYC research  
**Primary target device:** iQOO 15 (Android)  
**Research emphasis:** Smartphone acoustic FMCW sensing  
**Status:** Research plan; not evidence that acoustic sensing has been implemented or validated on the target phone  
**Prepared:** 10 October 2026

---

## 1. Executive summary

EdgePPG's research roadmap mentions acoustic FMCW sensing. The recommended first step is **not** to download a generic audio model and immediately make it part of the liveness decision. First establish that the phone can transmit a repeatable acoustic signal, record the reflections, and recover a useful measurement from real recordings. Only then decide whether a learned model is needed.

There are three distinct problems that are easy to confuse:

1. **Acoustic FMCW sensing:** The phone plays a known frequency-swept chirp and records its echoes. Signal processing estimates range and motion-related changes. It may become a source of additional evidence, but it does not automatically prove liveness.
2. **Acoustic gesture/action recognition:** A system classifies movements from the echo patterns produced by gestures. This requires action labels and data collected with a compatible acoustic setup.
3. **Speech/audio anti-spoofing:** A model classifies speech recordings as bona fide or spoofed, for example replayed, synthesized, or converted speech. AASIST and ASVspoof baselines are relevant to this task—not to FMCW echo sensing.

For the EdgePPG roadmap, work on **(1) FMCW sensing first**, then consider **(2) acoustic gesture recognition** if it serves a defined challenge. Treat **(3) speech anti-spoofing** as a separate optional research branch unless the product explicitly needs it.

### Recommended order

1. Read the acoustic sensing thesis and the repositories listed below.
2. Reproduce the offline MATLAB processing on sample recordings.
3. Run a hardware feasibility test on the iQOO 15 at conservative output levels; record the actual transmitted/received signal rather than assuming the device supports the expected frequencies.
4. Build a simple, measurable signal-processing baseline without ML.
5. Collect a labelled dataset for one clearly defined task.
6. Train a small baseline classifier on the PC only if the task needs classification.
7. Test on participants and conditions excluded from training.
8. Only after those steps consider Android deployment and a versioned EdgePPG integration.

**Do not change the frozen `edgeppg-1.0` 28-feature schema merely to insert experimental acoustic values.** Keep this branch isolated until the sensing method and evidence are validated. Any production use would require an explicitly versioned schema/interface migration, model retraining or revalidation, and regression tests.

---

## 2. What “acoustic sensing” means

Acoustic sensing uses sound emitted by a speaker and captured by a microphone to infer information from the returning signal. In active acoustic sensing, the system knows the signal it sent, so it can compare the microphone recording with that reference.

### 2.1 FMCW in plain language

FMCW means **Frequency-Modulated Continuous Wave**. Instead of emitting only a short click, the system continuously sweeps through a frequency range, creating a known chirp. A phone can play the chirp and record sound while it is being played. Reflections from objects and people combine with the direct speaker-to-microphone path in the recording.

The received waveform is not a clean recording of the echo alone. It can include:

- direct acoustic leakage from the phone speaker into its own microphone;
- reflections from the person, desk, walls and other objects;
- movement and small changes in distance;
- room reverberation and multipath reflections;
- microphone, speaker and Android audio processing effects;
- automatic gain control, noise suppression, echo cancellation or other system-level processing, depending on the device and audio route.

The signal-processing pipeline attempts to isolate the relevant information. FMCW processing often mixes the received chirp with a reference chirp (dechirping), then analyses the resulting beat frequency. A range FFT can map the beat-frequency information to range bins. Changes across time, including phase changes, may reveal motion.

A simple conceptual ranging equation is:

\[
R \approx \frac{c\tau}{2}
\]

where \(R\) is the target range, \(c\) is the speed of sound, and \(\tau\) is the round-trip delay. In a practical FMCW pipeline, range is generally derived from the relationship between the chirp slope and beat frequency rather than by relying only on a direct time-of-flight estimate.

### 2.2 What it may detect

Depending on the waveform, hardware, processing and environment, acoustic sensing may support:

- coarse distance or range-bin estimation;
- target motion toward or away from the phone;
- changes in a tracked reflection over time;
- gesture classes, if a dedicated and validated classifier is trained;
- subtle motion such as breathing, under a carefully designed sensing setup.

The last point is research-sensitive. A weak motion signature does not by itself establish a reliable heart-rate measurement, identity, or liveness result. Acoustic vital-sign research uses particular microphone layouts, signal processing and experimental conditions; those results cannot automatically be transferred to every Android phone.

### 2.3 What it does *not* prove

The detection of an echo, person-shaped target, motion or breathing-like periodicity is not itself proof of a genuine live KYC participant. A spoofing claim needs a well-defined attack model and experiments comparing genuine sessions with relevant attacks. Do not describe an acoustic signal as an anti-spoof detector until that claim has been tested.

---

## 3. Three different acoustic/ML directions

| Direction | Input | Output | Requires ML immediately? | Relevance to current EdgePPG roadmap |
|---|---|---|---|---|
| Acoustic FMCW sensing | Known emitted chirp + microphone recording | Range/motion-related signal measurements and quality indicators | **No.** Start with DSP and hardware validation | **Primary research track** |
| Acoustic gesture/action recognition | FMCW recordings or processed range-time/phase features labelled by action | Action label and confidence | Not necessarily; start with engineered features and a simple baseline | Optional next step if acoustic gestures are required |
| Speech/audio anti-spoofing | Speech waveform or speech representation | Bona fide/spoof score for speech attacks | Usually yes, using a trained audio classifier | Separate track; only relevant if the system needs speech replay/deepfake detection |

Another separate concept is **voice command recognition**. That recognizes words or commands and should not be confused with FMCW gesture sensing or speech anti-spoofing.

---

## 4. Recommended GitHub repositories and research resources

The resources below have different purposes. None should be described as a ready-made, pretrained acoustic KYC liveness model unless its documentation and evaluation actually establish that capability.

### 4.1 UltraSense — Android ultrasound sensing toolkit

**Repository:** <https://github.com/jkarolus/UltraSense>  
**License shown by repository:** GPL-2.0  
**What it contains:** An Android application called PulseRadar that can record audio while emitting continuous-wave (CW) and FMCW signals, MATLAB scripts for analysing recordings, and example audio files. The documented analysis includes FFT and Doppler-related analysis around 20 kHz. The project says its sample recordings were recorded using a Samsung Galaxy S3 microphone.

**Use it for:** Understanding Android full-duplex audio capture and playback, recording test signals, and learning the general signal-analysis flow.

**Do not assume:** The old sample hardware behaves like the iQOO 15; its outputs form an Android-ready ML model; or its default configuration will work unchanged on a modern phone.

**License caution:** GPL-2.0 has redistribution and derivative-work obligations. Use it for research/reference only until the team has reviewed license compatibility for the intended project. Do not copy source files into another codebase without understanding the license.

Repository: <https://github.com/jkarolus/UltraSense>

### 4.2 Target Detection with a Smartphone — FMCW ranging baseline

**Repository:** <https://github.com/Dharani-27/target-detection-with-SM>  
**Documented purpose:** Smartphone FMCW acoustic target detection and ranging for static and moving targets. The README describes generating a near-ultrasonic chirp in an 18–22 kHz sweep, recording its reflections and processing the recording offline in MATLAB.

The documented processing includes:

1. Aligning a received recording with the transmitted chirp template using cross-correlation.
2. Mixing down/dechirping to baseband I/Q.
3. Applying a range FFT.
4. Selecting a target range bin.
5. Estimating distance and phase-based sub-wavelength motion.

**Use it for:** A concrete first signal-processing baseline and to understand the order of the major DSP operations.

**Do not assume:** The code is production-ready Android software or a trained classifier. Inspect the repository's current files, requirements, data assumptions and license before reuse; do not assume an explicit license exists unless the repository provides one.

Repository: <https://github.com/Dharani-27/target-detection-with-SM>

### 4.3 FMCW Acoustic Gesture Recognition — research project

**Repository:** <https://github.com/lauren2018/fmcw-acoustic-gesture-recognition>  
**Documented purpose:** A 2018 university project on smartphone gesture recognition using FMCW and acoustic signals. It includes MATLAB scripts and sample `.pcm`/`.wav` recordings.

**Use it for:** Learning how researchers structure an acoustic gesture pipeline, inspect signal samples and explore the processing code before defining a new dataset.

**Limitations:** It is an older research project. Its public description does not establish that it offers a pretrained model that recognizes EdgePPG's custom gestures or attacks. Treat its code and sample data as research material, not as a verified drop-in dependency. Verify the repository license before reusing code or datasets.

Repository: <https://github.com/lauren2018/fmcw-acoustic-gesture-recognition>

### 4.4 Master's thesis — smartphone microphone/speaker acoustic sensing

**University repository link:** <https://hdl.handle.net/20.500.12741/rep:2265>

The thesis is titled *Health monitoring using acoustic sensing with smartphone microphone and speaker* (Azhar Chara, California State University, Sacramento, 2022, as listed in the repository source discovered for this guide). It is useful for studying the end-to-end research problem: signal emission, recording, signal extraction, experimental setup and limitations.

**Use it for:** Building intuition before implementing the Android pipeline. Read the methodology and evaluation sections closely; note the exact hardware, distance, room conditions, signal frequencies and assumptions. Do not transplant physiological-sensing results into a KYC liveness claim.

If the link or document has moved, search the title in the university repository rather than downloading an unverified mirror.

### 4.5 PowerPhone — smartphone acoustic sensing research

**Article:** <https://pmc.ncbi.nlm.nih.gov/articles/PMC12765216/>

This research discusses the acoustic sensing potential of smartphone speaker/microphone hardware and the practical frequency-response constraints of commodity devices. It is useful background for the central hardware question: a phone's speaker and microphone are not laboratory transducers, and both the acoustic path and device processing affect what can be measured.

**Use it for:** Planning device feasibility tests and understanding why a technique demonstrated on one handset must be validated on another handset.

### 4.6 Newer acoustic physiology research

**Article:** <https://www.mdpi.com/1424-8220/26/14/4591>

The paper *Smartphone-Based Acoustic Sensing for Breathing and Heartbeat Detection via AoA Clustering in Indoor Environments* describes a more advanced FMCW approach that uses range information, angle-of-arrival (AoA)-related phase information, a range–angle–time representation, clustering and a learned association method.

**Use it for:** A more advanced research path after basic chirp capture and range processing work reliably. It is not a simple drop-in EdgePPG model, and the paper's physiological sensing objective differs from KYC presentation-attack detection.

### 4.7 Android official audio APIs

- [`AudioRecord` API](https://developer.android.com/reference/android/media/AudioRecord) — manages capture from Android audio input hardware; applications read captured data from it.
- [`AudioTrack` API](https://developer.android.com/reference/android/media/AudioTrack) — plays PCM buffers to the Android audio output.

These are authoritative API references for implementing a custom Kotlin audio pipeline. Check the API behavior on the actual target device and Android build. A valid API call does not guarantee that the speaker/microphone frequency response is suitable for FMCW at a desired frequency.

### 4.8 Separate track only: AASIST and ASVspoof

If the intended task turns out to be **speech replay/synthesis/deepfake detection**, use a speech anti-spoofing baseline—not the FMCW repositories above.

- **AASIST GitHub:** <https://github.com/clovaai/aasist>
- **ASVspoof 5 baselines:** <https://github.com/asvspoof-challenge/asvspoof5>
- **ASVspoof 2021 information:** <https://www.asvspoof.org/index2021.html>

AASIST's repository includes training/evaluation code and reports pretrained AASIST/AASIST-L evaluation results on the task/dataset described in its README. These published figures are not a prediction of EdgePPG performance. ASVspoof data and models concern audio spoofing of speech, not ultrasonic echo-based gesture recognition. Dataset terms, model code license, pretrained checkpoints and deployment size must be reviewed separately.

---

## 5. How to download the resources

The commands below create a research workspace separate from the EdgePPG Android application. Keep research code separate until its requirements and license are reviewed.

### 5.1 Windows PowerShell — clone the primary repositories

```powershell
mkdir EdgePPG-Acoustic-Research
cd EdgePPG-Acoustic-Research

# Android acoustic capture/playback plus MATLAB reference scripts
 git clone https://github.com/jkarolus/UltraSense.git

# FMCW smartphone target detection and ranging baseline
 git clone https://github.com/Dharani-27/target-detection-with-SM.git

# Older FMCW acoustic gesture-recognition research project
 git clone https://github.com/lauren2018/fmcw-acoustic-gesture-recognition.git
```

If `git` is not installed, install Git for Windows and reopen PowerShell. If a repository cannot be cloned, open its GitHub page and use **Code → Download ZIP**. After extraction, preserve the original README, license and any attribution files.

### 5.2 Optional download — speech anti-spoofing research only

Do this only if the requirement is fake/replayed speech detection, not active FMCW sensing.

```powershell
git clone https://github.com/clovaai/aasist.git
git clone https://github.com/asvspoof-challenge/asvspoof5.git
```

Do not immediately download every dataset/checkpoint. First inspect the README, environment setup, data usage terms, storage requirement, and the task the model was trained to solve.

### 5.3 Verify every download

For each repository:

1. Read `README.md` and the license file.
2. Record the commit hash (`git rev-parse HEAD`).
3. Record the required language/runtime and version requirements.
4. Identify whether it contains signal-processing scripts, training code, a pretrained checkpoint, or all three.
5. Run the original documented example before adapting it.
6. Keep a small source register with the original URL, commit, license and planned use.

Do not copy a model file into Android assets until its format, license, task and inference dependencies are understood.

---

## 6. What ML models need to be trained?

### Model A — no ML: FMCW signal-processing baseline

**This is the first implementation task.** Before training any classifier, build a repeatable numerical processing pipeline.

Expected modules:

1. **Chirp generator:** Generate a known waveform with configurable start/stop frequency, duration, amplitude and repetition timing.
2. **Playback/recording controller:** Start `AudioTrack` output and `AudioRecord` input with controlled settings and capture timestamps/state.
3. **Recording validation:** Check that the input buffer is not clipped, empty, truncated or dominated by noise; record actual sample rate and audio route.
4. **Alignment:** Align the received signal to the expected transmit sequence where the algorithm requires it.
5. **Direct-path/clutter handling:** Characterize the speaker-to-microphone leakage and environmental reflections before applying a correction. Do not blindly subtract a filter that could remove the desired reflection.
6. **Dechirping / baseband conversion:** Compare the received chirp to the transmitted reference using the method chosen from the paper/repository.
7. **Range FFT:** Convert the beat signal into range bins using the declared chirp parameters and sample rate.
8. **Target-bin selection:** Select a stable, justified target bin; include an explicit failure outcome when it cannot be selected confidently.
9. **Temporal analysis:** Track changes in range/amplitude/phase across successive chirps when the signal supports them.
10. **Quality/status output:** Emit measurable values and validity flags; never return a fabricated range/motion result after a failed measurement.

Deliverable: an offline baseline that accepts a real WAV/PCM recording and exports repeatable plots, measurements and a machine-readable result file. At this point, no ML classifier is required.

### Model B — optional acoustic action/gesture classifier

Train this only if EdgePPG needs to identify a defined set of gestures from acoustic reflections.

**Start with a non-neural baseline:**

- SVM or Random Forest on engineered features such as stable range-bin amplitude, temporal energy, phase-change statistics and motion features.
- Establish a simple majority/threshold or template baseline for comparison where appropriate.

**Then evaluate a learned time-series model if data supports it:**

- A small CNN on range–time maps or spectrogram-like representations.
- A temporal CNN or another sequence model if the ordering and duration of gestures is important and sufficient data is available.

**Do not pick a model based on popularity.** Use a held-out subject/session evaluation to determine whether a learned model adds value over simpler signal-processing rules.

Define a limited label set first, for example:

- `NO_TARGET_OR_NO_VALID_ECHO`
- `STATIC_TARGET`
- `MOTION_TOWARD`
- `MOTION_AWAY`
- `LATERAL_OR_OTHER_MOTION` (only if your hardware and features can distinguish it)
- custom gesture classes only after each class can be collected repeatably.

Those are examples, not a prescribed label set. Replace them with labels that match the actual research hypothesis. Do not claim that acoustic sensing recognizes “thumbs up” or “pointing” just because a visual hand model can do so; the acoustic classifier must be trained and evaluated on the acoustic task.

### Model C — acoustic liveness/presentation-attack classifier (research only)

This is a different and harder objective. It asks whether acoustic measurements add reliable evidence for distinguishing a genuine session from specified attack scenarios.

Before training:

1. Define which attacks you expect to detect and why an acoustic reflection should differ.
2. Define the unit of evaluation: session, challenge, recording or person.
3. Collect genuine and attack examples using the actual device, with ground-truth labels and consistent capture metadata.
4. Prevent leakage: avoid splitting near-identical frames/windows from one recording across training and test.
5. Establish simple, transparent baselines before trying a neural model.
6. Evaluate generalization to unseen participants, rooms, distances, lighting/noise conditions (where relevant) and attack devices.

A CNN or temporal model can be considered only after this dataset exists. Do not call it an acoustic anti-spoofing model unless the train/test task actually includes presentation attacks. A classifier trained only on gestures, breathing, or target motion is not automatically a liveness classifier.

### Model D — voice replay/deepfake detector (separate, optional)

If the project needs to detect spoofed **speech**, investigate AASIST or a relevant ASVspoof baseline. This model processes a speech waveform or audio representation; it does not replace the FMCW capture/DSP pipeline. Its dataset, class labels, evaluation metrics and deployment path are separate.

### Model E — EdgePPG visual/acoustic fusion model (later)

Only after acoustic sensing is independently validated should you test whether it adds information to the existing visual/physiological pipeline. Do not force it into the existing 28-feature contract.

A production fusion change requires:

- a formally versioned feature schema/interface;
- updated training data with acoustic measurements and their validity states;
- subject/session-aware splits and evaluation;
- calibrated models and re-evaluated decision thresholds;
- PC reference inference and Android-vs-PC parity tests;
- updated transcript/schema version handling if the result contract changes;
- a documented rollback path to the prior production version.

---

## 7. Dataset and experiment design

### 7.1 Recordings need metadata

Every capture session should record, at minimum:

- anonymized participant/session identifier;
- handset model and OS/build;
- app/research build and commit hash;
- playback sample rate, requested and observed configuration, waveform parameters and amplitude;
- recording sample rate, channel count, PCM format and audio route;
- distance/position/orientation and room condition;
- action or target label and timing;
- whether the recording was valid, clipped, interrupted or excluded, with a reason;
- any calibration step and the version of the processing pipeline.

Record enough metadata to reproduce the experiment. Avoid collecting unnecessary personal data. Store raw audio securely and do not commit private recordings or any sensitive KYC data to public GitHub.

### 7.2 Split strategy

Do not randomly split adjacent windows from the same capture into train and test. That can make a model appear strong because it sees nearly the same environment and person in both sets.

Prefer splits by participant and session; keep the final test set untouched while tuning. If the intended deployment is a single handset model, still test separate rooms and distances to understand how brittle the signal is. If the claim is cross-device generalization, test on different devices rather than assuming it.

### 7.3 Metrics

For the signal-processing baseline, report:

- valid-capture rate and failure reasons;
- repeatability over repeated captures;
- range error against known distances if estimating distance;
- noise/clutter sensitivity and quality indicators;
- latency per chirp/window and end-to-end capture time;
- the conditions under which the result must be declared unavailable.

For an acoustic gesture classifier, report:

- per-class precision and recall;
- macro-F1 and confusion matrix;
- held-out participant/session performance;
- false detections when there is no valid echo;
- inference latency and memory on the target device if deployed.

For an acoustic presentation-attack model, use metrics suitable for the precise threat model and report attack types separately. A speech anti-spoof metric such as EER on ASVspoof data is not a KYC acoustic-FMCW security result. Include false accepts/rejects and uncertainty intervals where feasible.

### 7.4 Compare against a no-acoustic baseline

To claim the acoustic branch improves EdgePPG, compare the existing validated visual/physiological system against the same system with acoustic evidence under the same held-out evaluation conditions. Report where it helps, where it fails, and whether it increases latency or failure-to-acquire. Do not claim fusion improvement because the combined UI looks more informative.

---

## 8. Android implementation plan for the iQOO 15

### Phase 0 — isolate research from the production app

Create a separate research module or app first. Keep its code, raw recordings, DSP parameters and experiment results outside the production liveness path. Preserve the current Android build and frozen EdgePPG schema.

### Phase 1 — feasibility gate

Before writing the DSP implementation, determine:

- Can the phone speaker generate the chosen frequency range at a controlled output level?
- Can the built-in microphone capture useful energy in that range?
- Can playback and recording run simultaneously through the device's audio stack?
- Does Android processing alter the recorded waveform?
- Which sample rates and channel formats are actually supported by the active audio route?
- Is direct-path leakage so strong that useful reflections are obscured?

The nominal 18–22 kHz band used by some papers/repos must be treated as a test range, not as a guaranteed supported band. Commodity phone transducers, acoustic design, OS processing and device-specific speaker protection can limit performance. Do not drive the speaker at aggressive levels or bypass device protections. Start at a conservative level and stop if the device reports errors, sounds abnormal or heats unexpectedly.

### Phase 2 — capture/playback prototype

Use Android's official `AudioTrack` API for controlled PCM playback and `AudioRecord` for PCM capture. Request microphone permission through the normal Android permission flow. Check the selected audio source, channel layout, encoding, buffer sizes and sample rate. Record timestamps and error states.

Do not assume that requesting a sample rate forces the hardware to operate at that exact rate. Validate the actual recording using a known test tone/chirp and offline inspection. Disable or account for processing effects only where the API/device permits it and the consequences are understood; never assume effects are absent by default.

At this point the prototype should simply demonstrate: signal generated → signal played → microphone data recorded → recording saved → playback/recording metadata captured.

### Phase 3 — offline DSP first

Move the recorded waveform into a reproducible offline script (MATLAB or Python). Use one reference repository and its declared algorithm, then make changes one at a time. Generate plots of:

- transmitted chirp;
- recorded waveform and clipping indicators;
- frequency spectrum;
- aligned received/reference signals;
- dechirped/baseband output;
- range FFT;
- range-bin behaviour across repeated windows;
- phase or motion traces where supported.

Use fixed known distances and controlled target movement. A successful playback/recording alone is not proof that FMCW ranging works.

### Phase 4 — feature output and baseline classifier

When DSP results are repeatable, define a versioned acoustic result object, for example:

```json
{
  "schema": "edgeppg-acoustic-experiment-0.1",
  "capture_valid": true,
  "sample_rate_hz": null,
  "chirp_config_id": "recorded-config-id",
  "range_measurement_m": null,
  "motion_measurement": null,
  "quality_status": "MEASURED_OR_UNAVAILABLE",
  "pipeline_version": "recorded-commit-id"
}
```

The `null` values above are deliberately placeholders in a schema example, not measurements. Define units, valid ranges, missing states, calibration metadata and algorithm versions before using the object for experiments. Do not treat example JSON as runtime output.

Train an action classifier only after a labelled dataset exists and a baseline demonstrates a need. Train on the PC, save the preprocessing/model/version and test predictions on a held-out evaluation set. Do not assume a scikit-learn model can be loaded directly into Android; choose a deployment route only after measuring dependency size, numerical parity, latency and compatibility.

### Phase 5 — Android runtime and integration gate

Port or implement the verified DSP algorithm for Android only after offline results are reproducible. Compare Android outputs with the offline reference on identical recordings. For any trained model, test the same recorded examples through the PC reference and Android inference and define an acceptable tolerance.

The acoustic component must return explicit quality/failure status instead of fabricated scores. It must not block camera processing, silently change the session's final verdict, or insert values into `edgeppg-1.0` without an approved schema/version change.

---

## 9. Suggested module boundaries and data flow

A research-only data flow could be:

```text
Research session/config
        ↓
Chirp generator ────────────────┐
        ↓                       │ reference signal
AudioTrack playback             │
        ↓                       │
Room/person reflection          │
        ↓                       │
AudioRecord capture ←───────────┘
        ↓
Capture/quality validation
        ↓
Signal alignment and DSP
        ↓
Acoustic measurements + quality state
        ↓
(Optional) acoustic action classifier
        ↓
Evaluation report / research dashboard
```

For integration with EdgePPG, initially expose the result through a **separate experimental interface**. A future integration decision should be made only after the team has evidence that the new signal provides independent, robust value.

### Suggested repository structure

```text
EdgePPG-Acoustic-Research/
├── README.md
├── sources-and-licenses.md
├── device-feasibility/
│   ├── test-plan.md
│   └── device-results/
├── references/
│   ├── UltraSense/                 # cloned external repo; track commit
│   ├── target-detection-with-SM/    # cloned external repo; track commit
│   └── fmcw-acoustic-gesture-recognition/
├── data/                            # private; do not commit recordings
│   ├── raw/
│   ├── metadata/
│   └── processed/
├── dsp/
│   ├── alignment/
│   ├── dechirp/
│   ├── range_fft/
│   └── plots/
├── ml/
│   ├── baselines/
│   ├── train/
│   ├── evaluate/
│   └── model-card.md
├── android-prototype/
├── reports/
│   ├── feasibility-report.md
│   └── experiment-results.md
└── artifacts/                        # checkpoints/exports, with license metadata
```

You can keep the third-party repositories as separate Git clones rather than copying their source into your own repository. Do not commit large raw audio samples or sensitive recordings into the public application repository.

---

## 10. What to show in the dashboard

The acoustic dashboard should be an observability surface, not a decision override. Display only values measured by the active pipeline.

Useful fields:

- acoustic feature enabled/disabled;
- selected chirp configuration/version;
- requested versus actual sample rate/format;
- playback started/stopped and recording status;
- capture-valid state and failure reason;
- clipping or signal-quality flags;
- actual spectrum/range-time plots;
- selected range bin and confidence/quality indicator if defined;
- measured motion output if valid;
- acoustic model ID/version and its score only if a trained model is active;
- processing latency;
- result status (`VALID`, `UNAVAILABLE`, `INVALID`, `EXPERIMENTAL`) defined by the implementation.

Do not show a heart rate, distance, gesture score or liveness probability when the component did not measure it. Do not label an acoustic range estimate as a “live human score.”

---

## 11. Security, privacy, and safety boundaries

1. **Do not emit at high power or attempt to bypass device protections.** Start conservatively and test actual output/capture behavior.
2. **Do not assume near-ultrasonic means inaudible to every person or harmless in every setup.** Use responsible output levels and document the configuration.
3. **Protect recordings.** Raw audio can contain personal information; minimize collection and restrict access. Do not upload user recordings to a public repository.
4. **Keep research results honest.** Label simulated input, recorded sample input, offline DSP, Android runtime and physical-device results separately.
5. **No security claim without attack testing.** Demonstrating motion detection is not demonstrating spoof detection.
6. **Review all licenses.** In particular, note the GPL-2.0 license stated by UltraSense and inspect the exact license of every other repository, dataset and checkpoint before redistribution.
7. **Keep the existing decision path intact.** Experimental acoustic code cannot override `DecisionEngine`, force `LIVE`, force `SPOOF`, or hide a failed required sensor.

---

## 12. Acceptance gates and deliverables

Do not move to the next phase until the current phase has evidence.

### Gate A — resource review complete

- Read the primary repository READMEs and source thesis.
- Record license and source commit.
- Identify which code is Android capture, which code is offline DSP and which code (if any) is ML training/inference.

### Gate B — iQOO 15 feasibility measured

- Record actual playback/recording settings.
- Record repeatable test signals at controlled distances.
- Produce spectra/waveform plots and a failure report.
- Verify that the useful band survives the speaker, microphone and OS audio path sufficiently for the intended experiment.

### Gate C — DSP baseline reproduced

- A raw recording can be processed offline without manual, undocumented changes.
- Outputs are repeatable for repeated known test conditions.
- Failures return an explicit unavailable/invalid state.

### Gate D — dataset and ML are justified

- The classification task and labels are defined.
- The dataset includes relevant variation and held-out subjects/sessions.
- A no-ML or simple ML baseline is reported before a complex network.
- Test metrics and false detection behavior are documented.

### Gate E — Android and EdgePPG integration

- Android results match the offline reference on agreed test recordings.
- Latency, memory, failure rate and device stability are measured.
- Research outputs remain outside the frozen production feature schema until a documented migration is approved.
- Physical iQOO 15 tests are complete and recorded.

### Required deliverables

1. `sources-and-licenses.md` — repository, thesis, paper, commit and license register.
2. `device-feasibility-report.md` — target-phone recording/playback findings.
3. `dsp-baseline-report.md` — plots, measurements, algorithm/version and repeatability.
4. `dataset-card.md` — label definitions, capture protocol, privacy, split design and exclusions.
5. `model-card.md` — model purpose, data, metrics, limitations and deployment notes if a classifier is trained.
6. `integration-report.md` — Android/PC parity, runtime measurements and explicit statement of whether the acoustic branch remains experimental or is approved for production evaluation.

---

## 13. Suggested 1–2 day starter schedule

This is a practical sequence, not a promise that every phase will finish in the allotted time.

### Block 1 — 30–60 minutes: research orientation

Read this guide, inspect UltraSense, the target-detection repository and the acoustic gesture repository. Record licenses and language/tool requirements. Read the thesis abstract, methodology and evaluation sections.

### Block 2 — 1–2 hours: run an existing offline example

Install the required MATLAB/Python tools after checking the repository instructions. Use included sample data first. Reproduce one frequency spectrum, range FFT or Doppler/motion visualization. Keep an exact log of commands and versions.

### Block 3 — 1–2 hours: device feasibility

Build or run the least invasive capture/playback prototype. Capture known test signals at conservative levels. Inspect actual frequencies, sample rates, clipping, timing and repeatability. Do not train a model in this block.

### Block 4 — remaining time: define one experiment

Choose a single observable target, such as distance-bin repeatability or a simple motion class. Define the label and protocol. Collect a small pilot dataset only to test feasibility—not to claim generalizable model accuracy.

After the pilot, decide whether the sensing channel is technically usable before investing in larger data collection or neural-network training.

---

## 14. Source register

The following resources informed this guide. Read each resource directly and verify the current repository contents before copying code or using an artifact.

1. **UltraSense — Android ultrasound sensing, MATLAB scripts, example recordings; GPL-2.0 shown by repository.** <https://github.com/jkarolus/UltraSense>
2. **Target Detection with a Smartphone — FMCW target detection/ranging implementation.** <https://github.com/Dharani-27/target-detection-with-SM>
3. **FMCW Acoustic Gesture Recognition — older academic/research project with MATLAB scripts and PCM/WAV samples.** <https://github.com/lauren2018/fmcw-acoustic-gesture-recognition>
4. **Health monitoring using acoustic sensing with smartphone microphone and speaker — university thesis repository.** <https://hdl.handle.net/20.500.12741/rep:2265>
5. **PowerPhone: Unleashing the Acoustic Sensing Capability of Smartphones — device acoustics and sensing discussion.** <https://pmc.ncbi.nlm.nih.gov/articles/PMC12765216/>
6. **Smartphone-Based Acoustic Sensing for Breathing and Heartbeat Detection via AoA Clustering in Indoor Environments — advanced FMCW physiology research.** <https://www.mdpi.com/1424-8220/26/14/4591>
7. **Android `AudioRecord` official API.** <https://developer.android.com/reference/android/media/AudioRecord>
8. **Android `AudioTrack` official API.** <https://developer.android.com/reference/android/media/AudioTrack>
9. **AASIST — speech anti-spoofing training/evaluation implementation.** <https://github.com/clovaai/aasist>
10. **ASVspoof 5 baselines — audio speech anti-spoofing baselines.** <https://github.com/asvspoof-challenge/asvspoof5>
11. **ASVspoof 2021 dataset/challenge information.** <https://www.asvspoof.org/index2021.html>
12. **Google ML Kit Face Mesh for Android — official documentation.** <https://developers.google.com/ml-kit/vision/face-mesh-detection/android>
13. **Google AI Edge MediaPipe Gesture Recognizer for Android — official guide.** <https://developers.google.com/edge/mediapipe/solutions/vision/gesture_recognizer/android>

---

## 15. Final recommendation

For EdgePPG, start with **FMCW signal acquisition and classical signal processing, not ML training**. Use UltraSense as the Android capture/playback reference and the target-detection repository as an offline FMCW processing reference; use the gesture-recognition repository as research context, not as proof of a ready-made model. Read the thesis to understand the assumptions and limitations.

Then validate the entire signal path on the iQOO 15. If a task needs classification, collect a task-specific dataset and compare a simple SVM/Random Forest baseline with a small CNN only if justified. Treat acoustic liveness classification as its own research question. Treat AASIST/ASVspoof as a separate option for speech replay/deepfake detection.

Only after objective tests show that acoustic measurements are reproducible and add value should the team propose a formal EdgePPG schema/version change. Until then, keep acoustic experiments isolated, observable and reversible.
