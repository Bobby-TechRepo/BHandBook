package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class GraphPanelsTest {
    private fun state(vararg e: Pair<String, ProcessingStatus>) = CanvasState(emptyList(), e.associate { (id, s) -> id to ExpressionObject(id, status = s) })
    private fun ins(vararg graphable: String) = Insights(emptyMap(), graphable.toSet())

    @Test fun anExpressionThatWasDeleted_losesItsGraphPositionAndSize() {
        val saved = GraphPanelState(setOf("a", "gone"), mapOf("a" to Vec2(1f, 1f), "gone" to Vec2(2f, 2f)), mapOf("a" to 2f, "gone" to 3f))
        val r = GraphPanels.reconcile(saved, state("a" to ProcessingStatus.EVALUATED), ins("a"))
        assertEquals(setOf("a"), r.visible); assertEquals(setOf("a"), r.offsets.keys); assertEquals(setOf("a"), r.scales.keys)
    }
    @Test fun aHiddenGraph_keepsItsPositionAndSize_soShowingItAgainRestoresThem() {
        val saved = GraphPanelState(emptySet(), mapOf("a" to Vec2(50f, 60f)), mapOf("a" to 2f))
        assertEquals(saved, GraphPanels.reconcile(saved, state("a" to ProcessingStatus.EVALUATED), ins("a")))
    }
    @Test fun aVisibleGraph_isDropped_whenTheExpressionIsSettledAndNoLongerGraphable() {
        val saved = GraphPanelState(visible = setOf("a"))
        assertTrue(GraphPanels.reconcile(saved, state("a" to ProcessingStatus.EVALUATED), ins()).visible.isEmpty())
        assertTrue(GraphPanels.reconcile(saved, state("a" to ProcessingStatus.ERROR), ins()).visible.isEmpty())
        assertTrue(GraphPanels.reconcile(saved, state("a" to ProcessingStatus.NEEDS_REVIEW), ins()).visible.isEmpty())
    }
    @Test fun aVisibleGraph_survivesWhileTheExpressionIsBeingReRead() {
        // editing an equation makes it pending for a moment: the graph must come back with its place and size when it is read again
        for (s in listOf(ProcessingStatus.PENDING, ProcessingStatus.RECOGNIZING, ProcessingStatus.IDLE))
            assertEquals("$s", setOf("a"), GraphPanels.reconcile(GraphPanelState(visible = setOf("a"), offsets = mapOf("a" to Vec2(5f, 5f))), state("a" to s), ins()).visible)
    }
    @Test fun nothingChanges_whenNothingNeedsChanging() {
        val saved = GraphPanelState(setOf("a"), mapOf("a" to Vec2(1f, 2f)), mapOf("a" to 1.5f))
        assertEquals(saved, GraphPanels.reconcile(saved, state("a" to ProcessingStatus.EVALUATED), ins("a")))
    }
    @Test fun anEmptyCanvas_clearsEverything() { assertEquals(GraphPanelState(), GraphPanels.reconcile(GraphPanelState(setOf("a"), mapOf("a" to Vec2(1f, 1f)), mapOf("a" to 2f)), CanvasState(), ins())) }
    @Test fun theResultIsAlwaysValid() {
        val r = GraphPanels.reconcile(GraphPanelState(visible = setOf("a"), scales = mapOf("a" to Float.NaN, "b" to 99f)), state("a" to ProcessingStatus.EVALUATED, "b" to ProcessingStatus.EVALUATED), ins("a", "b"))
        assertTrue(r.scales.values.all { it.isFinite() })
    }
}
