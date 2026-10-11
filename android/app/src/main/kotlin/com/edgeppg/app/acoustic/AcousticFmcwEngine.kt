package com.edgeppg.app.acoustic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.edgeppg.app.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-Duplex FMCW Acoustic Sensing Engine for Android.
 *
 * Implements Phase 1-5 of the EdgePPG Acoustic Sensing Research and Implementation Guide:
 * - Emits near-ultrasonic FMCW chirps (18 - 21 kHz) at safe conservative amplitude (<= 0.30).
 * - Records continuous reflections via [AudioRecord] on a dedicated daemon thread.
 * - Extracts arrival alignment, baseband dechirp, and Range FFT distance/motion.
 * - Emits structured [AcousticSnapshot] observability telemetry.
 * - Completely isolated from the frozen 28-feature biometric schema.
 */
class AcousticFmcwEngine(
    private val context: Context,
    val config: ChirpConfig = ChirpConfig()
) {

    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null

    private val generator = ChirpGenerator(config)

    @Volatile
    var latestSnapshot: AcousticSnapshot = AcousticSnapshot.stopped()
        private set

    private var snapshotListener: ((AcousticSnapshot) -> Unit)? = null

    fun setSnapshotListener(listener: ((AcousticSnapshot) -> Unit)?) {
        this.snapshotListener = listener
    }

    val isActive: Boolean
        get() = isRunning.get()

    /**
     * Starts the full-duplex acoustic sensing pipeline if microphone permissions are granted.
     */
    @Synchronized
    fun start(): Boolean {
        if (isRunning.get()) return true

        // Check RECORD_AUDIO permission safely
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.warn("acoustic", "RECORD_AUDIO permission not granted; acoustic sensing remaining idle")
            latestSnapshot = AcousticSnapshot.unavailable("PERMISSION_DENIED", config.sampleRateHz, config.id)
            snapshotListener?.invoke(latestSnapshot)
            return false
        }

        isRunning.set(true)
        workerThread = Thread({
            runAcousticLoop()
        }, "EdgePPG-AcousticFmcwThread").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
            start()
        }

        Log.stage("acoustic", "Acoustic FMCW sensing engine started (${config.id}, gain=${config.safeGain})")
        return true
    }

    /**
     * Stops audio playback and capture, releasing all hardware resources.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                release()
            }
        } catch (t: Throwable) {
            Log.warn("acoustic", "Error releasing AudioTrack: ${t.message}")
        } finally {
            audioTrack = null
        }

        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        } catch (t: Throwable) {
            Log.warn("acoustic", "Error releasing AudioRecord: ${t.message}")
        } finally {
            audioRecord = null
        }

        workerThread?.interrupt()
        workerThread = null

        latestSnapshot = AcousticSnapshot.stopped()
        snapshotListener?.invoke(latestSnapshot)
        Log.stage("acoustic", "Acoustic FMCW sensing engine stopped cleanly")
    }

    private fun runAcousticLoop() {
        val sampleRate = config.sampleRateHz
        val channelOut = AudioFormat.CHANNEL_OUT_MONO
        val channelIn = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        // 1. Initialize AudioTrack for looped FMCW emission
        val loopedPcm = generator.generateLoopedPlaybackPcm16(repeatCount = 8)
        val trackBufferSize = loopedPcm.size * 2 // in bytes

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelOut)
                .setEncoding(encoding)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(trackBufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.apply {
                write(loopedPcm, 0, loopedPcm.size)
                setLoopPoints(0, loopedPcm.size, -1) // Loop continuously
                setVolume(config.safeGain)
                play()
            }
        } catch (t: Throwable) {
            Log.error("acoustic", "Failed to initialize AudioTrack playback", t)
            latestSnapshot = AcousticSnapshot.unavailable("TRACK_INIT_FAILED", sampleRate, config.id)
            snapshotListener?.invoke(latestSnapshot)
            isRunning.set(false)
            return
        }

        // 2. Initialize AudioRecord for reflection capture
        val minRecordBufSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
        if (minRecordBufSize == AudioRecord.ERROR || minRecordBufSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.error("acoustic", "Invalid AudioRecord buffer configuration")
            latestSnapshot = AcousticSnapshot.unavailable("RECORD_CONFIG_INVALID", sampleRate, config.id)
            snapshotListener?.invoke(latestSnapshot)
            stop()
            return
        }

        val recordBufSize = (minRecordBufSize * 4).coerceAtLeast(config.numSamples * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelIn,
                encoding,
                recordBufSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.error("acoustic", "AudioRecord failed to initialize")
                latestSnapshot = AcousticSnapshot.unavailable("RECORD_INIT_FAILED", sampleRate, config.id)
                snapshotListener?.invoke(latestSnapshot)
                stop()
                return
            }

            audioRecord?.startRecording()
        } catch (t: Throwable) {
            Log.error("acoustic", "SecurityException or error starting AudioRecord", t)
            latestSnapshot = AcousticSnapshot.unavailable("RECORD_START_FAILED", sampleRate, config.id)
            snapshotListener?.invoke(latestSnapshot)
            stop()
            return
        }

        // 3. Continuous DSP Processing Loop
        val readSize = config.numSamples * 2 // Read 2 full chirps of data per iteration
        val rxPcm = ShortArray(readSize)
        val rxFloat = FloatArray(readSize)
        var prevPhase: Float? = null

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            val startTime = System.currentTimeMillis()
            val samplesRead = audioRecord?.read(rxPcm, 0, readSize) ?: -1

            if (samplesRead <= 0) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            val isClipping = AcousticDsp.checkClipping(rxPcm)

            // Convert PCM shorts to normalized [-1, 1] floats
            for (i in 0 until samplesRead) {
                rxFloat[i] = rxPcm[i] / 32768.0f
            }

            // Find chirp start alignment via cross-correlation with reference
            val offset = AcousticDsp.findChirpOffset(rxFloat, generator.referenceFloat, config.numSamples)

            // Process FMCW range and micro-motion
            val dsp = AcousticDsp.processChirp(
                rxSignal = rxFloat,
                offset = offset,
                generator = generator,
                prevPhase = prevPhase,
                isClipping = isClipping
            )

            if (dsp.isValid) {
                prevPhase = dsp.currentPhase
            }

            val latency = System.currentTimeMillis() - startTime

            val snapshot = AcousticSnapshot(
                isValid = dsp.isValid,
                sampleRateHz = sampleRate,
                chirpConfigId = config.id,
                targetRangeCm = dsp.targetRangeCm,
                motionScore = dsp.motionScore,
                snrDb = dsp.snrDb,
                isClipping = dsp.isClipping,
                qualityStatus = dsp.qualityStatus,
                peakBin = dsp.peakBin,
                latencyMs = latency,
                timestampMs = System.currentTimeMillis()
            )

            latestSnapshot = snapshot
            snapshotListener?.invoke(snapshot)

            // Modulate loop pacing to approx 15-20 updates/second to minimize battery impact
            val elapsed = System.currentTimeMillis() - startTime
            val sleepMs = (50 - elapsed).coerceAtLeast(10)
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }
}
