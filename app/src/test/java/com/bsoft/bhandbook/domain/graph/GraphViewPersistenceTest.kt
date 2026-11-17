package com.bsoft.bhandbook.domain.graph

import com.bsoft.bhandbook.core.codec.*
import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.math.*
import com.bsoft.bhandbook.domain.editor.*
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import org.junit.Assert.*
import org.junit.Test

class GraphViewPersistenceTest {
    private val v1 = GraphViewport(-2.5, 7.5, -1.0, 6.2); private val v2 = GraphViewport(100.0, 110.0, 0.5, 9.5)
    private val panels = GraphPanelState(setOf("a", "b"), mapOf("a" to Vec2(5f, 6f)), mapOf("a" to 1.5f), mapOf("a" to v1, "b" to v2))

    @Test fun views_roundTripThroughTheCodec() { assertEquals(panels, GraphPanelCodec.decode(GraphPanelCodec.encode(panels))) }
    @Test fun aSavedStateFromBefore_hasNoViews() {
        val old = GraphPanelCodec.decode("{\"v\":1,\"visible\":[\"a\"],\"offsets\":{},\"scales\":{}}"); assertEquals(setOf("a"), old.visible); assertTrue(old.views.isEmpty())
    }
    @Test fun damagedViewsAreDropped_theRestSurvives() {
        val text = "{\"v\":1,\"visible\":[\"a\"],\"offsets\":{},\"scales\":{},\"views\":{\"ok\":[0,10,0,5],\"nan\":[1e999,1,0,1],\"inverted\":[5,1,0,1],\"short\":[1,2,3],\"text\":\"x\",\"huge\":[0,1e300,0,1],\"speck\":[0,1e-12,0,1]}}"
        assertEquals(setOf("ok"), GraphPanelCodec.decode(text).views.keys)
    }
    @Test fun invalidViewsAreNotWritten() { val s = GraphPanelState(setOf("a"), views = mapOf("a" to GraphViewport(1.0, 1.0, 0.0, 0.0), "b" to v1)); assertEquals(setOf("b"), GraphPanelCodec.decode(GraphPanelCodec.encode(s)).views.keys) }

    private fun insights(vararg graphable: String) = Insights(emptyMap(), graphable.toSet())
    private fun canvas(vararg ids: String) = CanvasState(emptyList(), ids.associateWith { ExpressionObject(it, recognized = "y=x", status = ProcessingStatus.EVALUATED) })
    @Test fun reconcile_keepsViewsOfExistingExpressions_andForgetsTheRest() {
        val r = GraphPanels.reconcile(panels, canvas("a"), insights("a")); assertEquals(mapOf("a" to v1), r.views)
    }
    @Test fun aHiddenGraphKeepsItsView() { val r = GraphPanels.reconcile(GraphPanelState(emptySet(), views = mapOf("a" to v1)), canvas("a"), insights("a")); assertEquals(v1, r.views["a"]) }

    @Test fun graphFor_buildsTheGraphForTheChosenView() {
        val parser = DefaultMathParser(); val ci = CanvasInsights(parser, DefaultSolutionEngine())
        val state = CanvasState(listOf(Stroke("s", listOf(StrokePoint(0f, 0f), StrokePoint(100f, 50f)), 3f, 0, "a")), mapOf("a" to ExpressionObject("a", recognized = "y=2x+1", status = ProcessingStatus.EVALUATED)))
        val ins = ci.analyse(state); assertEquals(v2, ci.graphFor("a", state, ins, CalcSettings(), v2)!!.viewport)
        assertNotEquals(v2, ci.graphFor("a", state, ins, CalcSettings())!!.viewport)
    }

    @Test fun views_surviveBackupAndRestore_andFollowTheNewIds() {
        val stroke = { id: String, e: String -> Stroke("s$id", listOf(StrokePoint(0f, 0f), StrokePoint(5f, 5f)), 3f, 0, e) }
        val state = CanvasState(listOf(stroke("a", "a"), stroke("b", "b")), mapOf("a" to ExpressionObject("a", recognized = "y=x"), "b" to ExpressionObject("b", recognized = "y=2x")))
        val file = CanvasFile("n", CanvasBackground.GRID, 1f, 0f, 0f, state, GraphPanelState(setOf("a"), views = mapOf("a" to v1, "ghost" to v2)))
        val back = (CanvasFileCodec.decode(CanvasFileCodec.encode(file)) as CanvasFileResult.Ok).file; assertEquals(mapOf("a" to v1), back.graphs.views)     // the view of an expression that is not there is dropped
        var n = 0; val fresh = CanvasFileCodec.withFreshIds(back) { "N${n++}" }
        assertEquals(1, fresh.graphs.views.size); assertEquals(v1, fresh.graphs.views.values.single()); assertTrue(fresh.graphs.views.keys.single() in fresh.state.expressions)
    }
}
