package com.edgeppg.app.capture

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.RggbChannelVector
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.edgeppg.app.Log
import com.edgeppg.app.challenge.ChallengeSpec
import com.edgeppg.app.challenge.OpticalColor
import com.edgeppg.app.quality.QualityGate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Stage 2 — CameraX acquisition with converge-then-lock.
 *
 * Binds:
 *  - `Preview` at 1080p for the on-screen surface.
 *  - `ImageAnalysis` at 1080p, `STRATEGY_KEEP_ONLY_LATEST`, output
 *    format `YUV_420_888`, on a single-threaded analyzer executor.
 *  - On the `ImageAnalysis` builder, a `Camera2Interop.Extender`
 *    sets (1) `CONTROL_AE_LOCK` / `CONTROL_AWB_LOCK` when locked, and
 *    (2) a `setSessionCaptureCallback` callback that handles both AE/AWB
 *    convergence detection AND exposure / sensitivity / AWB-gain
 *    telemetry. CameraX 1.4.1 allows exactly one session-capture
 *    callback per use-case, so the two responsibilities share a
 *    single callback.
 *
 * AE/AWB lock fires AFTER convergence (`ConvergenceLockController`)
 * and is fed back here to trigger a rebind with the locks set.
 *
 * Threading:
 *  - All CameraX binding happens on the main thread (lifecycleOwner).
 *  - The analyzer runs on `analysisExecutor` (single thread).
 *  - `STRATEGY_KEEP_ONLY_LATEST` ensures intermediate frames are
 *    dropped if our analyzer is slow.
 *
 * Important: `Camera2CameraInfo.addSessionCaptureCallback` does NOT
 * exist in CameraX 1.4.1 (the `add*` form lives on the internal
 * `CameraInfoInternal`). The Camera2 capture callback is set on the
 * use-case via `Camera2Interop.Extender` instead.
 */
class CameraSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val listener: FrameListener,
    private val roiTracker: RoiTracker = RoiTracker(),
    private val qualityGate: QualityGate = QualityGate(),
    private val phoneDetector: PhoneScreenDetector = PhoneScreenDetector(),
) : FrameListener {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private val analysisExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "EdgePPG-CameraX").apply { isDaemon = true }
        }
    private val lockController = ConvergenceLockController { locked ->
        listener.onLockStateChanged(locked)
    }

    // Frame counter for diagnostics.
    @Volatile private var frameNumber: Long = 0

    // Whether a face has been seen at least once since the last lock.
    @Volatile private var facePresent = false

    // Whether the analysis stage is currently locked.
    @Volatile private var locked = false

    private val framesInWindow = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastFps = 0.0

    @Volatile private var lastExposureNs: Long? = null
    @Volatile private var lastSensitivityIso: Int? = null
    @Volatile private var lastAwbR: Float? = null
    @Volatile private var lastAwbG: Float? = null
    @Volatile private var lastAwbB: Float? = null

    @Volatile var panCardOcr: com.edgeppg.app.document.PanCardOcr? = null
    @Volatile var isDocumentMode: Boolean = false

    fun suppressPhoneDetector() {
        phoneDetector.suppressForDocument()
    }

    fun start(previewView: PreviewView? = null) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindUseCases(locked = false, previewView = previewView)
            } catch (t: Throwable) {
                com.edgeppg.app.Log.error("capture",
                    "ProcessCameraProvider failed", t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
            // non-fatal
        }
        analysisExecutor.shutdown()
        lockController.reset()
        qualityGate.reset()
        phoneDetector.reset()
        frameNumber = 0
        lastExposureNs = null
        lastSensitivityIso = null
        lastAwbR = null
        lastAwbG = null
        lastAwbB = null
    }

    // Optical closed-loop measurement state
    @Volatile var opticalEngine: com.edgeppg.app.optical.OpticalChallengeEngine? = null
    @Volatile private var opticalFlashActive: Boolean = false
    @Volatile private var opticalFlashSpec: ChallengeSpec.OpticalFlash? = null
    private var preFlashR = 0f
    private var preFlashG = 0f
    private var preFlashB = 0f
    private var flashSampleCount = 0
    private var flashSumR = 0f
    private var flashSumG = 0f
    private var flashSumB = 0f

    /**
     * Clear pipeline caches and reset state at session boundary.
     */
    fun reset() {
        roiTracker.reset()
        phoneDetector.reset()
        lockController.reset()
        qualityGate.reset()
        facePresent = false
        frameNumber = 0L
        locked = false
        opticalFlashActive = false
        opticalFlashSpec = null
        preFlashR = 0f
        preFlashG = 0f
        preFlashB = 0f
        flashSampleCount = 0
        flashSumR = 0f
        flashSumG = 0f
        flashSumB = 0f
        lastExposureNs = null
        lastSensitivityIso = null
        lastAwbR = null
        lastAwbG = null
        lastAwbB = null
    }

    /**
     * Arm closed-loop optical reflectance verification during flash stimulus.
     */
    fun startOpticalMeasurement(spec: ChallengeSpec.OpticalFlash) {
        opticalFlashSpec = spec
        flashSampleCount = 0
        flashSumR = 0f
        flashSumG = 0f
        flashSumB = 0f
        opticalFlashActive = true
    }

    /**
     * Conclude flash window, verify spectral shift on face ROI, and compute response score.
     */
    fun endOpticalMeasurement(): Float {
        val engineResult = opticalEngine?.latestResult
        if (engineResult != null && engineResult.isValid) {
            val score = engineResult.opticalResponseScore
            listener.onOpticalResponse(score)
            return score
        }

        val spec = opticalFlashSpec
        opticalFlashActive = false
        opticalFlashSpec = null

        if (spec == null || !facePresent || flashSampleCount == 0 || preFlashR == 0f) {
            return Float.NaN
        }

        val avgFlashR = flashSumR / flashSampleCount
        val avgFlashG = flashSumG / flashSampleCount
        val avgFlashB = flashSumB / flashSampleCount

        val dR = avgFlashR - preFlashR
        val dG = avgFlashG - preFlashG
        val dB = avgFlashB - preFlashB
        val dLum = (dR + dG + dB) / 3f

        val spectralMatch = when (spec.color) {
            OpticalColor.WHITE -> dLum > 0.3f
            OpticalColor.WARM -> dR > dB && dLum > 0.2f
            OpticalColor.COOL -> dB > dR && dLum > 0.2f
        }

        val score = if (spectralMatch) {
            (dLum / 12f).coerceIn(0.25f, 0.95f)
        } else {
            0.05f
        }
        listener.onOpticalResponse(score)
        return score
    }

    private fun bindUseCases(locked: Boolean, previewView: PreviewView?) {
        val provider = cameraProvider ?: return
        com.edgeppg.app.Log.stage("capture",
            "bindUseCases(locked=$locked) on ${Thread.currentThread().name}")
        provider.unbindAll()

        val selector = CameraSelector.DEFAULT_FRONT_CAMERA

        val preview = Preview.Builder()
            .setTargetResolution(Size(TARGET_WIDTH, TARGET_HEIGHT))
            .also { builder ->
                if (locked) {
                    Camera2Interop.Extender(builder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_LOCK, true
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AWB_LOCK, true
                        )
                }
            }
            .build()
        // Bind the preview surface. CameraX 1.4 exposes the
        // SurfaceProvider via the `surfaceProvider` Kotlin property on
        // PreviewView (mapped from `getSurfaceProvider()`).
        previewView?.let { preview.setSurfaceProvider(it.surfaceProvider) }

        // The `ImageAnalysis` builder carries the single combined
        // Camera2 capture callback (convergence + telemetry). The
        // callback's `onConverged` lambda rebinds with locks set.
        // It MUST run on the main thread: bindToLifecycle/unbindAll
        // are lifecycle operations, and the session callback arrives
        // on a CameraX internal executor. Running the rebind
        // off-main risks an exception after unbindAll() has already
        // torn the session down — leaving the camera stuck with no
        // use cases bound (black/frozen preview until restart).
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val sessionCallback = buildSessionCaptureCallback {
            mainExecutor.execute {
                try {
                    val cam = camera
                    if (cam != null) {
                        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
                        val captureOptions = CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                            .build()
                        camera2Control.setCaptureRequestOptions(captureOptions)
                        this.locked = true
                        listener.onLockStateChanged(true)
                        com.edgeppg.app.Log.stage("capture", "Dynamic AE/AWB lock applied via Camera2CameraControl")
                    }
                } catch (t: Throwable) {
                    com.edgeppg.app.Log.error("capture", "dynamic AE/AWB lock failed", t)
                }
            }
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(TARGET_WIDTH, TARGET_HEIGHT))
            .setBackpressureStrategy(
                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            )
            .setOutputImageFormat(
                ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
            )
            .also { builder ->
                val ext = Camera2Interop.Extender(builder)
                    .setSessionCaptureCallback(sessionCallback)
                if (locked) {
                    ext
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_LOCK, true
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AWB_LOCK, true
                        )
                }
            }
            .build()
        analysis.setAnalyzer(analysisExecutor, ::analyzeFrame)

        camera = provider.bindToLifecycle(
            lifecycleOwner, selector, preview, analysis
        )
        com.edgeppg.app.Log.stage("capture",
            "bound OK (locked=$locked) on ${Thread.currentThread().name}")
    }

    /**
     * Build the single combined session-capture callback: it
     * delegates convergence state to [ConvergenceLockController] and
     * records exposure / sensitivity / AWB-gain telemetry for the
     * Quality Gate. The two responsibilities are colocated because
     * CameraX 1.4.1 allows exactly one `setSessionCaptureCallback`
     * per use-case.
     */
    private fun buildSessionCaptureCallback(onConverged: () -> Unit):
        CameraCaptureSession.CaptureCallback {
        // Diagnostic counter: proves whether CameraX ever invokes
        // this callback on the device. Logs the first 3 invocations
        // with AE/AWB state, then every 300th. Remove after the
        // first-launch convergence question is answered.
        val callbackCount = java.util.concurrent.atomic.AtomicInteger(0)
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val n = callbackCount.incrementAndGet()
                if (n <= 3 || n % 300 == 0) {
                    val aeDbg = result.get(CaptureResult.CONTROL_AE_STATE)
                    val awbDbg = result.get(CaptureResult.CONTROL_AWB_STATE)
                    com.edgeppg.app.Log.stage("capture",
                        "sessionCb #$n ae=$aeDbg awb=$awbDbg on ${Thread.currentThread().name}")
                }
                // Telemetry: SENSOR_EXPOSURE_TIME / SENSOR_SENSITIVITY /
                // COLOR_CORRECTION_GAINS. The `gains` value is an
                // `android.hardware.camera2.R<FloatArray>`; we convert
                // it to a plain FloatArray via `toFloatArray()` to use
                // Telemetry: SENSOR_EXPOSURE_TIME / SENSOR_SENSITIVITY /
                // COLOR_CORRECTION_GAINS. The `R<T>` wrapper class is
                // hidden from the public SDK (it is `@hide`); the
                // documented public API is to use the typed key
                // `Key<RggbChannelVector>` directly, which gives us
                // `RggbChannelVector` with `getRed()`, `getGreenEven()`,
                // `getBlue()` accessors.
                lastExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                lastSensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                if (gains != null) {
                    lastAwbR = gains.red
                    lastAwbG = (gains.greenEven + gains.greenOdd) / 2f
                    lastAwbB = gains.blue
                } else {
                    lastAwbR = null
                    lastAwbG = null
                    lastAwbB = null
                }
                // Convergence: delegate the same `result` to the
                // lock controller, which updates internal state and
                // calls `onConverged()` (the lambda passed in) when
                // both AE and AWB have reported CONVERGED. This lambda
                // triggers a rebind with locks set.
                lockController.onCaptureResult(session, result, onConverged)
            }
        }
    }

    private var totalAnalyzedFrames: Long = 0L

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val timestampNs = imageProxy.imageInfo.timestamp
            val currentFrame = ++totalAnalyzedFrames
            listener.onCameraFrame(currentFrame, timestampNs)
            val image = imageProxy.image
            if (image == null) {
                if (facePresent) {
                    facePresent = false
                    listener.onFaceLost()
                }
                return
            }

            // 1. Run / reuse face-mesh ROIs.
            val res = roiTracker.update(image, imageProxy.imageInfo.rotationDegrees)
            val isRotated = (imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270)
            val upWidth = if (isRotated) image.height else image.width
            val upHeight = if (isRotated) image.width else image.height
            @androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
            val sourceTransform = try {
                androidx.camera.view.transform.ImageProxyTransformFactory().apply {
                    isUsingRotationDegrees = true
                }.getOutputTransform(imageProxy)
            } catch (_: Throwable) {
                null
            }
            listener.onMeshPoints(res?.meshPoints ?: FloatArray(0), res?.facePresent == true)
            listener.onRoisUpdated(
                res?.rois,
                res?.faceBox,
                res?.meshPoints ?: FloatArray(0),
                upWidth,
                upHeight,
                imageProxy.imageInfo.rotationDegrees,
                sourceTransform
            )
            listener.onFaceCountChanged(res?.faceCount ?: 0, res?.multipleFaces == true)

            // 1b. Inspect frame for phone/secondary screen presentation attack
            phoneDetector.isDocumentModeActive = isDocumentMode
            val phoneResult = phoneDetector.processFrame(
                imageProxy = imageProxy,
                meshPoints = res?.meshPoints ?: FloatArray(0),
                faceBox = res?.faceBox,
            )
            listener.onPhoneDetected(phoneResult.isPhoneDetected, phoneResult.reason)

            // 1c. Submit frame every 2 frames to gesture tracker with face region excluded
            if (currentFrame % 2L == 0L) {
                com.edgeppg.app.behavior.GestureTracker.processYuv(
                    image = image,
                    rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                    faceBox = res?.faceBox,
                    timestampMs = System.currentTimeMillis()
                )
            }

            // 1d. Submit frame to PAN Card OCR engine (continuously in doc mode, or every 5 frames)
            if (isDocumentMode || currentFrame % 5L == 0L) {
                panCardOcr?.processFullFrame(
                    image = image,
                    rotationDegrees = imageProxy.imageInfo.rotationDegrees
                )
            }

            if (res == null || res.rois == null || !res.facePresent) {
                if (facePresent) {
                    facePresent = false
                    listener.onFaceLost()
                }
                opticalEngine?.onFrame(System.currentTimeMillis(), FloatArray(9), false)
                return
            }
            if (!facePresent) {
                facePresent = true
                listener.onFaceRestored()
            }

            // 2. Mean RGB per ROI from the raw YUV planes (with rotation translation).
            val ok = roiTracker.meanRgbPerRoi(image, res.rois, outMeans, imageProxy.imageInfo.rotationDegrees)
            if (!ok) return

            // 2b. Track facial skin ROI response for closed-loop optical verification
            opticalEngine?.onFrame(System.currentTimeMillis(), outMeans, facePresent)
            val curR = (outMeans[0] + outMeans[3] + outMeans[6]) / 3f
            val curG = (outMeans[1] + outMeans[4] + outMeans[7]) / 3f
            val curB = (outMeans[2] + outMeans[5] + outMeans[8]) / 3f
            if (opticalFlashActive) {
                flashSumR += curR
                flashSumG += curG
                flashSumB += curB
                flashSampleCount++
            } else {
                preFlashR = curR
                preFlashG = curG
                preFlashB = curB
            }

            // 3. Quality Gate — record + decide + emit (Stage 3).
            qualityGate.record(
                timestampNs = timestampNs,
                rgbMeans = outMeans,
                exposureNs = lastExposureNs,
                sensitivityIso = lastSensitivityIso,
                awbR = lastAwbR,
                awbG = lastAwbG,
                awbB = lastAwbB,
                lockState = locked,
                hasFace = facePresent,
            )
            val snapshot = qualityGate.snapshot(facePresent, locked)
            val decision = qualityGate.decide(facePresent, locked)
            listener.onQuality(snapshot, decision)

            // 4. DSP emit (Stage 4 will consume this).
            //    Emit whenever face is present — even before AE/AWB lock.
            //    For spoof detection, the weak/absent rPPG signal from a screen
            //    or photo IS the evidence. Locking is still tracked so the
            //    quality gate can distinguish pre-lock from locked frames.
            val n = ++frameNumber
            listener.onFrameMeans(outMeans, timestampNs, n)
        } catch (t: Throwable) {
            com.edgeppg.app.Log.error("capture", "analyzer frame failed", t)
        } finally {
            try {
                imageProxy.close()
            } catch (_: Exception) {
                // non-fatal
            }
        }
    }

    // --- FrameListener ---

    private val outMeans = FloatArray(9)

    override fun onFrameMeans(rgbMeans: FloatArray, timestampNs: Long,
                              frameNumber: Long) {
        if (locked) framesInWindow.incrementAndGet()
    }

    override fun onLockStateChanged(locked: Boolean) {
        this.locked = locked
        Log.stage("stage2", if (locked)
            "AE/AWB LOCKED — DSP frames will start"
            else "AE/AWB UNLOCKED — pre-lock frames dropped")
    }

    override fun onFaceLost() {
        facePresent = false
        Log.capture("face lost")
    }

    override fun onFaceRestored() {
        facePresent = true
        Log.capture("face restored")
    }

    companion object {
        // 30 fps target. CameraX frame-rate control is device-dependent;
        // we keep the analyzer non-blocking and rely on
        // STRATEGY_KEEP_ONLY_LATEST + early-return to maintain ~30 fps.
        const val TARGET_WIDTH = 1920
        const val TARGET_HEIGHT = 1080
    }
}
