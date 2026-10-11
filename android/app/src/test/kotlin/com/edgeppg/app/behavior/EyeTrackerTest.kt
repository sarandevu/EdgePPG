package com.edgeppg.app.behavior

import com.edgeppg.app.challenge.GazeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EyeTrackerTest {

    @Before
    fun setUp() {
        EyeTracker.reset()
    }

    @Test
    fun testEmptyMeshReturnsInvalidAndStale() {
        val snap = EyeTracker.processMesh(FloatArray(0), nowMs = 1000L)
        assertFalse("Empty mesh should not be valid", snap.isTrackingValid)
        assertEquals(GazeDirection.CENTER, snap.smoothedDirection)
        assertEquals(0f, snap.confidence, 0.001f)
    }

    @Test
    fun testSyntheticNeutralGazeEvaluatesCenter() {
        // Create 468 points (1404 floats)
        val mesh = FloatArray(478 * 3) { 0f }

        // Setup Right Eye (33 outer, 133 inner, 159 top, 145 bottom, 468 iris)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_OUTER, 100f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_INNER, 150f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_TOP, 125f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_BOTTOM, 125f, 210f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_TOP_2, 125f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_BOTTOM_2, 125f, 210f, 0f)

        // Setup Left Eye (263 outer, 362 inner, 386 top, 374 bottom, 473 iris)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_OUTER, 250f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_INNER, 200f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_TOP, 225f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_BOTTOM, 225f, 210f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_TOP_2, 225f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_BOTTOM_2, 225f, 210f, 0f)

        // Center iris (offset 0.5)
        setPoint(mesh, MeshLandmarks.RIGHT_IRIS_CENTER, 125f, 200f, 0f)

        val snap = EyeTracker.processMesh(mesh, nowMs = 1000L)
        assertTrue("Tracking should be valid with complete eye points", snap.isTrackingValid)
        assertEquals(GazeDirection.CENTER, snap.smoothedDirection)
        assertFalse("Eyes are open", snap.isBlinkActive)
    }

    @Test
    fun testBlinkDetectionWhenEarDrops() {
        val mesh = FloatArray(478 * 3) { 0f }

        // Setup closed eyes (vertical distance very small: 2px)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_OUTER, 100f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_INNER, 150f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_TOP, 125f, 199f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_BOTTOM, 125f, 201f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_TOP_2, 125f, 199f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_BOTTOM_2, 125f, 201f, 0f)

        setPoint(mesh, MeshLandmarks.LEFT_EYE_OUTER, 250f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_INNER, 200f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_TOP, 225f, 199f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_BOTTOM, 225f, 201f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_TOP_2, 225f, 199f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_BOTTOM_2, 225f, 201f, 0f)

        val snap = EyeTracker.processMesh(mesh, nowMs = 1000L)
        assertTrue("EAR should indicate active blink", snap.isBlinkActive)
        assertEquals(1, snap.blinkCount)
    }

    @Test
    fun testLookRightWhenIrisMovesToOuterCorner() {
        val mesh = FloatArray(478 * 3) { 0f }
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_OUTER, 100f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_INNER, 150f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_TOP, 125f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_BOTTOM, 125f, 210f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_TOP_2, 125f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_BOTTOM_2, 125f, 210f, 0f)

        setPoint(mesh, MeshLandmarks.LEFT_EYE_OUTER, 250f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_INNER, 200f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_TOP, 225f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_BOTTOM, 225f, 210f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_TOP_2, 225f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_BOTTOM_2, 225f, 210f, 0f)

        // For right eye in selfie view: outer corner (100) is image-left (abduction = looking RIGHT).
        setPoint(mesh, MeshLandmarks.RIGHT_IRIS_CENTER, 108f, 200f, 0f)

        val snap = EyeTracker.processMesh(mesh, nowMs = 1000L)
        assertTrue(snap.isTrackingValid)
        assertEquals(GazeDirection.RIGHT, snap.rawDirection)
    }

    @Test
    fun testLookLeftWhenIrisMovesToInnerCorner() {
        val mesh = FloatArray(478 * 3) { 0f }
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_OUTER, 100f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_INNER, 150f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_TOP, 125f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_BOTTOM, 125f, 210f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_TOP_2, 125f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.RIGHT_EYE_BOTTOM_2, 125f, 210f, 0f)

        setPoint(mesh, MeshLandmarks.LEFT_EYE_OUTER, 250f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_INNER, 200f, 200f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_TOP, 225f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_BOTTOM, 225f, 210f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_TOP_2, 225f, 190f, 0f)
        setPoint(mesh, MeshLandmarks.LEFT_EYE_BOTTOM_2, 225f, 210f, 0f)

        // For right eye in selfie view: inner corner (150) is image-right (adduction = looking LEFT).
        setPoint(mesh, MeshLandmarks.RIGHT_IRIS_CENTER, 142f, 200f, 0f)

        val snap = EyeTracker.processMesh(mesh, nowMs = 1000L)
        assertTrue(snap.isTrackingValid)
        assertEquals(GazeDirection.LEFT, snap.rawDirection)
    }

    private fun setPoint(mesh: FloatArray, index: Int, x: Float, y: Float, z: Float) {
        val off = index * 3
        mesh[off] = x
        mesh[off + 1] = y
        mesh[off + 2] = z
    }
}
