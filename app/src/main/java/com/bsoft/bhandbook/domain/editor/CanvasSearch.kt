package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.domain.model.CanvasState

/** One search result: a canvas (when [expressionId] is null, its name matched) or an expression in it. */
data class SearchHit(val canvasId: String, val canvasName: String, val expressionId: String?, val text: String, val result: String?)

/** Finding things across canvases by what was written (the expression's text or its answer) or by the canvas name. Pure. */
object CanvasSearch {
    /** Lower case, no spaces, and the usual operator variants made equal (× · ⋅ = *, ÷ = /, the minus signs = -), so "12 x 8" style differences do not matter. */
    fun normalize(s: String): String = buildString(s.length) {
        for (c in s.lowercase()) when (c) {
            ' ', '\t', '\n', '\r', '\u00A0' -> {}
            '×', '·', '⋅', '∙' -> append('*')
            '÷' -> append('/')
            '−', '–', '—' -> append('-')
            else -> append(c)
        }
    }

    /** Hits in one canvas: its name first, then matching expressions in reading order (top to bottom, then left to right); at most [max]. */
    fun inCanvas(canvasId: String, canvasName: String, state: CanvasState, query: String, max: Int): List<SearchHit> {
        val q = normalize(query)
        if (q.isEmpty() || max <= 0) return emptyList()
        val hits = ArrayList<SearchHit>()
        if (normalize(canvasName).contains(q)) hits += SearchHit(canvasId, canvasName, null, canvasName, null)
        val ordered = state.expressions.values.mapNotNull { e ->
            val strokes = state.strokesOf(e.id)
            if (strokes.isEmpty()) null else Triple(e, strokes.minOf { it.bounds.top }, strokes.minOf { it.bounds.left })
        }.sortedWith(compareBy({ it.second }, { it.third }))
        for ((e, _, _) in ordered) {
            if (hits.size >= max) break
            val text = e.displayText ?: e.recognized ?: continue
            if (listOfNotNull(e.displayText, e.recognized, e.result).any { normalize(it).contains(q) }) hits += SearchHit(canvasId, canvasName, e.id, e.recognized ?: text, e.result)
        }
        return hits.take(max)
    }
}
