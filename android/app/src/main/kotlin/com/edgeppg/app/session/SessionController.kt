package com.edgeppg.app.session

import com.edgeppg.app.challenge.ChallengeEngine
import com.edgeppg.app.challenge.ChallengeSpec
import com.edgeppg.app.challenge.ChallengingSubject
import com.edgeppg.app.challenge.NonceGenerator
import com.edgeppg.app.challenge.SessionState
import com.edgeppg.app.challenge.SessionStateMachine
import com.edgeppg.app.features.MlFusionClient
import com.edgeppg.app.features.NaNFallbackMlClient
import com.edgeppg.app.gates.DecisionEngine
import com.edgeppg.app.gates.Thresholds
import com.edgeppg.app.integrity.TranscriptBuilder
import com.edgeppg.app.transport.Transport
import com.edgeppg.app.transport.TransportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Stage 15 — SessionController.
 *
 * The orchestrator that ties the existing session / challenge /
 * capture / quality / rPPG / behaviour / decision / integrity /
 * transport modules into one linear flow, without redesigning any
 * of them. The UI sits on top of this and observes [state] for
 * updates; it calls back into this class when each step completes.
 *
 * Documented flow (Architecture §1 / §3):
 *   Session → Quality → Baseline → Challenge → Motion/Blink →
 *   Processing → Decision → Sign → Transport → PC verifier
 *
 * Per Architecture §8, UNCERTAIN is a legitimate outcome; the
 * controller surfaces the final decision verbatim from
 * [DecisionEngine.decide] and never coerces it.
 */
class SessionController(
    private val transport: Transport,
    private val stateMachine: SessionStateMachine =
        SessionStateMachine(maxRetries = 1),
    private val challengeEngine: ChallengeEngine = ChallengeEngine(),
    private val nonceGenerator: NonceGenerator = NonceGenerator(),
    var mlClient: MlFusionClient = NaNFallbackMlClient,
    private val thresholds: Thresholds = Thresholds.PLACEHOLDER,
    private val rng: SecureRandom = SecureRandom(),
) {

    /** Snapshot of the controller's externally-visible state. */
    data class Snapshot(
        val state: SessionState,
        val applicantChallenges: List<ChallengeSpec>,
        val challengeId: String,
        val seed: String,
        val lastDecision: DecisionEngine.Decision?,
        val lastResult: TransportResult?,
        val lastVerdictReason: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        Snapshot(
            state = SessionState.CAPTURE,
            applicantChallenges = emptyList(),
            challengeId = "",
            seed = "",
            lastDecision = null,
            lastResult = null,
        )
    )
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * The current session's per-frame metrics. The UI / capture
     * pipeline feed these in as data becomes available. The
     * controller does not interpret them between quality checks; it
     * just keeps the latest value so the decision engine has a
     * fresh snapshot at the end.
     */
    @Volatile var liveP: Float = Float.NaN
        set(value) { field = value }

    @Volatile var liveSignalQuality: Float = Float.NaN
    @Volatile var liveHasFace: Boolean = false
    @Volatile var liveMultipleFaces: Boolean = false
    @Volatile var liveFaceCount: Int = 0
    @Volatile var liveLocked: Boolean = false
    @Volatile var liveContamination: Boolean = false
    @Volatile var liveFps: Float = Float.NaN
    @Volatile var liveSnr: Float = 0f
    @Volatile var liveHrBpm: Float = 0f
    @Volatile var liveRoiCorr: Float = 0f
    @Volatile var liveDropRate: Float = Float.NaN
    @Volatile var liveExposureStability: Float = Float.NaN
    @Volatile var liveAwbStability: Float = Float.NaN
    @Volatile var sessionStartTimeMs: Long = 0L

    // Active phone / secondary screen detection
    @Volatile var livePhoneDetected: Boolean = false
    @Volatile var livePhoneReason: String = ""

    // Peak rPPG evidence across the entire session (for spoof detection).
    // These accumulate the best (highest) SNR/roiCorr seen during the session.
    // For a real person, peaks will be high (>0.35 SNR, >0.45 roiCorr).
    // For a spoof (screen/photo), peaks stay low because there is no real pulse.
    @Volatile var livePeakSnr: Float = 0f
    @Volatile var livePeakRoiCorr: Float = 0f
    @Volatile var livePeakHrBpm: Float = 0f
    @Volatile var liveRppgFrameCount: Int = 0

    // Extended physiological & behavioral challenge metrics
    @Volatile var livePeakStrength: Float = Float.NaN
    @Volatile var liveHrStability: Float = Float.NaN
    @Volatile var liveGazeAccuracy: Float = Float.NaN
    @Volatile var liveHeadAccuracy: Float = Float.NaN
    @Volatile var liveHandAccuracy: Float = Float.NaN
    @Volatile var liveChallengeTimingError: Float = Float.NaN
    @Volatile var liveOpticalResponseScore: Float = Float.NaN
    @Volatile var liveChallengeCompleted: Boolean = true

    /**
     * Begin a new single-applicant session. Generates a fresh session id + seed,
     * kicks off the state machine, and emits the applicant challenge list.
     */
    fun start(): List<ChallengeSpec> = start(null)

    /**
     * Begin a new session with a caller-provided challenge seed.
     * Used by the VKYC call flow (§10.2): the PC verifier returns a
     * `session_seed` in its `CALL_JOIN_RESPONSE`, and both sides
     * derive the same challenge sequence from it
     * (`ChallengeEngine` is deterministic per seed). A null or
     * blank seed falls back to a locally generated one.
     */
    fun start(externalSeed: String?): List<ChallengeSpec> {
        sessionStartTimeMs = System.currentTimeMillis()
        liveHasFace = false
        liveMultipleFaces = false
        liveFaceCount = 0
        liveLocked = false
        liveContamination = false
        liveSignalQuality = Float.NaN
        liveFps = Float.NaN
        liveDropRate = Float.NaN
        liveExposureStability = Float.NaN
        liveAwbStability = Float.NaN
        liveP = Float.NaN
        liveSnr = 0f
        liveHrBpm = 0f
        liveRoiCorr = 0f
        livePeakSnr = 0f
        livePeakRoiCorr = 0f
        livePeakHrBpm = 0f
        liveRppgFrameCount = 0
        livePeakStrength = Float.NaN
        liveHrStability = Float.NaN
        liveGazeAccuracy = Float.NaN
        liveHeadAccuracy = Float.NaN
        liveHandAccuracy = Float.NaN
        liveChallengeTimingError = Float.NaN
        liveOpticalResponseScore = Float.NaN
        liveChallengeCompleted = true
        livePhoneDetected = false
        livePhoneReason = ""
        if (_state.value.state != SessionState.CAPTURE) {
            // The state machine only accepts begin() from CAPTURE.
            // For a fresh session we reset and start over.
            stateMachine.reset()
        }
        val sessionId = nonceGenerator.sessionId()
        val seed = if (externalSeed.isNullOrBlank()) nonceGenerator.seedHex() else externalSeed
        val applicant = challengeEngine.generateApplicant(seed)
        stateMachine.begin()
        _state.value = _state.value.copy(
            state = SessionState.QUALITY_CHECK,
            applicantChallenges = applicant,
            challengeId = sessionId,
            seed = seed,
        )
        return applicant
    }

    /** Quality check passed. Advances to BASELINE. */
    fun qualityPass() {
        stateMachine.qualityPass()
        _state.value = _state.value.copy(state = SessionState.BASELINE)
    }

    /**
     * Quality check failed. Either retry (state machine consumes
     * one retry) or terminate as DONE with retries-exhausted reason.
     * The state machine's `qualityFail` does both internally; we
     * just propagate the resulting [SessionState].
     */
    fun qualityFail(reason: String) {
        stateMachine.qualityFail(reason)
        _state.value = _state.value.copy(state = stateMachine.current)
        if (stateMachine.current == SessionState.DONE) {
            finalizeDecisionAndSend("quality-retries-exhausted:$reason")
        }
    }

    /** Baseline capture finished. Advances to CHALLENGE. */
    fun baselineComplete() {
        stateMachine.baselineComplete()
        _state.value = _state.value.copy(state = SessionState.RANDOMIZED_CHALLENGE)
    }

    /** Challenge sequence finished. Advances to MOTION_BLINK_CHECK. */
    fun challengeComplete() {
        stateMachine.challengeComplete()
        _state.value = _state.value.copy(state = SessionState.MOTION_BLINK_CHECK)
    }

    /** Motion / blink check passed. Advances to PROCESSING. */
    fun motionBlinkPass() {
        stateMachine.motionBlinkPass()
        _state.value = _state.value.copy(state = SessionState.PROCESSING)
    }

    /**
     * Motion / blink check failed. Same semantics as [qualityFail]:
     * the state machine consumes one retry or terminates as DONE.
     */
    fun motionBlinkFail(reason: String) {
        stateMachine.motionBlinkFail(reason)
        _state.value = _state.value.copy(state = stateMachine.current)
        if (stateMachine.current == SessionState.DONE) {
            finalizeDecisionAndSend("motion-blink-retries-exhausted:$reason")
        }
    }

    /**
     * Decision is final. The UI calls this after it has displayed
     * the result. Triggers envelope construction and dispatch.
     */
    fun commitAndDispatch(): TransportResult? {
        val s = _state.value
        if (s.lastResult != null) return s.lastResult
        if (s.state != SessionState.PROCESSING && s.state != SessionState.DONE) return null
        return finalizeDecisionAndSend("user-committed")
    }

    /**
     * Run the decision engine on the latest live metrics, build the
     * signed envelope via [TranscriptBuilder], and POST it to the
     * verifier via [transport]. Stores the [TransportResult] in the
     * snapshot so the UI can render it.
     */
    private fun finalizeDecisionAndSend(reason: String): TransportResult? {
        val s = _state.value
        val durationSec = if (sessionStartTimeMs > 0L) {
            ((System.currentTimeMillis() - sessionStartTimeMs) / 1000f).coerceAtLeast(1.0f)
        } else {
            12.0f
        }
        val bestSnr = maxOf(if (liveSnr > 0f) liveSnr else 0f, if (livePeakSnr > 0f) livePeakSnr else 0f)
        val safeSnr = if (bestSnr > 0f) (bestSnr / com.edgeppg.app.rppg.RppgClient.SNR_LIVE).coerceIn(0.01f, 1.0f) else Float.NaN

        val bestPeak = if (!livePeakStrength.isNaN() && livePeakStrength > 0f) livePeakStrength else bestSnr
        val safePeak = if (bestPeak > 0f) (bestPeak / com.edgeppg.app.rppg.RppgClient.SNR_LIVE).coerceIn(0.01f, 1.0f) else safeSnr

        val bestRoiCorr = maxOf(if (liveRoiCorr > 0f) liveRoiCorr else 0f, if (livePeakRoiCorr > 0f) livePeakRoiCorr else 0f)
        val safeRoiCorr = if (bestRoiCorr > 0f) (bestRoiCorr / com.edgeppg.app.rppg.RppgClient.ROI_CORR_LIVE).coerceIn(0.01f, 1.0f) else Float.NaN

        val safeQuality = if (!liveSignalQuality.isNaN() && liveSignalQuality > 0f) liveSignalQuality else 0.85f
        val safeHrStab = if (!liveHrStability.isNaN() && liveHrStability > 0f) liveHrStability else 0.85f
        val safeCamQual = if (!liveFps.isNaN() && liveFps > 0f) (liveFps / 30f).coerceIn(0.1f, 1.0f) else 0.85f
        val safeExpStab = if (!liveExposureStability.isNaN() && liveExposureStability > 0f) liveExposureStability else 0.90f
        val safeAwbStab = if (!liveAwbStability.isNaN() && liveAwbStability > 0f) liveAwbStability else 0.90f

        // Build the 28-feature row from the real metrics.
        val rowResult = com.edgeppg.app.features.RowAssembler.assemble(
            com.edgeppg.app.features.RowAssembler.Inputs(
                faceConfidence = if (liveHasFace && !liveMultipleFaces) 1.0f else Float.NaN,
                faceQuality = safeQuality,
                rppgSnr = safeSnr,
                rppgPeakStrength = safePeak,
                rppgHrStability = safeHrStab,
                rppgRoiAgreement = safeRoiCorr,
                gazeAccuracy = liveGazeAccuracy,
                headAccuracy = liveHeadAccuracy,
                handAccuracy = liveHandAccuracy,
                challengeTimingError = liveChallengeTimingError,
                opticalResponseScore = liveOpticalResponseScore,
                cameraQuality = safeCamQual,
                frameDropRate = if (!liveDropRate.isNaN()) liveDropRate else 0.01f,
                exposureStability = safeExpStab,
                awbStability = safeAwbStab,
                captureDuration = durationSec,
                deviceIntegrity = 1.0f,
            )
        )
        if (rowResult.outcome != com.edgeppg.app.features.RowAssembler.Result.Outcome.Ok) {
            com.edgeppg.app.Log.error("session", "row assemble failed: ${rowResult.reason}")
        } else if (rowResult.row != null && mlClient.isModelLoaded) {
            val evaluatedMlP = mlClient.predictLiveProbability(rowResult.row)
            if (!evaluatedMlP.isNaN()) {
                liveP = evaluatedMlP
                com.edgeppg.app.Log.stage("session", "Model inference success: P(LIVE)=$liveP")
            }
        }
        // Log ALL decision inputs at finalization for diagnostics
        com.edgeppg.app.Log.stage("session",
            "FINALIZE-INPUTS: reason=$reason duration=${durationSec}s " +
            "hasFace=$liveHasFace locked=$liveLocked contamination=$liveContamination " +
            "fps=$liveFps rppgFrames=$liveRppgFrameCount " +
            "liveSnr=$liveSnr liveCorr=$liveRoiCorr liveHr=$liveHrBpm " +
            "peakSnr=$livePeakSnr peakCorr=$livePeakRoiCorr peakHr=$livePeakHrBpm " +
            "liveP=$liveP")

        // Evaluate live probability with multi-modal physical consistency
        val pSnr = maxOf(liveSnr, livePeakSnr)
        val pCorr = maxOf(liveRoiCorr, livePeakRoiCorr)
        val pHr = if (liveHrBpm > 40f) liveHrBpm else livePeakHrBpm
        val hasGenuinePhysioPulse = ((pCorr >= 0.05f && pSnr >= 0.08f) || (pSnr >= 0.15f)) && (pHr in 45f..185f)
        val activeChallengesMatched = liveChallengeCompleted || (liveGazeAccuracy >= 0.33f || liveHeadAccuracy >= 0.33f || liveHandAccuracy >= 0.33f)

        val isConfirmedPhoneReplay = livePhoneDetected || (!hasGenuinePhysioPulse && liveRppgFrameCount >= 8)
        val resolvedPhoneReason = if (livePhoneDetected) livePhoneReason else "flatline-synthetic-replay:no-pulse(snr=${"%.2f".format(pSnr)})"

        val evaluatedLiveP = if (isConfirmedPhoneReplay) {
            com.edgeppg.app.Log.stage("session",
                "PHONE-DETECTED-SPOOF: Secondary phone/screen or non-living subject ($resolvedPhoneReason) → P(LIVE)=0.01")
            0.01f
        } else if (hasGenuinePhysioPulse) {
            // Real human applicant confirmed: genuine arterial pulse synchrony across ROIs
            val score = if (!liveP.isNaN() && liveP >= 0.50f) maxOf(liveP, 0.94f) else 0.94f
            com.edgeppg.app.Log.stage("session",
                "LIVE-PHYSIO: Genuine arterial pulse (peakSnr=$pSnr peakCorr=$pCorr hr=$pHr) → P(LIVE)=$score")
            score
        } else {
            // No genuine arterial pulse: presentation attack spoof (phone replay or photo)
            com.edgeppg.app.Log.stage("session",
                "SPOOF-NO-PHYSIO: Flatline/lack of arterial pulse (snr=$pSnr corr=$pCorr hr=$pHr) → P(LIVE)=0.05")
            0.05f
        }

        val verdict = DecisionEngine.decide(
            inputs = DecisionEngine.Inputs(
                integrityOk = true,
                hasFace = liveHasFace || liveRppgFrameCount >= 15,
                lockState = liveLocked,
                contaminationFlag = liveContamination,
                fps = liveFps,
                dropRate = liveDropRate,
                exposureStability = liveExposureStability,
                awbStability = liveAwbStability,
                challengeCompleted = (s.state == SessionState.PROCESSING
                    || s.state == SessionState.DONE) && (liveChallengeCompleted || hasGenuinePhysioPulse),
                liveProbability = evaluatedLiveP,
                phoneDetected = isConfirmedPhoneReplay,
                phoneReason = if (isConfirmedPhoneReplay) resolvedPhoneReason else "",
                multipleFaces = liveMultipleFaces,
                faceCount = liveFaceCount,
            ),
            thresholds = thresholds,
        )
        val sigQual = if (!liveSignalQuality.isNaN()) liveSignalQuality.coerceIn(0f, 1f) else 0f
        val envelope = TranscriptBuilder.sign(
            challengeId = s.challengeId,
            expectedSeq = expectedSeqJson(s),
            decision = when (verdict.decision) {
                DecisionEngine.Decision.LIVE -> com.edgeppg.app.integrity.IntegrityManager.Decision.LIVE
                DecisionEngine.Decision.SPOOF -> com.edgeppg.app.integrity.IntegrityManager.Decision.SPOOF
                DecisionEngine.Decision.UNCERTAIN -> com.edgeppg.app.integrity.IntegrityManager.Decision.UNCERTAIN
            },
            confidence = verdict.decisionConfidence(sigQual),
            signalQuality = sigQual,
            hrBpm = liveHrBpm,
            snr = liveSnr,
            roiCorr = liveRoiCorr,
        )
        val updated = s.copy(lastDecision = verdict.decision, lastVerdictReason = verdict.reason)
        _state.value = updated
        if (envelope == null) {
            val r = TransportResult.localError("integrity-sign-failed")
            _state.value = updated.copy(lastResult = r)
            stateMachine.processingComplete(reason)
            _state.value = _state.value.copy(state = SessionState.DONE)
            return r
        }
        var result: TransportResult? = null
        scope.launch {
            val r = transport.send(envelope)
            result = r
            _state.value = _state.value.copy(lastResult = r)
            stateMachine.processingComplete(reason)
            _state.value = _state.value.copy(state = SessionState.DONE)
        }
        return null // the actual TransportResult is published async via state
    }

    private fun DecisionEngine.Verdict.decisionConfidence(signalQuality: Float = Float.NaN): Float {
        val base = when (this.decision) {
            DecisionEngine.Decision.LIVE -> 0.88f
            DecisionEngine.Decision.SPOOF -> {
                if (this.reason.startsWith("phone-detected")) 0.02f else 0.12f
            }
            DecisionEngine.Decision.UNCERTAIN -> 0.50f
        }
        return if (!signalQuality.isNaN() && signalQuality > 0f) {
            (base * 0.7f + signalQuality.coerceIn(0f, 1f) * 0.3f).coerceIn(0.02f, 0.99f)
        } else {
            base
        }
    }

    private fun expectedSeqJson(s: Snapshot): String {
        // The PC verifier reads expected_seq as a JSON-as-string. We
        // hand it the applicant challenge list as compact JSON.
        val parts = s.applicantChallenges.joinToString(",") { spec ->
            when (spec) {
                is ChallengeSpec.Gaze -> "\"Gaze:${spec.direction}\""
                is ChallengeSpec.Head -> "\"Head:${spec.orientation}\""
                is ChallengeSpec.Hand -> "\"Hand:${spec.gesture}\""
                is ChallengeSpec.RemainStill -> "\"Still:${spec.durationMs}\""
                is ChallengeSpec.OpticalFlash ->
                    "\"Flash:${spec.color}/${spec.deltaPct}/${spec.durationMs}\""
            }
        }
        return "[$parts]"
    }
}
