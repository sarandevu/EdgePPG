package com.edgeppg.app.rppg.cfrppg

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stage 4b — Pure-Kotlin CFrPPG (Remote Photoplethysmography Correspondence Feature) Engine.
 *
 * Paper Reference:
 * Si-Qi Liu, Xiangyuan Lan, Pong C. Yuen.
 * "Remote Photoplethysmography Correspondence Feature for 3D Mask Face Presentation Attack Detection."
 * European Conference on Computer Vision (ECCV), 2018.
 *
 * Multi-Channel Reference:
 * Si-Qi Liu, Xiangyuan Lan, Pong C. Yuen.
 * "Multi-Channel Remote Photoplethysmography Correspondence Feature for 3D Mask Face Presentation Attack Detection."
 * IEEE Transactions on Information Forensics and Security (TIFS), Vol. 16, 2021.
 *
 * Evaluates spatial and spectral consensus across 3 anatomical facial ROIs:
 *  - ROI 0: Forehead
 *  - ROI 1: Left Cheek
 *  - ROI 2: Right Cheek
 *
 * Living vascular tissue exhibits synchronized spectral peaks at the cardiac rate across all 3 ROIs.
 * In contrast, 3D masks, printed photos, or screen replays lack physiological arterial pulse flow,
 * yielding fragmented, noise-dominated, or low correspondence.
 *
 * Allocates ZERO objects on the hot path (reusable pre-allocated arrays).
 */
class CfrppgEngine(
    val windowSize: Int = 256,
    val fs: Float = 30f,
    val fLo: Float = 0.7f,
    val fHi: Float = 4.0f,
    val nRois: Int = 3,
) {
    // Cardiac band bins: 0.7 Hz .. 4.0 Hz at 30 fps, 256-pt FFT
    // bin = f * 256 / 30 -> bin 6 (0.703 Hz) to bin 34 (3.984 Hz)
    val kLo = 6
    val kHi = 34
    val nBins = kHi - kLo + 1

    // FFT twiddle & bit-reversal tables
    private val cosTab = FloatArray(windowSize / 2)
    private val sinTab = FloatArray(windowSize / 2)
    private val revTab = IntArray(windowSize)

    // Pre-allocated hot-path buffers (zero allocations during compute)
    private val re = FloatArray(windowSize)
    private val im = FloatArray(windowSize)
    private val rawPsd = Array(nRois) { FloatArray(nBins) }
    private val normPsd = Array(nRois) { FloatArray(nBins) }
    private val template = FloatArray(nBins)
    private val rawTemplate = FloatArray(nBins)
    private val scores = FloatArray(nRois)

    @Volatile var latestSnapshot: CfrppgSnapshot = CfrppgSnapshot.standby()
        private set

    init {
        val bits = log2i(windowSize)
        for (i in 0 until windowSize) {
            var r = 0
            for (b in 0 until bits) {
                r = r or (((i shr b) and 1) shl (bits - 1 - b))
            }
            revTab[i] = r
        }
        for (i in 0 until windowSize / 2) {
            val a = -2.0 * Math.PI * i / windowSize
            cosTab[i] = cos(a).toFloat()
            sinTab[i] = sin(a).toFloat()
        }
    }

    /**
     * Compute CFrPPG correspondence feature from filtered rPPG signals across ROIs.
     *
     * @param filt Row-major buffer of filtered signals `[t * nRois + r]`
     * @param nSamples Number of valid samples in current window
     * @param badStreak Count of consecutive motion spike frames
     */
    fun computeFromFilteredRois(
        filt: FloatArray,
        nSamples: Int,
        badStreak: Int = 0
    ): CfrppgSnapshot {
        val startUs = System.nanoTime() / 1000L

        if (nSamples < 45 || badStreak > 8) {
            val status = if (badStreak > 8) "MOTION_CONTAMINATED" else "INSUFFICIENT_SAMPLES"
            val snap = CfrppgSnapshot.unavailable(status)
            latestSnapshot = snap
            return snap
        }

        // 1. Compute FFT & cardiac power spectrum for each ROI
        for (r in 0 until nRois) {
            // Apply Hann window and copy into FFT real buffer; zero imaginary
            for (i in 0 until nSamples) {
                val w = 0.5f * (1f - cos(2.0 * Math.PI * i / (nSamples - 1)).toFloat())
                re[i] = filt[i * nRois + r] * w
                im[i] = 0f
            }
            // Zero-padding up to windowSize
            for (i in nSamples until windowSize) {
                re[i] = 0f
                im[i] = 0f
            }

            fftRadix2InPlace(re, im)

            var meanPower = 0f
            for (k in kLo..kHi) {
                val p = re[k] * re[k] + im[k] * im[k]
                val binIdx = k - kLo
                rawPsd[r][binIdx] = p
                meanPower += p
            }
            meanPower /= nBins

            // Noise-aware centering & L2 normalization per ROI
            var sumSq = 0f
            for (binIdx in 0 until nBins) {
                val centered = rawPsd[r][binIdx] - meanPower
                normPsd[r][binIdx] = centered
                sumSq += centered * centered
            }
            val l2Norm = sqrt(sumSq)
            val invNorm = if (l2Norm > 1e-9f) 1f / l2Norm else 0f
            for (binIdx in 0 until nBins) {
                normPsd[r][binIdx] *= invNorm
            }
        }

        // 2. Compute consensus template T(f) across ROIs
        var templateSumSq = 0f
        for (binIdx in 0 until nBins) {
            var sum = 0f
            var rawSum = 0f
            for (r in 0 until nRois) {
                sum += normPsd[r][binIdx]
                rawSum += rawPsd[r][binIdx]
            }
            val avg = sum / nRois
            template[binIdx] = avg
            rawTemplate[binIdx] = rawSum / nRois
            templateSumSq += avg * avg
        }

        val templateNorm = sqrt(templateSumSq)
        val invTemplateNorm = if (templateNorm > 1e-9f) 1f / templateNorm else 0f
        for (binIdx in 0 until nBins) {
            template[binIdx] *= invTemplateNorm
        }

        // 3. Compute correspondence scores c_k = <normPsd_k, template>
        var meanCorr = 0f
        var minCorr = 1f
        for (r in 0 until nRois) {
            var dot = 0f
            for (binIdx in 0 until nBins) {
                dot += normPsd[r][binIdx] * template[binIdx]
            }
            val score = dot.coerceIn(-1f, 1f)
            scores[r] = score
            meanCorr += score
            if (score < minCorr) minCorr = score
        }
        meanCorr /= nRois

        // Variance of correspondence across regions
        var varCorr = 0f
        for (r in 0 until nRois) {
            val diff = scores[r] - meanCorr
            varCorr += diff * diff
        }
        varCorr /= nRois

        // 4. Consensus Heart Rate & SNR
        var kPeak = 0
        var maxRawPower = rawTemplate[0]
        var totalRawPower = rawTemplate[0]
        for (binIdx in 1 until nBins) {
            val p = rawTemplate[binIdx]
            totalRawPower += p
            if (p > maxRawPower) {
                maxRawPower = p
                kPeak = binIdx
            }
        }

        val peakFreqBin = kLo + kPeak
        val consensusHrBpm = peakFreqBin * (fs / windowSize) * 60f

        // SNR in dB (+/- 1 bin around peak vs out-of-band)
        val kL = if (kPeak > 0) kPeak - 1 else 0
        val kR = if (kPeak < nBins - 1) kPeak + 1 else nBins - 1
        val peakPower = rawTemplate[kPeak] + 0.5f * (rawTemplate[kL] + rawTemplate[kR])
        val noisePower = (totalRawPower - peakPower).coerceAtLeast(1e-9f)
        val snrRatio = peakPower / noisePower
        val consensusSnrDb = (10f * (ln(snrRatio.coerceAtLeast(1e-4f)) / ln(10f))).toFloat()

        // 5. Spoof / 3D Mask Risk Classification
        // Decoupled validity: Divergent multi-ROI signals (3D mask attacks) set isValid = false
        // so they are never falsely reported as a "VALID CONSENSUS".
        val (risk, quality, isValid) = when {
            meanCorr >= 0.70f && minCorr >= 0.55f && varCorr <= 0.05f && consensusSnrDb >= -2.0f -> {
                Triple("LOW", "VALID", true)
            }
            meanCorr >= 0.50f && minCorr >= 0.35f && consensusSnrDb >= -4.0f -> {
                Triple("MEDIUM", "INTERMEDIATE", true)
            }
            totalRawPower < 1e-5f -> {
                Triple("INCONCLUSIVE", "POOR_SNR_OR_MOTION", false)
            }
            else -> {
                // Multi-ROI anatomical arterial pulse agreement failed (uncorrelated 3D mask / attack noise)
                Triple("HIGH_SUSPECTED_MASK", "DIVERGENT", false)
            }
        }

        val latencyUs = (System.nanoTime() / 1000L) - startUs
        val snapshot = CfrppgSnapshot(
            isValid = isValid,
            meanCorrespondence = meanCorr,
            minCorrespondence = minCorr,
            varianceCorrespondence = varCorr,
            regionScores = floatArrayOf(scores[0], scores[1], scores[2]),
            consensusHrBpm = consensusHrBpm,
            consensusSnrDb = consensusSnrDb,
            maskSpoofRisk = risk,
            qualityStatus = quality,
            latencyUs = latencyUs
        )

        latestSnapshot = snapshot
        return snapshot
    }

    /**
     * Compute CFrPPG from separate 1D float arrays per ROI.
     * Useful for direct multi-channel array input and unit tests.
     */
    fun computeFromMultiRoiArrays(
        roiSignals: Array<FloatArray>,
        badStreak: Int = 0
    ): CfrppgSnapshot {
        if (roiSignals.size < nRois) return CfrppgSnapshot.unavailable("INVALID_ROI_COUNT")
        val nSamples = roiSignals[0].size
        val filt = FloatArray(nSamples * nRois)
        for (i in 0 until nSamples) {
            for (r in 0 until nRois) {
                filt[i * nRois + r] = roiSignals[r][i]
            }
        }
        return computeFromFilteredRois(filt, nSamples, badStreak)
    }

    private fun fftRadix2InPlace(re: FloatArray, im: FloatArray) {
        val n = re.size
        for (i in 0 until n) {
            val j = revTab[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            var i = 0
            while (i < n) {
                var j = 0
                while (j < len / 2) {
                    val k = j * step
                    val wr = cosTab[k]
                    val wi = sinTab[k]
                    val ur = re[i + j]
                    val ui = im[i + j]
                    val vr = re[i + j + len / 2] * wr - im[i + j + len / 2] * wi
                    val vi = re[i + j + len / 2] * wi + im[i + j + len / 2] * wr
                    re[i + j] = ur + vr
                    im[i + j] = ui + vi
                    re[i + j + len / 2] = ur - vr
                    im[i + j + len / 2] = ui - vi
                    j++
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun log2i(n: Int): Int {
        var r = 0
        var x = n
        while (x > 1) { x = x shr 1; r++ }
        return r
    }
}
