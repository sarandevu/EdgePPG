package com.edgeppg.app.behavior

import com.edgeppg.app.challenge.BehavioralObservation
import com.edgeppg.app.challenge.BehavioralOutcome
import com.edgeppg.app.challenge.ChallengeSpec
import com.edgeppg.app.challenge.GazeDirection
import com.edgeppg.app.challenge.HandGesture
import com.edgeppg.app.challenge.HeadOrientation
import kotlin.math.abs

/**
 * Stage 6 — Behavioral observation matcher.
 *
 * Compares a [ChallengeSpec] against the most recent [GazeEstimate],
 * [HeadPose], and (optional) hand observation, and produces a
 * [BehavioralObservation]. The actual `kotlinx.coroutines`-driven
 * runner that schedules the prompts and waits for timeouts lives in
 * the on-device Activity (Stage 15). This class is the **pure
 * matcher** that decides match / wrong / timeout / contaminated.
 *
 * Output feeds the per-modality feature aggregator (Stage 9 row
 * assembler): `gaze_accuracy`, `head_accuracy`, `hand_accuracy`,
 * `challenge_timing_error`, etc.
 */
object BehavioralRunner {

    /**
     * Compute one observation. Pass null for any observation that
     * isn't available yet (face lost, estimator returned NaN, etc.).
     *
     * @param requested the challenge spec.
     * @param startedAtMs / [nowMs] wall-clock timestamps for latency.
     * @param gaze the latest [GazeEstimate], or null.
     * @param pose the latest [HeadPose], or null.
     * @param gesture the latest observed [HandGesture], or null.
     * @param contaminationFlag if true, the observation is recorded as
     *   contaminated — typically because a large RGB step happened
     *   during the response window.
     */
    fun observe(
        requested: ChallengeSpec,
        startedAtMs: Long,
        nowMs: Long,
        gaze: GazeEstimate? = null,
        pose: HeadPose? = null,
        gesture: HandGesture? = null,
        handedness: String? = null,
        contaminationFlag: Boolean = false,
    ): BehavioralObservation {
        val latencyMs = abs(nowMs - startedAtMs)
        val timedOut = nowMs - startedAtMs > requested.timeoutMs

        if (contaminationFlag) {
            return BehavioralObservation(requested,
                BehavioralOutcome.CONTAMINATED, latencyMs, success = false)
        }

        val matched = checkMatch(requested, gaze, pose, gesture, handedness)

        val outcome = when {
            matched == true -> BehavioralOutcome.MATCH
            matched == false -> BehavioralOutcome.WRONG
            timedOut -> BehavioralOutcome.TIMEOUT
            else -> BehavioralOutcome.TIMEOUT
        }
        val success = outcome == BehavioralOutcome.MATCH
        return BehavioralObservation(requested, outcome, latencyMs, success)
    }

    /**
     * Test whether an action is currently matching, opposite/wrong, or still in progress (null).
     */
    fun checkMatch(
        requested: ChallengeSpec,
        gaze: GazeEstimate? = null,
        pose: HeadPose? = null,
        gesture: HandGesture? = null,
        handedness: String? = null,
    ): Boolean? {
        return when (requested) {
            is ChallengeSpec.Gaze -> matchGaze(requested.direction, gaze, pose)
            is ChallengeSpec.Head -> matchHead(requested.orientation, pose, gaze)
            is ChallengeSpec.Hand -> matchHand(requested.gesture, gesture, handedness)
            is ChallengeSpec.RemainStill -> matchStill(gaze, pose, gesture)
            is ChallengeSpec.OpticalFlash -> null
        }
    }

    private fun matchGaze(
        requested: GazeDirection,
        gaze: GazeEstimate?,
        pose: HeadPose? = null,
    ): Boolean? {
        // Direct gaze match
        if (gaze != null && gaze.eyeAvailable && gaze.direction == requested) return true

        // Natural ergonomic co-movement: when asked to look down/up, natural head pitch assists
        if (requested == GazeDirection.DOWN) {
            if (pose != null && pose.available && pose.pitchDeg < -3.5f) return true
        } else if (requested == GazeDirection.UP) {
            if (pose != null && pose.available && pose.pitchDeg > 3.5f) return true
        }

        if (gaze == null || !gaze.eyeAvailable) return null
        if (gaze.direction == GazeDirection.CENTER) {
            return if (requested == GazeDirection.CENTER) true else null
        }

        // Only return false if opposite action is definitively detected
        val isOppositeGaze = when (requested) {
            GazeDirection.LEFT -> gaze.direction == GazeDirection.RIGHT
            GazeDirection.RIGHT -> gaze.direction == GazeDirection.LEFT
            GazeDirection.UP -> gaze.direction == GazeDirection.DOWN
            GazeDirection.DOWN -> gaze.direction == GazeDirection.UP
            GazeDirection.CENTER -> false
        }
        return if (isOppositeGaze) false else null
    }

    private fun matchHead(
        requested: HeadOrientation,
        pose: HeadPose?,
        gaze: GazeEstimate? = null,
    ): Boolean? {
        if (pose == null || !pose.available) {
            // Fallback: gaze down/up satisfies head pitch when pose is transiently unavailable
            if (requested == HeadOrientation.TURN_DOWN && gaze?.direction == GazeDirection.DOWN) return true
            if (requested == HeadOrientation.TURN_UP && gaze?.direction == GazeDirection.UP) return true
            return null
        }
        return when (requested) {
            HeadOrientation.TURN_LEFT -> when {
                pose.yawDeg > 5.5f -> true
                pose.yawDeg < -6.5f -> false // Turned right instead
                else -> null // Frontal/neutral, in progress
            }
            HeadOrientation.TURN_RIGHT -> when {
                pose.yawDeg < -5.5f -> true
                pose.yawDeg > 6.5f -> false // Turned left instead
                else -> null
            }
            HeadOrientation.TURN_UP -> when {
                pose.pitchDeg > 3.5f || gaze?.direction == GazeDirection.UP -> true
                pose.pitchDeg < -5.5f -> false // Tilted down instead
                else -> null
            }
            HeadOrientation.TURN_DOWN -> when {
                pose.pitchDeg < -3.5f || gaze?.direction == GazeDirection.DOWN -> true
                pose.pitchDeg > 5.5f -> false // Tilted up instead
                else -> null
            }
            HeadOrientation.NOD -> when {
                abs(pose.pitchDeg) > 4.5f || abs(pose.rollDeg) > 6.0f -> true
                abs(pose.yawDeg) > 10.0f -> false
                else -> null
            }
            HeadOrientation.SHAKE_NO -> when {
                abs(pose.yawDeg) > 5.5f || abs(pose.rollDeg) > 6.0f -> true
                abs(pose.pitchDeg) > 8.0f -> false
                else -> null
            }
        }
    }

    private fun matchHand(
        requested: HandGesture,
        observed: HandGesture?,
        handedness: String? = null,
    ): Boolean? {
        if (observed == null) return null // No hand gesture recognized yet -> keep waiting
        return when (requested) {
            HandGesture.RAISE_RIGHT -> when {
                observed == HandGesture.RAISE_RIGHT -> true
                observed == HandGesture.OPEN_PALM && (handedness == "Right" || handedness == null || handedness == "Center") -> true
                observed == HandGesture.RAISE_LEFT || (observed == HandGesture.OPEN_PALM && handedness == "Left") -> false
                else -> null
            }
            HandGesture.RAISE_LEFT -> when {
                observed == HandGesture.RAISE_LEFT -> true
                observed == HandGesture.OPEN_PALM && (handedness == "Left" || handedness == null || handedness == "Center") -> true
                observed == HandGesture.RAISE_RIGHT || (observed == HandGesture.OPEN_PALM && handedness == "Right") -> false
                else -> null
            }
            HandGesture.OPEN_PALM -> when {
                observed == HandGesture.OPEN_PALM || observed == HandGesture.RAISE_RIGHT || observed == HandGesture.RAISE_LEFT -> true
                observed == HandGesture.THUMBS_UP || observed == HandGesture.POINT -> false
                else -> null
            }
            HandGesture.THUMBS_UP -> when {
                observed == HandGesture.THUMBS_UP -> true
                // Hand in view / raising hand towards camera: keep waiting, do NOT penalize as wrong action
                observed == HandGesture.RAISE_RIGHT || observed == HandGesture.RAISE_LEFT -> null
                observed == HandGesture.POINT || observed == HandGesture.OPEN_PALM -> false
                else -> null
            }
            HandGesture.POINT -> when {
                observed == HandGesture.POINT -> true
                // Hand in view / raising hand / presenting palm while curling fingers: keep waiting
                observed == HandGesture.RAISE_RIGHT || observed == HandGesture.RAISE_LEFT || observed == HandGesture.OPEN_PALM -> null
                observed == HandGesture.THUMBS_UP -> false
                else -> null
            }
        }
    }

    /**
     * RemainStill succeeds when no detectable motion / gesture happened
     * during the window. Without the gesture observation we treat it as
     * MATCH (the still hold wasn't broken). The contamination flag is
     * checked separately above — if it's set, this challenge fails.
     */
    private fun matchStill(
        gaze: GazeEstimate?,
        pose: HeadPose?,
        gesture: HandGesture?,
    ): Boolean {
        if (gesture != null) return false  // a hand gesture is a motion event
        // Tiny head / gaze shifts are fine; large ones aren't.
        if (pose != null && pose.available &&
            (abs(pose.yawDeg) > 18f || abs(pose.pitchDeg) > 12f)
        ) return false
        if (gaze != null && gaze.direction != GazeDirection.CENTER) return false
        return true
    }
}