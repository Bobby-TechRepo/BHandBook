package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import org.junit.Assert.*
import org.junit.Test

class CanvasInsightsTest {
    private val insights = CanvasInsights(DefaultMathParser(), DefaultSolutionEngine())

    private fun stroke(id: String, expr: String, left: Float, top: Float, w: Float = 300f, h: Float = 60f) =
        Stroke(id, listOf(StrokePoint(left, top), StrokePoint(left + w, top + h)), 3f, 0, expr)
    private fun state(vararg items: Triple<String, String?, Pair<Float, Float>>, status: ProcessingStatus = ProcessingStatus.EVALUATED): CanvasState =
        CanvasState(
            strokes = items.map { (id, _, pos) -> stroke("s_$id", id, pos.first, pos.second) },
            expressions = items.associate { (id, text, _) -> id to ExpressionObject(id, recognized = text, status = status) },
        )
    private fun at(id: String, text: String?, top: Float, left: Float = 100f) = Triple(id, text, left to top)

    // ---------------------------------------------------------------- simultaneous equations
    @Test fun twoStackedEquations_areSolvedTogether_andAnchoredAtTheLowerOne() {
        val a = insights.analyse(state(at("a", "x+y=5", 100f), at("b", "x-y=1", 190f)))
        val sys = a.systems.getValue("a"); assertSame(sys, a.systems.getValue("b"))
        assertEquals("b", sys.anchorId); assertEquals(listOf("a", "b"), sys.memberIds); assertEquals("x = 3, y = 2", sys.summary); assertFalse(sys.isError); assertTrue(sys.steps.isNotEmpty())
    }
    @Test fun inconsistentSystem_saysSo() { assertEquals("no solution", insights.analyse(state(at("a", "x+y=2", 100f), at("b", "x+y=3", 190f))).systems.getValue("a").summary) }
    @Test fun nonLinearSystem_thatCrosses_isSolvedApproximately() {
        val s = insights.analyse(state(at("a", "x*y=1", 100f), at("b", "x+y=3", 190f))).systems.getValue("a")
        assertFalse(s.isError); assertTrue(s.summary, s.summary.contains("x ≈ 0.382, y ≈ 2.62") && s.summary.contains("x ≈ 2.62, y ≈ 0.382"))
    }
    @Test fun farApartOrIndependentEquations_formNoSystem() {
        assertTrue(insights.analyse(state(at("a", "x+y=5", 100f), at("b", "x-y=1", 900f))).systems.isEmpty())
        assertTrue(insights.analyse(state(at("a", "2x+5=15", 100f), at("b", "3y=9", 190f))).systems.isEmpty())
    }
    @Test fun expressionsStillBeingReadOrNeedingReview_areLeftOut() {
        assertTrue(insights.analyse(state(at("a", "x+y=5", 100f), at("b", "x-y=1", 190f), status = ProcessingStatus.NEEDS_REVIEW)).systems.isEmpty())
        assertTrue(insights.analyse(state(at("a", "x+y=5", 100f), at("b", null, 190f), status = ProcessingStatus.PENDING)).systems.isEmpty())
    }
    @Test fun editingOneEquation_changesTheAnswer() {
        val before = insights.analyse(state(at("a", "x+y=5", 100f), at("b", "x-y=1", 190f))).systems.getValue("a").summary
        val after = insights.analyse(state(at("a", "x+y=5", 100f), at("b", "x-y=3", 190f))).systems.getValue("a").summary
        assertEquals("x = 3, y = 2", before); assertEquals("x = 4, y = 1", after)
    }
    @Test fun threeEquations() { assertEquals("x = 1, y = 2, z = 3", insights.analyse(state(at("a", "x+y+z=6", 100f), at("b", "2x-y+z=3", 190f), at("c", "x+2y-z=2", 280f))).systems.getValue("a").summary) }

    // ---------------------------------------------------------------- graphs
    @Test fun graphableExpressions_areIdentified() {
        val g = insights.analyse(state(at("a", "y=2x+1", 100f), at("b", "x^2+y^2=25", 500f), at("c", "x^2-4", 900f), at("d", "2x+5=15", 1300f), at("e", "25+15", 1700f))).graphable
        assertEquals(setOf("a", "b", "c", "d"), g)
    }
    @Test fun membersOfAGraphableSystem_areGraphable() {
        assertEquals(setOf("a", "b"), insights.analyse(state(at("a", "x+y=5", 100f), at("b", "x-y=1", 190f))).graphable)
    }
    @Test fun graphForASingleExpression() {
        val st = state(at("a", "y=2x+1", 100f)); val d = insights.graphFor("a", st)!!
        assertEquals(1, d.curves.size); assertTrue(d.markers.isEmpty())
    }
    @Test fun graphForASystem_plotsEveryEquation_andMarksTheSolution() {
        val st = state(at("a", "x+y=5", 100f), at("b", "x-y=1", 190f))
        for (id in listOf("a", "b")) { val d = insights.graphFor(id, st)!!; assertEquals(2, d.curves.size); assertEquals("(3, 2)", d.markers.single().label) }
    }
    @Test fun graphKey_isTheAnchorForSystems_andTheExpressionOtherwise() {
        val st = state(at("a", "x+y=5", 100f), at("b", "x-y=1", 190f), at("c", "y=x", 700f)); val a = insights.analyse(st)
        assertEquals("b", insights.graphKey("a", a)); assertEquals("b", insights.graphKey("b", a)); assertEquals("c", insights.graphKey("c", a))
    }
    @Test fun anExpressionThatCannotBeGraphed_hasNoGraph() { assertNull(insights.graphFor("a", state(at("a", "25+15", 100f)))) }

    @Test fun analysisIsFast_forHundredsOfExpressions() {
        val items = (0 until 300).map { at("e$it", if (it % 2 == 0) "y=${it % 7}x+1" else "25+$it", 100f + it * 400f) }.toTypedArray()
        val st = state(*items)
        val t0 = System.nanoTime(); val a = insights.analyse(st); val ms = (System.nanoTime() - t0) / 1_000_000
        println("PERF analyse 300 expressions: $ms ms"); assertEquals(150, a.graphable.size); assertTrue("$ms ms", ms < 1500)
    }


    // ---------------------------------------------------------------- non-linear systems: the intersections found for the graph are reported (approximately)
    @Test fun aCircleAndALine_reportTheirIntersectionsAsApproximateSolutions() {
        val sys = insights.analyse(state(at("a", "x^2+y^2=25", 100f), at("b", "y=x", 190f))).systems.getValue("a")
        assertFalse(sys.isError); assertTrue(sys.summary, sys.summary.contains("x ≈ -3.54, y ≈ -3.54") && sys.summary.contains("x ≈ 3.54, y ≈ 3.54"))
    }
    @Test fun aCircleAndAVerticalLine() {
        val sys = insights.analyse(state(at("a", "x^2+y^2=25", 100f), at("b", "x=3", 190f))).systems.getValue("a")
        assertTrue(sys.summary, sys.summary.contains("x ≈ 3, y ≈ -4") && sys.summary.contains("x ≈ 3, y ≈ 4"))
    }
    @Test fun aNonLinearSystemWithNoIntersectionFound_staysUnsupported() {
        assertTrue(insights.analyse(state(at("a", "x^2+y^2=1", 100f), at("b", "x=50", 190f))).systems.getValue("a").isError)
    }
    @Test fun linearSystemsAreStillSolvedExactly() { assertEquals("x = 3, y = 2", insights.analyse(state(at("a", "x+y=5", 100f), at("b", "x-y=1", 190f))).systems.getValue("a").summary) }


    // ---------------------------------------------------------------- the modes reach systems and graphs
    private val deg = com.bsoft.bhandbook.core.math.CalcSettings(angle = com.bsoft.bhandbook.core.math.AngleMode.DEGREES)
    @Test fun aSystemWithTrigConstants_isSolvedInTheChosenUnit() {
        val st = state(at("a", "2*sin(30)*x+y=5", 100f), at("b", "x-y=1", 190f))
        assertEquals("x = 3, y = 2", insights.analyse(st, deg).systems.getValue("a").summary)
        assertTrue(insights.analyse(st).systems.getValue("a").isError)
    }
    @Test fun aSystemsSolutionIsWrittenInTheChosenFormat() {
        val st = state(at("a", "x/2+y=4", 100f), at("b", "x-y=1", 190f))
        assertEquals("x = 3.33, y = 2.33", insights.analyse(st, com.bsoft.bhandbook.core.math.CalcSettings(places = 2)).systems.getValue("a").summary)
    }
    @Test fun aGraphUsesTheChosenUnit() {
        val st = state(at("a", "y=sin(x)", 100f)); val d = insights.graphFor("a", st, insights.analyse(st, deg), deg)!!
        assertEquals(-360.0, d.viewport.xMin, 1e-9); assertTrue(d.curves[0].segments[0].any { Math.abs(it.x - 30.0) < 1.0 && Math.abs(it.y - 0.5) < 0.02 })
    }
    @Test fun aPeriodicSystemHasNoApproximateAnswer_becauseThereAreInfinitelyMany() {
        val st = state(at("a", "y=sin(x)", 100f), at("b", "y=0.5", 190f)); val sys = insights.analyse(st, deg).systems.getValue("a"); assertTrue(sys.summary, sys.isError)
    }


    // ---------------------------------------------------------------- definitions on the canvas
    private fun st(vararg items: Triple<String, String, Long>, status: ProcessingStatus = ProcessingStatus.EVALUATED): CanvasState {
        var y = 100f
        return CanvasState(items.map { (id, _, _) -> Stroke("s_$id", listOf(StrokePoint(100f, y), StrokePoint(400f, y + 60f).also { y += 900f }), 3f, 0, id) },
            items.associate { (id, text, modified) -> id to ExpressionObject(id, recognized = text, status = status, modifiedAt = modified) })
    }
    @Test fun definitionsAreCollectedFromTheCanvas() {
        val d = insights.analyse(st(Triple("a", "a=5", 1), Triple("b", "f(x)=x^2", 2), Triple("c", "2+3=", 3))).definitions
        assertEquals("5", com.bsoft.bhandbook.core.math.NumberFormatter.format(d.values.getValue("a")).primary); assertTrue("f" in d.functions); assertFalse(d.describe().any { it.contains("2+3") })
    }
    @Test fun theLatestWrittenDefinitionWins() {
        assertEquals("9", com.bsoft.bhandbook.core.math.NumberFormatter.format(insights.analyse(st(Triple("a", "a=5", 1), Triple("b", "a=9", 2))).definitions.values.getValue("a")).primary)
        assertEquals("5", com.bsoft.bhandbook.core.math.NumberFormatter.format(insights.analyse(st(Triple("a", "a=5", 9), Triple("b", "a=9", 2))).definitions.values.getValue("a")).primary)
    }
    @Test fun unreadOrUnconfirmedExpressionsDefineNothing() { assertTrue(insights.analyse(st(Triple("a", "a=5", 1), status = ProcessingStatus.NEEDS_REVIEW)).definitions.isEmpty) }
    @Test fun theUnknownsXYZ_areNeverDefined() { assertTrue(insights.analyse(st(Triple("a", "x=5", 1), Triple("b", "y=2", 2))).definitions.isEmpty) }
    @Test fun aGraphOfADefinedFunction_isOfferedAndDrawn() {
        val state = st(Triple("f", "f(x)=x^2", 1), Triple("g", "y=f(x)", 2)); val a = insights.analyse(state)
        assertTrue("g" in a.graphable); assertTrue(insights.graphFor("g", state, a)!!.curves[0].segments.isNotEmpty())
        assertFalse("g" in insights.analyse(st(Triple("g", "y=f(x)", 2))).graphable)
    }
    @Test fun theSignatureChangesWhenADefinitionChanges() {
        val a = insights.analyse(st(Triple("a", "a=5", 1))).definitions.signature(); assertEquals(a, insights.analyse(st(Triple("a", "a=5", 1))).definitions.signature()); assertNotEquals(a, insights.analyse(st(Triple("a", "a=6", 1))).definitions.signature())
    }
    @Test fun definitionsOf_isTheSameAsAnalyse() { val state = st(Triple("a", "a=5", 1), Triple("b", "b=a*2", 2)); assertEquals(insights.analyse(state).definitions.signature(), insights.definitionsOf(state).signature()) }
    @Test fun systemsStillWorkWithDefinitionsPresent() { assertEquals("x = 3, y = 2", insights.analyse(state(at("a", "x+y=5", 100f), at("b", "x-y=1", 190f))).systems.getValue("a").summary) }

    @Test fun usesNames_isTrueOnlyForTextWithNamesInIt() {
        for (t in listOf("2a+1=", "2x+1=7", "f(3)=", "b=a*2", "f(x)=2x")) assertTrue(t, insights.usesNames(t))
        for (t in listOf("25+15=", "sin(30)=", "sqrt(16)", "2^3", "", "not parseable (((")) assertFalse(t, insights.usesNames(t))
        assertFalse(insights.usesNames(null))
    }
}
