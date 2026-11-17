package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.model.Stroke
import kotlin.math.max

/** Spatial clustering of ink into separate mathematical expressions. */
object StrokeGrouper {
    private const val GAP_X = 2.0f       // in units of local glyph size
    private const val GAP_Y = 0.75f

    private fun size(b: WorldRect) = max(b.height, 0.5f * b.width)

    internal fun related(a: WorldRect, b: WorldRect): Boolean {
        val s = max(size(a), size(b))
        return Geometry.gapX(a, b) <= GAP_X * s && Geometry.gapY(a, b) <= GAP_Y * s
    }

    fun group(strokes: List<Stroke>): List<List<Stroke>> {
        val n = strokes.size
        if (n == 0) return emptyList()
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in 0 until n) for (j in i + 1 until n) if (related(strokes[i].bounds, strokes[j].bounds)) parent[find(i)] = find(j)
        return strokes.indices.groupBy { find(it) }.values.map { g -> g.map { strokes[it] } }.flatMap { TextLines.split(it) }
    }
}

sealed interface ExpressionAssignment {
    object New : ExpressionAssignment
    data class Join(val expressionId: String) : ExpressionAssignment
    data class Merge(val ids: List<String>) : ExpressionAssignment
}

/** Decides which expression a freshly drawn stroke belongs to (existing strokes carry their expressionId). */
object ExpressionAssigner {
    fun assign(newStroke: Stroke, existing: List<Stroke>): ExpressionAssignment {
        val byExpression = existing.filter { it.expressionId != null }.groupBy { it.expressionId!! }
        val hits = byExpression.filter { (_, strokes) -> strokes.any { StrokeGrouper.related(it.bounds, newStroke.bounds) } && !TextLines.startsNewLine(newStroke, strokes) }.keys.toList()
        return when (hits.size) { 0 -> ExpressionAssignment.New; 1 -> ExpressionAssignment.Join(hits[0]); else -> ExpressionAssignment.Merge(hits) }
    }
}
