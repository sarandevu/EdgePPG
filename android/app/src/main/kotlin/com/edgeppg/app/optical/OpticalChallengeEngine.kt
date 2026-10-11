package com.edgeppg.app.optical

import android.os.Handler
import android.os.Looper
import com.edgeppg.app.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage 5 / Stage 15 — Optical Challenge-Response Coordinator Engine.
 *
 * Coordinates the full closed-loop visual challenge:
 *  1. Generates session-unique randomized color sequences bound to the session nonce.
 *  2. Drives the [OpticalFlashOverlay] view with smooth, non-strobing stimulus transitions.
 *  3. Ingests timestamped facial ROI frame samples from [CameraSession].
 *  4. Executes the [OpticalResponseAnalyzer] to produce [OpticalChallengeResult].
 *  5. Feeds [OpticalChallengeResult.opticalResponseScore] into the frozen 28-feature schema.
 */
class OpticalChallengeEngine {

    enum class State {
        IDLE,
        ARMED,
        BASELINE,
        STIMULUS_ACTIVE,
        ANALYZING,
        COMPLETED,
        CANCELLED,
        ERROR
    }

    private val analyzer = OpticalResponseAnalyzer()
    private val isExecuting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var state: State = State.IDLE
        private set

    @Volatile var currentStep: OpticalStep? = null
        private set

    @Volatile var latestResult: OpticalChallengeResult = OpticalChallengeResult.standby()
        private set

    @Volatile private var currentSequence: OpticalStimulusSequence? = null
    @Volatile private var sequenceStartTimeMs: Long = 0L

    /**
     * Arm the optical challenge for a new session.
     *
     * @param sessionId unique session ID
     * @param seedHex session nonce seed
     */
    fun arm(sessionId: String, seedHex: String) {
        analyzer.clear()
        val seq = OpticalStimulusSequence.generate(sessionId, seedHex)
        currentSequence = seq
        currentStep = null
        latestResult = OpticalChallengeResult.standby()
        state = State.ARMED
        Log.behavior("OpticalChallengeEngine armed for session=$sessionId steps=${seq.steps.size} duration=${seq.totalDurationMs}ms")
    }

    /**
     * Feed incoming camera frame skin ROI means into the analyzer during the active challenge window.
     */
    fun onFrame(timestampMs: Long, rgbMeans: FloatArray, facePresent: Boolean) {
        if (state == State.BASELINE || state == State.STIMULUS_ACTIVE) {
            analyzer.addSample(timestampMs, rgbMeans, facePresent)
        }
    }

    /**
     * Execute the visual stimulus sequence using coroutines and drive the overlay view.
     *
     * @param overlay UI overlay view for rendering controlled stimulus colors
     * @param onStepChanged optional listener called at each stimulus transition
     */
    suspend fun execute(
        overlay: OpticalFlashOverlay?,
        onStepChanged: ((OpticalStep) -> Unit)? = null
    ): OpticalChallengeResult {
        if (!isExecuting.compareAndSet(false, true)) {
            Log.error("optical", "OpticalChallengeEngine: Execution already in flight, ignoring duplicate call")
            return latestResult
        }

        val seq = currentSequence
        if (seq == null) {
            isExecuting.set(false)
            state = State.ERROR
            return OpticalChallengeResult.inconclusive(reason = "NOT_ARMED")
        }

        try {
            state = State.BASELINE
            sequenceStartTimeMs = System.currentTimeMillis()
            Log.behavior("OpticalChallengeEngine: starting sequence (${seq.steps.size} steps, ${seq.totalDurationMs}ms)")

            for (step in seq.steps) {
                if (state == State.CANCELLED) break
                currentStep = step
                state = if (step.isBaseline) State.BASELINE else State.STIMULUS_ACTIVE

                // Render stimulus on main thread
                withContext(Dispatchers.Main) {
                    if (step.isBaseline) {
                        overlay?.cancel()
                    } else {
                        overlay?.showFlash(step.color, step.deltaPct, step.durationMs)
                    }
                    onStepChanged?.invoke(step)
                }

                // Dwell for step duration
                delay(step.durationMs)
            }

            // Restore overlay
            withContext(Dispatchers.Main) {
                overlay?.cancel()
                currentStep = null
            }

            if (state == State.CANCELLED) {
                latestResult = OpticalChallengeResult.inconclusive(
                    sessionId = seq.sessionId,
                    reason = "USER_CANCELLED",
                    reasons = listOf("user-cancelled-optical-test")
                )
                return latestResult
            }

            // Analysis Phase
            state = State.ANALYZING
            delay(120L) // Wait for final trailing camera frame buffer flush

            val result = analyzer.analyze(seq, sequenceStartTimeMs)
            latestResult = result
            state = State.COMPLETED
            Log.behavior("OpticalChallengeEngine: analysis complete valid=${result.isValid} score=${result.opticalResponseScore} lag=${result.timingErrorMs}ms corr=${result.correlation}")
            return result

        } catch (t: Throwable) {
            Log.error("optical", "Optical challenge execution failed", t)
            withContext(Dispatchers.Main) {
                overlay?.cancel()
            }
            state = State.ERROR
            latestResult = OpticalChallengeResult.inconclusive(
                sessionId = seq.sessionId,
                reason = "EXECUTION_ERROR",
                reasons = listOf(t.message ?: "unknown-error")
            )
            return latestResult
        } finally {
            isExecuting.set(false)
        }
    }

    /**
     * Cancel an active challenge and restore overlay view. Safe from any thread.
     */
    fun cancel(overlay: OpticalFlashOverlay? = null) {
        state = State.CANCELLED
        mainHandler.post {
            overlay?.cancel()
        }
        analyzer.clear()
        currentStep = null
    }

    /**
     * Reset engine at session boundary.
     */
    fun reset(overlay: OpticalFlashOverlay? = null) {
        cancel(overlay)
        state = State.IDLE
        currentSequence = null
        currentStep = null
        latestResult = OpticalChallengeResult.standby()
    }
}
