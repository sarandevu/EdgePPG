package com.edgeppg.app.behavior

import kotlin.math.sqrt

/**
 * Stage 6 — Fine-grained facial action tracking using Google ML Kit 468 3D landmarks.
 *
 * Implements:
 *  - Eye Aspect Ratio (EAR) for involuntary and challenged blinks (Soukupová & Čech, 2016)
 *  - Mouth Aspect Ratio (MAR) for mouth opening, smiling, and lip actions
 *  - High-precision geometric action classification (0 ms extra model overhead)
 */
object FaceActionTracker {

    data class ActionSnapshot(
        val leftEar: Float,
        val rightEar: Float,
        val avgEar: Float,
        val isBlinking: Boolean,
        val mar: Float,
        val isMouthOpen: Boolean,
        val isSmiling: Boolean,
    )

    private fun dist(p1: FloatArray, p2: FloatArray): Float {
        if (p1[0].isNaN() || p2[0].isNaN()) return Float.NaN
        val dx = p1[0] - p2[0]
        val dy = p1[1] - p2[1]
        val dz = p1[2] - p2[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Compute Eye Aspect Ratio (EAR) for canonical eye landmarks.
     */
    fun computeEar(
        mesh: FloatArray,
        pOuter: Int, pInner: Int,
        pTop1: Int, pBottom1: Int,
        pTop2: Int, pBottom2: Int,
    ): Float {
        val outer = MeshLandmarks.point(mesh, pOuter)
        val inner = MeshLandmarks.point(mesh, pInner)
        val top1 = MeshLandmarks.point(mesh, pTop1)
        val bot1 = MeshLandmarks.point(mesh, pBottom1)
        val top2 = MeshLandmarks.point(mesh, pTop2)
        val bot2 = MeshLandmarks.point(mesh, pBottom2)

        val horizontal = dist(outer, inner)
        if (horizontal.isNaN() || horizontal <= 0.001f) return Float.NaN
        val vertical1 = dist(top1, bot1)
        val vertical2 = dist(top2, bot2)
        if (vertical1.isNaN() || vertical2.isNaN()) return Float.NaN

        return (vertical1 + vertical2) / (2f * horizontal)
    }

    /**
     * Compute Mouth Aspect Ratio (MAR).
     */
    fun computeMar(mesh: FloatArray): Float {
        val leftCorner = MeshLandmarks.point(mesh, MeshLandmarks.LEFT_MOUTH_CORNER)
        val rightCorner = MeshLandmarks.point(mesh, MeshLandmarks.RIGHT_MOUTH_CORNER)
        val upperLip = MeshLandmarks.point(mesh, MeshLandmarks.UPPER_LIP_CENTER)
        val lowerLip = MeshLandmarks.point(mesh, MeshLandmarks.LOWER_LIP_CENTER)

        val horizontal = dist(leftCorner, rightCorner)
        if (horizontal.isNaN() || horizontal <= 0.001f) return Float.NaN
        val vertical = dist(upperLip, lowerLip)
        if (vertical.isNaN()) return Float.NaN

        return vertical / horizontal
    }

    /**
     * Analyze current 468-point mesh snapshot.
     */
    fun analyze(mesh: FloatArray): ActionSnapshot? {
        if (mesh.size < 400 * 3) return null

        val rEar = computeEar(
            mesh,
            MeshLandmarks.RIGHT_EYE_OUTER, MeshLandmarks.RIGHT_EYE_INNER,
            MeshLandmarks.RIGHT_EYE_TOP, MeshLandmarks.RIGHT_EYE_BOTTOM,
            MeshLandmarks.RIGHT_EYE_TOP_2, MeshLandmarks.RIGHT_EYE_BOTTOM_2,
        )
        val lEar = computeEar(
            mesh,
            MeshLandmarks.LEFT_EYE_OUTER, MeshLandmarks.LEFT_EYE_INNER,
            MeshLandmarks.LEFT_EYE_TOP, MeshLandmarks.LEFT_EYE_BOTTOM,
            MeshLandmarks.LEFT_EYE_TOP_2, MeshLandmarks.LEFT_EYE_BOTTOM_2,
        )
        val avgEar = when {
            !rEar.isNaN() && !lEar.isNaN() -> (rEar + lEar) / 2f
            !rEar.isNaN() -> rEar
            !lEar.isNaN() -> lEar
            else -> Float.NaN
        }
        val mar = computeMar(mesh)

        val isBlinking = !avgEar.isNaN() && avgEar < 0.18f
        val isMouthOpen = !mar.isNaN() && mar > 0.45f
        val isSmiling = !mar.isNaN() && mar in 0.22f..0.40f

        return ActionSnapshot(
            leftEar = lEar,
            rightEar = rEar,
            avgEar = avgEar,
            isBlinking = isBlinking,
            mar = mar,
            isMouthOpen = isMouthOpen,
            isSmiling = isSmiling,
        )
    }
}
