"""Remote Photoplethysmography Correspondence Feature (CFrPPG) Engine.

Reference:
Si-Qi Liu, Xiangyuan Lan, Pong C. Yuen.
"Remote Photoplethysmography Correspondence Feature for 3D Mask Face Presentation Attack Detection."
European Conference on Computer Vision (ECCV), 2018.
https://openaccess.thecvf.com/content_ECCV_2018/html/Siqi_Liu_Remote_Photoplethysmography_Correspondence_ECCV_2018_paper.html

Multi-Channel Extension (MCCFrPPG):
Si-Qi Liu, Xiangyuan Lan, Pong C. Yuen.
"Multi-Channel Remote Photoplethysmography Correspondence Feature for 3D Mask Face Presentation Attack Detection."
IEEE Transactions on Information Forensics and Security (TIFS), Vol. 16, 2021, pp. 2683-2696.

Core Concept:
Live arterial blood pulsations exhibit strict spatial and spectral synchronization
across anatomically vascularized facial regions (forehead, left cheek, right cheek).
In contrast, 3D mask presentations, printed photos, or screen replays lack coherent
physiological blood flow: their observed variations are governed by ambient lighting,
head movement, or camera noise, resulting in divergent or noise-dominated spectra.

CFrPPG establishes a noise-aware consensus spectral template T(f) across multiple facial
ROIs and measures the cosine correspondence c_k = <S_k, T> for each region k.
A bona fide live subject yields high uniform correspondence (c_bar >= 0.65, low variance).
A 3D mask attack yields fragmented, low correspondence (c_bar < 0.45 or c_min < 0.30).
"""

from __future__ import annotations

import math
from typing import Dict, List, Optional, Tuple, Union
import numpy as np
from scipy import signal, fft


def extract_spectra_per_roi(
    signals: np.ndarray,
    fs: float = 30.0,
    f_min: float = 0.7,
    f_max: float = 4.0,
    n_fft: int = 256,
) -> Tuple[np.ndarray, np.ndarray]:
    """Extract power spectral density (PSD) per facial ROI in the cardiac bandwidth.

    Args:
        signals: Array of shape (K, N) containing filtered rPPG signals from K regions.
        fs: Sampling rate in Hz (default 30.0 fps).
        f_min: Lower cardiac frequency bound (0.7 Hz = 42 BPM).
        f_max: Upper cardiac frequency bound (4.0 Hz = 240 BPM).
        n_fft: FFT length (default 256 points).

    Returns:
        cardiac_freqs: 1D array of frequency bins within [f_min, f_max].
        cardiac_psd: Array of shape (K, M) containing power spectra for each ROI.
    """
    if signals.ndim == 1:
        signals = signals[np.newaxis, :]

    k_rois, n_samples = signals.shape
    if n_samples < 32:
        return np.array([]), np.zeros((k_rois, 0))

    # Apply Hann window to reduce spectral leakage across finite window
    window = signal.windows.hann(n_samples)
    windowed = signals * window[np.newaxis, :]

    # Real FFT
    pxx = np.abs(fft.rfft(windowed, n=n_fft, axis=1)) ** 2
    freqs = np.linspace(0.0, fs / 2.0, pxx.shape[1])

    # Filter to cardiac frequency band [f_min, f_max]
    band_mask = (freqs >= f_min) & (freqs <= f_max)
    if not np.any(band_mask):
        return np.array([]), np.zeros((k_rois, 0))

    cardiac_freqs = freqs[band_mask]
    cardiac_psd = pxx[:, band_mask]
    return cardiac_freqs, cardiac_psd


def normalize_spectra(psd: np.ndarray, subtract_noise_floor: bool = True) -> np.ndarray:
    """Normalize each region's spectrum to unit L2 norm, optionally subtracting the noise floor.

    In the ECCV 2018 paper, noise-aware representation removes the global broadband
    noise floor so that correspondence focuses strictly on cardiac spectral peaks.

    Args:
        psd: Array of shape (K, M).
        subtract_noise_floor: If True, centers each spectrum around its mean noise floor
                              before L2 normalization.

    Returns:
        Array of shape (K, M) with each row having unit L2 norm.
    """
    if psd.size == 0:
        return psd

    k_rois, m_bins = psd.shape
    processed = psd.copy()
    if subtract_noise_floor:
        # Subtract mean noise floor per ROI
        means = np.mean(processed, axis=1, keepdims=True)
        processed = processed - means

    norms = np.linalg.norm(processed, ord=2, axis=1, keepdims=True)
    eps = 1e-9
    safe_norms = np.where(norms < eps, 1.0, norms)
    normalized = processed / safe_norms
    normalized[norms.squeeze(axis=-1) < eps] = 0.0
    return normalized


def compute_consensus_template(normalized_psd: np.ndarray) -> np.ndarray:
    """Compute the global consensus physiological template T(f).

    T(f) = (1/K) * sum_{k=1}^K S_k_hat(f)
    T_hat = T / ||T||_2

    Args:
        normalized_psd: Array of shape (K, M) of unit L2-normalized spectra.

    Returns:
        1D array of shape (M,) representing the unit-normalized consensus template.
    """
    if normalized_psd.size == 0:
        return np.array([])

    template = np.mean(normalized_psd, axis=0)
    norm = np.linalg.norm(template, ord=2)
    if norm < 1e-9:
        return np.zeros_like(template)
    return template / norm


def compute_correspondence_vector(
    normalized_psd: np.ndarray,
    template: np.ndarray,
) -> np.ndarray:
    """Compute the CFrPPG correspondence vector C = [c_1, ..., c_K]^T.

    c_k = <S_k_hat, T_hat> = sum_f S_k_hat(f) * T_hat(f)

    Args:
        normalized_psd: Array of shape (K, M).
        template: 1D array of shape (M,).

    Returns:
        1D array of length K containing regional correspondence scores.
    """
    if normalized_psd.size == 0 or template.size == 0:
        return np.zeros(normalized_psd.shape[0] if normalized_psd.ndim > 1 else 0)

    # Inner product per region: (K, M) @ (M,) -> (K,)
    scores = np.dot(normalized_psd, template)
    return np.clip(scores, -1.0, 1.0)


def compute_cfrppg_metrics(
    signals: np.ndarray,
    fs: float = 30.0,
    f_min: float = 0.7,
    f_max: float = 4.0,
    n_fft: int = 256,
) -> Dict[str, Union[float, bool, str, List[float]]]:
    """Compute complete CFrPPG metrics across anatomical ROIs.

    Args:
        signals: Array of shape (K, N) with filtered signals from K regions.
        fs: Sampling frequency in Hz.
        f_min: Minimum pulse frequency.
        f_max: Maximum pulse frequency.
        n_fft: FFT length.

    Returns:
        Dictionary containing:
        - is_valid: bool
        - mean_correspondence: float in [0, 1]
        - min_correspondence: float in [0, 1]
        - variance_correspondence: float
        - region_scores: list of float [c_1, ..., c_K]
        - consensus_hr_bpm: float
        - consensus_snr_db: float
        - mask_spoof_risk: str ("LOW", "MEDIUM", "HIGH_SUSPECTED_MASK")
        - quality_status: str
    """
    if signals.ndim == 1:
        signals = signals[np.newaxis, :]

    k_rois, n_samples = signals.shape
    if n_samples < 45:
        return {
            "is_valid": False,
            "mean_correspondence": 0.0,
            "min_correspondence": 0.0,
            "variance_correspondence": 0.0,
            "region_scores": [0.0] * k_rois,
            "consensus_hr_bpm": 0.0,
            "consensus_snr_db": 0.0,
            "mask_spoof_risk": "HIGH_SUSPECTED_MASK",
            "quality_status": "INSUFFICIENT_SAMPLES",
        }

    freqs, psd = extract_spectra_per_roi(signals, fs, f_min, f_max, n_fft)
    if psd.size == 0:
        return {
            "is_valid": False,
            "mean_correspondence": 0.0,
            "min_correspondence": 0.0,
            "variance_correspondence": 0.0,
            "region_scores": [0.0] * k_rois,
            "consensus_hr_bpm": 0.0,
            "consensus_snr_db": 0.0,
            "mask_spoof_risk": "HIGH_SUSPECTED_MASK",
            "quality_status": "EMPTY_BAND",
        }

    # Normalize spectra with noise-floor subtraction
    norm_psd = normalize_spectra(psd, subtract_noise_floor=True)
    template = compute_consensus_template(norm_psd)
    scores = compute_correspondence_vector(norm_psd, template)

    mean_corr = float(np.mean(scores))
    min_corr = float(np.min(scores))
    var_corr = float(np.var(scores))

    # Raw power template for consensus HR & SNR calculation
    raw_template = np.mean(psd, axis=0)
    peak_idx = int(np.argmax(raw_template))
    f_peak = float(freqs[peak_idx])
    consensus_hr_bpm = f_peak * 60.0

    # Calculate SNR of consensus template around dominant peak (+/- 5 BPM = 0.083 Hz)
    delta_f = 5.0 / 60.0
    peak_window = (freqs >= (f_peak - delta_f)) & (freqs <= (f_peak + delta_f))
    peak_power = float(np.sum(raw_template[peak_window]))
    total_power = float(np.sum(raw_template))
    noise_power = max(total_power - peak_power, 1e-9)

    snr_ratio = peak_power / noise_power
    consensus_snr_db = float(10.0 * np.log10(max(snr_ratio, 1e-4)))

    # Classification / Risk heuristic matching ECCV 2018 validation criteria
    # Bona fide: high correspondence (c_bar >= 0.70, c_min >= 0.55), low variance (<= 0.05), positive SNR
    if mean_corr >= 0.70 and min_corr >= 0.55 and var_corr <= 0.05 and consensus_snr_db >= -2.0:
        mask_spoof_risk = "LOW"
        quality_status = "VALID"
        is_valid = True
    elif mean_corr >= 0.50 and min_corr >= 0.35 and consensus_snr_db >= -4.0:
        mask_spoof_risk = "MEDIUM"
        quality_status = "INTERMEDIATE"
        is_valid = True
    else:
        mask_spoof_risk = "HIGH_SUSPECTED_MASK"
        quality_status = "DIVERGENT_OR_NOISE"
        is_valid = True

    return {
        "is_valid": is_valid,
        "mean_correspondence": round(mean_corr, 4),
        "min_correspondence": round(min_corr, 4),
        "variance_correspondence": round(var_corr, 6),
        "region_scores": [round(float(s), 4) for s in scores],
        "consensus_hr_bpm": round(consensus_hr_bpm, 1),
        "consensus_snr_db": round(consensus_snr_db, 2),
        "mask_spoof_risk": mask_spoof_risk,
        "quality_status": quality_status,
    }


def synthesize_multiroi_signals(
    duration_sec: float = 5.0,
    fs: float = 30.0,
    hr_bpm: float = 72.0,
    condition: str = "LIVE",
    seed: int = 42,
) -> np.ndarray:
    """Synthesize 3-ROI rPPG signals (Forehead, L-Cheek, R-Cheek) for testing.

    Conditions:
    - "LIVE": Synchronous pulse across ROIs with physiological phase shifts (< 30ms)
              and minor amplitude variations.
    - "3D_MASK": Attenuated/absent pulse, dominant uncorrelated sensor noise or surface reflectance.
    - "SCREEN_REPLAY": High-frequency screen flicker + flat rPPG spectrum.
    - "PARTIAL_OCCLUDED": Forehead and L-Cheek live, R-Cheek noise/occluded.
    """
    rng = np.random.default_rng(seed)
    n_samples = int(duration_sec * fs)
    t = np.linspace(0.0, duration_sec, n_samples, endpoint=False)
    f_hr = hr_bpm / 60.0

    signals = np.zeros((3, n_samples), dtype=np.float32)

    if condition == "LIVE":
        # Forehead (ROI 0)
        p0 = np.sin(2.0 * np.pi * f_hr * t) + 0.3 * np.sin(4.0 * np.pi * f_hr * t)
        n0 = rng.normal(0.0, 0.15, n_samples)
        signals[0] = p0 + n0

        # Left Cheek (ROI 1): slight phase delay (dt = 0.03s), amplitude 0.85
        dt1 = 0.03
        p1 = 0.85 * (np.sin(2.0 * np.pi * f_hr * (t - dt1)) + 0.25 * np.sin(4.0 * np.pi * f_hr * (t - dt1)))
        n1 = rng.normal(0.0, 0.18, n_samples)
        signals[1] = p1 + n1

        # Right Cheek (ROI 2): slight phase delay (dt = 0.02s), amplitude 0.80
        dt2 = 0.02
        p2 = 0.80 * (np.sin(2.0 * np.pi * f_hr * (t - dt2)) + 0.25 * np.sin(4.0 * np.pi * f_hr * (t - dt2)))
        n2 = rng.normal(0.0, 0.16, n_samples)
        signals[2] = p2 + n2

    elif condition == "3D_MASK":
        # No physiological pulse penetrates the mask; independent white Gaussian noise
        # + low frequency head motion drift at different frequencies
        for i in range(3):
            drift_f = 0.2 + 0.1 * i
            drift = 0.5 * np.sin(2.0 * np.pi * drift_f * t)
            noise = rng.normal(0.0, 0.4, n_samples)
            signals[i] = drift + noise

    elif condition == "SCREEN_REPLAY":
        # Display illumination flutter: high frequency periodic variation (e.g. 10 Hz)
        # without genuine 1.2 Hz pulse
        for i in range(3):
            flicker = 0.8 * np.sin(2.0 * np.pi * 8.5 * t)
            noise = rng.normal(0.0, 0.3, n_samples)
            signals[i] = flicker + noise

    elif condition == "PARTIAL_OCCLUDED":
        # Forehead and L-Cheek live, R-Cheek pure noise
        p0 = np.sin(2.0 * np.pi * f_hr * t)
        p1 = 0.9 * np.sin(2.0 * np.pi * f_hr * (t - 0.03))
        signals[0] = p0 + rng.normal(0.0, 0.15, n_samples)
        signals[1] = p1 + rng.normal(0.0, 0.15, n_samples)
        signals[2] = rng.normal(0.0, 0.8, n_samples)  # Uncorrelated occluded noise

    return signals
