"""EdgePPG Termux Research DSP.

Provides experimental rPPG signal analysis, POS projection, Butterworth filtering,
spectral power estimation, and multi-ROI correlation analysis for research evaluation
directly on the phone.
"""

from __future__ import annotations

import numpy as np
from scipy.signal import butter, filtfilt


def extract_pos_pulse(rgb_buffer: np.ndarray, fps: float = 30.0) -> np.ndarray:
    """Plane-Orthogonal-to-Skin (POS) algorithm on an (N, 3) RGB temporal trace."""
    if len(rgb_buffer) < 32:
        return np.zeros(len(rgb_buffer), dtype=np.float32)

    mean_rgb = np.mean(rgb_buffer, axis=0) + 1e-6
    norm = rgb_buffer / mean_rgb

    # POS projection matrix
    # S1 = G - B
    # S2 = G + B - 2*R
    s1 = norm[:, 1] - norm[:, 2]
    s2 = norm[:, 1] + norm[:, 2] - 2.0 * norm[:, 0]

    std_s1 = np.std(s1) + 1e-6
    std_s2 = np.std(s2) + 1e-6

    alpha = std_s1 / std_s2
    h = s1 + alpha * s2
    return h.astype(np.float32)


def bandpass_filter(signal: np.ndarray, low_hz: float = 0.7, high_hz: float = 4.0, fps: float = 30.0) -> np.ndarray:
    """2nd-order Butterworth bandpass filter between 0.7 Hz (42 BPM) and 4.0 Hz (240 BPM)."""
    if len(signal) < 16:
        return signal

    nyquist = 0.5 * fps
    low = low_hz / nyquist
    high = min(high_hz / nyquist, 0.99)
    b, a = butter(2, [low, high], btype="bandpass")
    try:
        return filtfilt(b, a, signal).astype(np.float32)
    except Exception:
        return signal


def compute_spectral_snr(filtered_signal: np.ndarray, fps: float = 30.0, n_fft: int = 256) -> tuple[float, float]:
    """Compute peak HR (BPM) and spectral SNR from FFT.

    Returns:
        (hr_bpm, snr_ratio)
    """
    if len(filtered_signal) < 32:
        return 0.0, 0.0

    pad_len = max(len(filtered_signal), n_fft)
    fft_res = np.abs(np.fft.rfft(filtered_signal, n=pad_len))
    freqs = np.fft.rfftfreq(pad_len, d=1.0 / fps)

    # In-band mask (0.7 Hz to 3.5 Hz)
    in_band = (freqs >= 0.7) & (freqs <= 3.5)
    if not np.any(in_band):
        return 0.0, 0.0

    band_freqs = freqs[in_band]
    band_power = fft_res[in_band] ** 2

    peak_idx = np.argmax(band_power)
    peak_freq = band_freqs[peak_idx]
    peak_hr_bpm = peak_freq * 60.0

    total_inband_power = np.sum(band_power) + 1e-9
    # Neighborhood around fundamental (+- 0.15 Hz)
    peak_neighborhood = (freqs >= peak_freq - 0.15) & (freqs <= peak_freq + 0.15)
    signal_power = np.sum(fft_res[peak_neighborhood] ** 2)

    snr = float(signal_power / total_inband_power)
    return float(peak_hr_bpm), snr
