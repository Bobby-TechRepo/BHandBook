package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.domain.model.CanvasState
import com.bsoft.bhandbook.domain.model.GraphPanelState
import com.bsoft.bhandbook.domain.model.ProcessingStatus

/** Keeps the remembered graph panels in step with the canvas. */
object GraphPanels {
    /** Statuses of an expression that is being (re)read: it is not graphable for a moment, which must not make its graph forget where it was. */
    private val BEING_READ = setOf(ProcessingStatus.IDLE, ProcessingStatus.PENDING, ProcessingStatus.RECOGNIZING)

    /**
     * Forgets panels of expressions that no longer exist, and hides a visible graph whose expression has settled on something that cannot be graphed.
     * A hidden graph keeps its position and size, so showing it again puts it back where it was.
     */
    fun reconcile(saved: GraphPanelState, state: CanvasState, insights: Insights): GraphPanelState {
        val exists = state.expressions
        return GraphPanelState(
            visible = saved.visible.filter { id -> exists[id]?.let { e -> id in insights.graphable || e.status in BEING_READ } == true }.toSet(),
            offsets = saved.offsets.filterKeys { it in exists },
            scales = saved.scales.filterKeys { it in exists }.mapValues { ExpressionLayout.clampGraphScale(it.value) },
            views = saved.views.filterKeys { it in exists },
        )
    }
}
