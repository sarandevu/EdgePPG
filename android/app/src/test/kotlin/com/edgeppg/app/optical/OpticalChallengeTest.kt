package com.edgeppg.app.optical

import com.edgeppg.app.challenge.OpticalColor
import com.edgeppg.app.features.FeatureSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the EdgePPG Optical Challenge Layer.
 *
 * Verifies:
 *  1. Seed-bound deterministic sequence generation with photosensitivity safety bounds.
 *  2. Time-series cross-correlation scanner & spectral shift verification.
 *  3. Inconclusive/spoof handling on static photos, lost faces, and ambient extremes.
 *  4. Strict adherence to frozen edgeppg-1.0 28-feature contract (index 10).
 */
class OpticalChallengeTest {

    private val testSessionId = "sess-opt-test-01"
    private val testSeedHex = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test
    fun sequence_generation_is_deterministic_and_seed_bound() {
        val seq1 = OpticalStimulusSequence.generate(testSessionId, testSeedHex)
        val seq2 = OpticalStimulusSequence.generate(testSessionId, testSeedHex)

        assertEquals("Same seed must yield same step count", seq1.steps.size, seq2.steps.size)
        assertEquals("Total duration must match", seq1.totalDurationMs, seq2.totalDurationMs)
        for (i in seq1.steps.indices) {
            assertEquals("Step $i color must match", seq1.steps[i].color, seq2.steps[i].color)
            assertEquals("Step $i deltaPct must match", seq1.steps[i].deltaPct, seq2.steps[i].deltaPct)
            assertEquals("Step $i duration must match", seq1.steps[i].durationMs, seq2.steps[i].durationMs)
        }

        val differentSeed = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        val seqDiff = OpticalStimulusSequence.generate(testSessionId, differentSeed)
        assertNotEquals("Different seed should generate different sequence", seq1.steps.map { it.color }, seqDiff.steps.map { it.color })
    }

    @Test
    fun sequence_adheres_to_photosensitivity_safety_standards() {
        val seq = OpticalStimulusSequence.generate(testSessionId, testSeedHex)

        // Must start with baseline
        assertTrue("First step must be baseline", seq.steps.first().isBaseline)
        assertEquals("Baseline color should be WHITE", OpticalColor.WHITE, seq.steps.first().color)

        // No step may dwell under 300ms (prevents rapid flicker / photoepileptic strobing)
        for (step in seq.steps) {
            assertTrue("Step dwell duration must be >= 300ms for user safety: ${step.durationMs}ms", step.durationMs >= 300L)
            assertTrue("Step deltaPct must be <= 65% for gentle stimulus: ${step.deltaPct}%", step.deltaPct <= 65)
        }

        // Must contain chromatic transitions
        val nonBaseline = seq.steps.filter { !it.isBaseline }
        assertTrue("Must contain active stimulus steps", nonBaseline.isNotEmpty())
        assertTrue("Must contain WARM or COOL chromatic steps", nonBaseline.any { it.color == OpticalColor.WARM || it.color == OpticalColor.COOL })
    }

    @Test
    fun analyzer_detects_correlated_reflective_response_with_lag() {
        val seq = OpticalStimulusSequence.generate(testSessionId, testSeedHex)
        val analyzer = OpticalResponseAnalyzer()

        val t0 = 10_000L
        val injectedLagMs = 75L // Injected hardware display-to-camera delay

        // Simulate 50 camera frames at 30 FPS (~33ms intervals)
        for (i in 0 until 50) {
            val frameTime = t0 + (i * 33L)

            // Look up active stimulus at (frameTime - injectedLagMs)
            var curStepTime = t0
            var activeStimulusPct = 0
            var activeColor = OpticalColor.WHITE

            for (step in seq.steps) {
                val stepEnd = curStepTime + step.durationMs
                if ((frameTime - injectedLagMs) in curStepTime until stepEnd) {
                    if (!step.isBaseline) {
                        activeStimulusPct = step.deltaPct
                        activeColor = step.color
                    }
                    break
                }
                curStepTime = stepEnd
            }

            // Base skin tone: R=130, G=105, B=90
            var r = 130f
            var g = 105f
            var b = 90f

            if (activeStimulusPct > 0) {
                val boost = (activeStimulusPct / 100f) * 12f
                when (activeColor) {
                    OpticalColor.WARM -> {
                        r += boost * 1.5f
                        g += boost * 0.8f
                        b += boost * 0.4f
                    }
                    OpticalColor.COOL -> {
                        r += boost * 0.4f
                        g += boost * 0.8f
                        b += boost * 1.5f
                    }
                    OpticalColor.WHITE -> {
                        r += boost
                        g += boost
                        b += boost
                    }
                }
            }

            // 9-ROI array [FH_RGB, LCK_RGB, RCK_RGB]
            val means = floatArrayOf(
                r, g, b,
                r * 0.98f, g * 0.98f, b * 0.98f,
                r * 1.02f, g * 1.02f, b * 1.02f
            )
            analyzer.addSample(frameTime, means, facePresent = true)
        }

        val result = analyzer.analyze(seq, t0)

        assertTrue("Analysis result should be valid for correlated reflection", result.isValid)
        assertTrue("Optical response score should be positive: ${result.opticalResponseScore}", result.opticalResponseScore > 0.40f)
        assertTrue("Correlation should be high (> 0.50): ${result.correlation}", result.correlation > 0.50f)
        assertTrue("Estimated lag should be near injected 75ms (within +/-30ms): ${result.timingErrorMs}ms",
            result.timingErrorMs in 45L..105L)
        assertEquals("Quality status should be VALID", "VALID", result.qualityStatus)
    }

    @Test
    fun analyzer_rejects_static_flat_surface_as_weak_reflectance() {
        val seq = OpticalStimulusSequence.generate(testSessionId, testSeedHex)
        val analyzer = OpticalResponseAnalyzer()

        val t0 = 10_000L
        // Completely flat, unchanging skin values (e.g. printed paper photo or static replay)
        for (i in 0 until 50) {
            val frameTime = t0 + (i * 33L)
            val staticMeans = floatArrayOf(
                120f, 100f, 90f,
                120f, 100f, 90f,
                120f, 100f, 90f
            )
            analyzer.addSample(frameTime, staticMeans, facePresent = true)
        }

        val result = analyzer.analyze(seq, t0)

        assertFalse("Static flat surface must not be flagged valid", result.isValid)
        assertEquals("Score should be clamped to low floor (0.05)", 0.05f, result.opticalResponseScore, 0.001f)
        assertEquals("Quality status should reflect weak reflectance", "UNSYNCHRONIZED_OR_WEAK", result.qualityStatus)
        assertTrue("Reason codes must explain low reflectance", result.reasonCodes.any { it.contains("low-optical-reflectance") })
    }

    @Test
    fun analyzer_marks_inconclusive_when_face_is_lost() {
        val seq = OpticalStimulusSequence.generate(testSessionId, testSeedHex)
        val analyzer = OpticalResponseAnalyzer()

        val t0 = 10_000L
        // Face is present for only 10 frames, then lost for 40 frames
        for (i in 0 until 50) {
            val frameTime = t0 + (i * 33L)
            val hasFace = i < 10
            val means = if (hasFace) floatArrayOf(120f, 100f, 90f, 120f, 100f, 90f, 120f, 100f, 90f) else FloatArray(9)
            analyzer.addSample(frameTime, means, facePresent = hasFace)
        }

        val result = analyzer.analyze(seq, t0)

        assertFalse("Lost face must result in invalid result", result.isValid)
        assertEquals("Quality status should be POOR_COVERAGE", "POOR_COVERAGE", result.qualityStatus)
        assertTrue("Reason code should explain face loss", result.reasonCodes.any { it.contains("face-lost-during-challenge") })
    }

    @Test
    fun frozen_28_feature_contract_preserves_optical_score_at_index_10() {
        assertEquals("Frozen schema must have exactly 28 features", 28, FeatureSchema.FEATURE_ORDER.size)
        assertEquals("Feature at index 10 must be optical_response_score", "optical_response_score", FeatureSchema.FEATURE_ORDER[10])
    }
}
