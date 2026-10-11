package com.edgeppg.app.optical

/**
 * Diagnostic and feature result emitted by the Optical Challenge-Response engine.
 *
 * Implements architectural constraints from EdgePPG Architecture & Research Guide:
 * - Active closed-loop visual stimulus-and-measurement verification.
 * - Extracts actual RGB and luminance changes from tracked facial ROIs.
 * - Cross-correlates observed response with known stimulus sequence.
 * - Produces [opticalResponseScore] in [0.0, 1.0] for the frozen edgeppg-1.0 schema (index 10).
 * - Preserves intermediate diagnostics (timingErrorMs, sequenceScore, correlation, roiCoverage)
 *   for observability without altering the frozen 28-feature schema.
 */
data class OpticalChallengeResult(
    val sessionId: String,
    val isValid: Boolean,
    val opticalResponseScore: Float, // Normalized [0.0, 1.0] for frozen feature row (index 10)
    val timingErrorMs: Long,         // Estimated delay between stimulus transition and facial response
    val sequenceScore: Float,        // Multi-step transition consistency score [0.0, 1.0]
    val correlation: Float,          // Pearson correlation between expected and observed signal curves
    val roiCoverage: FloatArray,     // Per-ROI signal response amplitude: [Forehead, Left Cheek, Right Cheek]
    val responseMagnitude: Float,    // Measured luminance delta above baseline
    val qualityStatus: String,       // "VALID", "INSUFFICIENT_FRAMES", "POOR_COVERAGE", "UNSYNCHRONIZED", "STANDBY", "USER_CANCELLED"
    val reasonCodes: List<String>,   // Diagnostic audit codes
    val challengeVersion: String = "opt-1.0",
    val latencyUs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OpticalChallengeResult
        if (sessionId != other.sessionId) return false
        if (isValid != other.isValid) return false
        if (opticalResponseScore != other.opticalResponseScore) return false
        if (timingErrorMs != other.timingErrorMs) return false
        if (sequenceScore != other.sequenceScore) return false
        if (correlation != other.correlation) return false
        if (!roiCoverage.contentEquals(other.roiCoverage)) return false
        if (responseMagnitude != other.responseMagnitude) return false
        if (qualityStatus != other.qualityStatus) return false
        if (reasonCodes != other.reasonCodes) return false
        if (challengeVersion != other.challengeVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + isValid.hashCode()
        result = 31 * result + opticalResponseScore.hashCode()
        result = 31 * result + timingErrorMs.hashCode()
        result = 31 * result + sequenceScore.hashCode()
        result = 31 * result + correlation.hashCode()
        result = 31 * result + roiCoverage.contentHashCode()
        result = 31 * result + responseMagnitude.hashCode()
        result = 31 * result + qualityStatus.hashCode()
        result = 31 * result + reasonCodes.hashCode()
        result = 31 * result + challengeVersion.hashCode()
        return result
    }

    companion object {
        fun standby(): OpticalChallengeResult = OpticalChallengeResult(
            sessionId = "",
            isValid = false,
            opticalResponseScore = Float.NaN,
            timingErrorMs = 0L,
            sequenceScore = 0f,
            correlation = 0f,
            roiCoverage = FloatArray(3),
            responseMagnitude = 0f,
            qualityStatus = "STANDBY",
            reasonCodes = listOf("optical-standby")
        )

        fun inconclusive(
            sessionId: String = "",
            reason: String = "INCONCLUSIVE",
            reasons: List<String> = listOf("insufficient-quality")
        ): OpticalChallengeResult = OpticalChallengeResult(
            sessionId = sessionId,
            isValid = false,
            opticalResponseScore = Float.NaN,
            timingErrorMs = 0L,
            sequenceScore = 0f,
            correlation = 0f,
            roiCoverage = FloatArray(3),
            responseMagnitude = 0f,
            qualityStatus = reason,
            reasonCodes = reasons
        )
    }
}
