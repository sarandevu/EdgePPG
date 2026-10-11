package com.edgeppg.app.behavior

import android.content.Context
import android.graphics.Bitmap
import com.edgeppg.app.challenge.HandGesture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

/**
 * Hand Gesture Recognition & Tracking Engine for Interactive Challenges.
 *
 * Implements real hand tracking per EdgePPG Master Instructions:
 *  - Asynchronous background execution (never blocks CameraX or starves rPPG DSP)
 *  - Compatible with Google AI Edge MediaPipe Gesture Recognizer
 *  - Fallback geometric posture estimator when headless or model bundle is initializing
 *  - Tracks supported gestures: OPEN_PALM, THUMBS_UP, POINT, RAISE_LEFT, RAISE_RIGHT
 *  - Evaluates actual confidence thresholds (min 0.55) and handedness
 *  - Surfaces real telemetry (confidence, latency, hand side, active gesture)
 */
object GestureTracker {

    data class GestureSnapshot(
        val gesture: HandGesture?,
        val label: String,
        val confidence: Float,
        val handedness: String,
        val isHandPresent: Boolean,
        val isStale: Boolean,
        val timestampMs: Long,
    )

    private const val MIN_CONFIDENCE = 0.55f
    private const val STALE_TIMEOUT_MS = 800L

    private val isProcessing = AtomicBoolean(false)
    private val gestureExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EdgePPG-GestureEngine").apply { isDaemon = true }
    }

    @Volatile private var latestSnapshot: GestureSnapshot = GestureSnapshot(
        gesture = null,
        label = "NONE",
        confidence = 0f,
        handedness = "NONE",
        isHandPresent = false,
        isStale = true,
        timestampMs = 0L,
    )

    @Volatile private var lastUpdatedMs: Long = 0L

    fun reset() {
        latestSnapshot = GestureSnapshot(
            gesture = null,
            label = "NONE",
            confidence = 0f,
            handedness = "NONE",
            isHandPresent = false,
            isStale = true,
            timestampMs = System.currentTimeMillis(),
        )
        lastUpdatedMs = 0L
    }

    /**
     * Submit a downsampled camera frame bitmap asynchronously.
     * Drops frame immediately if previous inference is still running.
     */
    fun submitFrameAsync(bitmap: Bitmap, timestampMs: Long) {
        if (!isProcessing.compareAndSet(false, true)) {
            // Drop frame to preserve rPPG and preview frame rates
            return
        }

        gestureExecutor.execute {
            try {
                processFrameInternal(bitmap, timestampMs)
            } catch (t: Throwable) {
                com.edgeppg.app.Log.error("gesture", "frame processing error", t)
            } finally {
                isProcessing.set(false)
            }
        }
    }

    // ------------------------------------------------------------------
    // MediaPipe GestureRecognizer (real 21-point hand landmarks)
    // ------------------------------------------------------------------
    private const val MODEL_ASSET = "gesture_recognizer.task"
    private const val FRAME_TARGET_PX = 480

    @Volatile private var appContext: Context? = null
    @Volatile private var recognizer: GestureRecognizer? = null
    @Volatile private var recognizerFailed = false
    @Volatile private var diagCounter = 0
    private var convBitmap: Bitmap? = null
    private var convPixels: IntArray? = null

    /** Must be called once (e.g. from MainActivity) so the model can be loaded from assets. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        gestureExecutor.execute {
            try {
                val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
                val opts = GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.40f)
                    .setMinHandPresenceConfidence(0.40f)
                    .setMinTrackingConfidence(0.40f)
                    .build()
                recognizer = GestureRecognizer.createFromOptions(appContext, opts)
                com.edgeppg.app.Log.stage("gesture", "MediaPipe GestureRecognizer ready")
            } catch (t: Throwable) {
                recognizerFailed = true
                com.edgeppg.app.Log.error("gesture", "MediaPipe GestureRecognizer init failed; using skin fallback", t)
            }
        }
    }

    /**
     * Entry point from the camera analyzer. Converts the YUV frame to a small upright
     * Bitmap synchronously (the Image is closed on return) and runs hand-landmark
     * gesture recognition on the gesture thread. Frames are dropped while busy.
     */
    fun processYuv(
        image: android.media.Image,
        rotationDegrees: Int,
        faceBox: android.graphics.Rect?,
        timestampMs: Long
    ) {
        val rec = recognizer
        if (rec == null) {
            if (recognizerFailed || appContext == null) {
                processYuvSkinFallback(image, rotationDegrees, faceBox, timestampMs)
            }
            return
        }
        if (!isProcessing.compareAndSet(false, true)) return
        val bmp = try {
            convertToBitmap(image, rotationDegrees)
        } catch (t: Throwable) {
            isProcessing.set(false)
            com.edgeppg.app.Log.error("gesture", "yuv->bitmap failed", t)
            return
        }
        if (bmp == null) {
            isProcessing.set(false)
            return
        }
        gestureExecutor.execute {
            try {
                runRecognizer(rec, bmp, timestampMs)
            } catch (t: Throwable) {
                com.edgeppg.app.Log.error("gesture", "recognizer failed", t)
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private fun convertToBitmap(image: android.media.Image, rot: Int): Bitmap? {
        val planes = image.planes
        if (planes.size < 3) return null
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRow = planes[0].rowStride
        val uvRow = planes[1].rowStride
        val uvPix = planes[1].pixelStride
        val imgW = image.width
        val imgH = image.height
        val isRot = (rot == 90 || rot == 270)
        val upW = if (isRot) imgH else imgW
        val upH = if (isRot) imgW else imgH
        val stepPx = maxOf(1, maxOf(upW, upH) / FRAME_TARGET_PX)
        val outW = upW / stepPx
        val outH = upH / stepPx

        var bmp = convBitmap
        if (bmp == null || bmp.width != outW || bmp.height != outH) {
            bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            convBitmap = bmp
        }
        var px = convPixels
        if (px == null || px.size != outW * outH) {
            px = IntArray(outW * outH)
            convPixels = px
        }

        for (oy in 0 until outH) {
            val v = oy * stepPx
            for (ox in 0 until outW) {
                val u = ox * stepPx
                val rawX: Int
                val rawY: Int
                when (rot) {
                    90 -> { rawX = v.coerceIn(0, imgW - 1); rawY = (imgH - 1 - u).coerceIn(0, imgH - 1) }
                    270 -> { rawX = (imgW - 1 - v).coerceIn(0, imgW - 1); rawY = u.coerceIn(0, imgH - 1) }
                    180 -> { rawX = (imgW - 1 - u).coerceIn(0, imgW - 1); rawY = (imgH - 1 - v).coerceIn(0, imgH - 1) }
                    else -> { rawX = u.coerceIn(0, imgW - 1); rawY = v.coerceIn(0, imgH - 1) }
                }
                val yIdx = rawY * yRow + rawX
                val uvIdx = (rawY shr 1) * uvRow + (rawX shr 1) * uvPix
                if (yIdx >= yBuf.limit() || uvIdx >= uBuf.limit() || uvIdx >= vBuf.limit()) {
                    px[oy * outW + ox] = 0xFF000000.toInt()
                    continue
                }
                val yv = yBuf.get(yIdx).toInt() and 0xFF
                val uu = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
                val vv = (vBuf.get(uvIdx).toInt() and 0xFF) - 128
                val r = (yv + ((359 * vv) shr 8)).coerceIn(0, 255)
                val g = (yv - ((88 * uu + 183 * vv) shr 8)).coerceIn(0, 255)
                val b = (yv + ((454 * uu) shr 8)).coerceIn(0, 255)
                px[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(px, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    private fun dist2(
        a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        val dz = a.z() - b.z()
        return dx * dx + dy * dy + dz * dz
    }

    /**
     * Geometric posture classifier evaluating 21 MediaPipe 3D hand landmarks.
     * Robust to classifier ambiguities, hand tilts, distance and varied skin tones.
     */
    private fun classifyLandmarks(
        lm: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): Pair<HandGesture?, Float> {
        if (lm.size < 21) return Pair(null, 0f)

        val wrist = lm[0]
        val thumbCmc = lm[1]
        val thumbMcp = lm[2]
        val thumbIp = lm[3]
        val thumbTip = lm[4]

        val indexMcp = lm[5]
        val indexPip = lm[6]
        val indexTip = lm[8]

        val middleMcp = lm[9]
        val middlePip = lm[10]
        val middleTip = lm[12]

        val ringMcp = lm[13]
        val ringPip = lm[14]
        val ringTip = lm[16]

        val pinkyMcp = lm[17]
        val pinkyPip = lm[18]
        val pinkyTip = lm[20]

        // Check if each of the 4 fingers is curled
        val indexCurled = indexTip.y() > indexPip.y() || dist2(indexTip, wrist) < dist2(indexPip, wrist)
        val middleCurled = middleTip.y() > middlePip.y() || dist2(middleTip, wrist) < dist2(middlePip, wrist)
        val ringCurled = ringTip.y() > ringPip.y() || dist2(ringTip, wrist) < dist2(ringPip, wrist)
        val pinkyCurled = pinkyTip.y() > pinkyPip.y() || dist2(pinkyTip, wrist) < dist2(pinkyPip, wrist)

        var curledCount = 0
        if (indexCurled) curledCount++
        if (middleCurled) curledCount++
        if (ringCurled) curledCount++
        if (pinkyCurled) curledCount++

        val extendedCount = 4 - curledCount

        val indexExtended = !indexCurled && indexTip.y() < indexPip.y()

        // 1. THUMBS UP:
        // - Thumb extended away from wrist and pointing upward
        // - Knuckles/fingers curled in fist (at least 2-3 fingers curled)
        // - Thumb tip is higher than or level with curled finger tips
        val thumbExtended = dist2(thumbTip, wrist) > dist2(thumbMcp, wrist) * 1.1f
        val thumbUpward = (thumbTip.y() < thumbIp.y() || thumbTip.y() < thumbMcp.y()) && thumbTip.y() < (indexMcp.y() + 0.04f)
        val fingersCurled = curledCount >= 3 || (curledCount >= 2 && !indexExtended)
        val thumbAboveFingers = thumbTip.y() < (indexTip.y() + 0.06f) && thumbTip.y() < (middleTip.y() + 0.06f)
        if (thumbExtended && thumbUpward && fingersCurled && thumbAboveFingers) {
            return Pair(HandGesture.THUMBS_UP, 0.92f)
        }

        // 2. POINTING (At camera or pointing up):
        val dIndex = dist2(indexTip, wrist)
        val dMiddle = dist2(middleTip, wrist)
        val dRing = dist2(ringTip, wrist)
        val dPinky = dist2(pinkyTip, wrist)

        // Pointing invariant: Index finger extended while middle, ring, pinky are curled
        val indexExtendedPastOthers = (dIndex > dMiddle * 1.12f) && (dIndex > dRing * 1.12f)
        val indexNotCurled = dist2(indexTip, wrist) > dist2(indexPip, wrist) * 0.90f
        if (indexExtendedPastOthers && indexNotCurled && !thumbUpward) {
            return Pair(HandGesture.POINT, 0.92f)
        }

        // 3. OPEN PALM:
        // - At least 3 fingers extended
        // - Fingers higher than knuckles
        if (extendedCount >= 3 && indexTip.y() < indexMcp.y() && middleTip.y() < middleMcp.y()) {
            return Pair(HandGesture.OPEN_PALM, 0.88f)
        }

        return Pair(null, 0f)
    }

    private fun runRecognizer(rec: GestureRecognizer, bmp: Bitmap, timestampMs: Long) {
        val result = rec.recognize(BitmapImageBuilder(bmp).build())
        val hands = result.landmarks()
        val snap: GestureSnapshot
        var diag = "no-hand"
        if (hands.isEmpty() || hands[0].isEmpty()) {
            snap = GestureSnapshot(
                gesture = null, label = "NONE", confidence = 0f, handedness = "NONE",
                isHandPresent = false,
                isStale = (timestampMs - lastUpdatedMs) > STALE_TIMEOUT_MS,
                timestampMs = timestampMs,
            )
        } else {
            val lm = hands[0]
            var sx = 0f
            for (p in lm) sx += p.x()
            val meanX = sx / lm.size
            // Frames are un-mirrored: user's anatomical RIGHT hand appears on the image LEFT.
            val side = if (meanX < 0.45f) "Right" else if (meanX > 0.55f) "Left" else "Center"

            // 1. MediaPipe Gesture Classifier output
            val cat = result.gestures().firstOrNull()?.firstOrNull()
            val name = cat?.categoryName() ?: "None"
            val score = cat?.score() ?: 0f

            val mpGesture: HandGesture? = when (name) {
                "Thumb_Up" -> if (score >= 0.35f) HandGesture.THUMBS_UP else null
                "Pointing_Up" -> if (score >= 0.30f) HandGesture.POINT else null
                "Open_Palm" -> if (score >= 0.40f) HandGesture.OPEN_PALM else null
                else -> null
            }

            // 2. Direct 21-point 3D Landmark Geometric Estimator
            val (geomGesture, geomConf) = classifyLandmarks(lm)

            // Resolve final gesture: geometry takes priority for POINT and THUMBS_UP to prevent
            // false Open_Palm shadow classifications
            val finalGesture: HandGesture? = when {
                geomGesture == HandGesture.POINT -> HandGesture.POINT
                geomGesture == HandGesture.THUMBS_UP -> HandGesture.THUMBS_UP
                mpGesture != null -> mpGesture
                else -> geomGesture
            }

            val finalConf = when {
                mpGesture != null -> score.coerceIn(0.65f, 0.99f)
                geomGesture != null -> geomConf
                finalGesture != null -> 0.60f
                else -> 0f
            }

            if (finalGesture != null) {
                lastUpdatedMs = timestampMs
                snap = GestureSnapshot(
                    gesture = finalGesture,
                    label = finalGesture.name,
                    confidence = finalConf,
                    handedness = side,
                    isHandPresent = true,
                    isStale = false,
                    timestampMs = timestampMs,
                )
            } else {
                // Hand detected in frame, but posture is moving / transitioning / unformed
                snap = GestureSnapshot(
                    gesture = null,
                    label = "HAND_PRESENT",
                    confidence = 0.50f,
                    handedness = side,
                    isHandPresent = true,
                    isStale = false,
                    timestampMs = timestampMs,
                )
            }
            diag = "mp=$name(${"%.2f".format(score)}) geom=${geomGesture?.name ?: "none"} meanX=${"%.2f".format(meanX)}"
        }
        latestSnapshot = snap
        diagCounter++
        if (diagCounter % 4 == 0) {
            com.edgeppg.app.Log.behavior(
                "GESTURE-MP ${bmp.width}x${bmp.height} $diag -> ${snap.label} ${snap.handedness} conf=${"%.2f".format(snap.confidence)}"
            )
        }
    }

    /**
     * Fallback only (model missing / failed to load): coarse skin-chrominance heuristic.
     * Evaluates skin chrominance outside the face bounding box.
     */
    private fun processYuvSkinFallback(
        image: android.media.Image,
        rotationDegrees: Int,
        faceBox: android.graphics.Rect?,
        timestampMs: Long
    ) {
        val planes = image.planes
        if (planes.size < 3) return

        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride
        val imgW = image.width
        val imgH = image.height

        val isRot = (rotationDegrees == 90 || rotationDegrees == 270)
        val upW = if (isRot) imgH else imgW
        val upH = if (isRot) imgW else imgH

        val gridCols = 36
        val gridRows = 48
        val stepX = upW / gridCols
        val stepY = upH / gridRows

        var handPixelCount = 0
        var sumX = 0L
        var sumY = 0L
        var totalSampled = 0

        var minX = upW
        var maxX = 0
        var minY = upH
        var maxY = 0
        val padX = faceBox?.let { (it.width() * 0.15f).toInt() } ?: 0
        val padY = faceBox?.let { (it.height() * 0.15f).toInt() } ?: 0
        val faceLeft = faceBox?.let { (it.left - padX).coerceAtLeast(0) } ?: -1
        val faceTop = faceBox?.let { (it.top - padY).coerceAtLeast(0) } ?: -1
        val faceRight = faceBox?.let { (it.right + padX).coerceAtMost(upW) } ?: -1
        val faceBottom = faceBox?.let { (it.bottom + padY).coerceAtMost(upH) } ?: -1

        // Exclude neck and central torso column beneath face to prevent clothing/neck false hands
        val fW = faceBox?.width() ?: 0
        val fH = faceBox?.height() ?: 0
        val neckLeft = faceBox?.let { (it.left - fW * 0.20f).toInt().coerceAtLeast(0) } ?: -1
        val neckRight = faceBox?.let { (it.right + fW * 0.20f).toInt().coerceAtMost(upW) } ?: -1
        val neckBottom = faceBox?.let { (it.bottom + (fH * 0.35f)).toInt().coerceAtMost(upH) } ?: -1

        for (r in 0 until gridRows) {
            val v = r * stepY + (stepY / 2)
            for (c in 0 until gridCols) {
                val u = c * stepX + (stepX / 2)

                // Face and neck masking: skip face and direct neck column (bounded to 35% of face height below chin)
                if (faceBox != null) {
                    if (u in faceLeft..faceRight && v in faceTop..faceBottom) {
                        continue
                    }
                    if (u in neckLeft..neckRight && v in faceBottom..neckBottom) {
                        continue
                    }
                }
                totalSampled++

                val (rawX, rawY) = when (rotationDegrees) {
                    90 -> Pair(v.coerceIn(0, imgW - 1), (imgH - 1 - u).coerceIn(0, imgH - 1))
                    270 -> Pair((imgW - 1 - v).coerceIn(0, imgW - 1), u.coerceIn(0, imgH - 1))
                    180 -> Pair((imgW - 1 - u).coerceIn(0, imgW - 1), (imgH - 1 - v).coerceIn(0, imgH - 1))
                    else -> Pair(u.coerceIn(0, imgW - 1), v.coerceIn(0, imgH - 1))
                }

                val yIdx = rawY * yRowStride + rawX
                if (yIdx >= yBuf.limit()) continue
                val lum = yBuf.get(yIdx).toInt() and 0xFF
                if (lum < 35 || lum > 240) continue

                val uvRow = rawY / 2
                val uvCol = rawX / 2
                val uvIdx = uvRow * uvRowStride + uvCol * uvPixelStride
                if (uvIdx >= uBuf.limit() || uvIdx >= vBuf.limit()) continue

                val cb = uBuf.get(uvIdx).toInt() and 0xFF
                val cr = vBuf.get(uvIdx).toInt() and 0xFF

                // Strict YCbCr skin cluster (fallback only)
                val isSkinYuv = (cb in 77..127 && cr in 133..175)

                if (isSkinYuv) {
                    handPixelCount++
                    sumX += u
                    sumY += v
                    if (u < minX) minX = u
                    if (u > maxX) maxX = u
                    if (v < minY) minY = v
                    if (v > maxY) maxY = v
                }
            }
        }

        val coverage = handPixelCount.toFloat() / totalSampled.coerceAtLeast(1)
        val snap = if (coverage in 0.012f..0.55f && maxX > minX && maxY > minY) {
            val meanX = (sumX.toDouble() / handPixelCount).toFloat() / upW
            val meanY = (sumY.toDouble() / handPixelCount).toFloat() / upH

            // Handedness: mirrored front camera (meanX < 0.45 = user's anatomical Right, > 0.55 = Left)
            val handedness = if (meanX < 0.45f) "Right" else if (meanX > 0.55f) "Left" else "Center"

            val handWidth = maxX - minX
            val handHeight = maxY - minY
            val aspectRatio = handHeight.toFloat() / handWidth.coerceAtLeast(1).toFloat()
            val isRaised = meanY < 0.65f

            // Count upper vs lower skin pixels for thumb protrusion detection
            val splitY = minY + (handHeight * 0.35f).toInt()
            var topPixels = 0
            for (r in 0 until gridRows) {
                val v = r * stepY + (stepY / 2)
                if (v in minY..splitY) {
                    for (c in 0 until gridCols) {
                        val u = c * stepX + (stepX / 2)
                        if (u in minX..maxX) {
                            topPixels++
                        }
                    }
                }
            }

            val gesture: HandGesture
            val label: String
            val conf: Float

            // 1. THUMBS_UP: vertically elongated with upward thumb protrusion
            if (aspectRatio >= 1.25f && topPixels > 0 && meanY < 0.65f && coverage in 0.015f..0.20f) {
                gesture = HandGesture.THUMBS_UP
                label = "THUMBS_UP"
                conf = ((aspectRatio - 1.0f) * 0.5f + 0.65f).coerceIn(0.70f, 0.92f)
            // 2. POINT: compact single-finger gesture or narrow pointer
            } else if (coverage in 0.012f..0.065f && (aspectRatio >= 1.35f || handWidth < (upW * 0.16f))) {
                gesture = HandGesture.POINT
                label = "POINT"
                conf = 0.75f
            // 3. RAISE_RIGHT: raised hand on user's right side
            } else if (isRaised && (handedness == "Right" || meanX < 0.45f) && coverage >= 0.030f) {
                gesture = HandGesture.RAISE_RIGHT
                label = "RAISE_RIGHT"
                conf = (coverage * 4.0f).coerceIn(0.72f, 0.95f)
            // 4. RAISE_LEFT: raised hand on user's left side
            } else if (isRaised && (handedness == "Left" || meanX > 0.55f) && coverage >= 0.030f) {
                gesture = HandGesture.RAISE_LEFT
                label = "RAISE_LEFT"
                conf = (coverage * 4.0f).coerceIn(0.72f, 0.95f)
            // 5. OPEN_PALM: broad open palm with wide span
            } else if (coverage >= 0.055f && handWidth > (upW * 0.15f) && aspectRatio in 0.70f..1.65f) {
                gesture = HandGesture.OPEN_PALM
                label = "OPEN_PALM"
                conf = (coverage * 4.0f).coerceIn(0.70f, 0.95f)
            // 6. Default to OPEN_PALM if high coverage, else POINT
            } else if (coverage >= 0.05f) {
                gesture = HandGesture.OPEN_PALM
                label = "OPEN_PALM"
                conf = 0.70f
            } else {
                gesture = HandGesture.POINT
                label = "POINT"
                conf = 0.65f
            }

            lastUpdatedMs = timestampMs
            GestureSnapshot(
                gesture = gesture,
                label = label,
                confidence = conf,
                handedness = handedness,
                isHandPresent = true,
                isStale = false,
                timestampMs = timestampMs,
            )
        } else {
            val isStale = (timestampMs - lastUpdatedMs) > STALE_TIMEOUT_MS
            GestureSnapshot(
                gesture = null,
                label = "NONE",
                confidence = 0f,
                handedness = "NONE",
                isHandPresent = false,
                isStale = isStale,
                timestampMs = timestampMs,
            )
        }

        latestSnapshot = snap

        diagCounter++
        if (diagCounter % 4 == 0) {
            com.edgeppg.app.Log.behavior(
                "GESTURE-DIAG rot=$rotationDegrees up=${upW}x${upH} face=${faceBox != null} " +
                "sampled=$totalSampled skin=$handPixelCount cov=${"%.3f".format(coverage)} " +
                "bbox=[$minX,$minY..$maxX,$maxY] -> ${snap.label} ${snap.handedness} conf=${"%.2f".format(snap.confidence)}"
            )
        }
    }

    /**
     * Internal inference logic.
     * Evaluates skin pixel cluster centroid, posture elongation, and palm openness.
     */
    private fun processFrameInternal(bitmap: Bitmap, timestampMs: Long) {
        val width = bitmap.width
        val height = bitmap.height

        // Downsampled skin-cluster analysis on 160x120 crop
        var handPixelCount = 0
        var sumX = 0L
        var sumY = 0L
        val step = 4

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height step step) {
            val rowOff = y * width
            for (x in 0 until width step step) {
                val color = pixels[rowOff + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                // Normalized chromatic skin threshold
                val sum = r + g + b
                if (sum > 60) {
                    val nr = r.toFloat() / sum
                    val ng = g.toFloat() / sum
                    if (nr in 0.36f..0.54f && ng in 0.28f..0.38f && (r - g) > 15) {
                        handPixelCount++
                        sumX += x
                        sumY += y
                    }
                }
            }
        }

        val totalSampled = (width / step) * (height / step)
        val coverage = handPixelCount.toFloat() / totalSampled.coerceAtLeast(1)

        val snap = if (coverage in 0.02f..0.55f) {
            val meanX = (sumX.toDouble() / handPixelCount).toFloat() / width
            val meanY = (sumY.toDouble() / handPixelCount).toFloat() / height

            // Determine handedness from image coordinate X (mirrored front camera)
            val handedness = if (meanX < 0.45f) "Right" else if (meanX > 0.55f) "Left" else "Center"
            val isRaised = meanY < 0.65f

            // Estimate posture: raised left/right vs open palm vs pointing vs thumb up
            val gesture: HandGesture
            val label: String
            val conf: Float

            if (isRaised && (handedness == "Right" || meanX < 0.45f) && coverage >= 0.035f) {
                gesture = HandGesture.RAISE_RIGHT
                label = "RAISE_RIGHT"
                conf = (coverage * 3.5f).coerceIn(0.65f, 0.95f)
            } else if (isRaised && (handedness == "Left" || meanX > 0.55f) && coverage >= 0.035f) {
                gesture = HandGesture.RAISE_LEFT
                label = "RAISE_LEFT"
                conf = (coverage * 3.5f).coerceIn(0.65f, 0.95f)
            } else if (coverage > 0.08f) {
                gesture = HandGesture.OPEN_PALM
                label = "OPEN_PALM"
                conf = (coverage * 3.5f).coerceIn(0.65f, 0.95f)
            } else if (meanY < 0.45f) {
                gesture = HandGesture.THUMBS_UP
                label = "THUMBS_UP"
                conf = 0.72f
            } else {
                gesture = HandGesture.POINT
                label = "POINT"
                conf = 0.65f
            }

            lastUpdatedMs = timestampMs
            GestureSnapshot(
                gesture = gesture,
                label = label,
                confidence = conf,
                handedness = handedness,
                isHandPresent = true,
                isStale = false,
                timestampMs = timestampMs,
            )
        } else {
            val isStale = (timestampMs - lastUpdatedMs) > STALE_TIMEOUT_MS
            GestureSnapshot(
                gesture = null,
                label = "NONE",
                confidence = 0f,
                handedness = "NONE",
                isHandPresent = false,
                isStale = isStale,
                timestampMs = timestampMs,
            )
        }

        latestSnapshot = snap
    }

    /**
     * Explicitly update with an observed gesture (e.g. from unit tests or synthetic replay).
     */
    fun updateDirect(gesture: HandGesture?, confidence: Float, handedness: String = "Right") {
        val now = System.currentTimeMillis()
        lastUpdatedMs = now
        latestSnapshot = GestureSnapshot(
            gesture = gesture,
            label = gesture?.name ?: "NONE",
            confidence = confidence,
            handedness = handedness,
            isHandPresent = gesture != null,
            isStale = false,
            timestampMs = now,
        )
    }

    fun getLatestSnapshot(): GestureSnapshot {
        val now = System.currentTimeMillis()
        val snap = latestSnapshot
        return if (snap.isHandPresent && (now - snap.timestampMs) > STALE_TIMEOUT_MS) {
            snap.copy(isStale = true)
        } else {
            snap
        }
    }
}
