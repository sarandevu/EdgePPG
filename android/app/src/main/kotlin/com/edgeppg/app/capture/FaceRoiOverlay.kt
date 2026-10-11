package com.edgeppg.app.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Real-time Facial ROI & Landmark Preview Overlay.
 *
 * Renders dynamic tracking bounding boxes for:
 *  - Cyan (#22D3EE): Forehead ROI
 *  - Emerald (#34D399): Anatomical Left Cheek ROI
 *  - Violet (#A78BFA): Anatomical Right Cheek ROI
 *  - Subtle Indigo: Full Face Boundary & Key Alignment Points
 *
 * Automatically transforms from camera analysis coordinate space to
 * View display dimensions, taking into account front-camera horizontal mirroring
 * and aspect-fill scaling matching CameraX PreviewView.
 */
class FaceRoiOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var rois: RoiTracker.Rois? = null
    private var faceBox: Rect? = null
    private var meshPoints: FloatArray = FloatArray(0)
    private var facePresent: Boolean = false
    private var isMirrored: Boolean = true
    private var camWidth: Int = 480
    private var camHeight: Int = 640
    private var transformMatrix: android.graphics.Matrix? = null
    private val scratchPoint = FloatArray(2)

    var isDocumentMode: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }

    var panRecord: com.edgeppg.app.document.PanDocumentRecord = com.edgeppg.app.document.PanDocumentRecord.empty()
        private set

    fun updatePanCardRecord(record: com.edgeppg.app.document.PanDocumentRecord) {
        panRecord = record
        postInvalidate()
    }

    // Drawing paints: Original Informative Face-Check Color System
    // Forehead: Cyan (#22D3EE), Left Cheek: Emerald (#34D399), Right Cheek: Orange (#FB923C), Reticles: Sky Blue (#38BDF8)
    private val faceBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0x60818CF8.toInt() // Subtle Indigo
    }

    private val foreheadStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF22D3EE.toInt() // Cyan #22D3EE
    }
    private val foreheadFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2822D3EE.toInt() // 16% Cyan
    }

    private val cheekLStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF34D399.toInt() // Emerald Green #34D399
    }
    private val cheekLFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2834D399.toInt() // 16% Emerald Green
    }

    private val cheekRStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFFFB923C.toInt() // Orange #FB923C
    }
    private val cheekRFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x28FB923C.toInt() // 16% Orange
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.WHITE
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC0F172A.toInt() // Dark neutral translucent pill
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x4DFFFFFF.toInt()
    }

    private val docGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFFFBBF24.toInt() // Amber #FBBF24
    }
    private val docGuideFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1AFBBF24.toInt() // 10% Amber
    }
    private val cardBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFBBF24.toInt() // Amber
    }

    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x8038BDF8.toInt() // 50% Sky Blue
    }

    private val scratchRectF = RectF()
    private val badgeRectF = RectF()

    init {
        setWillNotDraw(false)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun updateTracking(
        newRois: RoiTracker.Rois?,
        box: Rect?,
        mesh: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        present: Boolean,
        mirrored: Boolean = true,
        matrix: android.graphics.Matrix? = null
    ) {
        rois = newRois
        faceBox = box
        meshPoints = mesh
        facePresent = present
        isMirrored = mirrored
        transformMatrix = matrix
        if (frameWidth > 0 && frameHeight > 0) {
            camWidth = frameWidth
            camHeight = frameHeight
        }
        postInvalidate()
    }

    fun clear() {
        rois = null
        faceBox = null
        meshPoints = FloatArray(0)
        facePresent = false
        transformMatrix = null
        postInvalidate()
    }

    // Corner bracket paint
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        color = 0xFF38BDF8.toInt() // Sky Blue #38BDF8
    }

    private val statusBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC0F172A.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Draw KYC Document Guide if in document mode
        if (isDocumentMode) {
            val cardW = w * 0.78f
            val cardH = cardW / 1.586f
            val cardL = (w - cardW) / 2f
            val cardT = (h - cardH) / 2f + dp(10f)
            val cardRect = RectF(cardL, cardT, cardL + cardW, cardT + cardH)
            canvas.drawRoundRect(cardRect, dp(10f), dp(10f), docGuideFill)
            canvas.drawRoundRect(cardRect, dp(10f), dp(10f), docGuidePaint)

            val cLen = dp(16f)
            canvas.drawLine(cardRect.left, cardRect.top, cardRect.left + cLen, cardRect.top, cardBracketPaint)
            canvas.drawLine(cardRect.left, cardRect.top, cardRect.left, cardRect.top + cLen, cardBracketPaint)
            canvas.drawLine(cardRect.right, cardRect.top, cardRect.right - cLen, cardRect.top, cardBracketPaint)
            canvas.drawLine(cardRect.right, cardRect.top, cardRect.right, cardRect.top + cLen, cardBracketPaint)
            canvas.drawLine(cardRect.left, cardRect.bottom, cardRect.left + cLen, cardRect.bottom, cardBracketPaint)
            canvas.drawLine(cardRect.left, cardRect.bottom, cardRect.left, cardRect.bottom - cLen, cardBracketPaint)
            canvas.drawLine(cardRect.right, cardRect.bottom, cardRect.right - cLen, cardRect.bottom, cardBracketPaint)
            canvas.drawLine(cardRect.right, cardRect.bottom, cardRect.right, cardRect.bottom + cLen, cardBracketPaint)

            if (panRecord.status == com.edgeppg.app.document.CardTrackingStatus.FORMAT_CHECK_PASSED) {
                drawBadge(canvas, "🪪 PAN: ${panRecord.maskedPan} (VERIFIED)", cardRect.left, cardRect.top - dp(4f), 0xCC064E3B.toInt(), w, textColor = 0xFF34D399.toInt())
                if (panRecord.maskedName.isNotEmpty() && panRecord.maskedName != "NONE" && panRecord.maskedName != "SCANNING...") {
                    drawBadge(canvas, "👤 ${panRecord.maskedName}", cardRect.left, cardRect.bottom + dp(16f), 0xCC0F172A.toInt(), w, textColor = 0xFFCFE5FA.toInt())
                }
            } else {
                drawBadge(canvas, "🪪 ALIGN PAN CARD IN FRAME", cardRect.left, cardRect.top - dp(4f), 0xCC78350F.toInt(), w, textColor = 0xFFFBBF24.toInt())
            }
        }

        // Draw verified PAN badge at top if detected (in either mode)
        if (panRecord.status == com.edgeppg.app.document.CardTrackingStatus.FORMAT_CHECK_PASSED) {
            val verifiedText = "🪪 PAN: ${panRecord.maskedPan} ✓"
            val textW = textPaint.measureText(verifiedText)
            val bx = (w - textW - dp(16f)).coerceAtLeast(dp(8f))
            drawBadge(canvas, verifiedText, bx, dp(12f), 0xCC064E3B.toInt(), w, textColor = 0xFF34D399.toInt())
        }

        if (!facePresent) {
            if (!isDocumentMode) {
                // Draw alignment guide when searching for face
                val badgeText = "ALIGN FACE IN FRAME"
                val textWidth = textPaint.measureText(badgeText)
                val padH = dp(8f)
                val padV = dp(4f)
                val badgeW = textWidth + padH * 2f
                val badgeH = textPaint.textSize + padV * 2f
                val bx = (w - badgeW) / 2f
                val by = dp(12f)
                badgeRectF.set(bx, by, bx + badgeW, by + badgeH)
                statusBadgePaint.color = 0xCC0F172A.toInt()
                canvas.drawRoundRect(badgeRectF, dp(6f), dp(6f), statusBadgePaint)
                badgeBorderPaint.color = 0x80FBBF24.toInt()
                canvas.drawRoundRect(badgeRectF, dp(6f), dp(6f), badgeBorderPaint)
                textPaint.color = 0xFFFBBF24.toInt()
                canvas.drawText(badgeText, bx + padH, by + textPaint.textSize + padV * 0.5f, textPaint)
            }
            return
        }

        // Compute aspect-fill scaling to match PreviewView.ScaleType.FILL_CENTER (used as fallback when Matrix is null)
        val scaleX = w / camWidth.toFloat()
        val scaleY = h / camHeight.toFloat()
        val scale = max(scaleX, scaleY)
        val offsetX = (w - camWidth.toFloat() * scale) / 2f
        val offsetY = (h - camHeight.toFloat() * scale) / 2f

        val activeMatrix = transformMatrix

        fun mapRect(src: Rect, dst: RectF) {
            if (activeMatrix != null) {
                dst.set(src)
                activeMatrix.mapRect(dst)
            } else {
                val l = src.left.toFloat() * scale + offsetX
                val t = src.top.toFloat() * scale + offsetY
                val r = src.right.toFloat() * scale + offsetX
                val b = src.bottom.toFloat() * scale + offsetY
                if (isMirrored) {
                    dst.set(w - r, t, w - l, b)
                } else {
                    dst.set(l, t, r, b)
                }
            }
        }

        fun mapPoint(px: Float, py: Float, dst: FloatArray) {
            if (activeMatrix != null) {
                dst[0] = px
                dst[1] = py
                activeMatrix.mapPoints(dst)
            } else {
                val mapped = px * scale + offsetX
                dst[0] = if (isMirrored) w - mapped else mapped
                dst[1] = py * scale + offsetY
            }
        }

        // 1. Draw primary face bounding box and corner brackets
        faceBox?.let { fb ->
            mapRect(fb, scratchRectF)
            canvas.drawRoundRect(scratchRectF, dp(12f), dp(12f), faceBoxPaint)

            // Draw corner reticle brackets (Sky Blue)
            val bracketLen = dp(14f)
            // Top-left
            canvas.drawLine(scratchRectF.left, scratchRectF.top, scratchRectF.left + bracketLen, scratchRectF.top, bracketPaint)
            canvas.drawLine(scratchRectF.left, scratchRectF.top, scratchRectF.left, scratchRectF.top + bracketLen, bracketPaint)
            // Top-right
            canvas.drawLine(scratchRectF.right, scratchRectF.top, scratchRectF.right - bracketLen, scratchRectF.top, bracketPaint)
            canvas.drawLine(scratchRectF.right, scratchRectF.top, scratchRectF.right, scratchRectF.top + bracketLen, bracketPaint)
            // Bottom-left
            canvas.drawLine(scratchRectF.left, scratchRectF.bottom, scratchRectF.left + bracketLen, scratchRectF.bottom, bracketPaint)
            canvas.drawLine(scratchRectF.left, scratchRectF.bottom, scratchRectF.left, scratchRectF.bottom - bracketLen, bracketPaint)
            // Bottom-right
            canvas.drawLine(scratchRectF.right, scratchRectF.bottom, scratchRectF.right - bracketLen, scratchRectF.bottom, bracketPaint)
            canvas.drawLine(scratchRectF.right, scratchRectF.bottom, scratchRectF.right, scratchRectF.bottom + bracketLen, bracketPaint)
        }

        // 2. Draw key landmark dots if available (eyes, nose, mouth corners)
        if (meshPoints.size >= 468 * 3) {
            val keyIndices = intArrayOf(
                33, 133, 159, 145, 468, // Right Eye
                263, 362, 386, 374, 473, // Left Eye
                1, 168, 152, // Nose, Bridge, Chin
                61, 291 // Mouth corners
            )
            for (idx in keyIndices) {
                val off = idx * 3
                if (off + 1 < meshPoints.size) {
                    val px = meshPoints[off]
                    val py = meshPoints[off + 1]
                    if (!px.isNaN() && !py.isNaN()) {
                        mapPoint(px, py, scratchPoint)
                        canvas.drawCircle(scratchPoint[0], scratchPoint[1], dp(2.5f), landmarkPaint)
                    }
                }
            }
        }

        // 3. Draw active tracking HUD pill (Emerald Green)
        val statusText = "● TRACKING LOCKED: 3 ROIs ACTIVE"
        val statusTextW = textPaint.measureText(statusText)
        val statPadH = dp(6f)
        val statPadV = dp(3f)
        val statBadgeW = statusTextW + statPadH * 2f
        val statBadgeH = textPaint.textSize + statPadV * 2f
        val statBx = dp(8f)
        val statBy = dp(8f)
        badgeRectF.set(statBx, statBy, statBx + statBadgeW, statBy + statBadgeH)
        statusBadgePaint.color = 0xCC064E3B.toInt()
        canvas.drawRoundRect(badgeRectF, dp(4f), dp(4f), statusBadgePaint)
        badgeBorderPaint.color = 0x6634D399.toInt()
        canvas.drawRoundRect(badgeRectF, dp(4f), dp(4f), badgeBorderPaint)
        textPaint.color = 0xFF34D399.toInt()
        canvas.drawText(statusText, statBx + statPadH, statBy + textPaint.textSize + statPadV * 0.5f, textPaint)

        val currentRois = rois ?: return

        // 4. Forehead ROI (Cyan #22D3EE)
        mapRect(currentRois.forehead, scratchRectF)
        canvas.drawRoundRect(scratchRectF, dp(6f), dp(6f), foreheadFill)
        canvas.drawRoundRect(scratchRectF, dp(6f), dp(6f), foreheadStroke)
        drawBadge(canvas, "● FOREHEAD", scratchRectF.left, scratchRectF.top - dp(4f), 0xCC0F172A.toInt(), w, textColor = 0xFF22D3EE.toInt())

        // 5. Anatomical Left Cheek ROI (Emerald Green #34D399)
        mapRect(currentRois.cheekL, scratchRectF)
        canvas.drawRoundRect(scratchRectF, dp(6f), dp(6f), cheekLFill)
        canvas.drawRoundRect(scratchRectF, dp(6f), dp(6f), cheekLStroke)
        drawBadge(canvas, "● L-CHEEK", scratchRectF.left, scratchRectF.top - dp(4f), 0xCC0F172A.toInt(), w, textColor = 0xFF34D399.toInt())

        // 6. Anatomical Right Cheek ROI (Orange #FB923C)
        mapRect(currentRois.cheekR, scratchRectF)
        canvas.drawRoundRect(scratchRectF, dp(6f), dp(6f), cheekRFill)
        canvas.drawRoundRect(scratchRectF, dp(6f), dp(6f), cheekRStroke)
        drawBadge(canvas, "● R-CHEEK", scratchRectF.left, scratchRectF.top - dp(4f), 0xCC0F172A.toInt(), w, textColor = 0xFFFB923C.toInt())
    }

    private fun drawBadge(canvas: Canvas, text: String, x: Float, y: Float, bgColor: Int, maxW: Float, textColor: Int = Color.WHITE) {
        val textWidth = textPaint.measureText(text)
        val padH = dp(5f)
        val padV = dp(2.5f)
        val textH = textPaint.textSize
        val badgeW = textWidth + padH * 2f
        val badgeH = textH + padV * 2f
        val clampedX = x.coerceIn(dp(4f), (maxW - badgeW - dp(4f)).coerceAtLeast(dp(4f)))
        val badgeTop = (y - badgeH).coerceAtLeast(dp(2f))
        badgeRectF.set(
            clampedX,
            badgeTop,
            clampedX + badgeW,
            badgeTop + badgeH
        )
        badgeBgPaint.color = bgColor
        canvas.drawRoundRect(badgeRectF, dp(4f), dp(4f), badgeBgPaint)
        badgeBorderPaint.color = 0x4DFFFFFF.toInt()
        canvas.drawRoundRect(badgeRectF, dp(4f), dp(4f), badgeBorderPaint)
        textPaint.color = textColor
        canvas.drawText(text, clampedX + padH, badgeTop + textH + padV * 0.5f, textPaint)
    }
}
