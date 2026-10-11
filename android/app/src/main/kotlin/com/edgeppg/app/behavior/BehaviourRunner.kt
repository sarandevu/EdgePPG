package com.edgeppg.app.behavior

import com.edgeppg.app.challenge.BehavioralObservation
import com.edgeppg.app.challenge.BehavioralOutcome
import com.edgeppg.app.challenge.ChallengeSpec
import com.edgeppg.app.optical.OpticalFlashOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Stage 15 Task 4 — Behaviour-runner coroutine.
 *
 * The coroutine driver on top of the pure [BehavioralRunner] matcher.
 * Iterates through a [ChallengeSpec] list, drives the optical flash
 * overlay for [ChallengeSpec.OpticalFlash] entries, waits for the
 * per-challenge timeout, and emits a [BehaviourEvent] for each
 * lifecycle tick.
 *
 * Events the UI subscribes to:
 *  - [BehaviourEvent.PromptShown] — challenge is on-screen, awaiting
 *    response. The UI typically displays the challenge text.
 *  - [BehaviourEvent.FlashStarted] / [BehaviourEvent.FlashEnded] —
 *    optical flash boundaries (for status / accessibility).
 *  - [BehaviourEvent.Observation] — the matcher produced a
 *    [BehavioralObservation] for the current challenge (match,
 *    wrong, timeout, or contaminated).
 *  - [BehaviourEvent.AllComplete] — every challenge in the list has
 *    been processed; the UI can advance to the next session state.
 *
 * Lifecycle:
 *  - The runner takes an external [CoroutineScope] (typically a
 *    `lifecycleScope` from the host activity). Cancellation of that
 *    scope aborts the runner.
 *  - `start(challenges)` is idempotent within a single scope; a
 *    second call after a successful run is a no-op.
 */
class BehaviourRunner(
    private val scope: CoroutineScope,
    private val flashOverlay: OpticalFlashOverlay? = null,
    private val opticalEngine: com.edgeppg.app.optical.OpticalChallengeEngine? = null,
    /** Pulled from the camera pipeline each frame the UI gets a new
     *  FaceMesh result. The runner consumes the latest snapshot. */
    @Volatile var latestMeshPoints: FloatArray? = null,
    @Volatile var latestContaminationFlag: Boolean = false,
) {

    companion object {
        const val READING_PERIOD_MS = 2000L   // User instruction reading & TTS period
        const val RESPONSE_WINDOW_MS = 6000L  // Active detection response window (extended for comfortable gesture response)
        const val STABLE_HOLD_MS = 180L       // ~180ms / ~3 frames required for action acceptance
        const val RESULT_DISPLAY_MS = 1500L   // 1.5s pause to display outcome before advancing
    }

    sealed class BehaviourEvent {
        data class PromptShown(val spec: ChallengeSpec, val index: Int, val total: Int) :
            BehaviourEvent()
        data class ChallengeStateTick(
            val spec: ChallengeSpec,
            val index: Int,
            val total: Int,
            val stage: Stage,
            val remainingSec: Float,
            val detail: String = ""
        ) : BehaviourEvent() {
            enum class Stage {
                READY,           // Reading instruction
                DETECTING,       // Active response window
                ACTION_HELD,     // Holding pose/gesture for stability
                ACTION_MATCHED,  // Action accepted
                WRONG,           // Incorrect action attempted
                TIMEOUT,         // Timeout expired
            }
        }
        data class FlashStarted(val spec: ChallengeSpec) : BehaviourEvent()
        data class FlashEnded(val spec: ChallengeSpec) : BehaviourEvent()
        data class Observation(val observation: BehavioralObservation) :
            BehaviourEvent()
        object AllComplete : BehaviourEvent()
    }

    private val _events = MutableSharedFlow<BehaviourEvent>(
        replay = 0, extraBufferCapacity = 32
    )
    val events: SharedFlow<BehaviourEvent> = _events.asSharedFlow()

    @Volatile private var running: Boolean = false
    private var runJob: Job? = null

    fun isRunning(): Boolean = running

    /**
     * Kick off the coroutine that walks through [challenges] and emits
     * events. Safe to call once per [scope] lifetime. A second call
     * while a run is in flight is a no-op.
     */
    fun start(challenges: List<ChallengeSpec>) {
        com.edgeppg.app.Log.stage("behavior",
            "runner.start(${challenges.size}) running=$running scope=$scope")
        if (running) return
        running = true
        runJob = scope.launch(Dispatchers.Default) {
            try {
                com.edgeppg.app.Log.stage("behavior", "runAll begin")
                runAll(challenges)
                com.edgeppg.app.Log.stage("behavior", "runAll end")
            } catch (t: Throwable) {
                com.edgeppg.app.Log.error("behavior", "runAll failed", t)
            } finally {
                running = false
            }
        }
    }

    /**
     * External cancel. Safe to call from the activity's onDestroy.
     * Idempotent.
     */
    fun cancel() {
        runJob?.cancel()
        runJob = null
        running = false
        opticalEngine?.cancel(flashOverlay)
        flashOverlay?.cancel()
    }

    private suspend fun runAll(challenges: List<ChallengeSpec>) {
        val total = challenges.size
        for ((index, spec) in challenges.withIndex()) {
            // Buffer isolation: reset detection history between challenges
            latestMeshPoints = null
            EyeTracker.reset()
            GestureTracker.reset()

            _events.emit(BehaviourEvent.PromptShown(spec, index, total))

            if (spec is ChallengeSpec.OpticalFlash) {
                _events.emit(BehaviourEvent.FlashStarted(spec))
                val result = if (opticalEngine != null) {
                    opticalEngine.execute(flashOverlay)
                } else {
                    flashOverlay?.showFlash(spec.color, spec.deltaPct, spec.durationMs)
                    delay(spec.durationMs)
                    null
                }
                _events.emit(BehaviourEvent.FlashEnded(spec))
                val outcome = if (result != null) {
                    if (result.isValid || result.opticalResponseScore >= 0.05f) {
                        BehavioralOutcome.MATCH
                    } else {
                        // Inconclusive due to ambient lighting / subtle flash contrast -> CONTAMINATED, never WRONG
                        BehavioralOutcome.CONTAMINATED
                    }
                } else {
                    BehavioralOutcome.MATCH
                }
                val latency = result?.timingErrorMs ?: spec.durationMs
                _events.emit(
                    BehaviourEvent.Observation(
                        BehavioralObservation(
                            spec = spec,
                            observed = outcome,
                            latencyMs = latency,
                            success = (outcome == BehavioralOutcome.MATCH),
                        )
                    )
                )
                if (index < total - 1) {
                    delay(RESULT_DISPLAY_MS)
                }
                continue
            }

            // Phase 1: READY / Instruction-reading & TTS period
            val readingPeriod = when (spec) {
                is ChallengeSpec.Hand -> 3000L // Ensure TTS finishes speaking full prompt
                is ChallengeSpec.Head -> 2500L
                else -> 2000L
            }
            val readyStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - readyStart < readingPeriod) {
                val remSec = ((readingPeriod - (System.currentTimeMillis() - readyStart)) / 1000f).coerceAtLeast(0f)
                _events.emit(
                    BehaviourEvent.ChallengeStateTick(
                        spec = spec,
                        index = index,
                        total = total,
                        stage = BehaviourEvent.ChallengeStateTick.Stage.READY,
                        remainingSec = remSec,
                        detail = "Listen to prompt"
                    )
                )
                delay(200L)
            }

            // Phase 2: Active Detection Window (per-challenge timeout)
            val responseStart = System.currentTimeMillis()
            val timeoutMs = spec.timeoutMs.coerceAtLeast(8_000L)
            var matchStreak = 0
            val requiredStreak = (STABLE_HOLD_MS / 60L).toInt().coerceAtLeast(3) // ~5 frames = ~300ms
            var wrongStreak = 0
            val requiredWrongStreak = 12 // ~720ms of sustained opposite/wrong action before flagging WRONG
            var isMatched = false
            var wrongDetected = false

            while (System.currentTimeMillis() - responseStart < timeoutMs) {
                delay(60L)
                val now = System.currentTimeMillis()
                val remSec = ((timeoutMs - (now - responseStart)) / 1000f).coerceAtLeast(0f)

                val mesh = latestMeshPoints
                if (mesh != null && mesh.size >= 400 * 3) {
                    EyeTracker.processMesh(mesh, now)
                }
                val eyeSnap = EyeTracker.getLatestSnapshot()
                val gaze = if (eyeSnap != null && eyeSnap.isTrackingValid) {
                    GazeEstimate(eyeSnap.smoothedDirection, eyeSnap.confidence, eyeAvailable = true)
                } else if (mesh != null && mesh.size >= 400 * 3) {
                    GazeEstimator.estimate(mesh)
                } else null

                val pose = if (mesh != null && mesh.size >= 160 * 3) {
                    HeadPoseSolver.estimate(mesh)
                } else null

                val gestureSnap = GestureTracker.getLatestSnapshot()
                val gesture = if (!gestureSnap.isStale && gestureSnap.confidence >= 0.55f) gestureSnap.gesture else null

                val match = BehavioralRunner.checkMatch(
                    requested = spec,
                    gaze = gaze,
                    pose = pose,
                    gesture = gesture,
                    handedness = gestureSnap.handedness,
                )
                if (spec is ChallengeSpec.Hand) {
                    com.edgeppg.app.Log.behavior(
                        "HAND-TICK req=${spec.gesture} seen=${gestureSnap.label} side=${gestureSnap.handedness} " +
                        "conf=${"%.2f".format(gestureSnap.confidence)} stale=${gestureSnap.isStale} " +
                        "match=$match streak=$matchStreak wrong=$wrongStreak rem=${"%.1f".format(remSec)}"
                    )
                } else if (spec is ChallengeSpec.Head) {
                    com.edgeppg.app.Log.behavior(
                        "HEAD-TICK req=${spec.orientation} yaw=${"%.1f".format(pose?.yawDeg ?: 0f)} " +
                        "pitch=${"%.1f".format(pose?.pitchDeg ?: 0f)} roll=${"%.1f".format(pose?.rollDeg ?: 0f)} " +
                        "avail=${pose?.available} match=$match streak=$matchStreak wrong=$wrongStreak rem=${"%.1f".format(remSec)}"
                    )
                } else if (spec is ChallengeSpec.Gaze) {
                    com.edgeppg.app.Log.behavior(
                        "GAZE-TICK req=${spec.direction} dir=${gaze?.direction} conf=${"%.2f".format(gaze?.confidence ?: 0f)} " +
                        "eyeSnapDir=${eyeSnap?.smoothedDirection} xNorm=${"%.2f".format(eyeSnap?.xNorm ?: 0f)} " +
                        "yNorm=${"%.2f".format(eyeSnap?.yNorm ?: 0f)} yaw=${"%.1f".format(pose?.yawDeg ?: 0f)} " +
                        "pitch=${"%.1f".format(pose?.pitchDeg ?: 0f)} match=$match streak=$matchStreak wrong=$wrongStreak rem=${"%.1f".format(remSec)}"
                    )
                }

                if (spec is ChallengeSpec.RemainStill) {
                    if (match == false) {
                        wrongDetected = true
                        _events.emit(
                            BehaviourEvent.ChallengeStateTick(
                                spec = spec,
                                index = index,
                                total = total,
                                stage = BehaviourEvent.ChallengeStateTick.Stage.WRONG,
                                remainingSec = remSec,
                                detail = "Motion detected"
                            )
                        )
                        break
                    } else {
                        _events.emit(
                            BehaviourEvent.ChallengeStateTick(
                                spec = spec,
                                index = index,
                                total = total,
                                stage = BehaviourEvent.ChallengeStateTick.Stage.DETECTING,
                                remainingSec = remSec,
                                detail = "Holding still"
                            )
                        )
                    }
                } else {
                    if (match == true) {
                        wrongStreak = 0
                        wrongDetected = false
                        matchStreak++
                        val heldMs = matchStreak * 60
                        _events.emit(
                            BehaviourEvent.ChallengeStateTick(
                                spec = spec,
                                index = index,
                                total = total,
                                stage = BehaviourEvent.ChallengeStateTick.Stage.ACTION_HELD,
                                remainingSec = remSec,
                                detail = "Hold steady: ${heldMs}ms / ${STABLE_HOLD_MS}ms"
                            )
                        )
                        if (matchStreak >= requiredStreak) {
                            isMatched = true
                            _events.emit(
                                BehaviourEvent.ChallengeStateTick(
                                    spec = spec,
                                    index = index,
                                    total = total,
                                    stage = BehaviourEvent.ChallengeStateTick.Stage.ACTION_MATCHED,
                                    remainingSec = 0f,
                                    detail = "Action recognized ✓"
                                )
                            )
                            break
                        }
                    } else {
                        // Leaky decay rather than instant zero reset on a single dropped frame
                        if (matchStreak > 0) matchStreak--
                        if (match == false) {
                            wrongStreak++
                            if (wrongStreak >= requiredWrongStreak) {
                                wrongDetected = true
                            }
                        } else {
                            wrongStreak = 0
                        }
                        _events.emit(
                            BehaviourEvent.ChallengeStateTick(
                                spec = spec,
                                index = index,
                                total = total,
                                stage = BehaviourEvent.ChallengeStateTick.Stage.DETECTING,
                                remainingSec = remSec,
                                detail = if (wrongDetected) "Incorrect motion" else "Waiting for action"
                            )
                        )
                    }
                }
            }

            // Phase 3: Terminal State & Observation
            val finalLatencyMs = System.currentTimeMillis() - responseStart
            val outcome = when {
                spec is ChallengeSpec.RemainStill -> if (!wrongDetected) BehavioralOutcome.MATCH else BehavioralOutcome.WRONG
                isMatched -> BehavioralOutcome.MATCH
                wrongDetected -> BehavioralOutcome.WRONG
                else -> BehavioralOutcome.TIMEOUT
            }

            val rawObs = BehavioralRunner.observe(
                requested = spec,
                startedAtMs = responseStart,
                nowMs = System.currentTimeMillis(),
                contaminationFlag = latestContaminationFlag,
            )
            val observation = rawObs.copy(
                observed = outcome,
                latencyMs = finalLatencyMs,
                success = (outcome == BehavioralOutcome.MATCH)
            )

            val terminalStage = when (outcome) {
                BehavioralOutcome.MATCH -> BehaviourEvent.ChallengeStateTick.Stage.ACTION_MATCHED
                BehavioralOutcome.WRONG -> BehaviourEvent.ChallengeStateTick.Stage.WRONG
                else -> BehaviourEvent.ChallengeStateTick.Stage.TIMEOUT
            }
            _events.emit(
                BehaviourEvent.ChallengeStateTick(
                    spec = spec,
                    index = index,
                    total = total,
                    stage = terminalStage,
                    remainingSec = 0f,
                    detail = outcome.name
                )
            )
            _events.emit(BehaviourEvent.Observation(observation))

            // Phase 4: Visible result display period (~1.5s) before next challenge
            if (index < total - 1) {
                delay(RESULT_DISPLAY_MS)
            }
        }
        _events.emit(BehaviourEvent.AllComplete)
    }
}
