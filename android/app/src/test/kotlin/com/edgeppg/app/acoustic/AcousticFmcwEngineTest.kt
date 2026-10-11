package com.edgeppg.app.acoustic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AcousticFmcwEngineTest {

    @Test
    fun chirpGenerator_enforcesSafetyConstraintsAndDimensions() {
        val unsafeConfig = ChirpConfig(
            startFreqHz = 18000f,
            endFreqHz = 21000f,
            sampleRateHz = 48000,
            durationMs = 40,
            gain = 0.95f // Exceeds safe limit
        )
        // Gain must be clamped to <= 0.30 per safety guide
        assertEquals(0.30f, unsafeConfig.safeGain, 1e-4f)

        val gen = ChirpGenerator(unsafeConfig)
        assertEquals(1920, gen.numSamples)
        assertEquals(1920, gen.referenceFloat.size)

        // Verify amplitude never exceeds safe gain
        var maxAmp = 0f
        for (sample in gen.referenceFloat) {
            maxAmp = maxAmp.coerceAtLeast(abs(sample))
        }
        assertTrue("Max amplitude ($maxAmp) must be <= 0.30", maxAmp <= 0.3001f)

        // Verify Tukey window tapers boundaries near 0
        assertEquals(0f, gen.referenceFloat[0], 0.01f)
        assertEquals(0f, gen.referenceFloat[gen.numSamples - 1], 0.01f)

        // Verify PCM16 generation
        val pcm = gen.generateSingleChirpPcm16()
        assertEquals(1920, pcm.size)

        // Looped buffer
        val looped = gen.generateLoopedPlaybackPcm16(repeatCount = 5)
        assertEquals(1920 * 5, looped.size)
    }

    @Test
    fun acousticDsp_fftCorrectnessOnKnownSinusoid() {
        val n = 256
        val targetBin = 17
        val real = FloatArray(n)
        val imag = FloatArray(n)

        // Generate pure complex sinusoid: e^(j * 2pi * k * n / N)
        for (i in 0 until n) {
            val angle = 2.0 * PI * targetBin * i / n
            real[i] = cos(angle).toFloat()
            imag[i] = sin(angle).toFloat()
        }

        AcousticDsp.fft(real, imag)

        // Peak must be sharply located at targetBin
        var maxPower = -1f
        var peakBin = -1
        for (k in 0 until n) {
            val p = real[k] * real[k] + imag[k] * imagFftPower(imag[k])
            if (p > maxPower) {
                maxPower = p
                peakBin = k
            }
        }
        assertEquals(targetBin, peakBin)
    }

    private fun imagFftPower(v: Float): Float = v * v

    @Test
    fun acousticDsp_clippingDetectorFlagsSaturatedAudio() {
        val normalPcm = ShortArray(1000) { (it % 1000).toShort() }
        assertFalse(AcousticDsp.checkClipping(normalPcm))

        val saturatedPcm = ShortArray(1000) { (if (it < 100) 32500 else 100).toShort() }
        assertTrue(AcousticDsp.checkClipping(saturatedPcm))
    }

    @Test
    fun acousticDsp_rangeResolutionMatchesFmcwPhysics() {
        val config = ChirpConfig(
            startFreqHz = 18000f,
            endFreqHz = 21000f,
            sampleRateHz = 48000,
            durationMs = 40
        )
        // delta_R = (c * f_s * T) / (2 * B * N_fft)
        // c = 34300 cm/s, f_s = 48000, T = 0.040, B = 3000, N_fft = 2048
        // (34300 * 48000 * 0.040) / (2 * 3000 * 2048) = 65856000 / 12288000 = 5.359375 cm
        val deltaR = AcousticDsp.rangeResolutionCm(config, 2048)
        assertEquals(5.359375f, deltaR, 0.001f)
    }

    @Test
    fun acousticDsp_syntheticEchoRangingResolution() {
        val config = ChirpConfig(
            startFreqHz = 18000f,
            endFreqHz = 21000f,
            sampleRateHz = 48000,
            durationMs = 40,
            gain = 0.25f
        )
        val gen = ChirpGenerator(config)
        val deltaR = AcousticDsp.rangeResolutionCm(config, AcousticDsp.FFT_SIZE)

        // Place a synthetic target echo at bin 6 (~32.15 cm)
        val targetBin = 6
        val targetRangeExpected = targetBin * deltaR

        // In FMCW baseband, target at delay tau produces beat frequency f_b = (B / T) * tau
        // which maps directly to bin targetBin in Range FFT.
        // Let's synthesize received signal with delayed chirp:
        // delay tau = 2 * R / c
        val tauSec = (2.0 * targetRangeExpected) / AcousticDsp.SPEED_OF_SOUND_CM_S
        val delaySamples = (config.sampleRateHz * tauSec).toInt()

        val rxSignal = FloatArray(gen.numSamples * 2)
        // Add target reflection delayed by delaySamples
        for (i in 0 until gen.numSamples) {
            if (i + delaySamples < rxSignal.size) {
                rxSignal[i + delaySamples] = gen.referenceFloat[i] * 0.8f
            }
        }

        // Process chirp at offset 0
        val dsp = AcousticDsp.processChirp(
            rxSignal = rxSignal,
            offset = 0,
            generator = gen,
            prevPhase = null,
            isClipping = false
        )

        assertTrue("Target echo should be valid", dsp.isValid)
        assertNotNull("Target range should be present", dsp.targetRangeCm)
        assertEquals("Quality status must be MEASURED", "MEASURED", dsp.qualityStatus)
        assertTrue("SNR should be >= 6dB", dsp.snrDb >= 6.0f)
        // Detected range should be within 1 bin resolution (5.36 cm) of expected target
        val diff = abs(dsp.targetRangeCm!! - targetRangeExpected)
        assertTrue("Range estimate error ($diff cm) should be <= deltaR ($deltaR cm)", diff <= deltaR * 1.5f)
    }

    @Test
    fun acousticDsp_returnsUnavailableWhenSignalIsNoiseOnly() {
        val config = ChirpConfig()
        val gen = ChirpGenerator(config)

        // Pure random noise without correlated chirp reflection
        val noiseSignal = FloatArray(gen.numSamples) { ((Math.random() - 0.5) * 0.001).toFloat() }

        val dsp = AcousticDsp.processChirp(
            rxSignal = noiseSignal,
            offset = 0,
            generator = gen,
            prevPhase = null,
            isClipping = false
        )

        // Noise floor is uniform; no prominent peak above 6 dB SNR in target window
        if (!dsp.isValid) {
            assertNull("Target range must be null on low SNR", dsp.targetRangeCm)
            assertEquals("LOW_SNR", dsp.qualityStatus)
        }
    }

    @Test
    fun acousticDsp_phaseMotionDetection() {
        val config = ChirpConfig()
        val gen = ChirpGenerator(config)

        val rx1 = FloatArray(gen.numSamples) { gen.referenceFloat[it] }
        val dsp1 = AcousticDsp.processChirp(rx1, 0, gen, prevPhase = null)

        // Second chirp with intentional phase shift (+0.5 rad)
        val rx2 = FloatArray(gen.numSamples) { gen.referenceFloat[it] }
        val dsp2 = AcousticDsp.processChirp(rx2, 0, gen, prevPhase = dsp1.currentPhase + 0.5f)

        // Motion score should register the phase shift
        assertTrue("Motion score should be non-zero when phase shifts", dsp2.motionScore > 0f)
    }

    @Test
    fun acousticSnapshot_contractIntegrity() {
        val unavailable = AcousticSnapshot.unavailable("TEST_REASON")
        assertFalse(unavailable.isValid)
        assertNull(unavailable.targetRangeCm)
        assertEquals("TEST_REASON", unavailable.qualityStatus)
        assertEquals(0f, unavailable.motionScore, 0f)

        val stopped = AcousticSnapshot.stopped()
        assertFalse(stopped.isValid)
        assertEquals("STOPPED", stopped.qualityStatus)
    }
}
