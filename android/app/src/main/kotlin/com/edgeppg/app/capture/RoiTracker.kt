package com.edgeppg.app.capture

import android.graphics.Rect
import android.media.Image
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetector
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Stage 2 — Face-mesh-driven ROI tracker.
 *
 * For each analyzer frame:
 *  1. If a fresh detector run is not due (we run every Nth frame, ML Kit
 *     is heavy), reuse the last smoothed ROI rects.
 *  2. If a fresh run IS due, fire ML Kit's face mesh detector; on success,
 *     take the primary face's bounding box and convert it to three
 *     fractional-box ROIs (forehead, left cheek, right cheek), apply an
 *     EMA smoothing on the rect corners, and store.
 *  3. Whether or not the detector ran, compute mean RGB per ROI from the
 *     YUV_420_888 planes (stride-sampled every 4th pixel — BT.601
 *     full-range integer path, zero allocations on the hot path).
 *
 * The detector runs asynchronously. The analyzer thread does a short
 * bounded wait (1 frame budget at 30 fps, i.e. ≤ 33 ms) on a flag. If the
 * detector doesn't return in budget, the analyzer reuses the previous ROIs
 * and the next frame will run detection again — we never block the
 * analyzer.
 *
 * Configuration (matches Architecture §4.5):
 *  - ROI1 forehead    : x [0.30, 0.70] y [0.08, 0.28]
 *  - ROI2 left cheek  : x [0.08, 0.32] y [0.45, 0.65]
 *  - ROI3 right cheek : x [0.68, 0.92] y [0.45, 0.65]
 *  - Eyes / lips / nose excluded by the box layout.
 *  - EMA alpha = 0.15 (configurable).
 *
 * On-failure policy:
 *  - Detector exception → keep last ROIs, log via [Log.error], continue.
 *    We never zero-fill.
 */
class RoiTracker(
    private val emaAlpha: Float = 0.15f,
    private val detectionEveryNFrames: Int = 3,
    private val detector: FaceMeshDetector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build()
    ),
) {
    data class Rois(val forehead: Rect, val cheekL: Rect, val cheekR: Rect)

    /**
     * Per-frame compute result.
     *  - `rois` are the smoothed rects to use for the current frame.
     *  - `facePresent` is true iff a face was seen recently (within
     *    `detectionEveryNFrames` of a successful detection).
     *  - `hadFreshDetection` is true iff THIS frame triggered a detector
     *    run that succeeded.
     *  - `meshPoints` are the 468 3D landmark positions from the most
     *    recent successful detection. Empty when no face is present or
     *    the bounding-box-only mode is in effect.
     */
    data class Result(
        val rois: Rois?,
        val facePresent: Boolean,
        val hadFreshDetection: Boolean,
        val meshPoints: FloatArray = FloatArray(0),
        val faceBox: Rect? = null,
        val multipleFaces: Boolean = false,
        val faceCount: Int = 0,
    ) {
        /** Number of mesh points × 3 (x, y, z). */
        val meshPointCount: Int get() = meshPoints.size / 3
    }

    private var smooth: FloatArray? = null // [l1,t1,r1,b1, l2.., l3..] length 12
    private val detecting = AtomicBoolean(false)
    private var lastRois: Rois? = null
    private var lastMesh: FloatArray = FloatArray(0) // 468 × 3 = 1404 floats
    private var lastBox: Rect? = null
    private var framesSinceDetect = Int.MAX_VALUE

    /**
     * Reset tracker state at session boundary to eliminate cross-session leakage.
     */
    fun reset() {
        smooth = null
        lastRois = null
        lastMesh = FloatArray(0)
        lastBox = null
        framesSinceDetect = Int.MAX_VALUE
        detecting.set(false)
    }

    /**
     * Run detector at most every [detectionEveryNFrames] frames. Returns
     * the latest smoothed ROIs on skipped frames. Returns null when no
     * face has been seen yet.
     *
     * NOTE: blocking-with-timeout kept tiny; caller runs on the single
     * analyzer thread. We never touch the UI thread here.
     */
    fun update(image: Image, rotationDegrees: Int): Result? {
        framesSinceDetect++

        // Fast path: skip detection this frame; reuse last ROIs.
        if (framesSinceDetect < detectionEveryNFrames && lastRois != null) {
            return Result(
                rois = lastRois,
                facePresent = true,
                hadFreshDetection = false,
                meshPoints = lastMesh,
                faceBox = lastBox,
                multipleFaces = false,
                faceCount = 1,
            )
        }
        // Only one in-flight detection at a time.
        if (!detecting.compareAndSet(false, true)) {
            val isPresent = lastRois != null && framesSinceDetect <= 2
            return Result(
                rois = if (isPresent) lastRois else null,
                facePresent = isPresent,
                hadFreshDetection = false,
                meshPoints = if (isPresent) lastMesh else FloatArray(0),
                faceBox = if (isPresent) lastBox else null,
                multipleFaces = false,
                faceCount = if (isPresent) 1 else 0,
            )
        }

        return try {
            val input = InputImage.fromMediaImage(image, rotationDegrees)
            val task = detector.process(input)
            val meshes: List<FaceMesh>? = try {
                com.google.android.gms.tasks.Tasks.await(task, 65, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                null
            }
            if (meshes == null) {
                // Timeout or still running: reuse last valid ROIs if very fresh (<= 2 frames), else drop
                val isPresent = lastRois != null && framesSinceDetect <= 2
                return Result(
                    rois = if (isPresent) lastRois else null,
                    facePresent = isPresent,
                    hadFreshDetection = false,
                    meshPoints = if (isPresent) lastMesh else FloatArray(0),
                    faceBox = if (isPresent) lastBox else null,
                    multipleFaces = false,
                    faceCount = if (isPresent) 1 else 0,
                )
            }
            if (meshes.isEmpty()) {
                smooth = null
                lastRois = null
                lastMesh = FloatArray(0)
                lastBox = null
                framesSinceDetect = 0
                Result(
                    rois = null,
                    facePresent = false,
                    hadFreshDetection = true,
                    meshPoints = lastMesh,
                    faceBox = null,
                    multipleFaces = false,
                    faceCount = 0,
                )
            } else if (meshes.size > 1) {
                // Multi-face rejection (Fix 2.1):
                // Never select meshes[0] when multiple faces exist.
                // Invalidate ROIs and facePresent immediately.
                smooth = null
                lastRois = null
                lastMesh = FloatArray(0)
                lastBox = null
                framesSinceDetect = 0
                Result(
                    rois = null,
                    facePresent = false,
                    hadFreshDetection = true,
                    meshPoints = lastMesh,
                    faceBox = null,
                    multipleFaces = true,
                    faceCount = meshes.size,
                )
            } else {
                val box = meshes[0].boundingBox
                val isRotated = (rotationDegrees == 90 || rotationDegrees == 270)
                val upWidth = if (isRotated) image.height else image.width
                val upHeight = if (isRotated) image.width else image.height
                val meshPoints = extractMeshPoints(meshes[0])

                if (!validateHumanFace(meshPoints, box, upWidth, upHeight)) {
                    // Rejection of false-positives (fingers, hands, screen clutter)
                    smooth = null
                    lastRois = null
                    lastMesh = FloatArray(0)
                    lastBox = null
                    framesSinceDetect = 0
                    Result(
                        rois = null,
                        facePresent = false,
                        hadFreshDetection = true,
                        meshPoints = FloatArray(0),
                        faceBox = null,
                        multipleFaces = false,
                        faceCount = 0,
                    )
                } else {
                    val raw = rawRoisFromMeshOrBox(meshPoints, box, upWidth, upHeight)
                    val sm = ema(raw)
                    lastRois = Rois(
                        Rect(sm[0].roundToInt(), sm[1].roundToInt(),
                             sm[2].roundToInt(), sm[3].roundToInt()),
                        Rect(sm[4].roundToInt(), sm[5].roundToInt(),
                             sm[6].roundToInt(), sm[7].roundToInt()),
                        Rect(sm[8].roundToInt(), sm[9].roundToInt(),
                             sm[10].roundToInt(), sm[11].roundToInt()),
                    )
                    lastMesh = meshPoints
                    lastBox = box
                    framesSinceDetect = 0
                    Result(
                        rois = lastRois,
                        facePresent = true,
                        hadFreshDetection = true,
                        meshPoints = lastMesh,
                        faceBox = lastBox,
                        multipleFaces = false,
                        faceCount = 1,
                    )
                }
            }
        } catch (t: Throwable) {
            com.edgeppg.app.Log.error("capture", "face mesh failed", t)
            smooth = null
            lastRois = null
            lastBox = null
            Result(
                rois = null,
                facePresent = false,
                hadFreshDetection = false,
                meshPoints = lastMesh,
                faceBox = null,
                multipleFaces = false,
                faceCount = 0,
            )
        } finally {
            detecting.set(false)
        }
    }

    /**
     * Flatten the 468 3D mesh points into a single float array
     * [x0, y0, z0, x1, y1, z1, ...] in mesh-point index order. Z is in
     * the same coordinate space as X / Y but smaller magnitude (relative
     * depth, not millimetres).
     *
     * The MediaPipe face mesh indices used by the geometry extractors
     * (Stage 6) reference this ordering — see [behavior.MeshLandmarks].
     */
    private fun extractMeshPoints(mesh: FaceMesh): FloatArray {
        val pts = mesh.allPoints
        if (pts.isEmpty()) return FloatArray(0)
        val out = FloatArray(pts.size * 3)
        var i = 0
        for (p in pts) {
            val pos = p.position
            out[i++] = pos.x
            out[i++] = pos.y
            out[i++] = pos.z
        }
        return out
    }

    /**
     * Anthropometric and topological human face validator.
     * Rejects false detections on fingers, hands, phone screens, and background clutter.
     */
    private fun validateHumanFace(meshPoints: FloatArray, box: Rect, upW: Int, upH: Int): Boolean {
        val bw = box.width()
        val bh = box.height()

        // 1. Physical size check: face must occupy reasonable portion of the camera frame
        if (bw < upW * 0.12f || bh < upH * 0.14f) return false
        if (bw > upW * 0.95f || bh > upH * 0.95f) return false

        // 2. Aspect ratio: human faces have height-to-width ratio between 0.85 and 2.20
        val aspect = bh.toFloat() / bw.toFloat().coerceAtLeast(1f)
        if (aspect < 0.85f || aspect > 2.20f) return false

        // 3. Landmark topology invariants when full 468 mesh is present
        if (meshPoints.size >= 468 * 3) {
            fun ptX(idx: Int): Float = meshPoints[idx * 3]
            fun ptY(idx: Int): Float = meshPoints[idx * 3 + 1]

            val foreheadY = ptY(10)
            val chinY = ptY(152)
            val vertSpan = chinY - foreheadY
            if (vertSpan < upH * 0.14f) return false

            val eyeSpanX = kotlin.math.abs(ptX(263) - ptX(33))
            if (eyeSpanX < bw * 0.20f) return false

            val eyeTiltY = kotlin.math.abs(ptY(263) - ptY(33))
            if (eyeTiltY > bw * 0.35f) return false

            val noseY = ptY(1)
            if (noseY <= (foreheadY - bh * 0.05f) || noseY >= (chinY + bh * 0.05f)) return false

            val mouthY = 0.5f * (ptY(61) + ptY(291))
            if (mouthY <= foreheadY || mouthY >= (chinY + bh * 0.05f)) return false
        }
        return true
    }

    private fun rawRoisFromMeshOrBox(meshPoints: FloatArray, box: Rect, w: Int, h: Int): FloatArray {
        if (meshPoints.size >= 468 * 3) {
            fun boxFromIndices(indices: IntArray): FloatArray {
                var minX = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE
                var minY = Float.MAX_VALUE
                var maxY = Float.MIN_VALUE
                for (idx in indices) {
                    val off = idx * 3
                    if (off + 1 < meshPoints.size) {
                        val x = meshPoints[off]
                        val y = meshPoints[off + 1]
                        if (!x.isNaN() && !y.isNaN()) {
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }
                    }
                }
                if (minX >= maxX || minY >= maxY) return FloatArray(0)
                val l = minX.coerceIn(0f, (w - 16).toFloat())
                val t = minY.coerceIn(0f, (h - 16).toFloat())
                val r = maxX.coerceIn(16f, w.toFloat())
                val b = maxY.coerceIn(16f, h.toFloat())
                return floatArrayOf(l, t, r.coerceAtLeast(l + 16f), b.coerceAtLeast(t + 16f))
            }

            // Forehead: cluster around landmarks 10, 67, 109, 338, 297, 151, 9, 68, 298
            val fh = boxFromIndices(intArrayOf(10, 67, 109, 338, 297, 151, 9, 68, 298))
            // Anatomical Left Cheek: SCREEN RIGHT (landmarks 280, 347, 352, 425, 330, 371)
            val cl = boxFromIndices(intArrayOf(280, 347, 352, 425, 330, 371))
            // Anatomical Right Cheek: SCREEN LEFT (landmarks 50, 118, 123, 205, 101, 142)
            val cr = boxFromIndices(intArrayOf(50, 118, 123, 205, 101, 142))

            if (fh.isNotEmpty() && cl.isNotEmpty() && cr.isNotEmpty()) {
                return fh + cl + cr
            }
        }
        return rawRoisFromBox(box, w, h)
    }

    private fun rawRoisFromBox(box: Rect, w: Int, h: Int): FloatArray {
        val bw = box.width().toFloat()
        val bh = box.height().toFloat()
        fun r(fx0: Float, fy0: Float, fx1: Float, fy1: Float): FloatArray {
            val l = (box.left + bw * fx0).coerceIn(0f, (w - 16).toFloat())
            val t = (box.top + bh * fy0).coerceIn(0f, (h - 16).toFloat())
            val rr = (box.left + bw * fx1).coerceIn(16f, w.toFloat())
            val b = (box.top + bh * fy1).coerceIn(16f, h.toFloat())
            return floatArrayOf(
                l, t, rr.coerceAtLeast(l + 16f), b.coerceAtLeast(t + 16f)
            )
        }
        return (
            r(0.30f, 0.08f, 0.70f, 0.28f) + // ROI1: Forehead (centered X, top Y)
            r(0.68f, 0.45f, 0.92f, 0.65f) + // ROI2: Anatomical Left Cheek (Screen Right: X [0.68, 0.92])
            r(0.08f, 0.45f, 0.32f, 0.65f)   // ROI3: Anatomical Right Cheek (Screen Left: X [0.08, 0.32])
        )
    }

    private fun ema(raw: FloatArray): FloatArray {
        val s = smooth
        if (s == null) {
            smooth = raw.copyOf()
            return raw
        }
        for (i in raw.indices) {
            s[i] = emaAlpha * raw[i] + (1f - emaAlpha) * s[i]
        }
        return s
    }

    /**
     * Compute mean R, G, B per ROI directly from YUV_420_888 planes
     * (stride-sampled every 4th pixel, BT.601 full-range integer path).
     *
     * Maps upright display coordinates (u, v) back to sensor space (xRaw, yRaw)
     * using [rotationDegrees] so forehead and cheeks are sampled from the correct
     * sensor pixels regardless of device orientation.
     *
     * Writes into [out] float[9]. Returns false if any ROI is invalid
     * (zero-sized or out-of-frame).
     *
     * Zero allocations on the hot path: no Bitmap, no FloatArray per
     * frame, no conversions beyond the integer math shown inline.
     */
    fun meanRgbPerRoi(image: Image, rois: Rois, out: FloatArray, rotationDegrees: Int = 0): Boolean {
        if (out.size < 9) return false
        val rects = arrayOf(rois.forehead, rois.cheekL, rois.cheekR)
        val planes = image.planes
        if (planes.size < 3) return false
        val yRow = planes[0].rowStride
        val uvRow = planes[1].rowStride
        val uvPix = planes[1].pixelStride
        val wRaw = image.width
        val hRaw = image.height
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer

        for (ri in 0..2) {
            val r = rects[ri]
            var sumR = 0L; var sumG = 0L; var sumB = 0L
            var n = 0
            var v = r.top
            while (v < r.bottom) {
                var u = r.left
                while (u < r.right) {
                    val xRaw: Int
                    val yRaw: Int
                    when (rotationDegrees) {
                        90 -> {
                            xRaw = v.coerceIn(0, wRaw - 1)
                            yRaw = (hRaw - 1 - u).coerceIn(0, hRaw - 1)
                        }
                        180 -> {
                            xRaw = (wRaw - 1 - u).coerceIn(0, wRaw - 1)
                            yRaw = (hRaw - 1 - v).coerceIn(0, hRaw - 1)
                        }
                        270 -> {
                            xRaw = (wRaw - 1 - v).coerceIn(0, wRaw - 1)
                            yRaw = u.coerceIn(0, hRaw - 1)
                        }
                        else -> {
                            xRaw = u.coerceIn(0, wRaw - 1)
                            yRaw = v.coerceIn(0, hRaw - 1)
                        }
                    }
                    val yIdx = yRaw * yRow + xRaw
                    if (yIdx >= 0 && yIdx < yBuf.limit()) {
                        val yv = yBuf.get(yIdx).toInt() and 0xFF
                        val uvx = xRaw / 2
                        val uvy = yRaw / 2
                        val uvIdx = uvy * uvRow + uvx * uvPix
                        if (uvIdx >= 0 && uvIdx < uBuf.limit() && uvIdx < vBuf.limit()) {
                            val vv = vBuf.get(uvIdx).toInt() and 0xFF
                            val uu = uBuf.get(uvIdx).toInt() and 0xFF
                            // ITU-R BT.601 full-range YUV → RGB, integer path.
                            var rr = yv + ((359 * (vv - 128)) shr 8)
                            var gg = yv - (((88 * (uu - 128) + 183 * (vv - 128))) shr 8)
                            var bb = yv + ((454 * (uu - 128)) shr 8)
                            rr = rr.coerceIn(0, 255)
                            gg = gg.coerceIn(0, 255)
                            bb = bb.coerceIn(0, 255)
                            sumR += rr; sumG += gg; sumB += bb
                            n++
                        }
                    }
                    u += 4
                }
                v += 4
            }
            if (n == 0) return false
            out[ri * 3]     = sumR.toFloat() / n
            out[ri * 3 + 1] = sumG.toFloat() / n
            out[ri * 3 + 2] = sumB.toFloat() / n
        }

        // Chromatic validation: ensure all 3 ROIs are authentic human skin tissue
        for (ri in 0..2) {
            val r = out[ri * 3]
            val g = out[ri * 3 + 1]
            val b = out[ri * 3 + 2]
            val lum = (r + g + b) / 3f
            if (lum < 20f || lum > 248f) return false // Total darkness or blown-out lamp
            val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            if (cr <= cb - 5f) return false // Non-skin (neutral background, ceiling, screen)
        }
        return true
    }

    /** Helper used by MainActivity to peek at the current frame count for diagnostics. */
    fun framesSinceDetection(): Int = framesSinceDetect
}
