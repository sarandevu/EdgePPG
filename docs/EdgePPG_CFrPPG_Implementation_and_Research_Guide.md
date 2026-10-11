# EdgePPG — CFrPPG Research, Reproduction, ML Training, and Android Integration Guide

**Document type:** Research and engineering implementation guide  
**Project:** EdgePPG — edge-first multimodal presentation-attack detection / Video KYC  
**Primary method:** Remote Photoplethysmography Correspondence Feature (CFrPPG)  
**Extension to review:** Multi-Channel Remote Photoplethysmography Correspondence Feature (MCCFrPPG)  
**Target runtime:** Android/Kotlin on the iQOO 15; offline research and training may use a PC  
**Status:** Research plan; not evidence that CFrPPG is already implemented or validated in EdgePPG

---

## 1. Executive summary

CFrPPG stands for **Remote Photoplethysmography Correspondence Feature**. Si-Qi Liu, Xiangyuan Lan, and Pong C. Yuen introduced it in an ECCV 2018 paper titled *Remote Photoplethysmography Correspondence Feature for 3D Mask Face Presentation Attack Detection*. The paper investigates whether heartbeat-related evidence extracted from facial video can help distinguish genuine faces from three-dimensional (3D) mask presentation attacks. It proposes a correspondence feature designed to recover heartbeat evidence from noisy rPPG signals and a learning strategy that accounts for global interference. The authors report experiments involving 3D-mask attacks, dim lighting, and camera motion. These results are evidence for the paper's evaluated task—not a guarantee against every contemporary spoofing method. [Original ECCV paper](https://openaccess.thecvf.com/content_ECCV_2018/html/Siqi_Liu_Remote_Photoplethysmography_Correspondence_ECCV_2018_paper.html).

**CFrPPG is not another name for ordinary rPPG.** Ordinary rPPG is the broader task of estimating a pulse-related signal from video. POS and CHROM are classical signal-processing methods that can be used to extract such a signal. CFrPPG takes a further step: it turns relationships/correspondence between local rPPG observations and a learned noise-aware template into a feature for face presentation-attack detection (PAD).

The later method **MCCFrPPG** extends the line of work with multi-channel time-frequency analysis and a global noise-aware template-learning and verification framework. The authors report evaluations on several 3D-mask datasets and an extended HKBU-MARs V2+ database that includes three mask types, 12 lighting settings, and six cameras. See the [HKBU research record](https://scholars.hkbu.edu.hk/en/publications/multi-channel-remote-photoplethysmography-correspondence-feature--2/) and [DOI: 10.1109/TIFS.2021.3050060](https://doi.org/10.1109/TIFS.2021.3050060).

For EdgePPG, the recommended strategy is:

1. Keep the current rPPG implementation as the baseline and verify that it produces valid camera-derived signals on the iQOO 15.
2. Reproduce the CFrPPG feature offline from the original paper and data before attempting an Android port.
3. Compare CFrPPG against a fair baseline using subject/session-separated evaluation.
4. Consider MCCFrPPG only after the original method is understood and the additional complexity is justified.
5. Keep CFrPPG experimental until it is validated against the attack types EdgePPG actually needs to handle.
6. Do **not** silently add a CFrPPG value to EdgePPG's frozen `edgeppg-1.0` 28-feature schema. Use an isolated experiment first; changing the production schema requires an explicit versioned migration and a compatible trained model.

A reliable research reproduction is achievable, but it is more work than adding another signal-processing function. The difficult work includes obtaining suitable labelled PAD data, reproducing the feature faithfully, dealing with noisy local signals, controlling dataset leakage, evaluating unseen attack conditions, and ensuring Android output matches the offline reference implementation.

## 2. First understand the terms

### 2.1 What is rPPG?

Remote photoplethysmography (rPPG) estimates pulse-related physiological variation from a video camera without a contact sensor. The algorithm observes small changes in skin pixel colours over time. It typically averages or otherwise processes RGB values from skin regions and applies a temporal algorithm to isolate a weak pulse-related component.

Classical approaches include:

- **GREEN:** uses the green channel as a simple signal baseline.
- **CHROM:** uses chrominance-related combinations of colour channels to reduce certain illumination effects.
- **POS:** projects normalized colour variations into a plane designed to emphasize pulse-related information.
- **ICA/PBV/LGI:** alternative classical signal-extraction methods with different assumptions.

The [rPPG-Toolbox repository](https://github.com/ubicomplab/rPPG-Toolbox) lists and implements several classical and learned rPPG methods, including CHROM, POS, DeepPhys, PhysNet, TS-CAN, EfficientPhys, and others. It is useful for understanding and testing physiological signal extraction, but it is **not** an official CFrPPG implementation.

An rPPG output can be represented conceptually as a time series:

`x_r(t) = [x_r(1), x_r(2), ..., x_r(T)]`

where `r` identifies a facial region and `T` is the number of valid time samples. This notation is explanatory, not the exact tensor format used by the original paper.

### 2.2 Why does ordinary rPPG not equal liveness?

A pulse-looking spectral peak does not prove that a session is live. Illumination changes, head motion, camera auto-exposure, compression, display refresh, background interference, and imperfect tracking can create or contaminate temporal patterns. Conversely, a genuine person may yield an unusable signal under poor conditions.

rPPG can provide one physiological cue, but its presence or absence should not be treated as conclusive proof of liveness. The pipeline needs explicit signal-quality handling and a wider PAD decision policy.

### 2.3 What does CFrPPG add?

CFrPPG is an rPPG-based PAD feature, proposed specifically to improve identification of heartbeat-related evidence in noisy facial rPPG signals. The key idea is to use correspondence to a learned template rather than depending only on a pulse estimate from one ROI or on the mere presence of a dominant frequency.

At a high level:

1. Extract time-varying rPPG information from relevant facial regions.
2. Represent the signals in the form required by the paper's frequency/spectral analysis.
3. Learn or apply the paper's noise-aware correspondence template.
4. Compute the CFrPPG representation/feature using the specified response/correspondence procedure.
5. Use that feature in a presentation-attack detector, evaluated with the paper's protocol.

The **exact equations, normalization, spectral construction, template objective, and feature aggregation must be taken from the paper**, not reconstructed from this summary. This guide intentionally does not invent implementation formulas or missing hyperparameters.

### 2.4 What is MCCFrPPG?

MCCFrPPG is the later multi-channel extension. The authors describe multi-channel time-frequency analysis to exploit temporal variation in rPPG, together with a global noise-aware template learning/verification framework. It is an extension to study after the original CFrPPG reproduction, not a reason to skip the earlier method.

Paper and research sources:

- **CFrPPG, ECCV 2018:** [Official CVF/Open Access page](https://openaccess.thecvf.com/content_ECCV_2018/html/Siqi_Liu_Remote_Photoplethysmography_Correspondence_ECCV_2018_paper.html)
- **CFrPPG authors' publication record:** [Hong Kong Baptist University](https://scholars.hkbu.edu.hk/en/publications/remote-photoplethysmography-correspondence-feature-for-3d-mask-fa-2/)
- **MCCFrPPG, IEEE TIFS 2021:** [Authors' HKBU record](https://scholars.hkbu.edu.hk/en/publications/multi-channel-remote-photoplethysmography-correspondence-feature--2/)
- **MCCFrPPG DOI:** [10.1109/TIFS.2021.3050060](https://doi.org/10.1109/TIFS.2021.3050060)

## 3. Why CFrPPG is relevant to EdgePPG—and its limits

EdgePPG already uses facial ROIs and POS/CHROM-style physiological processing in its planned/runtime pipeline. CFrPPG could be evaluated as an additional feature family that attempts to measure spatial/spectral correspondence of physiological signals, particularly for 3D-mask presentation-attack detection.

However, the original research objective is **not broad universal spoof detection**. The paper is specifically about 3D mask face PAD. Do not assume that a method evaluated on 3D masks will detect every printed-photo attack, phone-screen replay, deepfake, injection attack, synthetic video, or other attack type. Each attack category needs explicit data and evaluation.

Recommended role in EdgePPG:

- Keep POS/CHROM as the existing rPPG baseline.
- Run CFrPPG as an isolated research challenger first.
- Evaluate MCCFrPPG only when you can reproduce the added multi-channel/time-frequency work.
- Report CFrPPG's validity and quality separately from pulse-rate estimation.
- Do not treat a high CFrPPG response as unconditional proof of liveness.
- Do not classify every failed CFrPPG measurement as a spoof; poor signal quality may require `UNCERTAIN` or a retry, according to the approved policy.

## 4. The research-to-production architecture

### 4.1 Offline reproduction and training

```text
Labelled face videos + subject/session metadata
                    |
                    v
             Dataset protocol
                    |
                    v
          Face / skin ROI extraction
                    |
                    v
         Region-wise colour time series
                    |
                    v
             POS / CHROM baseline
                    |
                    v
      Paper-faithful CFrPPG processing
                    |
                    v
        CFrPPG features + validity data
                    |
                    v
       Train/select PAD classifier if required
                    |
                    v
          Validation and held-out test
                    |
                    v
      Frozen feature/model/preprocessing package
```

### 4.2 Android runtime candidate

```text
Camera frames
    -> face/ROI tracker
    -> local colour buffers
    -> rPPG extraction
    -> CFrPPG correspondence processing
    -> validity and quality output
    -> experimental PAD feature consumer
```

The phone runtime should not need the original training dataset. It uses a frozen, tested implementation and any learned artifacts required by the method. Exact runtime dependencies depend on the paper-faithful implementation. They must be discovered and tested rather than assumed.

### 4.3 EdgePPG production boundary

The current production contract is the frozen 28-feature `edgeppg-1.0` schema. Do not insert CFrPPG in an unused/reserved slot or change feature interpretation silently. Until a schema migration is formally agreed, CFrPPG should be logged as **experimental diagnostic output** outside the production ML vector and final decision.

If experiments justify production integration, then:

1. Define a new schema version and explicit feature semantics.
2. Add the feature to the PC-side training pipeline.
3. Retrain and validate the corresponding classifier with that schema.
4. Freeze compatible preprocessing/model artifacts.
5. Verify PC-versus-Android feature and prediction parity.
6. Test all deterministic gates and failure paths.
7. Preserve the separation between ML output `P(LIVE)` and the final `LIVE` / `SPOOF` / `UNCERTAIN` decision.

## 5. What needs to be built—and what needs ML?

A common mistake is to assume that every part of CFrPPG is a neural network to download. The method includes signal processing and learned/template-based parts. The correct partition must follow the original paper.

| Component | Category | Train a model? | Objective |
|---|---|---:|---|
| Face detection / mesh / ROI tracking | Computer-vision SDK or existing component | Usually no new training for this task | Get stable face/skin regions |
| RGB time-series extraction | Deterministic preprocessing | No | Obtain local signals from camera frames |
| POS/CHROM | Classical signal processing | No | Produce baseline rPPG signals |
| Spectral/time-frequency processing | Signal processing | No, unless paper specifies a learned stage | Represent temporal/frequency behavior |
| CFrPPG template-learning procedure | Learned/statistical component as described by the paper | Yes, fit the specified template using training data | Capture heartbeat correspondence robustly under noise |
| Final PAD classifier | Depends on the exact reproduction protocol | Train/select as specified by the paper or an explicitly defined EdgePPG experiment | Separate bona fide versus target attacks |
| MCCFrPPG extra channel/time-frequency stage | Signal processing plus noise-aware template learning | Fit the specified learned component | Improve correspondence evidence under more challenging conditions |
| EdgePPG fusion classifier | Existing project ML system | Only if schema/model version changes | Combine approved features and estimate `P(LIVE)` |

**Important implementation rule:** The table is a high-level engineering separation. Use the paper's method section, equations, and experimental protocol to decide exactly what is optimized, what is fixed, and whether/how the final classifier is trained. Do not substitute a convenient SVM, Random Forest, neural network or threshold without explicitly labelling it as an EdgePPG variant rather than a faithful reproduction.

### Recommended experiments

Run at least these separate comparisons, using exactly the same data splits:

- Baseline A: existing POS/CHROM + the currently defined PAD baseline.
- Experiment B: CFrPPG feature with a defined and documented classifier.
- Experiment C: a paper-faithful CFrPPG implementation and training protocol, once reproducible.
- Experiment D: MCCFrPPG only if time/data/resources justify it.

Use the original paper's reported comparison protocol when accessible; additionally define EdgePPG-specific splits for new devices and new conditions. Keep published numbers, reproduced paper numbers, EdgePPG offline results, and iQOO 15 results separate.

## 6. Datasets and access

CFrPPG is specifically concerned with 3D mask attacks, so a dataset with only still-frame print/replay attacks may be inadequate for reproducing its temporal physiological cue. Dataset choice must match the method and target attack.

### 6.1 HKBU-MARs — highly relevant

The [HKBU-MARs official database page](https://rds.comp.hkbu.edu.hk/mars/) documents the HKBU 3D Mask Attack with Real World Variations database. The page lists the CFrPPG ECCV 2018 and MCCFrPPG TIFS 2021 work among related publications. Access requires printing, completing and signing a usage agreement and sending it to the stated contact; a download is provided after approval. Plan for access time. Read and follow the current agreement and permitted-use terms before collecting or training.

MCCFrPPG's author publication record says the extended HKBU-MARs V2+ experiments covered three mask types, 12 lighting settings, and six cameras. This makes it relevant for studying mask and capture-condition variation, but it does not guarantee unrestricted access or unrestricted redistribution.

### 6.2 3DMAD

The [3D Mask Attack Dataset (3DMAD) record on Zenodo](https://zenodo.org/record/4068477) describes RGB and depth data from 17 people, with 76,500 frames, recorded using Kinect for real-access and mask-attack samples. It is an established benchmark for 3D-mask PAD. Verify the exact download, license, data agreement, annotation protocol, and permitted use before use. It is older and relatively small, so it should not be the only evidence for generalization.

### 6.3 Dataset management repository

[CASIA-FAS-Dataset GitHub repository](https://github.com/liuajian/CASIA-FAS-Dataset) organizes references/protocols for several face anti-spoofing datasets, including CASIA-SURF, CASIA-CeFA, HKBU variants, and others. It is a useful index/protocol reference, not a substitute for checking the official dataset host and agreement for each collection.

### 6.4 Other PAD datasets

The [CASIA-SURF CeFA paper](https://openaccess.thecvf.com/content/WACV2021/html/Liu_CASIA-SURF_CeFA_A_Benchmark_for_Multi-Modal_Cross-Ethnicity_Face_Anti-Spoofing_WACV_2021_paper.html) describes a multimodal benchmark with three ethnicities, three modalities, 1,607 subjects, and 2D/3D attacks. It can help evaluate broader PAD generalization, but check whether its video timing and physiological signals support the exact CFrPPG experiment before assuming it can reproduce the method.

Use rPPG datasets such as PURE, UBFC-rPPG or COHFACE to study physiological-signal extraction only when their format, permissions and labels are appropriate. They are not automatically 3D-mask PAD datasets, and they should not be assigned spoof labels without defensible attack annotations.

### 6.5 Data-split policy

- Split by subject and/or capture session, not randomly by individual frames.
- Avoid having near-identical adjacent frames from one video in both training and test sets.
- Keep the final test set untouched during template/classifier tuning.
- Report which datasets, subjects, conditions and attack types appear in each split.
- Include cross-dataset or cross-device testing when feasible.
- Record failures and unusable signal windows; do not quietly drop difficult cases without reporting them.

## 7. GitHub and reference resources

### 7.1 Paper-faithful primary sources

1. **CFrPPG ECCV 2018 paper:** [CVF Open Access](https://openaccess.thecvf.com/content_ECCV_2018/html/Siqi_Liu_Remote_Photoplethysmography_Correspondence_ECCV_2018_paper.html). This is the primary method reference. Extract the exact equations, preprocessing, template objective, feature construction and evaluation protocol from the PDF.
2. **CFrPPG publication record:** [HKBU Scholars](https://scholars.hkbu.edu.hk/en/publications/remote-photoplethysmography-correspondence-feature-for-3d-mask-fa-2/).
3. **MCCFrPPG TIFS 2021 paper:** [DOI page](https://doi.org/10.1109/TIFS.2021.3050060) and [HKBU Scholars record](https://scholars.hkbu.edu.hk/en/publications/multi-channel-remote-photoplethysmography-correspondence-feature--2/).
4. **HKBU-MARs dataset:** [Official access page](https://rds.comp.hkbu.edu.hk/mars/).
5. **3DMAD dataset:** [Zenodo record](https://zenodo.org/record/4068477).

### 7.2 Community GitHub reference implementation — use cautiously

Repository: [shicaiwei123/anti-spoofing-of-rppg](https://github.com/shicaiwei123/anti-spoofing-of-rppg)

This repository contains a Python GUI, face detection, RGB region extraction, real-time rPPG processing and FFT-related code. Its README explicitly states that its spoof decision is a simple judgment and that SVM training is not used because the author lacked a dataset. Therefore:

- Use it to understand a basic rPPG video pipeline if helpful.
- Do not call it an official CFrPPG implementation without verifying the correspondence feature against the paper.
- Do not treat its current decision rule as a trained or validated PAD model.
- Do not port it wholesale to Android; first isolate and test any algorithmic ideas.
- Check code/data licensing and dependency/security status before reuse.

### 7.3 General rPPG reference implementation

Repository: [ubicomplab/rPPG-Toolbox](https://github.com/ubicomplab/rPPG-Toolbox)

It provides a research framework for comparing classical methods including POS and CHROM alongside deep-learning rPPG models. Use it to verify the current rPPG baseline and create reproducible signal comparisons. It does not establish a paper-faithful CFrPPG implementation.

## 8. Reproduction workflow

### Phase 0 — Source audit

Before coding:

- Obtain the original CFrPPG PDF and the MCCFrPPG PDF if you will study the extension.
- Make a method table from the paper: inputs, region definitions, signal preprocessing, equations, template construction, feature output, training labels, optimization procedure, classifier and evaluation protocol.
- Mark each detail as `EXPLICIT IN PAPER`, `IMPLEMENTATION CHOICE`, or `UNKNOWN / NEEDS VERIFICATION`.
- Do not infer missing equations from plots or another repository's simplified implementation.
- Search for official author code or supplementary materials. If there is none, document that the work is a from-paper reproduction rather than a code-based port.

### Phase 1 — Verify the existing rPPG input

Use existing EdgePPG face tracking and anatomical ROI handling, but isolate each local signal and verify its integrity. Record:

- Camera frame timestamps and achieved FPS.
- Actual valid ROI duration and pixel coverage.
- Exposure/white-balance changes where observable.
- Signal window duration and number of samples.
- POS/CHROM signal output and preprocessing parameters.
- Signal quality/failure reason.

Test static, moving, dim-light, overexposed and partial-occlusion scenarios. Do not interpret a generated sample or synthetic waveform as physical-device evidence.

### Phase 2 — Offline baseline

Implement a reproducible pipeline that can load a video, extract the same facial regions, and output per-region time series. Compare the offline POS/CHROM implementation with the Android runtime on the same recorded clip where possible. Differences must be explained: face crop, frame rate, RGB normalization, frame order, filtering, resampling and windowing can all affect the signal.

### Phase 3 — Reproduce CFrPPG

Implement each method step from the paper in an isolated module. Keep intermediate files available for debugging:

- face/ROI annotations or ROI validity logs;
- local raw colour sequences;
- extracted rPPG signals;
- frequency/spectral representations;
- learned template artifact or state, as specified by the paper;
- correspondence feature outputs;
- sample labels and split identifiers;
- classifier inputs, predictions and score logs.

Do not skip intermediate inspections and jump directly to a final PAD score. If the learned template is not stable, investigate region selection, signal quality, leakage and data distribution before changing the model.

### Phase 4 — Train/evaluate the PAD decision component

Use the paper's defined training and verification procedure when reproducing its result. For an EdgePPG-specific experiment, define the classifier and training protocol separately if they differ. Ensure that template estimation, feature normalization, classifier fitting and threshold selection do not leak test information.

If testing an SVM, Random Forest, or XGBoost as a research variant, treat each as a separate experiment. Do not represent it as the original paper's classifier unless the method paper confirms that choice. Use a validation set for hyperparameters and thresholds; do not tune on the final test set.

### Phase 5 — Held-out evaluation

Evaluate the model on subject/session-separated data and, when possible, cross-dataset and cross-device tests. Report all preprocessing and the exact split protocol. Include confidence intervals or repeated runs when feasible, especially when sample sizes are small.

### Phase 6 — Port to Android

After the offline reproduction is stable:

1. Define an interface for CFrPPG input/output.
2. Choose the implementation strategy based on profiling: pure Kotlin/Java for simpler operations, or a compatible native/DSP implementation if justified. Do not decide on native code prematurely.
3. Keep the camera/ROI data path consistent with offline processing.
4. Port the method step-by-step and keep a reference implementation for parity testing.
5. Compare the CFrPPG feature produced by Android with the offline version for identical recorded inputs.
6. Profile execution latency, memory, battery/thermal behavior if instrumentation is available, and missing-signal cases.
7. Run real-camera tests on the iQOO 15 across conditions. Mark only observed results as verified.

## 9. Feature and model artifact discipline

The training and runtime pipelines must share:

- method/version identifier;
- ROI semantics and geometry;
- sample-rate/frame-timing assumptions;
- temporal windowing and filtering;
- spectral representation and ordering;
- template or learned artifact version;
- feature dimension/order and normalization;
- final classifier and calibration, if used;
- missing-data policy and quality threshold;
- known limitations and evaluation dataset.

Store an experiment manifest with a commit hash, configuration, dataset version, split definition, preprocessing version, training seed where applicable, artifact checksum and evaluation report. Do not overwrite a known baseline without retaining its version.

## 10. Evaluation metrics

For a face-PAD experiment, use attack-appropriate metrics such as:

- **APCER:** Attack Presentation Classification Error Rate.
- **BPCER:** Bona Fide Presentation Classification Error Rate.
- **ACER:** Average of APCER and BPCER under the chosen protocol.
- ROC-AUC/PR-AUC and confusion matrix where appropriate.
- Per-attack and per-dataset results.
- Results by illumination, motion, camera/device and mask type where available.
- Failure-to-process / invalid-signal rates.
- Runtime latency, memory and frame timing on the physical phone where measured.

Define exactly how the bona fide and each attack class map to the positive/negative decision used for every metric. Report sample counts and split protocol. Accuracy alone is insufficient.

For the rPPG extraction baseline, report pulse-rate MAE/RMSE or waveform metrics only where synchronized reference measurements exist. Do not present pulse-rate error metrics as a face-PAD security metric.

Keep four result categories separate:

1. Values reported by the original paper.
2. Values reproduced on the original/approved benchmark protocol.
3. Results from EdgePPG offline experiments.
4. Results measured on the physical iQOO 15.

## 11. Failure handling and security integration

CFrPPG feature computation can fail because of motion, poor light, occlusion, short capture windows, invalid ROIs or weak physiological variation. EdgePPG must expose a validity/status result alongside any feature value.

Recommended semantics:

- **Valid:** feature computed under the defined processing/quality policy.
- **Unavailable:** not enough usable data to compute it.
- **Invalid:** processing produced an unusable or inconsistent result.

Follow the existing schema's missing-value rules. Do not use arbitrary zeros, fabricated confidence or a stale output to make the classifier accept input.

A failure to extract CFrPPG is not, by itself, proof of an attack. It may require reacquisition or `UNCERTAIN`, depending on the approved security policy. Conversely, a valid CFrPPG feature does not guarantee a bona fide face. The final verdict must remain governed by EdgePPG's deterministic security-gate layer; the classifier outputs `P(LIVE)`, not the final verdict.

## 12. EdgePPG integration checklist

### Research implementation
- [ ] Original CFrPPG method and equations extracted from the paper.
- [ ] MCCFrPPG kept separate until the original method is reproduced.
- [ ] No claim of official pretrained CFrPPG code unless verified.
- [ ] Dataset terms and licenses checked.
- [ ] Subject/session split prevents leakage.
- [ ] Baseline POS/CHROM evaluation recorded.
- [ ] Intermediate signals and template/feature outputs are inspectable.
- [ ] Failure-to-process rate is reported.

### Offline ML evaluation
- [ ] Template learning uses only permitted training data.
- [ ] Validation and final test sets remain disjoint from training/tuning.
- [ ] Classifier choice matches the declared experiment.
- [ ] Threshold/calibration selected without final-test leakage.
- [ ] APCER/BPCER/ACER definitions and attack categories are documented.
- [ ] Per-dataset/per-attack results are reported.
- [ ] Artifact and experiment manifest are versioned.

### Android integration
- [ ] ROI output feeds the CFrPPG signal path.
- [ ] The same preprocessing is used as the offline reference.
- [ ] Feature values and quality status are exposed for diagnostics.
- [ ] Android output parity is tested on the same input clips.
- [ ] Real camera tests run on the iQOO 15.
- [ ] No schema change occurs without versioning and model migration.
- [ ] UI displays actual runtime measurements only.
- [ ] Gate policy remains separate from ML output.

## 13. Suggested project directory for research artifacts

This is a suggestion for a separate research workspace, not a demand to restructure the current app:

```text
research/cfrppg/
  README.md
  paper-notes/
    CFrPPG_ECCV2018_method-notes.md
    MCCFrPPG_TIFS2021_method-notes.md
  configs/
    dataset.yaml
    preprocessing.yaml
    experiment.yaml
  data-manifests/
    train_manifest.csv
    validation_manifest.csv
    test_manifest.csv
  src/
    roi_loader.py
    signal_extraction.py
    cfrppg_feature.py
    evaluation.py
  models/
    # learned artifacts only; do not commit restricted datasets
  outputs/
    intermediate-signals/
    feature-tables/
    metrics/
  reports/
    baseline_report.md
    cfrppg_reproduction_report.md
    device_validation_report.md
```

For the Android project, use the existing module/package conventions. A conceptual separation might be `CfrppgProcessor`, `CfrppgResult`, and a diagnostics adapter, but names must be adapted to the actual source code rather than added blindly.

## 14. Antigravity implementation prompt

Use the following prompt only after reviewing this guide and deciding to run the implementation audit.

```text
You are auditing and implementing the CFrPPG research branch for EdgePPG.

First inspect the original ECCV 2018 CFrPPG paper and the current EdgePPG code. Study the MCCFrPPG 2021 extension separately. Do not assume that an existing GitHub rPPG example implements CFrPPG faithfully.

PHASE 1 — READ-ONLY AUDIT
1. Locate the actual rPPG implementation, face/ROI path, buffers, feature assembler, model client, and security gates.
2. Trace real camera frames through the current pipeline.
3. Identify whether local time series can be exported for a controlled offline experiment.
4. Find the exact frozen 28-feature schema and its authoritative specification.
5. Report what is implemented, partial, missing, deviating, or unverified.
6. Do not edit any file in this phase.

PHASE 2 — PAPER METHOD SPECIFICATION
1. Extract the exact CFrPPG equations, template learning, signal processing, classifier and evaluation protocol from the paper.
2. Mark any missing detail as NEEDS VERIFICATION; do not invent formulas or parameters.
3. Determine which dataset access agreements are required and whether the candidate data actually matches the 3D-mask PAD task.
4. Explain which steps require learning/training and which are deterministic processing.

PHASE 3 — OFFLINE REPRODUCTION PLAN
1. Define a reproducible input/output contract.
2. Create a baseline using the current POS/CHROM implementation.
3. Implement the paper method in an isolated research branch or folder.
4. Keep intermediate signals, templates and feature outputs inspectable.
5. Define subject/session-separated splits and appropriate PAD metrics.
6. Do not use the final test set for template fitting, feature selection, thresholds or calibration.

PHASE 4 — ANDROID PORT, ONLY AFTER OFFLINE VALIDATION
1. Port the validated feature calculation using the smallest suitable implementation.
2. Test Android/offline parity on identical recordings.
3. Measure latency and failure-to-process behavior on the iQOO 15.
4. Expose valid/unavailable/invalid status to diagnostics.
5. Do not silently insert CFrPPG into edgeppg-1.0's frozen 28-feature schema. Propose a versioned migration and compatible retraining if production integration is justified.
6. Preserve the distinction between ML P(LIVE) and the deterministic final security decision.

Never fabricate measurements, labels, accuracy or device results. Do not mark code as working merely because it compiles or a class exists. Report tests and evidence separately for offline, Android and physical-device results.
```

## 15. Recommended work order and stop/go gates

### Gate 1 — Source readiness

**Proceed only when:** the original paper's method can be specified from its text/equations, and the data protocol is understood.

**Stop if:** key equations or learned-template details remain unclear. Seek the paper's supplementary material or contact the authors instead of inventing a substitute.

### Gate 2 — rPPG input readiness

**Proceed only when:** local signals from the selected ROIs are reproducible, with explicit validity checks and inspectable intermediate outputs.

**Stop if:** Android frames do not map consistently to the offline signal pipeline, or poor-quality signals are silently marked valid.

### Gate 3 — Offline CFrPPG reproduction

**Proceed only when:** the feature computation is tested on known inputs and the training/template pipeline is reproducible.

**Stop if:** the implementation only returns a hand-written heuristic or unexplained score while being labelled CFrPPG.

### Gate 4 — PAD evaluation

**Proceed only when:** results use a documented subject/session protocol, separate validation/final test sets, and attack-appropriate metrics.

**Stop if:** all results come from training examples or a single narrow 3D-mask recording condition.

### Gate 5 — Android port

**Proceed only when:** offline evidence shows that the method is worth integrating and the feature contract is defined.

**Stop if:** the port changes region semantics, sampling assumptions or preprocessing without parity evaluation.

### Gate 6 — Production decision

**Proceed only when:** the model/feature schema migration, runtime policy, failure handling and physical-device tests have all been reviewed.

CFrPPG should not become a security-critical production signal simply because the offline prototype produces plausible numbers.

## 16. Final recommendation

For EdgePPG, the highest-value path is **baseline rPPG validation → paper-faithful CFrPPG reproduction → subject-separated 3D-mask PAD evaluation → Android parity test → versioned production proposal**.

Do not start by downloading a random model or replacing POS/CHROM. The original CFrPPG method is a correspondence feature designed to recover heartbeat-related evidence from noisy signals; it is not equivalent to a ready-made universal anti-spoof classifier. The MCCFrPPG paper is the next research step if the original reproduction is successful and additional robustness is needed.

Treat the original claims as paper results to reproduce, not guarantees for EdgePPG. Treat all EdgePPG results as unknown until measured. Keep every new feature isolated until the data, evaluation and production contract justify integration.

---

## References and access links

1. Liu, S.-Q., Lan, X., and Yuen, P. C. (2018). **Remote Photoplethysmography Correspondence Feature for 3D Mask Face Presentation Attack Detection.** ECCV 2018. [CVF Open Access](https://openaccess.thecvf.com/content_ECCV_2018/html/Siqi_Liu_Remote_Photoplethysmography_Correspondence_ECCV_2018_paper.html)
2. Liu, S.-Q., Lan, X., and Yuen, P. C. (2021). **Multi-Channel Remote Photoplethysmography Correspondence Feature for 3D Mask Face Presentation Attack Detection.** IEEE Transactions on Information Forensics and Security, 16, 2683–2696. [DOI](https://doi.org/10.1109/TIFS.2021.3050060) · [HKBU research record](https://scholars.hkbu.edu.hk/en/publications/multi-channel-remote-photoplethysmography-correspondence-feature--2/)
3. **HKBU-MARs Database — The HKBU 3D Mask Attack with Real World Variations Database.** [Official access page](https://rds.comp.hkbu.edu.hk/mars/)
4. **3D Mask Attack Dataset (3DMAD).** [Zenodo record](https://zenodo.org/record/4068477)
5. Liu, A. et al. (2021). **CASIA-SURF CeFA: A Benchmark for Multi-Modal Cross-Ethnicity Face Anti-Spoofing.** WACV 2021. [CVF Open Access](https://openaccess.thecvf.com/content/WACV2021/html/Liu_CASIA-SURF_CeFA_A_Benchmark_for_Multi-Modal_Cross-Ethnicity_Face_Anti-Spoofing_WACV_2021_paper.html)
6. **rPPG-Toolbox.** [GitHub](https://github.com/ubicomplab/rPPG-Toolbox)
7. **Community rPPG/3D-mask anti-spoof reference.** [GitHub](https://github.com/shicaiwei123/anti-spoofing-of-rppg) — read the README's limitations before reuse.
8. **CASIA-FAS-Dataset dataset/protocol index.** [GitHub](https://github.com/liuajian/CASIA-FAS-Dataset)

**Source discipline:** If a source becomes inaccessible, a dataset agreement changes, or model/code availability cannot be established, record that explicitly. This guide points to the research and access pages but does not claim to have downloaded restricted datasets, run the implementations, reproduced the paper, or validated CFrPPG on the iQOO 15.
