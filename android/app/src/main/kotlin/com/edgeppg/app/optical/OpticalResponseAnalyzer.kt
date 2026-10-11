package com.edgeppg.app.optical

import com.edgeppg.app.challenge.OpticalColor
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stage 4 / Stage 6 — Optical Challenge-Response Analyzer.
 *
 * Implements real-time closed-loop stimulus cross-correlation and spectral verification:
 * 1. Analyzes temporal facial skin ROI RGB/luminance modulation against known display stimulus.
 * 2. Computes multi-ROI spatial agreement (Forehead, Left Cheek, Right Cheek).
 * 3. Scans cross-correlation lag to estimate sensor-display latency (typically 50..150 ms).
 * 4. Yields [OpticalChallengeResult] with [opticalResponseScore] mapped to the frozen 28-feature schema.
 */
class OpticalResponseAnalyzer {

    data class FrameSample(
        val timestampMs: Long,
        val rgbMeans: FloatArray, // 9 elements: [FH_R, FH_G, FH_B, LCK_R, LCK_G, LCK_B, RCK_R, RCK_G, RCK_B]
        val facePresent: Boolean
    )

    private val samples = mutableListOf<FrameSample>()

    @Synchronized
    fun addSample(timestampMs: Long, rgbMeans: FloatArray, facePresent: Boolean) {
        samples.add(FrameSample(timestampMs, rgbMeans.clone(), facePresent))
    }

    @Synchronized
    fun clear() {
        samples.clear()
    }

    @Synchronized
    fun sampleCount(): Int = samples.size

    /**
     * Compute optical stimulus correlation and spectral response.
     *
     * @param sequence active session stimulus sequence
     * @param sequenceStartMs monotonic start timestamp of the first stimulus step
     */
    @Synchronized
    fun analyze(sequence: OpticalStimulusSequence, sequenceStartMs: Long): OpticalChallengeResult {
        val startUs = System.nanoTime() / 1000L
        val reasonCodes = mutableListOf<String>()

        // 1. Precondition checks
        if (samples.size < 12) {
            reasonCodes.add("insufficient-frames:${samples.size}")
            return OpticalChallengeResult.inconclusive(
                sessionId = sequence.sessionId,
                reason = "INSUFFICIENT_FRAMES",
                reasons = reasonCodes
            )
        }

        val facePresentCount = samples.count { it.facePresent }
        val facePresentRatio = facePresentCount.toFloat() / samples.size
        if (facePresentRatio < 0.60f) {
            reasonCodes.add("face-lost-during-challenge:${"%.2f".format(facePresentRatio)}")
            return OpticalChallengeResult.inconclusive(
                sessionId = sequence.sessionId,
                reason = "POOR_COVERAGE",
                reasons = reasonCodes
            )
        }

        // 2. Baseline extraction (first step in sequence is baseline)
        val baselineStep = sequence.steps.firstOrNull { it.isBaseline } ?: sequence.steps.first()
        val baselineEndMs = sequenceStartMs + baselineStep.durationMs
        val baselineSamples = samples.filter { it.facePresent && it.timestampMs in sequenceStartMs..baselineEndMs }

        val bCount = baselineSamples.size.coerceAtLeast(1)
        var bFhR = 0f; var bFhG = 0f; var bFhB = 0f
        var bLckR = 0f; var bLckG = 0f; var bLckB = 0f
        var bRckR = 0f; var bRckG = 0f; var bRckB = 0f

        for (s in baselineSamples) {
            bFhR += s.rgbMeans[0]; bFhG += s.rgbMeans[1]; bFhB += s.rgbMeans[2]
            bLckR += s.rgbMeans[3]; bLckG += s.rgbMeans[4]; bLckB += s.rgbMeans[5]
            bRckR += s.rgbMeans[6]; bRckG += s.rgbMeans[7]; bRckB += s.rgbMeans[8]
        }
        bFhR /= bCount; bFhG /= bCount; bFhB /= bCount
        bLckR /= bCount; bLckG /= bCount; bLckB /= bCount
        bRckR /= bCount; bRckG /= bCount; bRckB /= bCount

        val baseLumFh = (bFhR + bFhG + bFhB) / 3f
        val baseLumLck = (bLckR + bLckG + bLckB) / 3f
        val baseLumRck = (bRckR + bRckG + bRckB) / 3f
        val baseLumTotal = (baseLumFh + baseLumLck + baseLumRck) / 3f

        if (baseLumTotal < 3.0f || baseLumTotal > 252.0f) {
            reasonCodes.add("ambient-saturation-or-darkness:${"%.1f".format(baseLumTotal)}")
            return OpticalChallengeResult.inconclusive(
                sessionId = sequence.sessionId,
                reason = "LIGHTING_SATURATED_OR_DARK",
                reasons = reasonCodes
            )
        }

        // 3. Step-by-Step Response Extraction & Spectral Verification
        val activeSteps = sequence.steps.filter { !it.isBaseline }
        var currentStepStart = sequenceStartMs + baselineStep.durationMs
        var stepAgreements = 0f
        val roiDeltas = FloatArray(3) // [FH, LCK, RCK]
        var totalResponseMagnitude = 0f
        var totalStepCount = 0

        // Nominal pipeline display-to-sensor latency offset (~80ms)
        val assumedLagMs = 80L

        for (step in activeSteps) {
            val stepStartMs = currentStepStart
            val stepEndMs = stepStartMs + step.durationMs
            currentStepStart = stepEndMs

            val windowSamples = samples.filter {
                it.facePresent && it.timestampMs in (stepStartMs + assumedLagMs)..(stepEndMs + assumedLagMs)
            }
            if (windowSamples.isEmpty()) continue

            val wCount = windowSamples.size
            var sFhR = 0f; var sFhG = 0f; var sFhB = 0f
            var sLckR = 0f; var sLckG = 0f; var sLckB = 0f
            var sRckR = 0f; var sRckG = 0f; var sRckB = 0f

            for (s in windowSamples) {
                sFhR += s.rgbMeans[0]; sFhG += s.rgbMeans[1]; sFhB += s.rgbMeans[2]
                sLckR += s.rgbMeans[3]; sLckG += s.rgbMeans[4]; sLckB += s.rgbMeans[5]
                sRckR += s.rgbMeans[6]; sRckG += s.rgbMeans[7]; sRckB += s.rgbMeans[8]
            }
            sFhR /= wCount; sFhG /= wCount; sFhB /= wCount
            sLckR /= wCount; sLckG /= wCount; sLckB /= wCount
            sRckR /= wCount; sRckG /= wCount; sRckB /= wCount

            val dFhR = sFhR - bFhR; val dFhG = sFhG - bFhG; val dFhB = sFhB - bFhB
            val dLckR = sLckR - bLckR; val dLckG = sLckG - bLckG; val dLckB = sLckB - bLckB
            val dRckR = sRckR - bRckR; val dRckG = sRckG - bRckG; val dRckB = sRckB - bRckB

            val dR = (dFhR + dLckR + dRckR) / 3f
            val dG = (dFhG + dLckG + dRckG) / 3f
            val dB = (dFhB + dLckB + dRckB) / 3f
            val dLum = (dR + dG + dB) / 3f

            roiDeltas[0] += (dFhR + dFhG + dFhB) / 3f
            roiDeltas[1] += (dLckR + dLckG + dLckB) / 3f
            roiDeltas[2] += (dRckR + dRckG + dRckB) / 3f
            totalResponseMagnitude += dLum
            totalStepCount++

            // Verify spectral shift matches requested stimulus color
            val spectralMatch = when (step.color) {
                OpticalColor.WARM -> (dR > dB) && (dLum > 0.05f)
                OpticalColor.COOL -> (dB > dR) && (dLum > 0.05f)
                OpticalColor.WHITE -> (dLum > 0.10f)
            }
            if (spectralMatch) {
                stepAgreements += 1.0f
            } else if (dLum > 0.02f) {
                stepAgreements += 0.5f // Positive luminance response but weak chromatic shift
            }
        }

        val sequenceScore = if (totalStepCount > 0) (stepAgreements / totalStepCount) else 0f
        val avgMagnitude = if (totalStepCount > 0) (totalResponseMagnitude / totalStepCount) else 0f
        if (totalStepCount > 0) {
            roiDeltas[0] /= totalStepCount
            roiDeltas[1] /= totalStepCount
            roiDeltas[2] /= totalStepCount
        }

        // 4. Waveform Cross-Correlation & Latency Estimation
        // Build expected stimulus waveform S(t) and observed facial skin luminance curve Y(t)
        var bestCorrelation = 0f
        var bestLagMs = 80L

        // Scan candidate lags: 30 ms to 180 ms in 15 ms increments
        val candidateLags = (30L..180L step 15L).toList()
        for (lag in candidateLags) {
            var sumS = 0.0; var sumY = 0.0
            var validPts = 0

            val pairedPoints = mutableListOf<Pair<Double, Double>>()
            for (s in samples) {
                if (!s.facePresent) continue
                val effectiveTime = s.timestampMs - lag
                val activeStep = sequence.steps.firstOrNull { step ->
                    val stepStart = sequenceStartMs + sequence.steps.take(step.stepIndex).sumOf { it.durationMs }
                    val stepEnd = stepStart + step.durationMs
                    effectiveTime in stepStart..stepEnd
                }

                val expectedVal = if (activeStep != null && !activeStep.isBaseline) activeStep.deltaPct.toDouble() else 0.0
                val obsLum = ((s.rgbMeans[0] + s.rgbMeans[1] + s.rgbMeans[2] +
                               s.rgbMeans[3] + s.rgbMeans[4] + s.rgbMeans[5] +
                               s.rgbMeans[6] + s.rgbMeans[7] + s.rgbMeans[8]) / 9.0) - baseLumTotal

                pairedPoints.add(Pair(expectedVal, obsLum))
                sumS += expectedVal
                sumY += obsLum
                validPts++
            }

            if (validPts >= 10) {
                val meanS = sumS / validPts
                val meanY = sumY / validPts
                var num = 0.0; var denS = 0.0; var denY = 0.0
                for ((sv, yv) in pairedPoints) {
                    val ds = sv - meanS
                    val dy = yv - meanY
                    num += ds * dy
                    denS += ds * ds
                    denY += dy * dy
                }
                val den = sqrt(denS * denY)
                val r = if (den > 1e-6) (num / den).toFloat() else 0f
                if (r > bestCorrelation) {
                    bestCorrelation = r
                    bestLagMs = lag
                }
            }
        }

        // 5. Multi-ROI Spatial Consistency
        val roiMean = (roiDeltas[0] + roiDeltas[1] + roiDeltas[2]) / 3f
        val roiVar = ((roiDeltas[0] - roiMean) * (roiDeltas[0] - roiMean) +
                      (roiDeltas[1] - roiMean) * (roiDeltas[1] - roiMean) +
                      (roiDeltas[2] - roiMean) * (roiDeltas[2] - roiMean)) / 3f
        val spatialConsistency = (1.0f - (sqrt(roiVar) / (abs(roiMean) + 0.1f))).coerceIn(0f, 1f)

        // 6. Final Composite Optical Response Score
        // Weightings: 45% waveform correlation, 35% sequence step agreement, 20% spatial consistency
        val rawScore = (0.45f * bestCorrelation.coerceIn(0f, 1f) +
                        0.35f * sequenceScore +
                        0.20f * spatialConsistency).coerceIn(0f, 1f)

        val isValid = (bestCorrelation >= 0.25f || sequenceScore >= 0.50f) && (avgMagnitude >= 0.04f)
        val finalScore = if (isValid) rawScore else 0.05f

        if (isValid) {
            reasonCodes.add("optical-stimulus-verified:corr=${"%.2f".format(bestCorrelation)}|seq=${"%.2f".format(sequenceScore)}")
        } else {
            reasonCodes.add("low-optical-reflectance:mag=${"%.2f".format(avgMagnitude)}|corr=${"%.2f".format(bestCorrelation)}")
        }

        val latencyUs = (System.nanoTime() / 1000L) - startUs
        return OpticalChallengeResult(
            sessionId = sequence.sessionId,
            isValid = isValid,
            opticalResponseScore = finalScore,
            timingErrorMs = bestLagMs,
            sequenceScore = sequenceScore,
            correlation = bestCorrelation,
            roiCoverage = floatArrayOf(roiDeltas[0], roiDeltas[1], roiDeltas[2]),
            responseMagnitude = avgMagnitude,
            qualityStatus = if (isValid) "VALID" else "UNSYNCHRONIZED_OR_WEAK",
            reasonCodes = reasonCodes,
            latencyUs = latencyUs
        )
    }
}
