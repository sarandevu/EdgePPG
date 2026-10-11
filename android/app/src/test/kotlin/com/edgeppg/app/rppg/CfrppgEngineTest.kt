package com.edgeppg.app.rppg

import com.edgeppg.app.rppg.cfrppg.CfrppgEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class CfrppgEngineTest {

    @Test
    fun testInsufficientSamplesReturnsUnavailable() {
        val engine = CfrppgEngine()
        val emptyFilt = FloatArray(30 * 3)
        val snap = engine.computeFromFilteredRois(emptyFilt, nSamples = 30)

        assertFalse(snap.isValid)
        assertEquals("INSUFFICIENT_SAMPLES", snap.qualityStatus)
        assertEquals(0f, snap.meanCorrespondence, 1e-4f)
    }

    @Test
    fun testMotionContaminatedReturnsUnavailable() {
        val engine = CfrppgEngine()
        val filt = FloatArray(100 * 3)
        val snap = engine.computeFromFilteredRois(filt, nSamples = 100, badStreak = 10)

        assertFalse(snap.isValid)
        assertEquals("MOTION_CONTAMINATED", snap.qualityStatus)
    }

    @Test
    fun testLiveSynchronizedPulsesAchieveHighCorrespondenceAndLowRisk() {
        val engine = CfrppgEngine()
        val nSamples = 120 // 4 seconds at 30 fps
        val fs = 30f
        val hrHz = 72f / 60f // 1.2 Hz

        val s0 = FloatArray(nSamples)
        val s1 = FloatArray(nSamples)
        val s2 = FloatArray(nSamples)

        for (i in 0 until nSamples) {
            val t = i / fs
            s0[i] = sin(2.0 * Math.PI * hrHz * t).toFloat()
            s1[i] = 0.9f * sin(2.0 * Math.PI * hrHz * (t - 0.02)).toFloat()
            s2[i] = 0.85f * sin(2.0 * Math.PI * hrHz * (t - 0.03)).toFloat()
        }

        val snap = engine.computeFromMultiRoiArrays(arrayOf(s0, s1, s2))

        assertTrue(snap.isValid)
        assertEquals("LOW", snap.maskSpoofRisk)
        assertEquals("VALID", snap.qualityStatus)
        assertTrue("Mean correspondence should be >= 0.75", snap.meanCorrespondence >= 0.75f)
        assertTrue("Min correspondence should be >= 0.65", snap.minCorrespondence >= 0.65f)
        assertEquals(72f, snap.consensusHrBpm, 4f)
    }

    @Test
    fun testUncorrelated3DMaskNoiseYieldsHighSpoofRisk() {
        val engine = CfrppgEngine()
        val nSamples = 120
        val rng = Random(42)

        val s0 = FloatArray(nSamples) { rng.nextFloat() * 2f - 1f }
        val s1 = FloatArray(nSamples) { rng.nextFloat() * 2f - 1f }
        val s2 = FloatArray(nSamples) { rng.nextFloat() * 2f - 1f }

        val snap = engine.computeFromMultiRoiArrays(arrayOf(s0, s1, s2))

        assertEquals("HIGH_SUSPECTED_MASK", snap.maskSpoofRisk)
        assertTrue("SNR should be low for white noise", snap.consensusSnrDb < -3.0f)
    }

    @Test
    fun testPartialOcclusionDropsWorstCaseRoi() {
        val engine = CfrppgEngine()
        val nSamples = 120
        val fs = 30f
        val hrHz = 1.3f
        val rng = Random(123)

        val s0 = FloatArray(nSamples) { sin(2.0 * Math.PI * hrHz * (it / fs)).toFloat() }
        val s1 = FloatArray(nSamples) { 0.9f * sin(2.0 * Math.PI * hrHz * (it / fs - 0.02)).toFloat() }
        val s2 = FloatArray(nSamples) { (rng.nextFloat() * 2f - 1f) * 2f } // Occluded noise

        val snap = engine.computeFromMultiRoiArrays(arrayOf(s0, s1, s2))

        assertTrue(snap.regionScores[2] < snap.regionScores[0])
        assertTrue(snap.regionScores[2] < snap.regionScores[1])
    }

    @Test
    fun testCosineSimilarityBoundaries() {
        val engine = CfrppgEngine()
        val nSamples = 100
        val rng = Random(789)

        val s0 = FloatArray(nSamples) { rng.nextFloat() }
        val s1 = FloatArray(nSamples) { rng.nextFloat() }
        val s2 = FloatArray(nSamples) { rng.nextFloat() }

        val snap = engine.computeFromMultiRoiArrays(arrayOf(s0, s1, s2))

        for (score in snap.regionScores) {
            assertTrue(score in -1.0001f..1.0001f)
        }
    }
}
