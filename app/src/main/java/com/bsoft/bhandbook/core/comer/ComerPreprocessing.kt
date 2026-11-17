package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.domain.model.Stroke
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ComerPoint(val x: Double, val y: Double)

/** Reference input format: ordered points plus a pen width in canvas pixels. */
class ComerStroke(val points: List<ComerPoint>, val lineWidth: Double)

/**
 * Thresholds of ink-on's isStrokeMeaningful (8 px, 6 points, 15 px) and of its renderer. [targetStrokeThickness] is OURS: the
 * stroke thickness, in pixels of the 128 px-high image the model sees, that the adapter aims for (CROHME-style ink is a few pixels thick).
 */
data class ComerPreprocessConfig(
    val minStrokeSize: Double = 8.0,
    val minTotalPoints: Int = 6,
    val minPathLength: Double = 15.0,
    val targetStrokeThickness: Double = 3.0,
    /**
     * OURS, not the reference's. The reference pads by a constant 16 px, so the same expression reaches the model differently
     * depending on how large it was written (and at what canvas zoom). When non-null, the expression is first scaled so its
     * height is this many pixels (aspect ratio preserved), making recognition independent of writing size. 80 sits in the middle of
     * the 48-80 plateau measured with the real models; null reproduces the reference exactly.
     */
    val canonicalHeight: Double? = 80.0,
)

class ComerInput(
    val tensor: FloatArray,       // [1,1,height,width] row-major, 0 = black background, 1 = white ink
    val height: Int, val width: Int,
    val mask: ByteArray,          // [1,height,width]: 1 = padding, 0 = content
    val contentHeight: Int, val contentWidth: Int,
)

/** Turns the application's strokes into reference-format strokes, independent of world position, zoom and the pen-width setting. */
object ComerStrokeAdapter {
    private const val PAD = 16.0

    /**
     * Position never matters (the preprocessor works relative to the expression's own bounding box). Size matters only if
     * [ComerPreprocessConfig.canonicalHeight] is null; otherwise the expression is normalised to that height first.
     */
    fun fromDomain(strokes: List<Stroke>, config: ComerPreprocessConfig = ComerPreprocessConfig()): List<ComerStroke> {
        var pts = strokes.map { s -> s.points.map { ComerPoint(it.x.toDouble(), it.y.toDouble()) } }
        config.canonicalHeight?.let { pts = normaliseSize(pts, it) }
        val width = lineWidthFor(pts, config)
        return pts.map { ComerStroke(it, width) }
    }

    /** Scales (and moves to the origin) so the expression is [height] tall; flat expressions use a width-based basis so they do not explode. */
    fun normaliseSize(strokes: List<List<ComerPoint>>, height: Double): List<List<ComerPoint>> {
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (s in strokes) for (p in s) { minX = min(minX, p.x); minY = min(minY, p.y); maxX = max(maxX, p.x); maxY = max(maxY, p.y) }
        if (minX == Double.POSITIVE_INFINITY) return strokes
        val k = height / max(max(maxY - minY, (maxX - minX) * 0.1), 1.0)
        return strokes.map { s -> s.map { ComerPoint((it.x - minX) * k, (it.y - minY) * k) } }
    }

    /** The pen width (world units) the reference thresholds were written for: a 4 px canvas line. */
    const val REFERENCE_PEN = 4.0

    /**
     * The reference's meaningful-stroke test, with its pixel thresholds converted to the strokes' own pen width so the result does not
     * depend on canvas zoom: at a 4-unit pen they are exactly the reference's 8 / 15 px.
     */
    fun isMeaningful(strokes: List<Stroke>, config: ComerPreprocessConfig = ComerPreprocessConfig()): Boolean {
        if (strokes.isEmpty()) return false
        val pen = strokes.map { it.width.toDouble() }.average().takeIf { it > 0.0 } ?: REFERENCE_PEN
        val unit = pen / REFERENCE_PEN
        val scaled = config.copy(minStrokeSize = config.minStrokeSize * unit, minPathLength = config.minPathLength * unit)
        val pts = strokes.map { s -> ComerStroke(s.points.map { ComerPoint(it.x.toDouble(), it.y.toDouble()) }, pen) }
        return ComerPreprocessor.isMeaningful(pts, scaled)
    }

    /**
     * Pen thickness (in input units) that makes the ink exactly [ComerPreprocessConfig.targetStrokeThickness] px thick after the
     * preprocessor scales the expression to the model's size. Hence it does not depend on the pen setting or on canvas zoom.
     */
    fun lineWidthFor(strokes: List<List<ComerPoint>>, config: ComerPreprocessConfig = ComerPreprocessConfig()): Double {
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (s in strokes) for (p in s) { minX = min(minX, p.x); minY = min(minY, p.y); maxX = max(maxX, p.x); maxY = max(maxY, p.y) }
        if (minX == Double.POSITIVE_INFINITY) return 2.0
        val rawW = max(1.0, ceil(maxX - minX)) + 2 * PAD; val rawH = max(1.0, ceil(maxY - minY)) + 2 * PAD
        val scale = min(ComerPreprocessor.TARGET_H / rawH, ComerPreprocessor.MAX_W / rawW)
        return max(2.0, config.targetStrokeThickness / scale)
    }
}

/** Kotlin port of ink-on's preprocessing.ts with a built-in anti-aliased rasteriser (no Android Bitmap/Canvas, so it is JVM-testable). */
object ComerPreprocessor {
    const val MODEL_H = 256
    const val MAX_W = 1024
    const val MIN_W = 128
    const val W_ALIGN = 64
    const val TARGET_H = 128.0
    const val PAD = 16

    fun resample(points: List<ComerPoint>, interval: Double = 3.0): List<ComerPoint> {
        if (points.size < 2) return points
        val out = ArrayList<ComerPoint>(); out += points[0]
        var remaining = interval
        for (i in 1 until points.size) {
            val prev = points[i - 1]; val curr = points[i]
            val dx = curr.x - prev.x; val dy = curr.y - prev.y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= remaining) { remaining -= dist; continue }
            var covered = remaining
            while (covered <= dist) {
                val t = covered / dist
                out += ComerPoint(prev.x + dx * t, prev.y + dy * t)
                covered += interval
            }
            remaining = covered - dist
        }
        out += points.last()
        return out
    }

    fun isMeaningful(strokes: List<ComerStroke>, config: ComerPreprocessConfig = ComerPreprocessConfig()): Boolean {
        if (strokes.isEmpty()) return false
        val b = bounds(strokes) ?: return false
        if (b[2] - b[0] < config.minStrokeSize && b[3] - b[1] < config.minStrokeSize) return false
        var total = 0; var length = 0.0
        for (s in strokes) {
            total += s.points.size
            for (i in 1 until s.points.size) length += dist(s.points[i].x - s.points[i - 1].x, s.points[i].y - s.points[i - 1].y)
        }
        return total >= config.minTotalPoints && length >= config.minPathLength
    }

    /** [minX, minY, maxX, maxY] or null if there are no points. */
    private fun bounds(strokes: List<ComerStroke>): DoubleArray? {
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (s in strokes) for (p in s.points) { if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y; if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y }
        return if (minX == Double.POSITIVE_INFINITY) null else doubleArrayOf(minX, minY, maxX, maxY)
    }

    private class Raw(val w: Int, val h: Int, val px: FloatArray)       // coverage 0..1

    fun preprocess(strokes: List<ComerStroke>): ComerInput {
        val raw = render(strokes)
        // scaleToFit
        val scale = min(TARGET_H / raw.h, MAX_W.toDouble() / raw.w)
        val dw = max(1, jsRound(raw.w * scale)); val dh = max(1, jsRound(raw.h * scale))
        val canvasW = min(MAX_W, max(MIN_W, ceil((dw + PAD).toDouble() / W_ALIGN).toInt() * W_ALIGN))
        val tensor = FloatArray(MODEL_H * canvasW)
        resampleInto(raw, dw, dh, tensor, canvasW)
        for (i in tensor.indices) tensor[i] = Math.round(tensor[i] * 255f) / 255f       // the reference reads an 8-bit canvas
        val mask = ByteArray(MODEL_H * canvasW)
        for (y in 0 until MODEL_H) for (x in 0 until canvasW) mask[y * canvasW + x] = if (y < dh && x < dw) 0 else 1
        return ComerInput(tensor, MODEL_H, canvasW, mask, min(dh, MODEL_H), min(dw, canvasW))
    }

    private fun jsRound(v: Double): Int = floor(v + 0.5).toInt()       // Math.round semantics (.5 rounds up)

    // ------------------------------------------------------------------------------------------ rasteriser
    private fun render(strokes: List<ComerStroke>): Raw {
        val b = bounds(strokes) ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val rawW = max(1, ceil(b[2] - b[0]).toInt()); val rawH = max(1, ceil(b[3] - b[1]).toInt())
        val w = rawW + PAD * 2; val h = rawH + PAD * 2
        val raw = Raw(w, h, FloatArray(w * h))
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val pts = resample(stroke.points)
            if (pts.size < 2) continue                                  // moveTo only: nothing is drawn
            val halfWidth = max(2.0, stroke.lineWidth) / 2.0
            val path = flattenPath(pts, b[0], b[1])
            for (i in 0 until path.size / 2 - 1) drawSegment(raw, path[2 * i], path[2 * i + 1], path[2 * i + 2], path[2 * i + 3], halfWidth)
        }
        return raw
    }

    /** The reference's path (moveTo, quadraticCurveTo through midpoints, lineTo last) as a flat x,y polyline. */
    private fun flattenPath(pts: List<ComerPoint>, minX: Double, minY: Double): DoubleArray {
        fun ox(p: ComerPoint) = p.x - minX + PAD
        fun oy(p: ComerPoint) = p.y - minY + PAD
        val out = ArrayList<Double>(pts.size * 8)
        out += ox(pts[0]); out += oy(pts[0])
        if (pts.size == 2) { out += ox(pts[1]); out += oy(pts[1]) }
        else {
            var cx = ox(pts[0]); var cy = oy(pts[0])
            for (i in 1 until pts.size - 1) {
                val qx = ox(pts[i]); val qy = oy(pts[i])
                val ex = (pts[i].x + pts[i + 1].x) / 2 - minX + PAD; val ey = (pts[i].y + pts[i + 1].y) / 2 - minY + PAD
                val chord = dist(ex - cx, ey - cy) + dist(qx - cx, qy - cy)
                val n = max(2, min(24, ceil(chord / 1.5).toInt()))
                for (k in 1..n) {                                       // flatten the quadratic Bezier
                    val t = k.toDouble() / n; val u = 1 - t
                    out += u * u * cx + 2 * u * t * qx + t * t * ex
                    out += u * u * cy + 2 * u * t * qy + t * t * ey
                }
                cx = ex; cy = ey
            }
            out += ox(pts.last()); out += oy(pts.last())
        }
        return out.toDoubleArray()
    }

    /** Capsule (round cap/join) with a 1 px anti-aliased edge; overlapping segments combine with max, so a stroke never double-darkens. */
    private fun drawSegment(r: Raw, x0: Double, y0: Double, x1: Double, y1: Double, halfWidth: Double) {
        val reach = halfWidth + 1.0
        val minX = max(0, floor(min(x0, x1) - reach).toInt()); val maxX = min(r.w - 1, ceil(max(x0, x1) + reach).toInt())
        val minY = max(0, floor(min(y0, y1) - reach).toInt()); val maxY = min(r.h - 1, ceil(max(y0, y1) + reach).toInt())
        val dx = x1 - x0; val dy = y1 - y0; val len2 = dx * dx + dy * dy
        for (py in minY..maxY) for (px in minX..maxX) {
            val cx = px + 0.5; val cy = py + 0.5
            val t = if (len2 == 0.0) 0.0 else (((cx - x0) * dx + (cy - y0) * dy) / len2).coerceIn(0.0, 1.0)
            val d = dist(cx - (x0 + t * dx), cy - (y0 + t * dy))
            val cov = (halfWidth + 0.5 - d).coerceIn(0.0, 1.0).toFloat()
            val i = py * r.w + px
            if (cov > r.px[i]) r.px[i] = cov
        }
    }

    /**
     * Draws [src] scaled to dw x dh into the top-left of [dst] (width dstW, height MODEL_H). Downscaling averages the covered source area
     * (box filter); upscaling interpolates bilinearly.
     */
    private fun resampleInto(src: Raw, dw: Int, dh: Int, dst: FloatArray, dstW: Int) {
        val sx = src.w.toDouble() / dw; val sy = src.h.toDouble() / dh
        val rows = min(dh, MODEL_H); val cols = min(dw, dstW)
        for (y in 0 until rows) for (x in 0 until cols) {
            dst[y * dstW + x] = if (sx > 1.0 || sy > 1.0) boxAverage(src, x * sx, y * sy, (x + 1) * sx, (y + 1) * sy) else bilinear(src, (x + 0.5) * sx - 0.5, (y + 0.5) * sy - 0.5)
        }
    }

    private fun boxAverage(s: Raw, x0: Double, y0: Double, x1: Double, y1: Double): Float {
        val ix0 = floor(x0).toInt(); val ix1 = min(s.w - 1, ceil(x1).toInt() - 1)
        val iy0 = floor(y0).toInt(); val iy1 = min(s.h - 1, ceil(y1).toInt() - 1)
        var sum = 0.0; var area = 0.0
        for (iy in iy0..iy1) {
            val wy = min(y1, iy + 1.0) - max(y0, iy.toDouble())
            if (wy <= 0) continue
            for (ix in ix0..ix1) {
                val wx = min(x1, ix + 1.0) - max(x0, ix.toDouble())
                if (wx <= 0) continue
                sum += s.px[iy * s.w + ix] * wx * wy
                area += wx * wy
            }
        }
        return if (area <= 0) 0f else (sum / area).toFloat()
    }

    private fun bilinear(s: Raw, fx: Double, fy: Double): Float {
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt(); val tx = (fx - x0).toFloat(); val ty = (fy - y0).toFloat()
        fun at(x: Int, y: Int) = s.px[y.coerceIn(0, s.h - 1) * s.w + x.coerceIn(0, s.w - 1)]
        val top = at(x0, y0) * (1 - tx) + at(x0 + 1, y0) * tx; val bot = at(x0, y0 + 1) * (1 - tx) + at(x0 + 1, y0 + 1) * tx
        return top * (1 - ty) + bot * ty
    }
}

/** sqrt(dx^2 + dy^2): Math.hypot guards against overflow we never see and is several times slower on Android. */
private fun dist(dx: Double, dy: Double): Double = sqrt(dx * dx + dy * dy)
