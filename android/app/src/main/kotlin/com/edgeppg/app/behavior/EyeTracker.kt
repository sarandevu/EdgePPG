package com.edgeppg.app.behavior

import com.edgeppg.app.challenge.GazeDirection
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Camera-based Eye Tracking, Gaze Estimation & Blink State Engine.
 *
 * Implements first-class eye and gaze tracking per EdgePPG Master Instructions:
 *  - Dual-eye anatomical tracking from Google ML Kit 468 3D landmarks
 *  - Real-time Eye Aspect Ratio (EAR) for involuntary and prompted blinks (Soukupová & Čech)
 *  - Camera-based gaze estimation (LEFT, RIGHT, UP, DOWN, CENTER) with confidence
 *  - Temporal smoothing (EMA alpha = 0.6) to eliminate frame-to-frame landmark jitter
 *  - Stale state detection (> 500 ms without valid mesh)
 *  - Safe fallback to UNKNOWN/CENTER under low signal or occlusion
 */
object EyeTracker {

    /** Eye Tracking & Gaze diagnostic snapshot for UI & challenge verification. */
    data class EyeSnapshot(
        val leftEar: Float,
        val rightEar: Float,
        val avgEar: Float,
        val isBlinkActive: Boolean,
        val blinkCount: Int,
        val rawDirection: GazeDirection,
        val smoothedDirection: GazeDirection,
        val xNorm: Float,
        val yNorm: Float,
        val confidence: Float,
        val isTrackingValid: Boolean,
        val isStale: Boolean,
        val timestampMs: Long,
    )

    private const val EAR_BLINK_THRESHOLD = 0.18f
    private const val CENTER_X_THRESHOLD = 0.10f
    private const val CENTER_Y_THRESHOLD = 0.08f
    private const val MIN_OFFSET = 0.02f
    private const val STALE_TIMEOUT_MS = 500L
    private const val SMOOTHING_ALPHA = 0.60f

    @Volatile private var smoothedX = 0f
    @Volatile private var smoothedY = 0f
    @Volatile private var lastUpdateMs = 0L
    @Volatile private var blinkCounter = 0
    @Volatile private var wasBlinking = false
    @Volatile private var lastSnapshot: EyeSnapshot? = null

    fun reset() {
        smoothedX = 0f
        smoothedY = 0f
        lastUpdateMs = 0L
        blinkCounter = 0
        wasBlinking = false
        lastSnapshot = null
    }

    private fun dist(p1: FloatArray, p2: FloatArray): Float {
        if (p1[0].isNaN() || p2[0].isNaN()) return Float.NaN
        val dx = p1[0] - p2[0]
        val dy = p1[1] - p2[1]
        val dz = p1[2] - p2[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun computeEyeEar(
        mesh: FloatArray,
        pOuter: Int, pInner: Int,
        pTop1: Int, pBottom1: Int,
        pTop2: Int, pBottom2: Int
    ): Float {
        val outer = MeshLandmarks.point(mesh, pOuter)
        val inner = MeshLandmarks.point(mesh, pInner)
        val top1 = MeshLandmarks.point(mesh, pTop1)
        val bot1 = MeshLandmarks.point(mesh, pBottom1)
        val top2 = MeshLandmarks.point(mesh, pTop2)
        val bot2 = MeshLandmarks.point(mesh, pBottom2)

        val horizontal = dist(outer, inner)
        if (horizontal.isNaN() || horizontal < 1.0f) return Float.NaN
        val vertical1 = dist(top1, bot1)
        val vertical2 = dist(top2, bot2)
        if (vertical1.isNaN() || vertical2.isNaN()) return Float.NaN

        return (vertical1 + vertical2) / (2f * horizontal)
    }

    /**
     * Update tracking state with the latest 468-point face mesh.
     * Thread-safe and non-blocking.
     */
    fun processMesh(meshPoints: FloatArray, nowMs: Long = System.currentTimeMillis()): EyeSnapshot {
        if (meshPoints.size < 400 * 3) {
            val isStale = (nowMs - lastUpdateMs) > STALE_TIMEOUT_MS
            val snap = EyeSnapshot(
                leftEar = Float.NaN,
                rightEar = Float.NaN,
                avgEar = Float.NaN,
                isBlinkActive = false,
                blinkCount = blinkCounter,
                rawDirection = GazeDirection.CENTER,
                smoothedDirection = GazeDirection.CENTER,
                xNorm = 0f,
                yNorm = 0f,
                confidence = 0f,
                isTrackingValid = false,
                isStale = isStale,
                timestampMs = nowMs,
            )
            lastSnapshot = snap
            return snap
        }

        // 1. Dual-eye EAR computation
        val rEar = computeEyeEar(
            meshPoints,
            MeshLandmarks.RIGHT_EYE_OUTER, MeshLandmarks.RIGHT_EYE_INNER,
            MeshLandmarks.RIGHT_EYE_TOP, MeshLandmarks.RIGHT_EYE_BOTTOM,
            MeshLandmarks.RIGHT_EYE_TOP_2, MeshLandmarks.RIGHT_EYE_BOTTOM_2
        )
        val lEar = computeEyeEar(
            meshPoints,
            MeshLandmarks.LEFT_EYE_OUTER, MeshLandmarks.LEFT_EYE_INNER,
            MeshLandmarks.LEFT_EYE_TOP, MeshLandmarks.LEFT_EYE_BOTTOM,
            MeshLandmarks.LEFT_EYE_TOP_2, MeshLandmarks.LEFT_EYE_BOTTOM_2
        )

        val avgEar = when {
            !rEar.isNaN() && !lEar.isNaN() -> (rEar + lEar) / 2f
            !rEar.isNaN() -> rEar
            !lEar.isNaN() -> lEar
            else -> Float.NaN
        }

        val isBlinkActive = !avgEar.isNaN() && avgEar < EAR_BLINK_THRESHOLD
        if (isBlinkActive && !wasBlinking) {
            blinkCounter++
        }
        wasBlinking = isBlinkActive

        // 2. Camera-based Gaze Estimation
        val rOuter = MeshLandmarks.point(meshPoints, MeshLandmarks.RIGHT_EYE_OUTER)
        val rInner = MeshLandmarks.point(meshPoints, MeshLandmarks.RIGHT_EYE_INNER)
        val rTop = MeshLandmarks.point(meshPoints, MeshLandmarks.RIGHT_EYE_TOP)
        val rBottom = MeshLandmarks.point(meshPoints, MeshLandmarks.RIGHT_EYE_BOTTOM)

        val lOuter = MeshLandmarks.point(meshPoints, MeshLandmarks.LEFT_EYE_OUTER)
        val lInner = MeshLandmarks.point(meshPoints, MeshLandmarks.LEFT_EYE_INNER)
        val noseBridge = MeshLandmarks.point(meshPoints, MeshLandmarks.NOSE_BRIDGE)

        var rawX = 0f
        var rawY = 0f
        var validGaze = false

        // Check refined iris landmark first
        val rIris = MeshLandmarks.point(meshPoints, MeshLandmarks.RIGHT_IRIS_CENTER)
        val hasIris = !rIris[0].isNaN() && !rOuter[0].isNaN() && !rInner[0].isNaN()

        val rWidth = abs(rInner[0] - rOuter[0])
        val rHeight = abs(rBottom[1] - rTop[1])

        if (hasIris && rWidth > 2f && rHeight > 2f) {
            rawX = ((rIris[0] - rOuter[0]) / rWidth).coerceIn(-1f, 2f) - 0.5f
            rawY = ((rIris[1] - rTop[1]) / rHeight).coerceIn(-1f, 2f) - 0.5f
            validGaze = true
        } else if (!noseBridge[0].isNaN() && !rInner[0].isNaN() && !lInner[0].isNaN() && rWidth > 1f) {
            // Geometrical midline triangulation
            val dR = abs(rInner[0] - noseBridge[0])
            val dL = abs(lInner[0] - noseBridge[0])
            val lWidth = abs(lInner[0] - lOuter[0])
            val avgWidth = (rWidth + lWidth) / 2f
            val hDiff = if (avgWidth > 1f) (dR - dL) / avgWidth else 0f

            val rOpen = if (rWidth > 1f) rHeight / rWidth else 0.3f
            val lHeight = abs(MeshLandmarks.point(meshPoints, MeshLandmarks.LEFT_EYE_BOTTOM)[1] -
                    MeshLandmarks.point(meshPoints, MeshLandmarks.LEFT_EYE_TOP)[1])
            val lOpen = if (lWidth > 1f) lHeight / lWidth else rOpen
            val avgOpen = (rOpen + lOpen) / 2f

            rawX = (hDiff * 0.85f).coerceIn(-1f, 1f)
            rawY = ((0.30f - avgOpen) * 2.0f).coerceIn(-1f, 1f)
            validGaze = true
        }

        // Apply EMA temporal smoothing
        if (validGaze) {
            smoothedX = SMOOTHING_ALPHA * rawX + (1f - SMOOTHING_ALPHA) * smoothedX
            smoothedY = SMOOTHING_ALPHA * rawY + (1f - SMOOTHING_ALPHA) * smoothedY
        }

        // Classify Raw Direction
        val rawDirection = classifyDirection(rawX, rawY)
        val smoothedDirection = classifyDirection(smoothedX, smoothedY)

        val conf = when (smoothedDirection) {
            GazeDirection.CENTER -> (1f - (abs(smoothedX) + abs(smoothedY))).coerceIn(0f, 1f)
            GazeDirection.LEFT, GazeDirection.RIGHT ->
                ((abs(smoothedX) - CENTER_X_THRESHOLD) / 0.5f).coerceIn(0f, 1f)
            GazeDirection.UP, GazeDirection.DOWN ->
                ((abs(smoothedY) - CENTER_Y_THRESHOLD) / 0.5f).coerceIn(0f, 1f)
        }

        lastUpdateMs = nowMs
        val snap = EyeSnapshot(
            leftEar = lEar,
            rightEar = rEar,
            avgEar = avgEar,
            isBlinkActive = isBlinkActive,
            blinkCount = blinkCounter,
            rawDirection = rawDirection,
            smoothedDirection = smoothedDirection,
            xNorm = smoothedX,
            yNorm = smoothedY,
            confidence = if (validGaze) conf else 0f,
            isTrackingValid = validGaze,
            isStale = false,
            timestampMs = nowMs,
        )
        lastSnapshot = snap
        return snap
    }

    private fun classifyDirection(x: Float, y: Float): GazeDirection {
        val ax = abs(x)
        val ay = abs(y)
        return when {
            ax < CENTER_X_THRESHOLD && ay < CENTER_Y_THRESHOLD -> GazeDirection.CENTER
            ay >= ax && ay >= CENTER_Y_THRESHOLD -> if (y > 0) GazeDirection.DOWN else GazeDirection.UP
            ax >= CENTER_X_THRESHOLD -> if (x < 0) GazeDirection.RIGHT else GazeDirection.LEFT
            ay >= CENTER_Y_THRESHOLD -> if (y > 0) GazeDirection.DOWN else GazeDirection.UP
            else -> GazeDirection.CENTER
        }
    }

    fun getLatestSnapshot(): EyeSnapshot? = lastSnapshot
}
