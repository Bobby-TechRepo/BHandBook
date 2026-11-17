package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.model.Stroke

/** Rows of numbers stacked above a bar. [anchorX]/[anchorTop] are where the total is written: under the bar, right-aligned to its right end. */
class ColumnLayout(
    val rows: List<List<Stroke>>, val bar: Stroke, val anchorX: Float, val anchorTop: Float, val bounds: WorldRect,
    /** Ink the user wrote under the bar (their own answer). It is not part of the sum; the app's total is written below it. */
    val answer: List<Stroke> = emptyList(),
)

/**
 * Detects a grocery-style column calculation from the ink alone: two or more rows of numbers above a long horizontal bar, with nothing below the bar.
 * Everything is relative to the size of the handwriting, so zoom does not matter. A stacked fraction (ink below its bar) and a single row over a bar
 * (an unfinished fraction) are deliberately NOT columns. Ink under the bar is accepted only as the user's own answer: exactly one row, below two or
 * more rows (a fraction has exactly one row above its bar), within the bar's span.
 */
object ColumnArithmetic {
    private const val MIN_BAR_HEIGHTS = 1.5f         // bar length, in glyph heights
    private const val MAX_BAR_THICKNESS = 0.25f      // flatness: thickness / length
    private const val LEVEL = 0.15f                  // ink this close to the bar's centre line is "on" the bar
    
    fun detect(strokes: List<Stroke>): ColumnLayout? {
        val usable = strokes.filter { it.points.isNotEmpty() && !it.bounds.isEmpty }
        if (usable.size < 3) return null
        val h = TextLines.glyphHeight(usable) ?: return null

        val bars = usable.filter { it.bounds.width >= MIN_BAR_HEIGHTS * h && it.bounds.height <= MAX_BAR_THICKNESS * it.bounds.width }.sortedByDescending { it.bounds.centerY }
        for (bar in bars) {
            val b = bar.bounds
            val others = usable.filter { it !== bar }
            if (others.any { kotlin.math.abs(it.bounds.centerY - b.centerY) <= LEVEL * h }) continue       // something sits on the bar's own line
            val below = others.filter { it.bounds.centerY > b.centerY + LEVEL * h }
            val above = others.filter { it.bounds.centerY < b.centerY - LEVEL * h }
            if (above.any { it.bounds.centerX < b.left - 0.6f * h || it.bounds.centerX > b.right + 0.6f * h }) continue
            val rows = TextLines.clusterRows(above, h)
            if (rows.size < 2) continue                                                                     // one row over a bar is a (maybe unfinished) fraction
            if (below.isNotEmpty()) {                                                                       // only the user's own single-row answer may sit below
                if (TextLines.clusterRows(below, h).size != 1) continue
                if (below.any { it.bounds.centerX < b.left - 0.6f * h || it.bounds.centerX > b.right + 0.6f * h }) continue
                if (below.fold(WorldRect.Empty) { a, x -> a.union(x.bounds) }.width > 1.1f * b.width) continue
            }
            val extents = rows.map { r -> r.fold(WorldRect.Empty) { a, s -> a.union(s.bounds) } }
            if (b.width < 0.8f * extents.maxOf { it.width }) continue                                       // the bar must span the numbers
            if (extents.maxOf { it.right } > b.right + 0.6f * h) continue
            val answerBounds = below.fold(WorldRect.Empty) { a, x -> a.union(x.bounds) }
            val ink = extents.fold(b) { a, r -> a.union(r) }.union(answerBounds)
            return ColumnLayout(rows, bar, b.right, if (below.isEmpty()) b.bottom else answerBounds.bottom, ink, below)
        }
        return null
    }
}
