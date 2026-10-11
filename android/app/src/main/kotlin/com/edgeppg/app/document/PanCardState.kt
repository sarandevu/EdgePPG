package com.edgeppg.app.document

import android.graphics.PointF

/**
 * KYC Document Lane — PAN Card Data Models & State Machine.
 *
 * Implements strict separation between biometric PAD and identity documents:
 *  - Must NOT enter the frozen 28-feature biometric vector
 *  - Distinguishes tracking, OCR parsing, and format validation states
 *  - Enforces privacy masking by default (ABCDE****F)
 *  - Formal verification disclaimer: offline syntax validation only
 */

enum class CardTrackingStatus {
    IDLE,
    SEARCHING,
    DETECTED,
    TRACKING_STABLE,
    OCR_PARTIAL,
    FORMAT_CHECK_PASSED,
    USER_CONFIRMED,
    VERIFICATION_UNAVAILABLE;

    val isStable: Boolean
        get() = this == TRACKING_STABLE || this == OCR_PARTIAL || this == FORMAT_CHECK_PASSED || this == USER_CONFIRMED
}

/** Normalized quadrilateral card boundary [0, 1]. */
data class CardQuad(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF,
    val aspectRatio: Float,
    val area: Float,
    val jitterMetric: Float,
)

/** Masked PAN Document result record. Full PAN is NEVER retained or logged. */
data class PanDocumentRecord(
    val status: CardTrackingStatus,
    val maskedPan: String,
    val maskedName: String,
    val maskedDob: String,
    val entityType: String,
    val consecutiveStableFrames: Int,
    val trackingConfidence: Float,
    val ocrConfidence: Float,
    val disclaimer: String = "Offline syntax check only. Government database verification unavailable.",
    val timestampMs: Long = System.currentTimeMillis(),
) {
    companion object {
        fun empty(): PanDocumentRecord = PanDocumentRecord(
            status = CardTrackingStatus.SEARCHING,
            maskedPan = "NONE",
            maskedName = "NONE",
            maskedDob = "NONE",
            entityType = "UNKNOWN",
            consecutiveStableFrames = 0,
            trackingConfidence = 0f,
            ocrConfidence = 0f,
        )

        /**
         * Masks an Indian PAN number (e.g. ABCDE1234F -> ABCDE****F).
         * Replaces all 4 numeric digits with asterisks.
         */
        fun maskPan(pan: String): String {
            val clean = pan.trim().uppercase()
            val regex = Regex("^([A-Z]{5})([0-9]{4})([A-Z])$")
            val match = regex.find(clean) ?: return "INVALID_FORMAT"
            val (prefix, _, suffix) = match.destructured
            return "${prefix}****$suffix"
        }

        /**
         * Masks an applicant name for privacy (e.g. NAREN KUMAR -> N***N K***R).
         */
        fun maskName(name: String): String {
            val words = name.trim().split("\\s+".toRegex())
            return words.joinToString(" ") { word ->
                if (word.length <= 2) word
                else "${word.first()}${"*".repeat((word.length - 2).coerceAtLeast(1))}${word.last()}"
            }
        }
    }
}
