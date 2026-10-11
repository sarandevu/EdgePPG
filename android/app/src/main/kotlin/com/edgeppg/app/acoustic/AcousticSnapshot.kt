package com.edgeppg.app.acoustic

/**
 * Clean diagnostic snapshot emitted by the FMCW Acoustic Sensing Engine.
 *
 * Implements the telemetry contract defined in the EdgePPG Acoustic Sensing Guide (§8 & §10):
 * - Ranging measurements and micro-motion statistics are strictly observability metrics.
 * - This structure is decoupled from the frozen 28-feature edgeppg-1.0 core schema.
 * - Explicit quality and failure states are maintained; never fabricates values.
 */
data class AcousticSnapshot(
    val isValid: Boolean,
    val sampleRateHz: Int,
    val chirpConfigId: String,
    val targetRangeCm: Float?,
    val motionScore: Float,
    val snrDb: Float,
    val isClipping: Boolean,
    val qualityStatus: String, // "MEASURED", "UNAVAILABLE", "CLIPPING", "LOW_SNR", "PERMISSION_DENIED", "STOPPED"
    val peakBin: Int = 0,
    val latencyMs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
) {
    companion object {
        fun unavailable(
            status: String = "UNAVAILABLE",
            sampleRateHz: Int = 48000,
            chirpConfigId: String = "FMCW-18-21kHz-40ms"
        ): AcousticSnapshot = AcousticSnapshot(
            isValid = false,
            sampleRateHz = sampleRateHz,
            chirpConfigId = chirpConfigId,
            targetRangeCm = null,
            motionScore = 0f,
            snrDb = 0f,
            isClipping = false,
            qualityStatus = status,
            peakBin = 0,
            latencyMs = 0L
        )

        fun stopped(): AcousticSnapshot = unavailable(status = "STOPPED")
    }
}
