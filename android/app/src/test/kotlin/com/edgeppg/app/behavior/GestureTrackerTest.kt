package com.edgeppg.app.behavior

import com.edgeppg.app.challenge.HandGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GestureTrackerTest {

    @Before
    fun setUp() {
        GestureTracker.reset()
    }

    @Test
    fun testInitialStateIsNoneAndStale() {
        val snap = GestureTracker.getLatestSnapshot()
        assertFalse("Initial hand presence should be false", snap.isHandPresent)
        assertEquals("NONE", snap.label)
        assertEquals(0f, snap.confidence, 0.001f)
    }

    @Test
    fun testDirectUpdateSetsRecognizedGesture() {
        GestureTracker.updateDirect(HandGesture.OPEN_PALM, 0.88f, handedness = "Right")
        val snap = GestureTracker.getLatestSnapshot()
        assertTrue("Hand presence should be true", snap.isHandPresent)
        assertEquals(HandGesture.OPEN_PALM, snap.gesture)
        assertEquals("OPEN_PALM", snap.label)
        assertEquals(0.88f, snap.confidence, 0.001f)
        assertEquals("Right", snap.handedness)
        assertFalse("Immediately updated gesture should not be stale", snap.isStale)
    }

    @Test
    fun testResetClearsState() {
        GestureTracker.updateDirect(HandGesture.THUMBS_UP, 0.95f)
        assertTrue(GestureTracker.getLatestSnapshot().isHandPresent)
        GestureTracker.reset()
        val snap = GestureTracker.getLatestSnapshot()
        assertFalse("State should be cleared after reset", snap.isHandPresent)
        assertEquals("NONE", snap.label)
    }
}
