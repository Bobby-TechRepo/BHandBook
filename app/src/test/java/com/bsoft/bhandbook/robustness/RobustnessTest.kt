package com.bsoft.bhandbook.robustness

import com.bsoft.bhandbook.core.comer.OrientationEstimator
import com.bsoft.bhandbook.core.comer.WritingDirection
import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.recognition.*
import com.bsoft.bhandbook.domain.editor.*
import com.bsoft.bhandbook.domain.graph.GraphBuilder
import com.bsoft.bhandbook.domain.graph.GraphModel
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.model.rotated
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * Throws awkward and random input at everything the canvas does on the main thread: nothing may throw, hang, or return NaN geometry.
 * Real ink is messy (single taps, repeated points, zero-size strokes, ink loaded from older canvases), and one unexpected exception here is a crash.
 */
class RobustnessTest {
    private val parser = DefaultMathParser()
    private val insights = CanvasInsights(parser, DefaultSolutionEngine())

    // ---------------------------------------------------------------- awkward ink
    private fun stroke(id: String, vararg pts: Pair<Float, Float>, expr: String? = "e1", t0: Long = 0L) =
        Stroke(id, pts.mapIndexed { i, (x, y) -> StrokePoint(x, y, t0 + i * 8L) }, 3f, 0, expr)

    private fun awkward(): List<Pair<String, List<Stroke>>> = listOf(
        "empty" to emptyList(),
        "one tap" to listOf(stroke("a", 5f to 5f)),
        "pointless stroke" to listOf(Stroke("a", emptyList(), 3f, 0, "e1")),
        "identical points" to (0 until 8).map { stroke("s$it", 10f to 10f, 10f to 10f, 10f to 10f) },
        "all on one line" to (0 until 8).map { stroke("s$it", (it * 30f) to 50f, (it * 30f + 20f) to 50f) },
        "vertical line" to (0 until 8).map { stroke("s$it", 50f to (it * 30f), 50f to (it * 30f + 20f)) },
        "zero-size boxes" to (0 until 6).map { stroke("s$it", (it * 40f) to 0f, (it * 40f) to 0f) },
        "huge coordinates" to (0 until 6).map { stroke("s$it", (1e7f + it * 1e5f) to 1e7f, (1e7f + it * 1e5f + 5e4f) to (1e7f + 8e4f)) },
        "tiny coordinates" to (0 until 6).map { stroke("s$it", (it * 1e-4f) to 0f, (it * 1e-4f + 5e-5f) to 6e-5f) },
        "negative coordinates" to (0 until 6).map { stroke("s$it", (-500f - it * 40f) to -300f, (-480f - it * 40f) to -250f) },
        "all the same time" to (0 until 6).map { s -> Stroke("s$s", listOf(StrokePoint(s * 40f, 0f, 0L), StrokePoint(s * 40f + 20f, 40f, 0L)), 3f, 0, "e1") },
        "two strokes only" to listOf(stroke("a", 0f to 0f, 10f to 40f), stroke("b", 30f to 0f, 40f to 40f)),
    )

    @Test fun awkwardInk_neverThrows_inGeometryAndLayout() {
        for ((name, ink) in awkward()) {
            try {
                StrokeGrouper.group(ink); TextLines.split(ink); TextLines.glyphHeight(ink); ColumnArithmetic.detect(ink); EqualsDetector.endsWithEquals(ink)
                OrientationEstimator.estimate(ink); WritingDirection.of(ink)
                val ex = ink.mapNotNull { it.expressionId }.distinct().associateWith { ExpressionObject(it, result = "40", resultInline = true, status = ProcessingStatus.EVALUATED, steps = emptyList()) }
                val layouts = ExpressionLayout.all(ink, ex, graphs = ex.keys, graphable = ex.keys)
                for (l in layouts.values) for (r in listOfNotNull(l.hitBounds, l.graphRect)) assertTrue("$name: bounds ${r}", r.isEmpty || (r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite()))
                // incremental drawing, exactly as the editor does it
                val placed = ArrayList<Stroke>(); var n = 0
                for (s in ink) {
                    val id = when (val a = ExpressionAssigner.assign(s, placed)) { ExpressionAssignment.New -> "x${n++}"; is ExpressionAssignment.Join -> a.expressionId; is ExpressionAssignment.Merge -> a.ids.first() }
                    placed += s.copy(expressionId = id)
                }
            } catch (t: Throwable) { throw AssertionError("'$name' threw $t", t) }
        }
    }

    @Test fun nonFiniteCoordinates_neverThrow() {
        val bad = listOf(
            stroke("a", Float.NaN to 0f, 10f to 10f), stroke("b", 0f to Float.POSITIVE_INFINITY, 10f to 10f), stroke("c", Float.NEGATIVE_INFINITY to Float.NaN, 1f to 1f),
            stroke("d", 0f to 0f, 30f to 40f), stroke("e", 40f to 0f, 70f to 40f),
        )
        try {
            StrokeGrouper.group(bad); TextLines.split(bad); ColumnArithmetic.detect(bad); OrientationEstimator.estimate(bad); WritingDirection.of(bad)
            ExpressionLayout.all(bad, mapOf("e1" to ExpressionObject("e1", result = "1", status = ProcessingStatus.EVALUATED)))
        } catch (t: Throwable) { throw AssertionError("non-finite ink threw $t", t) }
    }

    @Test fun randomText_inRandomOrientations_neverThrows_andLayoutStaysFinite() {
        val rnd = Random(11); val chars = "0123456789+-=x*/()."
        repeat(150) { round ->
            val text = (0 until 1 + rnd.nextInt(9)).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            val lines = 1 + rnd.nextInt(3)
            var ink = (0 until lines).flatMap { l -> runCatching { Pen(round * 7L + l, size = 20f + rnd.nextInt(200), sloppiness = rnd.nextFloat()).at(100f, 200f + l * 130f).text(text).strokes }.getOrDefault(emptyList()) }
            if (ink.isEmpty()) return@repeat
            val a = rnd.nextDouble() * 2 * Math.PI; val p = ink.flatMap { it.points }
            if (rnd.nextBoolean()) ink = ink.rotated(a, p.map { it.x.toDouble() }.average(), p.map { it.y.toDouble() }.average())
            try {
                val groups = StrokeGrouper.group(ink)
                val ex = HashMap<String, ExpressionObject>(); val tagged = ArrayList<Stroke>()
                groups.forEachIndexed { gi, g -> ex["g$gi"] = ExpressionObject("g$gi", result = "42", resultInline = rnd.nextBoolean(), status = ProcessingStatus.EVALUATED); g.forEach { tagged += it.copy(expressionId = "g$gi") } }
                val layouts = ExpressionLayout.all(tagged, ex, graphs = ex.keys.filter { rnd.nextBoolean() }.toSet(), graphable = ex.keys, graphOffsets = ex.keys.associateWith { com.bsoft.bhandbook.core.graphics.Vec2(rnd.nextFloat() * 500 - 250, rnd.nextFloat() * 500 - 250) }, graphScales = ex.keys.associateWith { rnd.nextFloat() * 10 })
                for (l in layouts.values) { assertTrue(l.fontSize.isFinite() && l.fontSize > 0f); l.result?.let { assertTrue(it.origin.x.isFinite() && it.origin.y.isFinite()) } }
            } catch (t: Throwable) { throw AssertionError("round $round '$text' x$lines threw $t", t) }
        }
    }

    // ---------------------------------------------------------------- awkward text for the maths and the graphs
    private val awkwardTexts = listOf(
        "", " ", "=", "==", "x=", "=x", "x==y", "y=", "x+", "+", "((", "))", "()", "2^^3", "x^", "^2", "x/0=y", "y=1/x", "y=tan(x)", "y=x^x", "y=2^x", "y=log(x)", "y=sqrt(x)", "y=sin(x)/x",
        "x^2+y^2=0", "x^2+y^2=-4", "x*y=0", "x*y*y=1", "y^3=x", "x^2=y^2", "0=0", "1=2", "x=x", "y=y", "0x+0y=0", "0x+0y=5", "x/y=1", "x^y=2", "9999999999999999999999x+y=1", "0.0000001x=y",
        "y=1000000x", "y=x/1000000", "x^2+y^2=1000000000000", "x^2+y^2=0.000000000001", "y=(x-5)/(x-5)", "abs(x)=y", "y=abs(x)", "pi*x=y", "x+y+z=1", "a+b=2", "2(x+y)=6", "x=y=2",
    )

    @Test fun awkwardEquations_neverThrow_inParsingSolvingAndGraphing() {
        val engine = DefaultSolutionEngine()
        for (t in awkwardTexts) try {
            val st = (parser.parse(t) as? com.bsoft.bhandbook.core.math.ParseResult.Success)?.statement ?: continue
            engine.solve(st)
            val spec = GraphBuilder.build(listOf(st)) ?: continue
            val d = GraphModel.layout(spec)
            val v = d.viewport; assertTrue("'$t' viewport $v", v.xMin.isFinite() && v.xMax.isFinite() && v.yMin.isFinite() && v.yMax.isFinite() && v.xMax > v.xMin && v.yMax > v.yMin)
            for (c in d.curves) for (s in c.segments) for (p in s) assertTrue("'$t'", p.x.isFinite() && p.y.isFinite())
            for (m in d.markers) assertTrue("'$t' marker", m.x.isFinite() && m.y.isFinite())
        } catch (e: Throwable) { throw AssertionError("'$t' threw $e", e) }
    }

    @Test fun awkwardSystems_neverThrow() {
        val rnd = Random(5)
        repeat(300) {
            val a = awkwardTexts[rnd.nextInt(awkwardTexts.size)]; val b = awkwardTexts[rnd.nextInt(awkwardTexts.size)]
            val strokes = listOf(stroke("s1", 100f to 100f, 300f to 160f, expr = "a"), stroke("s2", 100f to 220f, 300f to 280f, expr = "b"))
            val state = CanvasState(strokes, mapOf("a" to ExpressionObject("a", recognized = a, status = ProcessingStatus.EVALUATED), "b" to ExpressionObject("b", recognized = b, status = ProcessingStatus.ERROR)))
            try { val ins = insights.analyse(state); for (k in state.expressions.keys) insights.graphFor(k, state, ins); ExpressionLayout.all(state.strokes, state.expressions, ins.systems, state.expressions.keys, ins.graphable) }
            catch (e: Throwable) { throw AssertionError("system '$a' / '$b' threw $e", e) }
        }
    }

    @Test fun graphsOfEverySupportedKind_finishQuickly() {
        val cases = listOf("y=x", "y=x^2", "y=tan(x)", "y=1/x", "x^2+y^2=25", "x*y=1", "x^2/4+y^2/9=1", "y^2=x", "x^2+y^2=250000", "y=sin(x)")
        val t0 = System.nanoTime()
        for (c in cases) { val st = (parser.parse(c) as com.bsoft.bhandbook.core.math.ParseResult.Success).statement; GraphModel.layout(GraphBuilder.build(listOf(st))!!) }
        val ms = (System.nanoTime() - t0) / 1_000_000; println("PERF 10 graphs: $ms ms"); assertTrue("$ms ms", ms < 2500)
    }
}
