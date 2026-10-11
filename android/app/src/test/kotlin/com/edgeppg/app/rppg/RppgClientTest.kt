package com.edgeppg.app.rppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class RppgClientTest {

    @Test
    fun rppg_initial_state_is_uncertain() {
        val client = RppgClient()
        assertEquals(RppgClient.DECISION_UNCERTAIN, client.decision)
        assertEquals(0f, client.hrBpm, 0.001f)
        assertEquals(0f, client.snr, 0.001f)
        assertEquals(0f, client.chromHrBpm, 0.001f)
        assertEquals(0f, client.methodAgreement, 0.001f)
    }

    @Test
    fun rppg_extracts_pulse_from_synthetic_signal() {
        val client = RppgClient()
        val fs = 30.0
        val targetFreq = 1.25 // 1.25 Hz = 75 BPM

        // Simulate 90 frames (~3 seconds) of physiological skin reflection
        for (i in 0 until 90) {
            val t = i / fs
            val pulse = (sin(2.0 * Math.PI * targetFreq * t)).toFloat()

            // Physiological RGB variation: Green modulates strongest, Red moderately, Blue least
            // 3 ROIs: Forehead, Left Cheek, Right Cheek
            val rVal = 120f + 2.0f * pulse
            val gVal = 100f + 5.0f * pulse
            val bVal = 80f + 1.0f * pulse

            val frameMeans = floatArrayOf(
                rVal, gVal, bVal,             // ROI 0: Forehead
                rVal * 0.98f, gVal * 0.98f, bVal * 0.98f, // ROI 1: Left cheek
                rVal * 0.99f, gVal * 0.99f, bVal * 0.99f  // ROI 2: Right cheek
            )

            client.pushFrame(frameMeans, (i * 33_333_333L))
        }

        // Verify HR is within physiological pulse band (60..90 BPM for 75 BPM input)
        assertTrue("HR should be detected > 50 BPM, got ${client.hrBpm}", client.hrBpm in 50f..100f)
        assertTrue("CHROM HR should be detected > 50 BPM, got ${client.chromHrBpm}", client.chromHrBpm in 50f..100f)

        // Verify POS and CHROM agreement
        assertTrue("Method agreement should be positive, got ${client.methodAgreement}", client.methodAgreement > 0.4f)

        // Verify peak strength and stability
        assertTrue("Peak strength should be > 0.02, got ${client.peakStrength}", client.peakStrength > 0.02f)
        assertTrue("HR stability should be > 0.5, got ${client.hrStability}", client.hrStability > 0.5f)

        // Verify ROI correlation is high across synchronized facial ROIs
        assertTrue("Cross-ROI correlation should be > 0.5, got ${client.roiCorr}", client.roiCorr > 0.5f)

        // Verify CFrPPG consensus telemetry (ECCV 2018)
        val cfrppg = client.latestCfrppg
        assertTrue("CFrPPG should be valid on synthetic pulse", cfrppg.isValid)
        assertTrue("CFrPPG mean correspondence should be > 0.70, got ${cfrppg.meanCorrespondence}", cfrppg.meanCorrespondence > 0.70f)
        assertEquals("LOW", cfrppg.maskSpoofRisk)
        assertEquals("VALID", cfrppg.qualityStatus)
    }

    @Test
    fun rppg_handles_invalid_input_gracefully() {
        val client = RppgClient()
        // Wrong size array (should be ignored without exception)
        client.pushFrame(FloatArray(5), 1000L)
        assertEquals(0f, client.hrBpm, 0.001f)
    }

    @Test
    fun rppg_reset_clears_metrics() {
        val client = RppgClient()
        for (i in 0 until 50) {
            client.pushFrame(FloatArray(9) { 100f }, i * 33_333_333L)
        }
        client.reset()
        assertEquals(0f, client.hrBpm, 0.001f)
        assertEquals(0f, client.snr, 0.001f)
        assertEquals(0f, client.chromHrBpm, 0.001f)
        assertEquals(0f, client.methodAgreement, 0.001f)
        assertEquals(RppgClient.DECISION_UNCERTAIN, client.decision)
    }
}
