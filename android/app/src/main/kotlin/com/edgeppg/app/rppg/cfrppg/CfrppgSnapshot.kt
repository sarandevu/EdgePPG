package com.edgeppg.app.rppg.cfrppg

/**
 * Diagnostic telemetry snapshot emitted by the CFrPPG (Remote Photoplethysmography
 * Correspondence Feature) Engine.
 *
 * Paper Reference:
 * Si-Qi Liu, Xiangyuan Lan, Pong C. Yuen.
 * "Remote Photoplethysmography Correspondence Feature for 3D Mask Face Presentation Attack Detection."
 * European Conference on Computer Vision (ECCV), 2018.
 *
 * Implements strict research & architectural safety constraints:
 * - Observability & diagnostics telemetry ONLY.
 * - Decoupled from the frozen 28-feature schema (edgeppg-1.0).
 * - Does not override deterministic security gates in DecisionEngine.
 * - Explicit validity and quality statuses; never fabricates values or zero-fills.
 */
data class CfrppgSnapshot(
    val isValid: Boolean,
    val meanCorrespondence: Float,
    val minCorrespondence: Float,
    val varianceCorrespondence: Float,
    val regionScores: FloatArray, // [c_forehead, c_left_cheek, c_right_cheek]
    val consensusHrBpm: Float,
    val consensusSnrDb: Float,
    val maskSpoofRisk: String, // "LOW", "MEDIUM", "HIGH_SUSPECTED_MASK"
    val qualityStatus: String, // "VALID", "INSUFFICIENT_SAMPLES", "MOTION_CONTAMINATED", "DIVERGENT_OR_NOISE", "STANDBY"
    val latencyUs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CfrppgSnapshot
        if (isValid != other.isValid) return false
        if (meanCorrespondence != other.meanCorrespondence) return false
        if (minCorrespondence != other.minCorrespondence) return false
        if (varianceCorrespondence != other.varianceCorrespondence) return false
        if (!regionScores.contentEquals(other.regionScores)) return false
        if (consensusHrBpm != other.consensusHrBpm) return false
        if (consensusSnrDb != other.consensusSnrDb) return false
        if (maskSpoofRisk != other.maskSpoofRisk) return false
        if (qualityStatus != other.qualityStatus) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isValid.hashCode()
        result = 31 * result + meanCorrespondence.hashCode()
        result = 31 * result + minCorrespondence.hashCode()
        result = 31 * result + varianceCorrespondence.hashCode()
        result = 31 * result + regionScores.contentHashCode()
        result = 31 * result + consensusHrBpm.hashCode()
        result = 31 * result + consensusSnrDb.hashCode()
        result = 31 * result + maskSpoofRisk.hashCode()
        result = 31 * result + qualityStatus.hashCode()
        return result
    }

    companion object {
        fun standby(): CfrppgSnapshot = CfrppgSnapshot(
            isValid = false,
            meanCorrespondence = 0f,
            minCorrespondence = 0f,
            varianceCorrespondence = 0f,
            regionScores = FloatArray(3),
            consensusHrBpm = 0f,
            consensusSnrDb = 0f,
            maskSpoofRisk = "STANDBY",
            qualityStatus = "STANDBY"
        )

        fun unavailable(status: String = "INSUFFICIENT_SAMPLES"): CfrppgSnapshot = CfrppgSnapshot(
            isValid = false,
            meanCorrespondence = 0f,
            minCorrespondence = 0f,
            varianceCorrespondence = 0f,
            regionScores = FloatArray(3),
            consensusHrBpm = 0f,
            consensusSnrDb = 0f,
            maskSpoofRisk = "INCONCLUSIVE",
            qualityStatus = status
        )
    }
}
