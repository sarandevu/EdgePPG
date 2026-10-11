package com.edgeppg.app.acoustic

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin FMCW Acoustic Signal Processing Engine.
 *
 * Implements Model A (Signal Processing Baseline) as specified in the EdgePPG Acoustic Sensing Guide:
 * 1. Clipping detection & signal validity checking
 * 2. Template cross-correlation for arrival alignment
 * 3. Baseband dechirp (I/Q complex mixing)
 * 4. Windowed Radix-2 Range FFT (2048-point)
 * 5. Target range-bin selection (15 cm - 80 cm KYC inspection corridor)
 * 6. Target SNR calculation
 * 7. Sub-wavelength phase-based micro-motion tracking
 */
object AcousticDsp {

    const val SPEED_OF_SOUND_CM_S = 34300.0 // 343 m/s in cm/s
    const val FFT_SIZE = 2048
    const val MIN_TARGET_RANGE_CM = 15.0f
    const val MAX_TARGET_RANGE_CM = 85.0f
    const val MIN_SNR_THRESHOLD_DB = 6.0f

    data class DspResult(
        val isValid: Boolean,
        val targetRangeCm: Float?,
        val motionScore: Float,
        val snrDb: Float,
        val isClipping: Boolean,
        val qualityStatus: String,
        val peakBin: Int,
        val currentPhase: Float
    )

    /**
     * Checks if the PCM audio buffer exhibits significant clipping (saturation).
     */
    fun checkClipping(pcm: ShortArray, maxThreshold: Short = 32000, maxRatio: Float = 0.01f): Boolean {
        if (pcm.isEmpty()) return false
        var clippedCount = 0
        for (sample in pcm) {
            val absVal = if (sample < 0) -sample.toInt() else sample.toInt()
            if (absVal >= maxThreshold) {
                clippedCount++
            }
        }
        return (clippedCount.toFloat() / pcm.size) > maxRatio
    }

    /**
     * Finds the start index of the chirp in the received signal by computing cross-correlation
     * with the reference template over a sliding window of length [searchLength].
     */
    fun findChirpOffset(
        signal: FloatArray,
        template: FloatArray,
        searchLength: Int = template.size
    ): Int {
        val tLen = template.size
        val maxOffset = (signal.size - tLen).coerceAtMost(searchLength).coerceAtLeast(0)
        if (maxOffset <= 0) return 0

        var bestOffset = 0
        var maxEnergy = -1.0f

        // Energy of template
        var templateNorm = 0.0f
        for (v in template) templateNorm += v * v
        if (templateNorm < 1e-9f) return 0

        for (offset in 0 until maxOffset) {
            var dot = 0.0f
            for (i in 0 until tLen) {
                dot += signal[offset + i] * template[i]
            }
            if (dot > maxEnergy) {
                maxEnergy = dot
                bestOffset = offset
            }
        }
        return bestOffset
    }

    /**
     * Range resolution in centimeters per FFT bin:
     * delta_R = (c * f_s * T) / (2 * B * N_fft)
     */
    fun rangeResolutionCm(config: ChirpConfig, fftSize: Int = FFT_SIZE): Float {
        val tSec = config.durationMs / 1000.0
        val bHz = config.bandwidthHz.toDouble()
        val deltaR = (SPEED_OF_SOUND_CM_S * config.sampleRateHz * tSec) / (2.0 * bHz * fftSize)
        return deltaR.toFloat()
    }

    /**
     * In-place Radix-2 Decimation-In-Time Cooley-Tukey FFT.
     * Expects [real] and [imag] arrays of size N = 2^k.
     */
    fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        require(n and (n - 1) == 0) { "FFT size must be a power of 2, got $n" }

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        // Cooley-Tukey butterfly computations
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * PI / len
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (k in 0 until halfLen) {
                    val pos = i + k + halfLen
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[pos] * wR - imag[pos] * wI
                    val vI = real[pos] * wI + imag[pos] * wR

                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[pos] = uR - vR
                    imag[pos] = uI - vI

                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Executes the complete FMCW signal-processing pipeline on an aligned chirp window:
     * 1. Multiplies with complex conjugate reference (Dechirp).
     * 2. Windows with Hamming taper.
     * 3. Zero-pads to [FFT_SIZE] and computes Range FFT.
     * 4. Selects target range bin and computes SNR and phase micro-motion.
     */
    fun processChirp(
        rxSignal: FloatArray,
        offset: Int,
        generator: ChirpGenerator,
        prevPhase: Float? = null,
        isClipping: Boolean = false
    ): DspResult {
        val numSamples = generator.numSamples
        if (offset + numSamples > rxSignal.size) {
            return DspResult(
                isValid = false,
                targetRangeCm = null,
                motionScore = 0f,
                snrDb = 0f,
                isClipping = isClipping,
                qualityStatus = "TRUNCATED_CHIRP",
                peakBin = 0,
                currentPhase = 0f
            )
        }

        if (isClipping) {
            return DspResult(
                isValid = false,
                targetRangeCm = null,
                motionScore = 0f,
                snrDb = 0f,
                isClipping = true,
                qualityStatus = "CLIPPING",
                peakBin = 0,
                currentPhase = 0f
            )
        }

        // Baseband Dechirping: r(t) * exp(-j * phi(t))
        // Multiplies received signal by reference and applies a 16-sample moving average low-pass
        // filter to eliminate the ~40 kHz carrier sum frequency (A+B) while preserving < 1 kHz beat frequencies (A-B).
        val rawI = FloatArray(numSamples)
        val rawQ = FloatArray(numSamples)

        for (i in 0 until numSamples) {
            val r = rxSignal[offset + i]
            rawI[i] = r * generator.cosReference[i]
            rawQ[i] = r * generator.sinReference[i]
        }

        val lpfWindow = 16
        val realFft = FloatArray(FFT_SIZE)
        val imagFft = FloatArray(FFT_SIZE)

        var sumI = 0.0f
        var sumQ = 0.0f
        for (i in 0 until numSamples) {
            sumI += rawI[i]
            sumQ += rawQ[i]
            if (i >= lpfWindow) {
                sumI -= rawI[i - lpfWindow]
                sumQ -= rawQ[i - lpfWindow]
            }
            val count = (i + 1).coerceAtMost(lpfWindow)
            val filteredI = sumI / count
            val filteredQ = sumQ / count

            // Apply Hamming window to smoothed baseband beat signal
            val ham = (0.54 - 0.46 * cos(2.0 * PI * i / (numSamples - 1))).toFloat()
            realFft[i] = filteredI * ham
            imagFft[i] = filteredQ * ham
        }

        // Compute Range FFT
        fft(realFft, imagFft)

        // Range resolution per bin
        val deltaR = rangeResolutionCm(generator.config, FFT_SIZE)

        val minBin = (MIN_TARGET_RANGE_CM / deltaR).toInt().coerceAtLeast(2)
        val maxBin = (MAX_TARGET_RANGE_CM / deltaR).toInt().coerceAtMost(FFT_SIZE / 8)

        var peakBin = minBin
        var peakPower = -1.0f

        val power = FloatArray(FFT_SIZE / 2)
        for (k in 0 until (FFT_SIZE / 2)) {
            val p = realFft[k] * realFft[k] + imagFft[k] * imagFft[k]
            power[k] = p
            if (k in minBin..maxBin && p > peakPower) {
                peakPower = p
                peakBin = k
            }
        }

        // Compute in-band noise floor immediately adjacent to KYC target window (e.g. bins maxBin+4 .. maxBin+30)
        var noiseSum = 0.0f
        var noiseCount = 0
        val noiseStartBin = maxBin + 4
        val noiseEndBin = (maxBin + 32).coerceAtMost(FFT_SIZE / 4)
        for (k in noiseStartBin until noiseEndBin) {
            noiseSum += power[k]
            noiseCount++
        }
        val noiseFloor = if (noiseCount > 0) noiseSum / noiseCount else 1e-9f
        val snrDb = (10.0 * log10((peakPower / (noiseFloor + 1e-9f)).toDouble())).toFloat().coerceIn(0f, 60f)

        val phase = atan2(imagFft[peakBin], realFft[peakBin])
        var motionScore = 0f
        if (prevPhase != null) {
            // Shortest angular difference between consecutive chirps
            val diff = atan2(sin(phase - prevPhase), cos(phase - prevPhase))
            motionScore = (kotlin.math.abs(diff) / PI).toFloat().coerceIn(0f, 1f)
        }

        val targetRange = peakBin * deltaR
        val isValid = snrDb >= MIN_SNR_THRESHOLD_DB

        val status = if (isValid) "MEASURED" else "LOW_SNR"

        return DspResult(
            isValid = isValid,
            targetRangeCm = if (isValid) targetRange else null,
            motionScore = motionScore,
            snrDb = snrDb,
            isClipping = false,
            qualityStatus = status,
            peakBin = peakBin,
            currentPhase = phase
        )
    }
}
