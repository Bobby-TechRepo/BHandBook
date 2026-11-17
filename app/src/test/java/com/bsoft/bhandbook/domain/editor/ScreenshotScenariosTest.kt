package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import org.junit.Assert.*
import org.junit.Test

/** Replays what happened on the phone: two equations written close together, and a single equation in x and y, through the real editor. */
class ScreenshotScenariosTest {
    private var n = 0
    private fun editor() = CanvasEditor(idGenerator = { "e${n++}" }, clock = { 1L })
    private fun draw(ed: CanvasEditor, strokes: List<Stroke>) { var i = 0; for (s in strokes) ed.addStroke(s.copy(id = "s${n}_${i++}")) }
    private fun line(text: String, baseline: Float, seed: Long) = Pen(seed, size = 70f, sloppiness = 0.5f).at(100f, baseline).text(text).strokes
    private fun recognised(ed: CanvasEditor, id: String, text: String, status: ProcessingStatus = ProcessingStatus.ERROR, error: String? = "Equations with more than one variable are not supported yet.") {
        ed.applyRecognition(id, ed.signatureOf(id)) { it.copy(recognized = text, status = status, error = error) }
    }
    private val insights = CanvasInsights(DefaultMathParser(), DefaultSolutionEngine())

    @Test fun twoEquationsWrittenCloseTogether_areTwoExpressions_andFormASystemThatIsSolved() {
        val ed = editor(); draw(ed, line("12+34=5", 200f, 1)); draw(ed, line("12-34=1", 310f, 2))
        assertEquals("each line is its own expression", 2, ed.state.expressions.size)
        val (top, bottom) = ed.state.expressions.keys.sortedBy { id -> ed.state.strokesOf(id).map { it.bounds.centerY }.average() }
        recognised(ed, top, "x+y=5"); recognised(ed, bottom, "x-y=1")
        val a = insights.analyse(ed.state); val sys = a.systems.getValue(top)
        assertEquals("x = 3, y = 2", sys.summary); assertEquals(bottom, sys.anchorId)
        val layouts = ExpressionLayout.all(ed.state.strokes, ed.state.expressions, a.systems, emptySet(), a.graphable)
        val under = layouts.getValue(bottom)         // the steps are showing, so they carry the answer instead of a separate summary line
        assertNull(under.systemSummary); assertTrue(under.systemSteps.joinToString(" ") { it.text }.let { "x = 3" in it && "y = 2" in it })
        assertNull("no 'not supported' error under the equations", layouts.getValue(top).status); assertNull(layouts.getValue(bottom).status)
        assertTrue("and they can be graphed together", top in a.graphable && bottom in a.graphable)
    }
    @Test fun asingleEquationInXAndY_isGraphable_andShowsAHintNotAnError() {
        val ed = editor(); draw(ed, line("12+34=5", 200f, 1)); val id = ed.state.expressions.keys.single()
        recognised(ed, id, "y=2x+4"); val a = insights.analyse(ed.state)
        assertTrue(id in a.graphable)
        val l = ExpressionLayout.all(ed.state.strokes, ed.state.expressions, a.systems, emptySet(), a.graphable).getValue(id)
        assertTrue(l.statusIsHint); assertTrue(l.status!!.text.contains("Graph"))
        val g = insights.graphFor(id, ed.state, a)!!; assertEquals("y = 2x + 4", g.curves.single().label)
    }
    @Test fun aThirdLineJoinsTheSystem() {
        val ed = editor(); draw(ed, line("12+34=5", 200f, 1)); draw(ed, line("12-34=1", 310f, 2)); draw(ed, line("2+34=9", 420f, 3))
        assertEquals(3, ed.state.expressions.size)
    }
}
