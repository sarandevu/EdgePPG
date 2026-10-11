package com.edgeppg.app.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageProxy
import com.edgeppg.app.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time Phone and Secondary Screen Presentation Attack Detector.
 *
 * Implements multi-layered physical and algorithmic detection:
 *  1. ML Kit Image Labeling / Object Recognition:
 *     Actively identifies electronic displays, mobile phones, smartphones,
 *     cellular devices, screens, monitors, and tablets visible in frame.
 *  2. 3D Facial Mesh Planarity Collapse:
 *     A genuine human face exhibits 3D anatomical relief (nose tip extends
 *     30-60mm forward from the eye/cheek plane, normalized depth relief > 0.12).
 *     On a mobile phone screen or printed photo, the face is rendered on a
 *     flat 2D plane: depth variance and facial curvature collapse to ~0.
 *  3. Phone Bezel / Screen Border Edge Analysis:
 *     Detects high-contrast straight rectangular boundaries (phone bezels)
 *     framing the face in the camera YUV luminance buffer.
 *
 * When any layer detects a phone/secondary screen, the detector latches
 * [isPhoneDetected] = true, triggering immediate and uncompromising SPOOF
 * routing in [SessionController] and [DecisionEngine].
 */
class PhoneScreenDetector(
    private val labelConfidenceThreshold: Float = 0.65f,
    private val minConsecutiveDetections: Int = 3,
    private val labeler: ImageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.50f)
            .build()
    ),
) {

    data class Result(
        val isPhoneDetected: Boolean,
        val reason: String,
        val confidence: Float,
        val planarityScore: Float = 0f,
        val bezelScore: Float = 0f,
        val detectedLabels: List<String> = emptyList(),
    )

    companion object {
        // Specific keywords indicating actual secondary mobile phone screens
        private val PHONE_KEYWORDS = setOf(
            "mobile phone", "cell phone", "cellphone", "smartphone",
            "tablet computer", "cellular phone"
        )

        // Specific keywords indicating 2D photo / print presentation media
        private val MEDIA_KEYWORDS = setOf(
            "photograph", "picture", "poster", "snapshot", "portrait"
        )

        // Keywords indicating documents, paper, or identity cards that should suppress false spoof alarms
        private val DOCUMENT_KEYWORDS = setOf(
            "document", "identity document", "paper", "card", "business card",
            "passport", "credit card", "id card", "badge"
        )
    }

    private val isRunningLabeler = AtomicBoolean(false)
    private var consecutiveDetections = 0
    private var consecutiveCleanFrames = 0
    private var consecutiveLabelHits = 0
    private var phoneLatchUntilMs = 0L
    @Volatile var isPhoneDetected: Boolean = false
        private set
    @Volatile var lastReason: String = ""
        private set
    @Volatile var lastDetectedLabels: List<String> = emptyList()
        private set
    @Volatile var isDocumentModeActive: Boolean = false

    /** Temporarily suppress phone screen detections when a valid KYC document or card is verified. */
    fun suppressForDocument(durationMs: Long = 4000L) {
        phoneLatchUntilMs = 0L
        isPhoneDetected = false
        lastReason = ""
        consecutiveDetections = 0
        consecutiveLabelHits = 0
    }

    /** Reset state for a fresh VKYC session. */
    fun reset() {
        consecutiveDetections = 0
        consecutiveCleanFrames = 0
        consecutiveLabelHits = 0
        phoneLatchUntilMs = 0L
        isPhoneDetected = false
        lastReason = ""
        lastDetectedLabels = emptyList()
        isDocumentModeActive = false
    }

    /**
     * Inspect frame for secondary phone / screen presentation attacks.
     * Decouples bitmap for async ML Kit labeling so CameraX ImageProxy can close immediately.
     */
    fun processFrame(
        imageProxy: ImageProxy,
        meshPoints: FloatArray,
        faceBox: Rect?,
    ): Result {
        if (isDocumentModeActive) {
            isPhoneDetected = false
            return Result(
                isPhoneDetected = false,
                reason = "document-mode-active",
                confidence = 0f,
                detectedLabels = lastDetectedLabels
            )
        }

        val image = imageProxy.image ?: return Result(
            isPhoneDetected = isPhoneDetected,
            reason = lastReason,
            confidence = 0f,
            detectedLabels = lastDetectedLabels
        )
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        var detectedByThisFrame = false
        var primaryReason = ""
        var maxConfidence = 0f

        // Check if hand is currently present or actively tracked in frame
        val handSnap = com.edgeppg.app.behavior.GestureTracker.getLatestSnapshot()
        val isHandPresent = handSnap.isHandPresent || (System.currentTimeMillis() - handSnap.timestampMs < 2500L)

        val isRot = (rotationDegrees == 90 || rotationDegrees == 270)
        val upW = if (isRot) image.height else image.width
        val upH = if (isRot) image.width else image.height
        val bw = faceBox?.width() ?: 0
        val bh = faceBox?.height() ?: 0
        val faceCoverageW = if (upW > 0) bw.toFloat() / upW.toFloat() else 0f
        val faceCoverageH = if (upH > 0) bh.toFloat() / upH.toFloat() else 0f
        val isTouchingEdges = faceBox != null && (
            faceBox.left <= upW * 0.04f || faceBox.right >= upW * 0.96f ||
            faceBox.top <= upH * 0.04f || faceBox.bottom >= upH * 0.96f
        )

        // --- Layer 1 & 2: Multi-Cue Screen Replay & 2D Image Verification ---
        val planarityScore = evaluateMeshPlanarity(meshPoints)
        val bezelScore = if (faceBox != null && !faceBox.isEmpty && planarityScore >= 0.40f) {
            evaluateScreenBezelEdges(image, faceBox, rotationDegrees)
        } else 0f

        // Mode A: Close-Up Presentation Attack (photo or screen held so close that edges/phone are cropped out)
        if (faceCoverageW >= 0.58f || faceCoverageH >= 0.62f || (faceCoverageW >= 0.48f && isTouchingEdges)) {
            detectedByThisFrame = true
            primaryReason = "2d-close-up-spoof:face-cropped-or-too-close(${"%.2f".format(faceCoverageW)})"
            maxConfidence = 0.92f
        } else if (planarityScore >= 0.75f) {
            // Mode B: 2D Planar Surface Collapse (flat photo or screen replay without bezel)
            detectedByThisFrame = true
            primaryReason = "2d-planar-spoof:planarity=${"%.2f".format(planarityScore)}"
            maxConfidence = planarityScore
        } else if (planarityScore >= 0.50f && bezelScore >= 0.50f) {
            // Mode C: Screen Replay with visible bezel
            detectedByThisFrame = true
            primaryReason = "screen-replay-detected:planarity=${"%.2f".format(planarityScore)}|bezel=${"%.2f".format(bezelScore)}"
            maxConfidence = max(planarityScore, bezelScore)
        }

        // --- Layer 3: ML Kit Image Labeling / 2D Presentation & Device Recognition ---
        if (isRunningLabeler.compareAndSet(false, true)) {
            try {
                val bmp = imageProxy.toBitmap()
                val targetW = if (isRot) 240 else 320
                val targetH = if (isRot) 320 else 240
                val scaledBmp = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                if (scaledBmp != bmp) {
                    bmp.recycle()
                }
                val input = InputImage.fromBitmap(scaledBmp, rotationDegrees)
                labeler.process(input)
                    .addOnSuccessListener { labels ->
                        val found = mutableListOf<String>()
                        var hasDocumentLabel = false
                        for (lbl in labels) {
                            val text = lbl.text.lowercase().trim()
                            found.add("${lbl.text}:${"%.2f".format(lbl.confidence)}")
                            if (DOCUMENT_KEYWORDS.any { dw -> text == dw || text.contains(dw) }) {
                                hasDocumentLabel = true
                            }
                        }
                        if (!hasDocumentLabel) {
                            var matchedKeyword = false
                            var matchedConf = 0f
                            var matchedText = ""
                            var isMedia = false
                            for (lbl in labels) {
                                val text = lbl.text.lowercase().trim()
                                val isPhone = PHONE_KEYWORDS.any { kw -> text == kw || text.contains(kw) }
                                val isPhoto = MEDIA_KEYWORDS.any { kw -> text == kw || text.contains(kw) }
                                if (isPhone && lbl.confidence >= labelConfidenceThreshold) {
                                    matchedKeyword = true
                                    matchedConf = lbl.confidence
                                    matchedText = lbl.text
                                    isMedia = false
                                } else if (isPhoto && lbl.confidence >= 0.60f) {
                                    matchedKeyword = true
                                    matchedConf = lbl.confidence
                                    matchedText = lbl.text
                                    isMedia = true
                                }
                            }
                            if (matchedKeyword) {
                                consecutiveLabelHits++
                                if (consecutiveLabelHits >= 2) {
                                    isPhoneDetected = true
                                    lastReason = if (isMedia) {
                                        "2d-media-label:$matchedText(${"%.2f".format(matchedConf)})"
                                    } else {
                                        "device-label:$matchedText(${"%.2f".format(matchedConf)})"
                                    }
                                    phoneLatchUntilMs = System.currentTimeMillis() + 2500L
                                    Log.stage("detector", "Presentation attack detected: $lastReason")
                                }
                            } else {
                                consecutiveLabelHits = 0
                            }
                        } else {
                            consecutiveLabelHits = 0
                        }
                        lastDetectedLabels = found
                    }
                    .addOnFailureListener { t ->
                        Log.error("detector", "PhoneScreenDetector labeling failed", t)
                    }
                    .addOnCompleteListener {
                        try { scaledBmp.recycle() } catch (_: Throwable) {}
                        isRunningLabeler.set(false)
                    }
            } catch (t: Throwable) {
                Log.error("detector", "PhoneScreenDetector labeling input failed", t)
                isRunningLabeler.set(false)
            }
        }

        val now = System.currentTimeMillis()
        if (now < phoneLatchUntilMs) {
            isPhoneDetected = true
        } else if (detectedByThisFrame) {
            consecutiveCleanFrames = 0
            consecutiveDetections = (consecutiveDetections + 1).coerceAtMost(10)
            if (consecutiveDetections >= minConsecutiveDetections) {
                isPhoneDetected = true
                lastReason = primaryReason
            }
        } else {
            consecutiveCleanFrames++
            if (consecutiveCleanFrames >= 6) {
                consecutiveDetections = 0
                isPhoneDetected = false
                lastReason = ""
                phoneLatchUntilMs = 0L
            }
        }

        return Result(
            isPhoneDetected = isPhoneDetected,
            reason = if (isPhoneDetected) lastReason else "",
            confidence = if (isPhoneDetected) maxConfidence else 0f,
            planarityScore = planarityScore,
            bezelScore = bezelScore,
            detectedLabels = lastDetectedLabels,
        )
    }

    fun processFrame(
        image: Image,
        rotationDegrees: Int,
        meshPoints: FloatArray,
        faceBox: Rect?,
    ): Result {
        if (isDocumentModeActive) {
            isPhoneDetected = false
            return Result(
                isPhoneDetected = false,
                reason = "document-mode-active",
                confidence = 0f,
                detectedLabels = lastDetectedLabels
            )
        }

        var detectedByThisFrame = false
        var primaryReason = ""
        var maxConfidence = 0f

        // Check if hand is currently present or actively tracked in frame
        val handSnap = com.edgeppg.app.behavior.GestureTracker.getLatestSnapshot()
        val isHandPresent = handSnap.isHandPresent && (System.currentTimeMillis() - handSnap.timestampMs < 1500L)

        val planarityScore = if (!isHandPresent) evaluateMeshPlanarity(meshPoints) else 0f
        val bezelScore = if (!isHandPresent && faceBox != null && !faceBox.isEmpty && planarityScore >= 0.50f) {
            evaluateScreenBezelEdges(image, faceBox, rotationDegrees)
        } else 0f

        if (planarityScore >= 0.85f && bezelScore >= 0.65f) {
            detectedByThisFrame = true
            primaryReason = "screen-replay-detected:planarity=${"%.2f".format(planarityScore)}|bezel=${"%.2f".format(bezelScore)}"
            maxConfidence = max(planarityScore, bezelScore)
        }

        val now = System.currentTimeMillis()
        if (now < phoneLatchUntilMs) {
            isPhoneDetected = true
        } else if (detectedByThisFrame) {
            consecutiveCleanFrames = 0
            consecutiveDetections = (consecutiveDetections + 1).coerceAtMost(6)
            if (consecutiveDetections >= minConsecutiveDetections) {
                isPhoneDetected = true
                lastReason = primaryReason
            }
        } else {
            consecutiveCleanFrames++
            if (consecutiveCleanFrames >= 6) {
                consecutiveDetections = 0
                isPhoneDetected = false
                lastReason = ""
                phoneLatchUntilMs = 0L
            }
        }

        return Result(
            isPhoneDetected = isPhoneDetected,
            reason = if (isPhoneDetected) lastReason else "",
            confidence = if (isPhoneDetected) maxConfidence else 0f,
            planarityScore = planarityScore,
            bezelScore = bezelScore,
            detectedLabels = lastDetectedLabels,
        )
    }

    /**
     * Measure 3D anatomical relief vs 2D planar collapse.
     * Returns a score in [0.0, 1.0], where 1.0 = completely flat 2D surface (phone screen/photo),
     * and 0.0 = deep natural 3D human skull anatomy.
     */
    private fun evaluateMeshPlanarity(meshPoints: FloatArray): Float {
        if (meshPoints.size < 468 * 3) return 0f

        // Key landmarks:
        // Nose tip: 1, Nose bridge: 168, Chin: 152, Forehead: 10
        // Left eye outer: 263, Right eye outer: 33
        // Left cheek: 234, Right cheek: 454
        fun pt(idx: Int): FloatArray {
            val off = idx * 3
            return floatArrayOf(meshPoints[off], meshPoints[off + 1], meshPoints[off + 2])
        }

        val noseTip = pt(1)
        val eyeL = pt(263)
        val eyeR = pt(33)
        val cheekL = pt(234)
        val cheekR = pt(454)

        val faceWidth = hypot(cheekR[0] - cheekL[0], cheekR[1] - cheekL[1]).coerceAtLeast(1f)
        if (faceWidth < 40f) return 0f // Face too small / low-res to judge planarity

        // Eye-plane depth midpoint
        val eyeZ = 0.5f * (eyeL[2] + eyeR[2])
        val cheekZ = 0.5f * (cheekL[2] + cheekR[2])
        val baseZ = 0.5f * (eyeZ + cheekZ)

        // Depth relief: distance nose sticks forward from the eye/cheek base
        val depthRelief = abs(noseTip[2] - baseZ)
        val normalizedRelief = depthRelief / faceWidth

        // Monocular Z relief from ML Kit Face Mesh:
        // A true flat 2D screen or paper print has virtually zero depth variance (normalizedRelief < 0.012).
        // A natural living face exhibits organic nose projection (normalizedRelief >= 0.025).
        return when {
            normalizedRelief < 0.012f -> 0.95f // Completely flat 2D plane -> Screen / Paper
            normalizedRelief < 0.020f -> 0.60f // Low depth variance
            normalizedRelief < 0.030f -> 0.30f // Slight flattening
            else -> 0.0f                      // Confirmed natural 3D relief
        }
    }

    /**
     * Inspect concentric margin outside face bounding box for straight rectangular
     * high-contrast edges (phone chassis / screen bezel).
     */
    private fun evaluateScreenBezelEdges(image: Image, box: Rect, rotationDegrees: Int): Float {
        val planes = image.planes
        if (planes.isEmpty()) return 0f
        val yBuf = planes[0].buffer
        val yRowStride = planes[0].rowStride
        val imgW = image.width
        val imgH = image.height

        val bw = box.width()
        val bh = box.height()
        if (bw < 50 || bh < 50) return 0f

        val isRotated = (rotationDegrees == 90 || rotationDegrees == 270)
        val upW = if (isRotated) imgH else imgW
        val upH = if (isRotated) imgW else imgH

        fun getPixel(u: Int, v: Int): Int {
            val uClamped = u.coerceIn(0, upW - 1)
            val vClamped = v.coerceIn(0, upH - 1)
            val (rawX, rawY) = when (rotationDegrees) {
                90 -> Pair(vClamped, imgW - 1 - uClamped)
                270 -> Pair(imgW - 1 - vClamped, uClamped)
                180 -> Pair(imgW - 1 - uClamped, imgH - 1 - vClamped)
                else -> Pair(uClamped, vClamped)
            }
            val idx = (rawY.coerceIn(0, imgH - 1)) * yRowStride + (rawX.coerceIn(0, imgW - 1))
            return if (idx in 0 until yBuf.limit()) yBuf.get(idx).toInt() and 0xFF else 0
        }

        // Bezel check zones: 12% to 35% margin to the left and right of face box in upright coordinate space
        val leftBezelX = (box.left - (bw * 0.25f).toInt()).coerceIn(4, upW - 8)
        val rightBezelX = (box.right + (bw * 0.25f).toInt()).coerceIn(4, upW - 8)
        val topBezelY = (box.top - (bh * 0.20f).toInt()).coerceIn(4, upH - 8)
        val bottomBezelY = (box.bottom + (bh * 0.20f).toInt()).coerceIn(4, upH - 8)

        var strongLeft = 0
        var totalLeft = 0
        var y = box.top
        while (y < box.bottom) {
            val p0 = getPixel(leftBezelX - 3, y)
            val p1 = getPixel(leftBezelX + 3, y)
            val gradL = abs(p1 - p0)
            if (gradL > 45) strongLeft++
            totalLeft++
            y += 8
        }

        // Vertical gradient scan on right bezel
        var strongRight = 0
        var totalRight = 0
        y = box.top
        while (y < box.bottom) {
            val p0 = getPixel(rightBezelX - 3, y)
            val p1 = getPixel(rightBezelX + 3, y)
            val gradR = abs(p1 - p0)
            if (gradR > 45) strongRight++
            totalRight++
            y += 8
        }

        // Horizontal gradient scan on top bezel
        var strongTop = 0
        var totalTop = 0
        var x = box.left
        while (x < box.right) {
            val p0 = getPixel(x, topBezelY - 3)
            val p1 = getPixel(x, topBezelY + 3)
            val gradT = abs(p1 - p0)
            if (gradT > 45) strongTop++
            totalTop++
            x += 8
        }

        val rL = if (totalLeft > 0) strongLeft.toFloat() / totalLeft else 0f
        val rR = if (totalRight > 0) strongRight.toFloat() / totalRight else 0f
        val rT = if (totalTop > 0) strongTop.toFloat() / totalTop else 0f

        // A phone screen framing a face MUST have bezels enclosing the face on multiple sides (both left and right, or top and side)
        // A single-sided straight edge (such as an ID card held by the user or an open door) is NOT a framing screen bezel.
        val isEnclosedBezel = (rL > 0.35f && rR > 0.35f) || (rT > 0.35f && (rL > 0.35f || rR > 0.35f))
        if (!isEnclosedBezel) return 0f

        val avgRatio = (rL + rR + rT) / 3f
        return when {
            avgRatio > 0.35f -> 0.85f // Distinct rectangular bezel border enclosing face
            avgRatio > 0.25f -> 0.60f
            else -> 0.0f
        }
    }
}
