package com.bsoft.bhandbook.domain.history

import com.bsoft.bhandbook.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class HistoryTest {
    private fun s(id: String, x: Float = 0f, expr: String? = null) =
        Stroke(id, listOf(StrokePoint(x, 0f), StrokePoint(x + 10f, 10f)), 4f, 0, expr)

    private fun expr(id: String) = ExpressionObject(id = id)

    @Test fun addStroke_undo_redo() {
        val h = History()
        var st = CanvasState()
        st = h.execute(st, AddStroke(s("a")))
        assertEquals(listOf("a"), st.strokes.map { it.id })
        st = h.undo(st)!!
        assertTrue(st.strokes.isEmpty())
        st = h.redo(st)!!
        assertEquals(listOf("a"), st.strokes.map { it.id })
    }

    @Test fun newCommand_clearsRedoStack() {
        val h = History(); var st = CanvasState()
        st = h.execute(st, AddStroke(s("a")))
        st = h.undo(st)!!
        assertTrue(h.canRedo)
        st = h.execute(st, AddStroke(s("b")))
        assertFalse(h.canRedo)
    }

    @Test fun undoOnEmptyHistory_returnsNull() {
        assertNull(History().undo(CanvasState()))
        assertNull(History().redo(CanvasState()))
    }

    @Test fun removeStrokes_restoresOriginalOrderOnUndo() {
        val h = History()
        var st = CanvasState(strokes = listOf(s("a"), s("b"), s("c")))
        st = h.execute(st, RemoveStrokes(listOf("b")))
        assertEquals(listOf("a", "c"), st.strokes.map { it.id })
        st = h.undo(st)!!
        assertEquals(listOf("a", "b", "c"), st.strokes.map { it.id })
    }

    @Test fun partialErase_replacesStrokeWithParts_andUndoRestoresOriginal() {
        val h = History()
        val original = s("a")
        var st = CanvasState(strokes = listOf(original))
        st = h.execute(st, ReplaceStroke("a", listOf(s("a1"), s("a2"))))
        assertEquals(listOf("a1", "a2"), st.strokes.map { it.id })
        st = h.undo(st)!!
        assertEquals(listOf(original), st.strokes)
    }

    @Test fun moveExpression_movesItsStrokes_andUndoMovesBack() {
        val h = History()
        var st = CanvasState(
            strokes = listOf(s("a", 0f, "e1"), s("b", 50f, "e2")),
            expressions = mapOf("e1" to expr("e1"), "e2" to expr("e2"))
        )
        st = h.execute(st, MoveExpression("e1", 100f, 20f))
        assertEquals(100f, st.strokes[0].points[0].x, 0f)
        assertEquals(20f, st.strokes[0].points[0].y, 0f)
        assertEquals(50f, st.strokes[1].points[0].x, 0f) // unrelated expression untouched
        st = h.undo(st)!!
        assertEquals(0f, st.strokes[0].points[0].x, 0f)
    }

    @Test fun deleteExpression_removesStrokesAndObject_undoRestoresBoth() {
        val h = History()
        var st = CanvasState(
            strokes = listOf(s("a", 0f, "e1"), s("b", 50f, "e2")),
            expressions = mapOf("e1" to expr("e1"), "e2" to expr("e2"))
        )
        st = h.execute(st, DeleteExpression("e1"))
        assertEquals(listOf("b"), st.strokes.map { it.id })
        assertFalse(st.expressions.containsKey("e1"))
        st = h.undo(st)!!
        assertEquals(listOf("a", "b"), st.strokes.map { it.id })
        assertTrue(st.expressions.containsKey("e1"))
    }

    @Test fun updateExpression_isUndoable_forResultsAndSolutions() {
        val h = History()
        var st = CanvasState(expressions = mapOf("e1" to expr("e1")))
        st = h.execute(st, UpdateExpression("e1") { it.copy(result = "40") })
        assertEquals("40", st.expressions["e1"]!!.result)
        st = h.undo(st)!!
        assertNull(st.expressions["e1"]!!.result)
    }

    @Test fun composite_undoesAsOneStep() {
        val h = History(); var st = CanvasState()
        st = h.execute(st, CompositeCommand(listOf(AddStroke(s("a")), AddStroke(s("b")))))
        st = h.undo(st)!!
        assertTrue(st.strokes.isEmpty())
        assertFalse(h.canUndo)
    }

    @Test fun history_isBounded() {
        val h = History(limit = 3); var st = CanvasState()
        repeat(10) { st = h.execute(st, AddStroke(s("s$it"))) }
        var undone = 0
        while (true) { st = h.undo(st) ?: break; undone++ }
        assertEquals(3, undone)
    }

    @Test fun command_storesOnlyDelta_notWholeCanvas() {
        val big = CanvasState(strokes = (0 until 5000).map { s("s$it") })
        val cmd = AddStroke(s("new"))
        // the command object must not retain the canvas it was applied to
        val after = cmd.apply(big)
        assertEquals(5001, after.strokes.size)
        assertEquals(1, cmd.retainedStrokeCount)
    }
}
