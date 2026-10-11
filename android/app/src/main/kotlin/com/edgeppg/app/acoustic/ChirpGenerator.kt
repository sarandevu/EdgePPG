package com.edgeppg.app.acoustic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Windowing function types for FMCW chirp generation.
 */
enum class WindowType {
    TUKEY,
    HANNING,
    RECTANGULAR
}

/**
 * Configuration parameters for an FMCW near-ultrasound chirp.
 *
 * Adheres strictly to the safety constraints outlined in the guide:
 * - Near-ultrasonic frequencies (18 kHz - 21 kHz)
 * - Conservative gain capped at <= 0.30 to avoid transducer clipping or ear fatigue
 */
data class ChirpConfig(
    val startFreqHz: Float = 18000f,
    val endFreqHz: Float = 21000f,
    val sampleRateHz: Int = 48000,
    val durationMs: Int = 40,
    val gain: Float = 0.25f,
    val windowType: WindowType = WindowType.TUKEY,
    val id: String = "FMCW-18-21kHz-40ms"
) {
    val safeGain: Float = gain.coerceIn(0.01f, 0.30f)
    val numSamples: Int = (sampleRateHz * (durationMs / 1000.0)).toInt()
    val sweepRate: Double = (endFreqHz.toDouble() - startFreqHz.toDouble()) / (durationMs / 1000.0)
    val bandwidthHz: Float = endFreqHz - startFreqHz
}

/**
 * Deterministic FMCW chirp generator.
 *
 * Produces reference templates and 16-bit PCM playback buffers for full-duplex acoustic sensing.
 */
class ChirpGenerator(val config: ChirpConfig = ChirpConfig()) {

    val numSamples: Int = config.numSamples

    // Precomputed instantaneous phase phi[n]
    val phaseRads: DoubleArray = DoubleArray(numSamples) { n ->
        val t = n.toDouble() / config.sampleRateHz.toDouble()
        2.0 * PI * (config.startFreqHz.toDouble() * t + 0.5 * config.sweepRate * t * t)
    }

    // Precomputed window weights
    val windowWeights: FloatArray = FloatArray(numSamples) { n ->
        when (config.windowType) {
            WindowType.TUKEY -> {
                val alpha = 0.15
                val nTukey = (alpha * (numSamples - 1) / 2.0).toInt()
                when {
                    n < nTukey -> 0.5f * (1.0 - cos(PI * n / nTukey)).toFloat()
                    n > (numSamples - 1 - nTukey) -> 0.5f * (1.0 - cos(PI * (numSamples - 1 - n) / nTukey)).toFloat()
                    else -> 1.0f
                }
            }
            WindowType.HANNING -> {
                0.5f * (1.0 - cos(2.0 * PI * n / (numSamples - 1).toDouble())).toFloat()
            }
            WindowType.RECTANGULAR -> 1.0f
        }
    }

    /**
     * Normalized float reference waveform s(t) in [-gain, +gain].
     */
    val referenceFloat: FloatArray = FloatArray(numSamples) { n ->
        (config.safeGain * windowWeights[n] * sin(phaseRads[n])).toFloat()
    }

    /**
     * In-phase reference cos(phi(t)).
     */
    val cosReference: FloatArray = FloatArray(numSamples) { n ->
        (windowWeights[n] * cos(phaseRads[n])).toFloat()
    }

    /**
     * Quadrature reference sin(phi(t)).
     */
    val sinReference: FloatArray = FloatArray(numSamples) { n ->
        (windowWeights[n] * sin(phaseRads[n])).toFloat()
    }

    /**
     * 16-bit PCM audio buffer of a single chirp.
     */
    fun generateSingleChirpPcm16(): ShortArray {
        val out = ShortArray(numSamples)
        val maxVal = 32767.0
        for (i in 0 until numSamples) {
            val clamped = referenceFloat[i].coerceIn(-1.0f, 1.0f)
            out[i] = (clamped * maxVal).toInt().toShort()
        }
        return out
    }

    /**
     * Generates a multi-chirp concatenated PCM buffer for seamless looped AudioTrack playback.
     *
     * @param repeatCount Number of back-to-back chirps in the looped buffer (default 5 = 200ms at 40ms/chirp).
     * @param silentGapMs Optional silent guard interval between chirps (default 0ms for continuous FMCW).
     */
    fun generateLoopedPlaybackPcm16(repeatCount: Int = 5, silentGapMs: Int = 0): ShortArray {
        val gapSamples = (config.sampleRateHz * (silentGapMs / 1000.0)).toInt()
        val totalLen = repeatCount * (numSamples + gapSamples)
        val buffer = ShortArray(totalLen)
        val single = generateSingleChirpPcm16()

        var cursor = 0
        for (r in 0 until repeatCount) {
            System.arraycopy(single, 0, buffer, cursor, numSamples)
            cursor += numSamples
            if (gapSamples > 0) {
                // Buffer is already zeroed
                cursor += gapSamples
            }
        }
        return buffer
    }
}
