package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.model.Stroke
import kotlin.math.abs
import kotlin.math.max

/**
 * Rows of handwriting. Two lines of writing sit closer together than the grouper's "related" distance, so without this they merged into one
 * expression (and were read as garbage like "x_x-y=5"). A stroke that is a whole line away from every row of an expression that already has a full
 * line of writing starts a new expression, unless the expression has a bar: fractions, roots and columns legitimately stack ink vertically, and
 * bars always join what they touch. Everything is relative to the size of the handwriting.
 */
object TextLines {
    const val LINE_OFFSET = 0.9f        // centre-to-centre distance, in glyph heights, at which a stroke counts as another line
    const val MIN_ROW_STROKES = 3       // an expression needs at least one row this full before anything can start a new line
    const val BAR_WIDTH = 1.5f          // a flat stroke this long (in glyph heights) is a bar
    private const val MIN_ROW_JOIN = 0.65f   // a stroke joins a row if its centre is this close (in glyph heights) to the row's centre

    /** Typical glyph height: the median of the taller half of the upright strokes (dots and bars are ignored); null if there are none. */
    fun glyphHeight(strokes: List<Stroke>): Float? {
        val upright = strokes.map { it.bounds }.filter { !it.isEmpty && it.height > 0f && it.height > 0.5f * it.width }.sortedByDescending { it.height }
        if (upright.isEmpty()) return null
        val top = upright.take((upright.size + 1) / 2)
        return top.map { it.height }.sorted()[top.size / 2]
    }

    fun isLongBar(b: WorldRect, h: Float) = !b.isEmpty && b.width >= BAR_WIDTH * h && b.height <= 0.25f * b.width

    private fun isUpright(b: WorldRect) = !b.isEmpty && b.height > 0f && b.height > 0.5f * b.width

    /** Groups strokes into rows by the vertical position of their centres (decimal points and flat tops of digits stay with their row). */
    fun clusterRows(strokes: List<Stroke>, h: Float): List<List<Stroke>> {
        val rows = ArrayList<MutableList<Stroke>>()
        for (s in strokes.sortedBy { it.bounds.centerY }) {
            val current = rows.lastOrNull()
            val fits = current != null && abs(s.bounds.centerY - centre(current)) <= MIN_ROW_JOIN * h
            if (fits) current!!.add(s) else rows.add(mutableListOf(s))
        }
        return rows
    }

    private fun centre(row: List<Stroke>): Float = row.map { it.bounds.centerY }.sorted()[row.size / 2]

    /** Writing that runs steeply (sideways, diagonal, upside down) stacks its symbols vertically; that is one line, not several. */
    private fun writtenTurned(strokes: List<Stroke>): Boolean {
        val d = com.bsoft.bhandbook.core.comer.OrientationEstimator.estimate(strokes) ?: return false
        return abs(d.angle) > Math.toRadians(20.0) && d.orderCorrelation >= 0.8 && d.axisAlignment >= 0.9
    }

    /** True if [stroke], drawn next to [expression], begins another line of writing rather than continuing a symbol of it. */
    fun startsNewLine(stroke: Stroke, expression: List<Stroke>): Boolean {
        val e = expression.filter { it.points.isNotEmpty() }
        if (e.size < MIN_ROW_STROKES) return false
        val h = glyphHeight(e) ?: return false
        val nb = stroke.bounds
        if (nb.isEmpty || isLongBar(nb, h)) return false                                  // bars join what they touch
        if (e.any { isLongBar(it.bounds, h) }) return false                              // fractions, roots, columns: vertical structure is expected
        if (writtenTurned(e)) return false                                               // sideways writing is one line, not several
        if (max(nb.width, nb.height) < 0.3f * h) return false                            // a speck: decimal point, comma, noise
        val rows = clusterRows(e.filter { isUpright(it.bounds) }, h)
        if (rows.none { it.size >= MIN_ROW_STROKES }) return false
        return rows.minOf { abs(nb.centerY - centre(it)) } >= LINE_OFFSET * h
    }

    /** Splits one group of related strokes into its lines (top to bottom), or returns it unchanged if it is not several full lines of writing. */
    fun split(group: List<Stroke>): List<List<Stroke>> {
        if (group.size <= MIN_ROW_STROKES) return listOf(group)
        val h = glyphHeight(group) ?: return listOf(group)
        if (group.any { isLongBar(it.bounds, h) } || writtenTurned(group)) return listOf(group)
        val rows = clusterRows(group, h)
        if (rows.size < 2) return listOf(group)
        fun uprightCount(r: List<Stroke>) = r.count { isUpright(it.bounds) }
        val lines = rows.filter { uprightCount(it) >= 2 }
        if (lines.size < 2 || lines.none { uprightCount(it) >= MIN_ROW_STROKES }) return listOf(group)
        val centres = lines.map { centre(it) }.sorted()
        if (centres.zipWithNext().any { (a, b) -> b - a < LINE_OFFSET * h }) return listOf(group)   // too close to be separate lines (e.g. exponents)
        val out = lines.map { it.toMutableList() }.sortedBy { centre(it) }
        for (row in rows) if (row !in lines) out.minByOrNull { abs(centre(it) - centre(row)) }!!.addAll(row)       // dots and marks join the nearest line
        return out
    }
}
