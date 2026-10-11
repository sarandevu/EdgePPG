# Changelog

All notable changes to the **EdgePPG** project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.1.0] - 2026-10-11

### Added
* **Active Optical Stimulus Engine**: Screen color flash challenges with ambient illuminance compensation (`OpticalChallengeEngine.kt`, `OpticalResponseAnalyzer.kt`).
* **Ambient Lighting Resilience**: Calibrated optical probe analyzer to designate low flash contrast under bright indoor/sunlight conditions as `CONTAMINATED` (inconclusive lighting) rather than false `WRONG` (presentation attack), preventing genuine applicants in good lighting from false rejections.
* **Physiological Pulse Sensitivity Calibration**: Calibrated `hasGenuinePhysioPulse` to accept genuine rPPG signals (`(pCorr >= 0.05f && pSnr >= 0.08f) || (pSnr >= 0.15f)`) within normal human heart rates (45–185 bpm).
* **Phone & Secondary Screen Detector**: Detection of screen bezels, specular reflections, and moiré patterns to defend against mobile display replays (`PhoneScreenDetector.kt`).
* **White & Light-Blue UI Design System**: Clean financial-grade interface with dominant `#FFFFFF` surfaces, `#EAF5FF`/`#CFE5FA` elevation cards, and `#77BFFF` accents.
* **Restored Face ROI Color Scheme**: Distinct color-coded facial regions (Forehead: Cyan `#22D3EE`, Left Cheek: Emerald `#34D399`, Right Cheek: Orange `#FB923C`).
* **Live Telemetry Gateway Synchronization**: Bidirectional real-time stream (`POST /api/live`) transmitting live FPS, face lock state, cardiac pulse waveform, and challenge status to PC verifier at `192.168.112.130:8080`.
* **Extended Android Unit Test Suite**: 44 passing unit tests covering acoustic sensing, behavioral estimators, OCR, 28-feature normalization, optical stimulus, rPPG POS DSP, decision gates, and session lifecycle.
* **Standalone Documentation Suite**: Comprehensive `ARCHITECTURE.md`, `IMPLEMENTATION_PLAN.md`, and updated `README.md`.

---

## [1.0.0] - 2026-09-27

### Added
* **Physiological rPPG Engine**: Pure Kotlin POS DSP, radix-2 FFT spectral analysis, biquad Butterworth filtering, and multi-ROI Pearson correlation.
* **Decision Engine & Quality Gates**: 28-feature schema compliance, exposure/AWB stability gates, and physiological spoof detection fallback.
* **Hardware Cryptographic Attestation**: `AndroidKeyStore` SECP256R1 ECDSA transcript signing and canonical payload serialization.
* **PC Verifier Gateway**: Local HTTP server with real-time verification dashboard, live queue management, and offline tamper-evident receipt generation.
* **Attack Scenario Test Harness**: Built-in test suite evaluating genuine subjects, printed photos, video replays, and signature tampering.
* **Simplified Session Orchestration**: Short call identifiers (`001`, `User 1`), sample autofill, and automatic scheduled call matching.
* **Comprehensive Documentation**: Complete `README.md`, `WALKTHROUGH.md`, `CONTRIBUTING.md`, `SECURITY.md`, and CI workflow.
