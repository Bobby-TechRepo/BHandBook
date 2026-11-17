package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class CanvasEditorSplitTest {
    private fun pen(seed: Long) = Pen(seed, size = 70f, sloppiness = 0.5f)
    private fun line(text: String, baseline: Float, seed: Long) = pen(seed).at(100f, baseline).text(text).strokes
    private var n = 0
    private fun editorWith(strokes: List<Stroke>, recognized: String = "x_x-y=5"): CanvasEditor {
        val state = CanvasState(
            strokes = strokes.mapIndexed { i, s -> s.copy(id = "s$i", expressionId = "e1") },
            expressions = mapOf("e1" to ExpressionObject("e1", recognized = recognized, status = ProcessingStatus.NEEDS_REVIEW)),
        )
        return CanvasEditor(state, idGenerator = { "n${n++}" }, clock = { 1000L })
    }

    @Test fun aMergedTwoLineExpression_isSplitIntoOneExpressionPerLine() {
        val ed = editorWith(line("x+2=57", 200f, 1) + line("x-1=33", 310f, 2))
        assertTrue(ed.splitMergedLines())
        assertEquals(2, ed.state.expressions.size)
        val tops = ed.state.expressions.keys.map { id -> id to ed.state.strokesOf(id).map { it.bounds.centerY }.average() }.sortedBy { it.second }
        assertEquals("the upper line keeps the original id", "e1", tops[0].first)
        assertTrue(tops[0].second < tops[1].second)
        assertEquals(ed.state.strokes.size, ed.state.expressions.keys.sumOf { ed.state.strokesOf(it).size }); assertTrue(ed.state.strokes.all { it.expressionId != null })
    }
    @Test fun bothLinesNeedRecognitionAfterwards_andTheOldGarbledReadingIsGone() {
        val ed = editorWith(line("x+2=57", 200f, 1) + line("x-1=33", 310f, 2)); ed.splitMergedLines()
        assertTrue(ed.state.expressions.values.all { it.status == ProcessingStatus.PENDING && it.recognized == null })
        assertEquals(ed.state.expressions.keys, ed.expressionsNeedingRecognition())
    }
    @Test fun splittingIsOneUndoStep() {
        val ed = editorWith(line("x+2=57", 200f, 1) + line("x-1=33", 310f, 2)); val before = ed.state
        ed.splitMergedLines(); ed.undo()
        assertEquals(1, ed.state.expressions.size); assertEquals(before.strokes.map { it.expressionId }, ed.state.strokes.map { it.expressionId })
    }
    @Test fun threeLines() { val ed = editorWith(line("x+2=5", 200f, 1) + line("x-1=3", 310f, 2) + line("2x=8", 420f, 3)); ed.splitMergedLines(); assertEquals(3, ed.state.expressions.size) }
    @Test fun aSingleLine_isLeftAlone() { val ed = editorWith(line("25+15=", 200f, 1)); assertFalse(ed.splitMergedLines()); assertEquals(1, ed.state.expressions.size); assertEquals(ProcessingStatus.NEEDS_REVIEW, ed.state.expressions["e1"]!!.status) }
    @Test fun aFraction_isLeftAlone() { assertFalse(editorWith(pen(1).at(100f, 300f).frac("123", "456").strokes).splitMergedLines()) }
    @Test fun aColumnWithItsBar_isLeftAlone() {
        val bar = Stroke("bar", (0..12).map { StrokePoint(60f + 420f * it / 12f, 345f, 900_000L + it) }, 4f, 0)
        assertFalse(editorWith(line("105", 200f, 1) + line("-37", 310f, 2) + bar).splitMergedLines())
    }
    @Test fun canBeRestrictedToSomeExpressions() {
        val ed = editorWith(line("x+2=5", 200f, 1) + line("x-1=3", 310f, 2)); assertFalse(ed.splitMergedLines(listOf("other"))); assertEquals(1, ed.state.expressions.size)
    }
}
