package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.domain.model.Stroke
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * [angle] is the direction in which the user wrote, in radians on the y-down screen (0 = left to right, +PI/2 = top to bottom,
 * PI = right to left). Rotating the ink by -[angle] makes it read left to right. [linearity] is 0.5 for a blob and 1.0 for a perfect line.
 */
data class OrientationEstimate(
    val angle: Double, val linearity: Double, val strokeCount: Int,
    /** Correlation (-1..1) between the order of drawing and the position along [angle]: about 1 when written in order, about 0 when scrambled. */
    val orderCorrelation: Double = 1.0,
    /** |cos| of the angle between [angle] and the long axis of the ink: about 1 for a line of text, small when the order runs across the ink (two lines). */
    val axisAlignment: Double = 1.0,
)

object OrientationEstimator {
    const val MIN_STROKES = 3

    /**
     * Reading direction from the ORDER of drawing: people write in reading order, so the path of stroke centres over time points
     * along the line of text (and, unlike a principal-axis fit, also says which end is the start: left to right vs right to left).
     * Returns null when there is too little ink or no usable timestamps.
     */
    fun estimate(strokes: List<Stroke>): OrientationEstimate? {
        class Item(val x: Double, val y: Double, val t: Long)
        val items = strokes.filter { it.points.isNotEmpty() }.map { s -> Item(s.points.map { it.x.toDouble() }.average(), s.points.map { it.y.toDouble() }.average(), s.points.first().t) }
        if (items.size < MIN_STROKES) return null
        if (items.maxOf { it.t } == items.minOf { it.t }) return null               // no timing information: the direction cannot be known
        val ordered = items.sortedBy { it.t }
        val n = ordered.size
        val meanI = (n - 1) / 2.0; val meanX = ordered.map { it.x }.average(); val meanY = ordered.map { it.y }.average()
        var vx = 0.0; var vy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for ((i, it) in ordered.withIndex()) {
            val dx = it.x - meanX; val dy = it.y - meanY
            vx += (i - meanI) * dx; vy += (i - meanI) * dy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        if (hypot(vx, vy) < 1e-9) return null
        val trace = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = kotlin.math.sqrt((trace * trace / 4 - det).coerceAtLeast(0.0))
        val l1 = trace / 2 + disc
        val linearity = if (trace <= 0) 0.5 else l1 / trace
        val ux = vx / hypot(vx, vy); val uy = vy / hypot(vx, vy)
        var sip = 0.0; var sii = 0.0; var spp = 0.0
        for ((i, it) in ordered.withIndex()) { val p = (it.x - meanX) * ux + (it.y - meanY) * uy; sip += (i - meanI) * p; sii += (i - meanI) * (i - meanI); spp += p * p }
        val corr = if (sii <= 0 || spp <= 0) 0.0 else sip / kotlin.math.sqrt(sii * spp)
        val axis = 0.5 * atan2(2 * sxy, sxx - syy)
        return OrientationEstimate(atan2(vy, vx), linearity, n, corr, kotlin.math.abs(kotlin.math.cos(atan2(vy, vx) - axis)))
    }
}

/**
 * The direction (degrees, clockwise on screen, snapped to 15) an expression was written in, or 0 for ordinary left-to-right writing, so that the
 * answer can be written the same way up. Derived from the ink alone, so it needs no stored state. Deliberately conservative, because turning
 * ordinary text by mistake is worse than not turning rotated text: it needs enough strokes, a line-like layout, drawing order that clearly follows
 * that line, and no bar (fractions and column sums are written top to bottom but are not rotated).
 */
object WritingDirection {
    private const val MIN_STROKES = 4
    private const val MIN_ANGLE = 20.0
    private const val MIN_LINEARITY = 0.85
    private const val MIN_ORDER_CORRELATION = 0.6
    private const val SNAP = 15.0

    fun of(strokes: List<Stroke>): Double {
        if (strokes.size < MIN_STROKES || hasBar(strokes)) return 0.0
        val e = OrientationEstimator.estimate(strokes) ?: return 0.0
        if (e.linearity < MIN_LINEARITY || e.orderCorrelation < MIN_ORDER_CORRELATION) return 0.0
        val degrees = Math.toDegrees(e.angle)
        if (kotlin.math.abs(degrees) <= MIN_ANGLE) return 0.0
        var snapped = Math.round(degrees / SNAP) * SNAP
        if (snapped > 180.0) snapped -= 360.0
        if (snapped <= -180.0) snapped += 360.0
        return snapped
    }

    /** A long, thin stroke relative to the typical glyph. Uses each stroke's longer side, so it is the same whichever way the ink is turned. */
    private fun hasBar(strokes: List<Stroke>): Boolean {
        fun length(s: Stroke) = maxOf(s.bounds.width, s.bounds.height)
        fun thin(s: Stroke) = minOf(s.bounds.width, s.bounds.height) <= 0.25f * length(s)
        val glyphs = strokes.filter { !it.bounds.isEmpty && !thin(it) }.map { length(it) }.sortedDescending()
        if (glyphs.isEmpty()) return false
        val top = glyphs.take((glyphs.size + 1) / 2); val h = top.sorted()[top.size / 2]
        return strokes.any { !it.bounds.isEmpty && thin(it) && length(it) >= 1.5f * h }
    }
}
