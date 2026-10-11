package com.edgeppg.app.session

import com.edgeppg.app.integrity.IntegrityManager
import com.edgeppg.app.transport.Transport
import com.edgeppg.app.transport.TransportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionResetTest {

    private class FakeTransport : Transport {
        override suspend fun send(envelope: IntegrityManager.Envelope): TransportResult {
            return TransportResult.localError("test")
        }
    }

    @Test
    fun start_resets_all_live_metrics_to_clean_state() {
        val controller = SessionController(FakeTransport())

        // Simulate stale state from a previous session
        controller.liveHasFace = true
        controller.liveMultipleFaces = true
        controller.liveFaceCount = 3
        controller.liveLocked = true
        controller.liveContamination = true
        controller.liveFps = 30f
        controller.liveDropRate = 0.05f
        controller.liveExposureStability = 0.90f
        controller.liveAwbStability = 0.90f
        controller.liveP = 0.85f
        controller.liveSnr = 1.5f
        controller.liveHrBpm = 72f
        controller.liveRoiCorr = 0.80f
        controller.livePeakSnr = 2.0f
        controller.livePeakRoiCorr = 0.85f
        controller.livePeakHrBpm = 75f
        controller.liveRppgFrameCount = 100
        controller.livePhoneDetected = true
        controller.livePhoneReason = "stale-phone"

        // Begin a new session
        controller.start("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

        // Verify that NO state from the previous session survives
        assertFalse(controller.liveHasFace)
        assertFalse(controller.liveMultipleFaces)
        assertEquals(0, controller.liveFaceCount)
        assertFalse(controller.liveLocked)
        assertFalse(controller.liveContamination)
        assertTrue(controller.liveFps.isNaN())
        assertTrue(controller.liveDropRate.isNaN())
        assertTrue(controller.liveExposureStability.isNaN())
        assertTrue(controller.liveAwbStability.isNaN())
        assertTrue(controller.liveP.isNaN())
        assertEquals(0f, controller.liveSnr, 0.001f)
        assertEquals(0f, controller.liveHrBpm, 0.001f)
        assertEquals(0f, controller.liveRoiCorr, 0.001f)
        assertEquals(0f, controller.livePeakSnr, 0.001f)
        assertEquals(0f, controller.livePeakRoiCorr, 0.001f)
        assertEquals(0f, controller.livePeakHrBpm, 0.001f)
        assertEquals(0, controller.liveRppgFrameCount)
        assertFalse(controller.livePhoneDetected)
        assertEquals("", controller.livePhoneReason)
    }
}
