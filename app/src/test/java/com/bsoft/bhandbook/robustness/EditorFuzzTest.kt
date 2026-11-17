package com.bsoft.bhandbook.robustness

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.domain.editor.CanvasEditor
import com.bsoft.bhandbook.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** Random sequences of edits (drawing lines, columns and fractions; erasing; moving; splitting; undo/redo) must never break the canvas's invariants. */
class EditorFuzzTest {
    private fun check(ed: CanvasEditor, context: String) {
        val s = ed.state
        val ids = s.expressions.keys
        for (st in s.strokes) assertTrue("$context: stroke ${st.id} belongs to missing expression ${st.expressionId}", st.expressionId == null || st.expressionId in ids)
        for (id in ids) assertTrue("$context: expression $id has no ink", s.strokesOf(id).isNotEmpty())
        assertEquals("$context: duplicate stroke ids", s.strokes.size, s.strokes.map { it.id }.toSet().size)
        for (st in s.strokes) for (p in st.points) assertTrue("$context: non-finite point", p.x.isFinite() && p.y.isFinite())
    }

    @Test fun randomEditing_keepsTheCanvasConsistent_andUndoRestoresExactly() {
        for (seed in 1..40) {
            val rnd = Random(seed.toLong()); var n = 0
            val ed = CanvasEditor(idGenerator = { "n${n++}" }, clock = { 1L })
            val texts = listOf("25+15=", "x+2=57", "105", "-37", "12*8", "7-4", "2x+5=15", "1.5+2.5")
            var strokeId = 0
            fun draw(strokes: List<Stroke>) { for (s in strokes) ed.addStroke(s.copy(id = "s${strokeId++}", expressionId = null)) }
            for (step in 0 until 45) {
                val before = ed.state; val op = rnd.nextInt(100); val strokesBefore = ed.state.strokes.size
                val ctx = "seed $seed step $step op $op"
                when {
                    op < 38 -> draw(Pen(seed * 100L + step, size = 40f + rnd.nextInt(80), sloppiness = 0.5f).at(rnd.nextFloat() * 800, rnd.nextFloat() * 1200).text(texts[rnd.nextInt(texts.size)]).strokes)
                    op < 46 -> draw(Pen(step.toLong(), size = 70f).at(300f, 300f + rnd.nextInt(600)).frac("12", "34").strokes)
                    op < 52 -> { val y = 300f + rnd.nextInt(800); draw(Pen(step.toLong(), size = 60f).at(100f, y).text("105").strokes + Pen(step + 1L, size = 60f).at(100f, y + 90f).text("-37").strokes
                        + Stroke("bar", (0..10).map { StrokePoint(60f + 40f * it, y + 115f, 9_000L + it) }, 4f, 0)) }
                    op < 62 -> { ed.beginErase(); repeat(1 + rnd.nextInt(5)) { ed.eraseAt(Vec2(rnd.nextFloat() * 900, rnd.nextFloat() * 1400), 10f + rnd.nextInt(60)) }; ed.endErase() }
                    op < 70 && ed.state.expressions.isNotEmpty() -> ed.moveExpression(ed.state.expressions.keys.toList()[rnd.nextInt(ed.state.expressions.size)], rnd.nextFloat() * 400 - 200, rnd.nextFloat() * 400 - 200)
                    op < 76 && ed.state.expressions.isNotEmpty() -> ed.deleteExpression(ed.state.expressions.keys.toList()[rnd.nextInt(ed.state.expressions.size)])
                    op < 84 -> ed.splitMergedLines()
                    op < 92 -> ed.undo()
                    else -> ed.redo()
                }
                check(ed, ctx)
                // an undo right after any change returns the canvas to what it was before that change
                if (op < 84 && ed.state !== before && ed.canUndo) {
                    // every stroke is its own undo step; an erase gesture, a move, a delete and a split are one step each
                    val steps = if (op < 52) ed.state.strokes.size - strokesBefore else 1
                    val after = ed.state; repeat(steps) { ed.undo() }; check(ed, "$ctx (after undo)")
                    assertEquals("$ctx: undo did not restore the strokes", before.strokes.map { it.id to it.expressionId }.toSet(), ed.state.strokes.map { it.id to it.expressionId }.toSet())
                    repeat(steps) { ed.redo() }; check(ed, "$ctx (after redo)")
                    assertEquals("$ctx: redo did not reapply", after.strokes.map { it.id to it.expressionId }.toSet(), ed.state.strokes.map { it.id to it.expressionId }.toSet())
                }
            }
        }
    }

    @Test fun splitting_isIdempotent_andNeverLosesInk() {
        for (seed in 1..30) {
            var n = 0; val ed = CanvasEditor(idGenerator = { "n${n++}" }, clock = { 1L })        // distinct from the loaded id "e0"
            val rnd = Random(seed.toLong()); var sid = 0
            val lines = 2 + rnd.nextInt(3)
            val ink = (0 until lines).flatMap { l -> Pen(seed * 10L + l, size = 70f, sloppiness = 0.5f).at(100f, 200f + l * 105f).text(listOf("x+2=57", "x-1=33", "2x=84", "5+5=10")[rnd.nextInt(4)]).strokes }
            ed.load(CanvasState(ink.mapIndexed { i, s -> s.copy(id = "s${sid++}", expressionId = "e0") }, mapOf("e0" to ExpressionObject("e0"))))
            val total = ed.state.strokes.size
            ed.splitMergedLines(); check(ed, "seed $seed"); assertEquals(total, ed.state.strokes.size)
            val parts = ed.state.expressions.size; assertFalse("a second split changes nothing", ed.splitMergedLines()); assertEquals(parts, ed.state.expressions.size)
        }
    }
}
