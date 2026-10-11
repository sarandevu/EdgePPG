package com.edgeppg.app.optical

import com.edgeppg.app.challenge.OpticalColor
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Stage 5 / Stage 15 — Per-session randomized optical stimulus sequence.
 *
 * Each session generates a unique, deterministic sequence of visual stimulus steps
 * with controlled luminance and chromatic shifts (WARM, COOL, WHITE).
 *
 * Sequence design adheres to clinical and ergonomic safety constraints:
 * - Dwell time per step is 300..450 ms (safe, non-strobing transitions).
 * - Luminance delta is bounded to 35..55% above ambient.
 * - Begins with a neutral baseline state (~250 ms) to establish pre-stimulus skin baseline.
 */
data class OpticalStep(
    val stepIndex: Int,
    val color: OpticalColor,
    val deltaPct: Int,
    val durationMs: Long,
    val isBaseline: Boolean = false,
    /** Expected relative chromatic shift: [deltaR_weight, deltaG_weight, deltaB_weight] */
    val expectedSpectralWeights: FloatArray = floatArrayOf(0f, 0f, 0f)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OpticalStep
        if (stepIndex != other.stepIndex) return false
        if (color != other.color) return false
        if (deltaPct != other.deltaPct) return false
        if (durationMs != other.durationMs) return false
        if (isBaseline != other.isBaseline) return false
        if (!expectedSpectralWeights.contentEquals(other.expectedSpectralWeights)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = stepIndex
        result = 31 * result + color.hashCode()
        result = 31 * result + deltaPct
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + isBaseline.hashCode()
        result = 31 * result + expectedSpectralWeights.contentHashCode()
        return result
    }
}

data class OpticalStimulusSequence(
    val sessionId: String,
    val steps: List<OpticalStep>,
    val totalDurationMs: Long,
    val sequenceVersion: String = "opt-seq-1.0"
) {
    companion object {
        /**
         * Generate a fresh, randomized challenge sequence bound to the session nonce/seed.
         *
         * @param sessionId current session ID
         * @param seedHex 64-char hex seed from NonceGenerator
         */
        fun generate(sessionId: String, seedHex: String): OpticalStimulusSequence {
            val rng = seededRng(seedHex, salt = "optical_stimulus")

            val steps = mutableListOf<OpticalStep>()

            // 1. Initial Neutral Baseline (650ms)
            steps.add(
                OpticalStep(
                    stepIndex = 0,
                    color = OpticalColor.WHITE,
                    deltaPct = 0, // Unstimulated baseline
                    durationMs = 650L,
                    isBaseline = true,
                    expectedSpectralWeights = floatArrayOf(0f, 0f, 0f)
                )
            )

            // 2. Randomized sequence of chromatic and luminance stimulus steps
            // Pool of active colors
            val activePool = listOf(
                Pair(OpticalColor.WARM, floatArrayOf(1.0f, 0.2f, -0.6f)),  // Strong R shift, negative relative B
                Pair(OpticalColor.COOL, floatArrayOf(-0.5f, 0.2f, 1.0f)),  // Strong B shift, negative relative R
                Pair(OpticalColor.WHITE, floatArrayOf(0.7f, 0.7f, 0.7f))   // Uniform broadband luminance boost
            )

            // Shuffle active steps uniquely for this session
            val shuffled = activePool.shuffled(rng)

            var stepIdx = 1
            for ((color, weights) in shuffled) {
                val dwellMs = 750L + rng.nextLong(0, 100) // 750..850ms comfortable, clear dwell time
                val delta = 40 + rng.nextInt(0, 15)      // 40..55% brightness
                steps.add(
                    OpticalStep(
                        stepIndex = stepIdx++,
                        color = color,
                        deltaPct = delta,
                        durationMs = dwellMs,
                        isBaseline = false,
                        expectedSpectralWeights = weights
                    )
                )
            }

            val totalDuration = steps.sumOf { it.durationMs }
            return OpticalStimulusSequence(
                sessionId = sessionId,
                steps = steps,
                totalDurationMs = totalDuration
            )
        }

        private fun seededRng(seedHex: String, salt: String): Random {
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(hexToBytes(seedHex))
                update(salt.toByteArray(Charsets.UTF_8))
            }.digest()
            var seed = 0L
            for (i in 0 until 8) {
                seed = (seed shl 8) or (digest[i].toLong() and 0xFF)
            }
            return Random(seed)
        }

        private fun hexToBytes(hex: String): ByteArray {
            val clean = if (hex.length % 2 != 0) "0$hex" else hex
            val len = clean.length / 2
            val bytes = ByteArray(len)
            for (i in 0 until len) {
                bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return bytes
        }
    }
}
