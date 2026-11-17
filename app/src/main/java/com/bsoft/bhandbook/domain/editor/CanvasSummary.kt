package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.model.CanvasState
import com.bsoft.bhandbook.domain.model.ExpressionObject
import com.bsoft.bhandbook.domain.model.ProcessingStatus

/**
 * A plain-text account of the canvas (what was written and what it came to), for sharing to any app. Expressions appear in reading order
 * (top to bottom, then left to right); simultaneous equations are listed together with their answer once; steps are optional.
 */
object CanvasSummary {
    private val UNREAD = setOf(ProcessingStatus.IDLE, ProcessingStatus.PENDING, ProcessingStatus.RECOGNIZING)

    fun build(state: CanvasState, insights: Insights, title: String, includeSteps: Boolean): String {
        val bounds = HashMap<String, WorldRect>()
        for (s in state.strokes) s.expressionId?.let { id -> bounds[id] = bounds[id]?.union(s.bounds) ?: s.bounds }
        val ordered = state.expressions.values.filter { bounds[it.id] != null }.sortedWith(compareBy({ bounds.getValue(it.id).top }, { bounds.getValue(it.id).left }))

        val lines = ArrayList<String>(); val doneSystems = HashSet<SystemSolution>()
        for (e in ordered) {
            val system = insights.systems[e.id]
            if (system != null) {
                if (!doneSystems.add(system)) continue                              // listed once, at its first member
                for (id in system.memberIds) state.expressions[id]?.let { m -> textOf(m).takeIf { it.isNotEmpty() }?.let(lines::add) }       // just the equations: their own "needs the others" status is irrelevant
                lines += "  →  ${system.summary}"
                val anchor = state.expressions[system.anchorId]
                if (includeSteps && anchor?.solutionVisible != false) system.steps.forEach { lines += "    ${it.number}. ${it.explanation}: ${it.resulting}" }
                continue
            }
            val text = label(e) ?: continue
            lines += text
            if (includeSteps && e.solutionVisible) e.steps.forEach { lines += "    ${it.number}. ${it.explanation}: ${it.resulting}" }
        }
        if (lines.isEmpty()) return ""
        val body = lines.joinToString("\n")
        return if (title.isBlank()) body else title.trim() + "\n\n" + body
    }

    /** What the user wrote, as text (a trailing "=" that only asked for the answer is dropped). */
    private fun textOf(e: ExpressionObject): String = (e.displayText?.takeIf { it.isNotBlank() } ?: e.recognized?.trimEnd('=', ' ')?.trim()).orEmpty()

    /** One line for an expression, or null if there is nothing meaningful to say yet. */
    private fun label(e: ExpressionObject): String? {
        val text = textOf(e)
        if (text.isEmpty() || (e.status in UNREAD && e.result == null)) return null
        val result = e.result
        return when {
            result != null -> if (result.contains('=')) "$text  →  $result" else "$text = $result"
            e.status == ProcessingStatus.NEEDS_REVIEW -> "$text  (needs review)"
            e.status == ProcessingStatus.ERROR -> "$text  (${e.error ?: "error"})"
            else -> text
        }
    }
}
