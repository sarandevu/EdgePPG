package com.edgeppg.app.behavior

import com.edgeppg.app.challenge.HeadOrientation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Stage 6 — Geometric head-pose solver.
 *
 * Per `TechStack §11`: "landmark-based geometry" is the documented first
 * choice. We avoid a full `solvePnP` (which would require OpenCV) and
 * instead estimate yaw / pitch / roll from a small set of facial
 * landmarks whose relative positions change in known ways with head
 * rotation:
 *
 *  - Yaw (turn left / right) is the asymmetry of the cheek / face-oval
 *    landmarks in image-X. A frontal face has roughly equal left/right
 *    widths; a turned face has one side compressed.
 *  - Pitch (look up / down) is the relative Y of the nose tip vs the
 *    forehead-chin midpoint. A pitched-up face has a higher nose tip;
 *    a pitched-down face has a lower one.
 *  - Roll (head tilt) is the slope of the eye-line. The two eye-centre
 *    Xs and Ys give an angle.
 *
 * Thresholds (in degrees) are starting values calibrated by geometry
 * intuition. They are explicitly labeled as such and will be tuned
 * once a real validation set exists (Stage 10 calibration).
 */
object HeadPoseSolver {

    /** Yaw in degrees above which we report TURN_LEFT / TURN_RIGHT. */
    private const val YAW_THRESHOLD_DEG = 5.5f

    /** Pitch in degrees above which we report TURN_UP / TURN_DOWN. */
    private const val PITCH_THRESHOLD_DEG = 4.0f

    /** Roll in degrees above which we report NOD / SHAKE_NO. */
    private const val ROLL_THRESHOLD_DEG = 6.0f

    fun estimate(meshPoints: FloatArray): HeadPose {
        val nose = MeshLandmarks.point(meshPoints, MeshLandmarks.NOSE_TIP)
        val forehead = MeshLandmarks.point(meshPoints, MeshLandmarks.FOREHEAD_CENTER)
        val chin = MeshLandmarks.point(meshPoints, MeshLandmarks.CHIN)
        val leftEyeOuter = MeshLandmarks.point(meshPoints, MeshLandmarks.LEFT_EYE_OUTER)
        val rightEyeOuter = MeshLandmarks.point(meshPoints, MeshLandmarks.RIGHT_EYE_OUTER)
        val leftMouth = MeshLandmarks.point(meshPoints, MeshLandmarks.LEFT_MOUTH_CORNER)
        val rightMouth = MeshLandmarks.point(meshPoints, MeshLandmarks.RIGHT_MOUTH_CORNER)

        if (nose.any { it.isNaN() } || forehead.any { it.isNaN() } ||
            chin.any { it.isNaN() } || leftEyeOuter.any { it.isNaN() } ||
            rightEyeOuter.any { it.isNaN() } || leftMouth.any { it.isNaN() } ||
            rightMouth.any { it.isNaN() }
        ) {
            return HeadPose(0f, 0f, 0f, available = false)
        }

        // Yaw: distance from nose tip to left mouth corner vs right mouth corner.
        val dL = hypot(nose[0] - leftMouth[0], nose[1] - leftMouth[1])
        val dR = hypot(nose[0] - rightMouth[0], nose[1] - rightMouth[1])
        val yawRad = atan2(dR - dL, dR + dL)  // signed, +ve when turning LEFT, -ve when turning RIGHT
        val yawDeg = (yawRad * 180.0 / PI).toFloat()

        // Pitch: vertical position of the nose tip relative to forehead-chin span.
        // On a neutral human face, the nose tip is naturally ~5% below midpoint.
        val midY = 0.5f * (forehead[1] + chin[1])
        val spanY = (chin[1] - forehead[1]).coerceAtLeast(1f)
        val noseFrac = ((nose[1] - midY) / spanY) - 0.05f
        val pitchDeg = noseFrac * -70f  // +ve = TURN_UP, −ve = TURN_DOWN

        val dx = leftEyeOuter[0] - rightEyeOuter[0]
        val dy = leftEyeOuter[1] - rightEyeOuter[1]
        val rollRad = atan2(dy, dx)
        val rollDeg = (rollRad * 180.0 / PI).toFloat()

        return HeadPose(yawDeg, pitchDeg, rollDeg, available = true)
    }

    /**
     * Map a [HeadPose] to the closest [HeadOrientation] challenge
     * spec, given a "primary" axis (yaw, pitch, or roll) that the
     * challenge engine expects. The behavioral runner picks the axis
     * per challenge.
     */
    fun classify(pose: HeadPose, primary: HeadAxis): HeadOrientation {
        if (!pose.available) return HeadOrientation.TURN_UP // default
        return when (primary) {
            HeadAxis.YAW -> when {
                pose.yawDeg > YAW_THRESHOLD_DEG -> HeadOrientation.TURN_LEFT
                pose.yawDeg < -YAW_THRESHOLD_DEG -> HeadOrientation.TURN_RIGHT
                else -> HeadOrientation.NOD  // frontal → no turn
            }
            HeadAxis.PITCH -> when {
                pose.pitchDeg > PITCH_THRESHOLD_DEG -> HeadOrientation.TURN_UP
                pose.pitchDeg < -PITCH_THRESHOLD_DEG -> HeadOrientation.TURN_DOWN
                else -> HeadOrientation.NOD
            }
            HeadAxis.ROLL -> when {
                pose.rollDeg > ROLL_THRESHOLD_DEG -> HeadOrientation.SHAKE_NO
                pose.rollDeg < -ROLL_THRESHOLD_DEG -> HeadOrientation.NOD
                else -> HeadOrientation.NOD
            }
        }
    }
}

enum class HeadAxis { YAW, PITCH, ROLL }

/** Three-axis head pose in degrees; positive yaw = head turned left
 *  (subject's left), positive pitch = head tilted up. */
data class HeadPose(
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val available: Boolean,
)
