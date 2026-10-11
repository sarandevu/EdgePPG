package com.edgeppg.app.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanCardTest {

    @Test
    fun testPanMaskingPrivacyContract() {
        val rawPan = "ABCDE1234F"
        val masked = PanDocumentRecord.maskPan(rawPan)
        assertEquals("ABCDE****F", masked)

        val invalid = PanDocumentRecord.maskPan("INVALID123")
        assertEquals("INVALID_FORMAT", invalid)
    }

    @Test
    fun testNameMaskingPrivacyContract() {
        val rawName = "NAREN KUMAR"
        val masked = PanDocumentRecord.maskName(rawName)
        assertEquals("N***N K***R", masked)

        val shortName = "BO"
        assertEquals("BO", PanDocumentRecord.maskName(shortName))
    }

    @Test
    fun testPanCardDetectorAspectRatio() {
        val detector = PanCardDetector()
        val quad = detector.detectCard(1080, 1920)

        assertTrue(
            "Aspect ratio should be within standard ID-1 card bounds",
            quad.aspectRatio in PanCardDetector.MIN_ASPECT_RATIO..PanCardDetector.MAX_ASPECT_RATIO
        )
    }

    @Test
    fun testPanCardDetectorTemporalStability() {
        val detector = PanCardDetector()
        assertFalse("Initial frame is not stable", detector.isStable)

        // Simulate 5 stable frames
        for (i in 1..PanCardDetector.STABLE_FRAME_THRESHOLD) {
            detector.detectCard(1080, 1920)
        }
        assertTrue("5 consecutive frames should establish tracking stability", detector.isStable)
    }

    @Test
    fun testPanOcrTextParsing() {
        var observedRecord: PanDocumentRecord? = null
        val parser = PanCardOcr { record ->
            observedRecord = record
        }

        val testText = """
            INCOME TAX DEPARTMENT
            GOVT. OF INDIA
            Permanent Account Number Card
            ABCPE1234F
            NAREN KUMAR
            FATHER NAME
            01/01/1990
        """.trimIndent()

        parser.parseVisionText(testText, consecutiveStableFrames = 5, trackingConfidence = 0.95f)

        val rec = observedRecord
        assertTrue("Record should be populated", rec != null)
        assertEquals(CardTrackingStatus.FORMAT_CHECK_PASSED, rec?.status)
        assertEquals("ABCPE****F", rec?.maskedPan)
        assertEquals("Individual", rec?.entityType)
        assertEquals("**/**/1990", rec?.maskedDob)
        assertTrue(rec?.disclaimer?.contains("Offline syntax check") == true)
    }
}
