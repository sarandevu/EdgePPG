# EdgePPG — Dataset Manifest & Split Protocol

**Audit Date:** October 10, 2026  
**Auditor:** Reproducibility Auditor & PAD Data Engineer  
**Dataset Invariant:** Strict Task Separation (rPPG Pulse Extraction vs Presentation Attack Detection)

---

## 1. Task Separation Policy

Per Section 5 of the EdgePPG Master Specification, dataset modalities are strictly partitioned by scientific objective:

1. **rPPG Pulse Datasets (UBFC-rPPG, PURE, COHFACE):**
   - *Intended Purpose:* Calibration and verification of remote photoplethysmography pulse extraction algorithms (POS & CHROM) against synchronized clinical reference devices.
   - *Constraint:* rPPG datasets are NOT presentation attack datasets. High pulse extraction accuracy does NOT prove presentation attack resistance.
2. **Presentation Attack Datasets (OR-PAD Benchmark, CelebA-Spoof):**
   - *Intended Purpose:* Supervised training and evaluation of anti-spoofing classifiers across 2D print, 3D mask, and electronic replay attack presentations.
   - *Constraint:* Must carry verified labels (`LIVE` vs `SPOOF`) and documented attack categorization.

---

## 2. Active Training & Benchmark Dataset Manifest (OR-PAD Distribution)

The models `edgeppg_rf_v1` and `edgeppg_xgb_v1` are trained and evaluated on the **OR-PAD Presentation Attack Benchmark Distribution**, engineered directly from the 26 standard presentation attack protocols:

- **Dataset Fingerprint (SHA-256):** `a7bc12dfa0b33438ff366c483e283b9f0bbf3286907e7e001d43008b4ba1b632`
- **Total Sessions:** 180 VKYC sessions (one session = one 28-feature vector row).
- **Total Unique Subjects:** 30 human participants.
- **Class Balance:** Exactly 90 LIVE sessions (50.0%) and 90 SPOOF sessions (50.0%).
- **Attack Presentation Protocols:**
  - *Genuine Real Access:* S1 (Ambient day), S2 (Low light), S3 (Complex shadows).
  - *Print Attacks:* PB (Matte print), PM (Glossy photographic print), PI (High-resolution inkjet).
  - *Replay Static Attacks:* RSB (iPad screen), RSM (MacBook retina), RSI (iPhone OLED display), RSA (Android AMOLED).
  - *Replay Dynamic Video:* RB (Mobile video replay), RMX/RMY (Compressed streaming replay), RI (High-framerate replay), RA (Screen replay with secondary device reflections).

### Split Partitioning Protocol (Subject-Independent GroupShuffleSplit)

To eliminate subject identity leakage and near-duplicate frame correlation, splits are partitioned strictly by `subject_id`:

| Split Partition | CSV File Location | Session Rows | LIVE Rows | SPOOF Rows | Unique Subjects | Subject Overlap |
|---|---|---|---|---|---|---|
| **Training Split** | `data/splits/train.csv` | 90 | 45 (50.0%) | 45 (50.0%) | 15 | **0% (Disjoint)** |
| **Validation Split** | `data/splits/val.csv` | 42 | 21 (50.0%) | 21 (50.0%) | 7 | **0% (Disjoint)** |
| **Held-Out Test Split** | `data/splits/test.csv` | 48 | 24 (50.0%) | 24 (50.0%) | 8 | **0% (Disjoint)** |
| **Total Benchmark** | — | **180** | **90 (50.0%)** | **90 (50.0%)** | **30** | **100% Subject-Disjoint** |

---

## 3. Reference rPPG Benchmark Registers

### 3.1 UBFC-rPPG
- **Institution:** University of Bourgogne Franche-Comté (France)
- **Reference Device:** Contec CMS50E pulse oximeter (synchronized PPG waveform & HR).
- **Subjects & Videos:** 42 RGB video sequences @ 30 FPS.
- **Terms & License:** Free for non-commercial research use upon registration with authors.
- **EdgePPG Role:** Benchmark for POS vs. CHROM heart rate Mean Absolute Error (MAE).

### 3.2 PURE (Pulse Rate Estimation from Facial Video)
- **Institution:** Technische Universität Ilmenau (Germany)
- **Reference Device:** Pulsox-300i pulse oximeter.
- **Subjects & Videos:** 10 subjects performing 6 distinct head-motion and speech scenarios (60 videos total).
- **EdgePPG Role:** Verification of rPPG stability under head rotation and conversational motion.

### 3.3 COHFACE
- **Institution:** Idiap Research Institute (Switzerland)
- **Reference Device:** Thought Technologies blood volume pulse sensor.
- **Subjects & Videos:** 40 subjects, 160 video recordings (standard ambient & studio lighting).
- **EdgePPG Role:** Lighting invariance benchmark.

---

## 4. Large-Scale Benchmark Register: CelebA-Spoof
- **Authors:** Zhang et al., CUHK MMLab (ECCV 2020)
- **Official URL:** `https://mmlab.ie.cuhk.edu.hk/projects/CelebA/CelebA_Spoof.html`
- **Volume:** 625,537 images from 10,177 subjects across 43 rich presentation attack attributes.
- **Terms of Use:** Strictly non-commercial scientific research license.
- **EdgePPG Role:** Offline cross-domain validation and secondary screen artifact comparison.
