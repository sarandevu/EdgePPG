# EdgePPG Termux Local Compute & Research Module

This module enables **on-device, local Python and ML execution** directly within Termux on the **iQOO phone** for EdgePPG remote human trust and liveness verification.

---

## Architecture Role

- **Host Device:** iQOO 12 / 15 / Android 14+ (Localhost: `127.0.0.1:8081`).
- **Owner of Camera:** Android Native CameraX ONLY. (Termux NEVER accesses the camera).
- **Communication:** Loopback IPC (`http://127.0.0.1:8081`). Zero network dependencies, strictly offline.
- **Root of Trust:** Android Native Enclave + Keystore ECDSA P-256 (Termux does not bypass Android security).

```
                      iQOO PHONE (Local Hardware)
  ┌─────────────────────────────────────────────────────────────┐
  │                                                             │
  │   Android Native (CameraX)                                  │
  │     - 1080p ImageAnalysis                                   │
  │     - FaceMesh + Dynamic 3-ROI extraction                   │
  │     - Pure-Kotlin POS + 256-FFT DSP                         │
  │     - Active Behavioral verification (gaze/head)            │
  │     - 28-feature assembly                                   │
  │                            │                                │
  │                            │ HTTP POST /predict (127.0.0.1) │
  │                            ▼                                │
  │   Termux Local Compute Layer                                │
  │     - Local scikit-learn / joblib ML inference              │
  │     - Calibrated Platt scaling (P(LIVE))                    │
  │     - Research DSP & system diagnostics                     │
  │                            │                                │
  │                            │ P(LIVE) response               │
  │                            ▼                                │
  │   Android Security Gate                                     │
  │     - Deterministic 3-state hierarchy (LIVE/SPOOF/UNCERTAIN)│
  │     - Android Keystore ECDSA P-256 signing                  │
  │                                                             │
  └─────────────────────────────────────────────────────────────┘
                               │
                               │ Signed Transcript Envelope
                               ▼
                      PC Independent Verifier
```

---

## Installation & Setup on iQOO Phone

1. Install **Termux** on the iQOO phone (from F-Droid or GitHub).
2. Clone or copy `EdgePPG` into Termux home directory:
   ```bash
   cd $HOME
   git clone <repo-url> EdgePPG
   cd EdgePPG/termux
   ```
3. Run the setup script:
   ```bash
   bash setup.sh
   ```

---

## Service Management

- **Start Background Daemon:**
  ```bash
  bash start_edgeppg.sh
  ```
- **Check Status & Diagnostics:**
  ```bash
  bash status_edgeppg.sh
  ```
- **Stop Daemon:**
  ```bash
  bash stop_edgeppg.sh
  ```

---

## API Endpoints

- `GET /status` — Service health and loaded model status.
- `POST /predict` — Evaluates 28-feature array and returns calibrated $P(\text{LIVE})$.
- `GET /diagnostics` — CPU load, resident memory, and inference latencies.
- `POST /research/dsp` — Offline research POS and spectral SNR analysis.
