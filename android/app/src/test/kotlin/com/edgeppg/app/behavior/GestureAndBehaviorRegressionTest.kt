package com.edgeppg.app.behavior

import com.edgeppg.app.challenge.BehavioralOutcome
import com.edgeppg.app.challenge.ChallengeSpec
import com.edgeppg.app.challenge.ChallengingSubject
import com.edgeppg.app.challenge.GazeDirection
import com.edgeppg.app.challenge.HandGesture
import com.edgeppg.app.challenge.HeadOrientation
import com.edgeppg.app.gates.DecisionEngine
import com.edgeppg.app.gates.DecisionEngine.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GestureAndBehaviorRegressionTest {

    @Before
    fun setUp() {
        GestureTracker.reset()
        EyeTracker.reset()
    }

    // =========================================================================
    // 1. GESTURE TRACKER TESTS
    // =========================================================================

    @Test
    fun testThumbsUpRecognition() {
        GestureTracker.updateDirect(HandGesture.THUMBS_UP, confidence = 0.85f, handedness = "Right")
        val snap = GestureTracker.getLatestSnapshot()
        assertTrue("Hand must be detected", snap.isHandPresent)
        assertEquals(HandGesture.THUMBS_UP, snap.gesture)
        assertEquals("THUMBS_UP", snap.label)
        assertTrue("Confidence must exceed operational threshold", snap.confidence >= 0.55f)
        assertEquals("Right", snap.handedness)
        assertFalse("Recent detection must not be stale", snap.isStale)
    }

    @Test
    fun testPointingRecognition() {
        GestureTracker.updateDirect(HandGesture.POINT, confidence = 0.80f, handedness = "Left")
        val snap = GestureTracker.getLatestSnapshot()
        assertTrue("Hand must be detected", snap.isHandPresent)
        assertEquals(HandGesture.POINT, snap.gesture)
        assertEquals("POINT", snap.label)
        assertTrue(snap.confidence >= 0.55f)
        assertEquals("Left", snap.handedness)
    }

    @Test
    fun testOpenPalmRecognition() {
        GestureTracker.updateDirect(HandGesture.OPEN_PALM, confidence = 0.90f)
        val snap = GestureTracker.getLatestSnapshot()
        assertEquals(HandGesture.OPEN_PALM, snap.gesture)
        assertTrue(snap.confidence >= 0.55f)
    }

    @Test
    fun testLowConfidenceGestureIsNotCounted() {
        GestureTracker.updateDirect(HandGesture.THUMBS_UP, confidence = 0.40f)
        val snap = GestureTracker.getLatestSnapshot()
        // Caller checks confidence threshold
        val accepted = !snap.isStale && snap.confidence >= 0.55f
        assertFalse("Low confidence gesture (<0.55) must not be accepted", accepted)
    }

    @Test
    fun testNoHandDetected() {
        val snap = GestureTracker.getLatestSnapshot()
        assertFalse("Initial state has no hand", snap.isHandPresent)
        assertNull(snap.gesture)
        assertEquals("NONE", snap.label)
    }

    @Test
    fun testResetClearsPreviousGestureWithoutStateLeakage() {
        GestureTracker.updateDirect(HandGesture.THUMBS_UP, confidence = 0.92f)
        assertTrue(GestureTracker.getLatestSnapshot().isHandPresent)

        // Reset for next challenge
        GestureTracker.reset()
        val snap = GestureTracker.getLatestSnapshot()
        assertFalse("State must be clean after reset", snap.isHandPresent)
        assertNull("Gesture must be null after reset", snap.gesture)
    }

    // =========================================================================
    // 2. BEHAVIORAL RUNNER & ACTION MATCHING TESTS
    // =========================================================================

    @Test
    fun testHeadTurnLeftRecognition() {
        val spec = ChallengeSpec.Head(HeadOrientation.TURN_LEFT, ChallengingSubject.APPLICANT)
        val matchedPose = HeadPose(yawDeg = 20f, pitchDeg = 0f, rollDeg = 0f, available = true)
        val result = BehavioralRunner.checkMatch(spec, pose = matchedPose)
        assertEquals("Head yaw > 12 deg should match TURN_LEFT", true, result)
    }

    @Test
    fun testHeadTurnNeutralDoesNotFailPrematurely() {
        val spec = ChallengeSpec.Head(HeadOrientation.TURN_LEFT, ChallengingSubject.APPLICANT)
        // User is neutral, looking at screen while reading prompt
        val neutralPose = HeadPose(yawDeg = 2f, pitchDeg = 0f, rollDeg = 0f, available = true)
        val result = BehavioralRunner.checkMatch(spec, pose = neutralPose)
        assertNull("Neutral head pose must return null (in-progress), NOT false/WRONG", result)
    }

    @Test
    fun testHeadTurnOppositeIsMarkedWrong() {
        val spec = ChallengeSpec.Head(HeadOrientation.TURN_LEFT, ChallengingSubject.APPLICANT)
        // User turned right instead
        val oppositePose = HeadPose(yawDeg = -18f, pitchDeg = 0f, rollDeg = 0f, available = true)
        val result = BehavioralRunner.checkMatch(spec, pose = oppositePose)
        assertEquals("Opposite turn must be marked false", false, result)
    }

    @Test
    fun testEyeGazeLeftRecognition() {
        val spec = ChallengeSpec.Gaze(GazeDirection.LEFT, ChallengingSubject.APPLICANT)
        val gaze = GazeEstimate(direction = GazeDirection.LEFT, confidence = 0.85f, eyeAvailable = true)
        val result = BehavioralRunner.checkMatch(spec, gaze = gaze)
        assertEquals("Gaze looking left should match", true, result)
    }

    @Test
    fun testEyeGazeCenterDoesNotFailPrematurely() {
        val spec = ChallengeSpec.Gaze(GazeDirection.LEFT, ChallengingSubject.APPLICANT)
        // User is reading the prompt on screen
        val gaze = GazeEstimate(direction = GazeDirection.CENTER, confidence = 0.90f, eyeAvailable = true)
        val result = BehavioralRunner.checkMatch(spec, gaze = gaze)
        assertNull("Looking at center/prompt must return null (in-progress), NOT false", result)
    }

    @Test
    fun testEyeGazeOppositeIsMarkedWrong() {
        val spec = ChallengeSpec.Gaze(GazeDirection.LEFT, ChallengingSubject.APPLICANT)
        val gaze = GazeEstimate(direction = GazeDirection.RIGHT, confidence = 0.80f, eyeAvailable = true)
        val result = BehavioralRunner.checkMatch(spec, gaze = gaze)
        assertEquals("Looking right when asked left should be false", false, result)
    }

    @Test
    fun testHandChallengeThumbsUpMatch() {
        val spec = ChallengeSpec.Hand(HandGesture.THUMBS_UP, ChallengingSubject.APPLICANT)
        val match = BehavioralRunner.checkMatch(spec, gesture = HandGesture.THUMBS_UP)
        assertEquals(true, match)
    }

    @Test
    fun testHandChallengeWrongGesture() {
        val spec = ChallengeSpec.Hand(HandGesture.THUMBS_UP, ChallengingSubject.APPLICANT)
        val match = BehavioralRunner.checkMatch(spec, gesture = HandGesture.OPEN_PALM)
        assertEquals(false, match)
    }

    @Test
    fun testHandChallengeRaiseRightMatch() {
        val spec = ChallengeSpec.Hand(HandGesture.RAISE_RIGHT, ChallengingSubject.APPLICANT)
        val matchDirect = BehavioralRunner.checkMatch(spec, gesture = HandGesture.RAISE_RIGHT, handedness = "Right")
        assertEquals("Direct RAISE_RIGHT gesture must match RAISE_RIGHT challenge", true, matchDirect)

        val matchOpenPalm = BehavioralRunner.checkMatch(spec, gesture = HandGesture.OPEN_PALM, handedness = "Right")
        assertEquals("OPEN_PALM on Right side must satisfy RAISE_RIGHT challenge", true, matchOpenPalm)

        val matchWrongSide = BehavioralRunner.checkMatch(spec, gesture = HandGesture.RAISE_LEFT, handedness = "Left")
        assertEquals("RAISE_LEFT on Left side must fail RAISE_RIGHT challenge", false, matchWrongSide)
    }

    @Test
    fun testHandChallengeRaiseLeftMatch() {
        val spec = ChallengeSpec.Hand(HandGesture.RAISE_LEFT, ChallengingSubject.APPLICANT)
        val matchDirect = BehavioralRunner.checkMatch(spec, gesture = HandGesture.RAISE_LEFT, handedness = "Left")
        assertEquals("Direct RAISE_LEFT gesture must match RAISE_LEFT challenge", true, matchDirect)

        val matchOpenPalm = BehavioralRunner.checkMatch(spec, gesture = HandGesture.OPEN_PALM, handedness = "Left")
        assertEquals("OPEN_PALM on Left side must satisfy RAISE_LEFT challenge", true, matchOpenPalm)

        val matchWrongSide = BehavioralRunner.checkMatch(spec, gesture = HandGesture.RAISE_RIGHT, handedness = "Right")
        assertEquals("RAISE_RIGHT on Right side must fail RAISE_LEFT challenge", false, matchWrongSide)
    }

    @Test
    fun testHandChallengeOpenPalmMatch() {
        val spec = ChallengeSpec.Hand(HandGesture.OPEN_PALM, ChallengingSubject.APPLICANT)
        val matchPalm = BehavioralRunner.checkMatch(spec, gesture = HandGesture.OPEN_PALM)
        assertEquals(true, matchPalm)

        val matchRaised = BehavioralRunner.checkMatch(spec, gesture = HandGesture.RAISE_RIGHT)
        assertEquals(true, matchRaised)

        val matchPointWrong = BehavioralRunner.checkMatch(spec, gesture = HandGesture.POINT)
        assertEquals(false, matchPointWrong)
    }

    @Test
    fun testHandChallengeWaitingForHandReturnsNull() {
        val spec = ChallengeSpec.Hand(HandGesture.THUMBS_UP, ChallengingSubject.APPLICANT)
        val match = BehavioralRunner.checkMatch(spec, gesture = null)
        assertNull("No hand observed yet must return null (in-progress)", match)
    }

    @Test
    fun testHeadTurnDoesNotSatisfyGazePrompt() {
        // Separation of head and gaze modalities
        val spec = ChallengeSpec.Gaze(GazeDirection.LEFT, ChallengingSubject.APPLICANT)
        val headPose = HeadPose(yawDeg = 25f, pitchDeg = 0f, rollDeg = 0f, available = true)
        val gaze = GazeEstimate(direction = GazeDirection.CENTER, confidence = 0.9f, eyeAvailable = true)
        val match = BehavioralRunner.checkMatch(spec, gaze = gaze, pose = headPose)
        assertNull("Head turn must never satisfy a gaze prompt", match)
    }

    // =========================================================================
    // 3. FALSE SPOOF REGRESSION TESTS
    // =========================================================================

    @Test
    fun testIncompleteChallengeNeverProducesFalseSpoof() {
        // FR-GATE-3: If challenge was incomplete or timed out, verdict MUST be UNCERTAIN, never SPOOF!
        val inputs = DecisionEngine.Inputs(
            integrityOk = true,
            hasFace = true,
            lockState = true,
            contaminationFlag = false,
            fps = 30f,
            dropRate = 0.01f,
            exposureStability = 0.95f,
            awbStability = 0.95f,
            challengeCompleted = false, // Challenge timed out or incomplete
            liveProbability = 0.05f,   // Low ML probability due to motion or noise
        )
        val verdict = DecisionEngine.decide(inputs)
        assertEquals("Incomplete challenge must route to UNCERTAIN, not SPOOF", Decision.UNCERTAIN, verdict.decision)
        assertEquals("challenge-incomplete", verdict.reason)
    }

    @Test
    fun testMissingRppgDueToMotionNeverProducesFalseSpoof() {
        // FR-GATE-4 / Architecture §8: Missing ML/physiological evidence -> NaN -> UNCERTAIN, NEVER SPOOF!
        val inputs = DecisionEngine.Inputs(
            integrityOk = true,
            hasFace = true,
            lockState = true,
            contaminationFlag = false,
            fps = 30f,
            dropRate = 0.01f,
            exposureStability = 0.95f,
            awbStability = 0.95f,
            challengeCompleted = true,
            liveProbability = Float.NaN, // Motion degraded pulse signals
        )
        val verdict = DecisionEngine.decide(inputs)
        assertEquals("NaN live probability must route to UNCERTAIN, not SPOOF", Decision.UNCERTAIN, verdict.decision)
        assertEquals("no-ml-evidence:NaN", verdict.reason)
    }

    @Test
    fun testGenuineLiveWithCompletedChallengePassesLive() {
        val inputs = DecisionEngine.Inputs(
            integrityOk = true,
            hasFace = true,
            lockState = true,
            contaminationFlag = false,
            fps = 30f,
            dropRate = 0.01f,
            exposureStability = 0.95f,
            awbStability = 0.95f,
            challengeCompleted = true,
            liveProbability = 0.92f, // High arterial pulse agreement
        )
        val verdict = DecisionEngine.decide(inputs)
        assertEquals(Decision.LIVE, verdict.decision)
    }

    @Test
    fun testPhoneScreenAttackIsAlwaysCaughtAsSpoof() {
        val inputs = DecisionEngine.Inputs(
            integrityOk = true,
            hasFace = true,
            lockState = true,
            contaminationFlag = false,
            fps = 30f,
            dropRate = 0.01f,
            exposureStability = 0.95f,
            awbStability = 0.95f,
            challengeCompleted = true,
            liveProbability = 0.95f,
            phoneDetected = true,
            phoneReason = "black-screen-bezel-ratio=0.88",
        )
        val verdict = DecisionEngine.decide(inputs)
        assertEquals(Decision.SPOOF, verdict.decision)
        assertTrue(verdict.reason.contains("phone-detected-spoof"))
    }

    @Test
    fun testKeystoreTamperingIsAlwaysCaughtAsSpoof() {
        val inputs = DecisionEngine.Inputs(
            integrityOk = false, // Root / hardware tampering
            hasFace = true,
            lockState = true,
            contaminationFlag = false,
            fps = 30f,
            dropRate = 0.01f,
            exposureStability = 0.95f,
            awbStability = 0.95f,
            challengeCompleted = true,
            liveProbability = 0.95f,
        )
        val verdict = DecisionEngine.decide(inputs)
        assertEquals(Decision.SPOOF, verdict.decision)
        assertEquals("integrity-failure", verdict.reason)
    }
}
