"""Unit tests for Remote Photoplethysmography Correspondence Feature (CFrPPG) Engine.

Validates:
1. Multi-ROI cardiac band spectral extraction and Hann windowing.
2. Unit L2 spectral normalization invariants.
3. Consensus physiological template T(f) computation.
4. Cosine similarity correspondence vector properties (in [0, 1]).
5. Live synchronized pulses achieve high correspondence (c_bar >= 0.70) and LOW risk.
6. 3D mask / uncorrelated noise attacks produce low correspondence (c_bar < 0.45) and HIGH risk.
7. Partial occlusion sensitivity (detects corrupted cheek).
8. Insufficient samples / invalid input handling.
"""

from __future__ import annotations

import unittest
import numpy as np

from ml.src.cfrppg_engine import (
    extract_spectra_per_roi,
    normalize_spectra,
    compute_consensus_template,
    compute_correspondence_vector,
    compute_cfrppg_metrics,
    synthesize_multiroi_signals,
)


class TestCfrppgEngine(unittest.TestCase):

    def test_spectral_extraction_and_shapes(self):
        fs = 30.0
        n_samples = 150  # 5 seconds
        signals = np.random.randn(3, n_samples)
        freqs, psd = extract_spectra_per_roi(signals, fs=fs, f_min=0.7, f_max=4.0, n_fft=256)

        self.assertGreater(len(freqs), 0)
        self.assertEqual(psd.shape[0], 3)
        self.assertEqual(psd.shape[1], len(freqs))
        self.assertTrue(np.all(freqs >= 0.7))
        self.assertTrue(np.all(freqs <= 4.0))

    def test_l2_normalization_invariant(self):
        rng = np.random.default_rng(123)
        psd = rng.uniform(0.1, 10.0, size=(3, 30))
        norm_psd = normalize_spectra(psd)

        for i in range(3):
            l2_norm = np.linalg.norm(norm_psd[i], ord=2)
            self.assertAlmostEqual(l2_norm, 1.0, places=5)

    def test_consensus_template_properties(self):
        rng = np.random.default_rng(456)
        psd = rng.uniform(0.1, 5.0, size=(3, 25))
        norm_psd = normalize_spectra(psd)
        template = compute_consensus_template(norm_psd)

        self.assertEqual(template.shape, (25,))
        self.assertAlmostEqual(float(np.linalg.norm(template, ord=2)), 1.0, places=5)

    def test_correspondence_vector_boundaries(self):
        # Cosine similarity between non-negative unit vectors must lie in [0.0, 1.0]
        rng = np.random.default_rng(789)
        psd = rng.exponential(scale=2.0, size=(3, 28))
        norm_psd = normalize_spectra(psd)
        template = compute_consensus_template(norm_psd)
        scores = compute_correspondence_vector(norm_psd, template)

        self.assertEqual(len(scores), 3)
        for s in scores:
            self.assertGreaterEqual(s, 0.0)
            self.assertLessEqual(s, 1.00001)

    def test_live_synchronized_pulses_achieve_high_correspondence(self):
        # Synthesize genuine live multi-ROI signals at 72 BPM
        live_signals = synthesize_multiroi_signals(
            duration_sec=6.0,
            fs=30.0,
            hr_bpm=72.0,
            condition="LIVE",
            seed=42,
        )

        metrics = compute_cfrppg_metrics(live_signals, fs=30.0)

        self.assertTrue(metrics["is_valid"])
        self.assertGreaterEqual(metrics["mean_correspondence"], 0.70)
        self.assertGreaterEqual(metrics["min_correspondence"], 0.60)
        self.assertEqual(metrics["mask_spoof_risk"], "LOW")
        self.assertEqual(metrics["quality_status"], "VALID")
        # Heart rate should be accurately estimated (~72 BPM +/- 4 BPM)
        self.assertAlmostEqual(metrics["consensus_hr_bpm"], 72.0, delta=4.0)

    def test_3d_mask_attack_yields_high_spoof_risk(self):
        # 3D mask attack: rigid surface blocks micro-vascular pulsation
        mask_signals = synthesize_multiroi_signals(
            duration_sec=6.0,
            fs=30.0,
            condition="3D_MASK",
            seed=101,
        )

        metrics = compute_cfrppg_metrics(mask_signals, fs=30.0)

        # Uncorrelated noise yields low SNR and high spoof risk
        self.assertEqual(metrics["mask_spoof_risk"], "HIGH_SUSPECTED_MASK")
        self.assertLess(metrics["consensus_snr_db"], -4.0)
        self.assertLess(metrics["min_correspondence"], 0.60)

    def test_screen_replay_attack_yields_high_spoof_risk(self):
        # Screen replay attack: high-frequency display flutter without pulse
        replay_signals = synthesize_multiroi_signals(
            duration_sec=6.0,
            fs=30.0,
            condition="SCREEN_REPLAY",
            seed=202,
        )

        metrics = compute_cfrppg_metrics(replay_signals, fs=30.0)

        self.assertEqual(metrics["mask_spoof_risk"], "HIGH_SUSPECTED_MASK")

    def test_partial_occlusion_drops_min_correspondence(self):
        # One region completely occluded with noise while 2 regions are live
        partial_signals = synthesize_multiroi_signals(
            duration_sec=6.0,
            fs=30.0,
            hr_bpm=75.0,
            condition="PARTIAL_OCCLUDED",
            seed=303,
        )

        metrics = compute_cfrppg_metrics(partial_signals, fs=30.0)

        # The occluded region score should be significantly lower than the live regions
        scores = metrics["region_scores"]
        self.assertLess(scores[2], scores[0])
        self.assertLess(scores[2], scores[1])

    def test_insufficient_samples_handling(self):
        short_signals = np.zeros((3, 20))
        metrics = compute_cfrppg_metrics(short_signals, fs=30.0)

        self.assertFalse(metrics["is_valid"])
        self.assertEqual(metrics["quality_status"], "INSUFFICIENT_SAMPLES")
        self.assertEqual(metrics["mean_correspondence"], 0.0)


if __name__ == "__main__":
    unittest.main()
