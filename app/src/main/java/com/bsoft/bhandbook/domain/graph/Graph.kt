package com.bsoft.bhandbook.domain.graph

import com.bsoft.bhandbook.core.math.*
import com.bsoft.bhandbook.domain.solver.DefinitionParser
import com.bsoft.bhandbook.domain.solver.Definitions
import com.bsoft.bhandbook.domain.solver.FunctionDefinition
import com.bsoft.bhandbook.domain.solver.Substitution
import com.bsoft.bhandbook.domain.solver.AlgebraText
import com.bsoft.bhandbook.domain.solver.InvalidMath
import com.bsoft.bhandbook.domain.solver.LinearForms
import com.bsoft.bhandbook.domain.solver.UnsupportedMath
import com.bsoft.bhandbook.domain.solver.normalizeLiterals
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// ------------------------------------------------------------------------------------------ what to draw
sealed interface GraphSeries {
    val label: String

    /** y = f(x), evaluated numerically (exactly where possible) by the app's own calculation engine. */
    class Curve(override val label: String, val expr: Expr, angle: AngleMode = AngleMode.RADIANS) : GraphSeries {
        private val engine = ExactCalculationEngine(angle)
        fun valueAt(x: Double): Double = (engine.evaluate(expr, mapOf("x" to Value.Approx(x))) as? CalcResult.Success)?.value?.toDouble() ?: Double.NaN
    }
    data class VerticalLine(override val label: String, val x: Double) : GraphSeries

    /** F(x, y) = 0 for a non-linear equation (circle, ellipse, hyperbola, ...); [expr] is lhs - rhs. */
    class Implicit(override val label: String, val expr: Expr, angle: AngleMode = AngleMode.RADIANS) : GraphSeries {
        private val engine = ExactCalculationEngine(angle)
        fun valueAt(x: Double, y: Double): Double = (engine.evaluate(expr, mapOf("x" to Value.Approx(x), "y" to Value.Approx(y))) as? CalcResult.Success)?.value?.toDouble() ?: Double.NaN
    }
}

data class GraphMarker(val label: String, val x: Double, val y: Double) {
    companion object {
        /** "(3.33, 2.33)": at most three significant digits, no trailing zeros. */
        fun label(x: Double, y: Double) = "(${number(x)}, ${number(y)})"
        fun number(v: Double): String {
            if (!v.isFinite()) return "?"
            if (abs(v) < 1e-9) return "0"
            if (abs(v) >= 1e6 || abs(v) < 1e-3) return scientific(v, 3)
            return BigDecimal(v).round(MathContext(3)).stripTrailingZeros().toPlainString()
        }
        internal fun scientific(v: Double, digits: Int): String {
            val e = floor(log10(abs(v))).toInt()
            val m = BigDecimal(v / 10.0.pow(e)).round(MathContext(digits)).stripTrailingZeros().toPlainString()
            return "${m}e$e"
        }
    }
}

/** [angle]: the unit trigonometric functions are read in; [usesTrig]: some series is periodic (sin, cos, tan), so the view shows whole waves. */
class GraphSpec(val series: List<GraphSeries>, val markers: List<GraphMarker>, val angle: AngleMode = AngleMode.RADIANS, val usesTrig: Boolean = false)

// ------------------------------------------------------------------------------------------ building a spec from the user's maths
object GraphBuilder {
    private const val MAX_SERIES = 6

    fun canGraph(statements: List<MathStatement>, angle: AngleMode = AngleMode.RADIANS, definitions: Definitions = Definitions.EMPTY) = build(statements, angle, definitions) != null

    /**
     * Supports: expressions in x (graphed as y = f(x)); y = f(x) in either order; linear equations in x and y (including vertical lines);
     * one-unknown equations f(x) = g(x) (both sides drawn, roots marked); and systems of these (intersections marked).
     * Defined functions and values (`f(x) = ...`, `a = ...`) are expanded first, so `y = f(x)` and `y = a x + b` can be graphed; labels keep what was written.
     * Returns null for anything else (implicit non-linear curves, other letters, no unknown).
     */
    fun build(statements: List<MathStatement>, angle: AngleMode = AngleMode.RADIANS, definitions: Definitions = Definitions.EMPTY): GraphSpec? {
        if (statements.isEmpty() || statements.size > MAX_SERIES) return null
        val series = ArrayList<GraphSeries>()
        for (original in statements) {
            val fd = (original as? MathStatement.Equation)?.let { DefinitionParser.parse(it) as? FunctionDefinition }
            if (fd != null) {                                                                  // the definition f(x) = ... of a function is simply drawn as y = f(x)
                val body = (prepared(MathStatement.Expression(Substitution.rename(fd.body, fd.param, "x")), definitions) as MathStatement.Expression).expr
                if (variables(body) != setOf("x")) return null
                series += GraphSeries.Curve("y = ${fd.name}(x)", body, angle); continue
            }
            series += graphSeriesOf(original, angle, definitions) ?: return null
        }
        if (series.size > MAX_SERIES) return null
        return finish(series, angle)
    }

    private fun graphSeriesOf(original: MathStatement, angle: AngleMode, definitions: Definitions): List<GraphSeries>? =
        (when (val st = prepared(original, definitions)) {
            is MathStatement.Expression -> if (variables(st.expr) == setOf("x")) listOf(GraphSeries.Curve("y = " + Printer.print(normalizeLiterals((original as MathStatement.Expression).expr)), st.expr, angle)) else null
            is MathStatement.Equation -> fromEquation(st, angle, original as MathStatement.Equation)
        })

    private fun finish(series: List<GraphSeries>, angle: AngleMode): GraphSpec {
        val periodic = series.any { s -> (s is GraphSeries.Curve && usesPeriodic(s.expr)) || (s is GraphSeries.Implicit && usesPeriodic(s.expr)) }
        // a periodic graph has endlessly many intersections: only the ones in the window it is shown in are looked for
        return GraphSpec(series, Intersections.of(series, if (periodic) GraphModel.trigWindow(angle) else 100.0), angle, periodic)
    }

    private fun usesPeriodic(e: Expr): Boolean = when (e) {
        is Func -> e.name in PERIODIC || e.args.any { usesPeriodic(it) }
        is Neg -> usesPeriodic(e.operand); is Group -> usesPeriodic(e.inner)
        is Bin -> usesPeriodic(e.left) || usesPeriodic(e.right); is Pow -> usesPeriodic(e.base) || usesPeriodic(e.exponent)
        else -> false
    }
    private val PERIODIC = setOf("sin", "cos", "tan")

    /** [st] with defined functions expanded and defined values replaced by numbers (x, y and z are never replaced). */
    private fun prepared(st: MathStatement, defs: Definitions): MathStatement {
        if (defs.isEmpty) return st
        fun prep(e: Expr) = Substitution.withValues(Substitution.expandCalls(e, defs.functions), defs)
        return when (st) { is MathStatement.Expression -> MathStatement.Expression(prep(st.expr)); is MathStatement.Equation -> MathStatement.Equation(prep(st.lhs), prep(st.rhs)) }
    }

    /** [eq] is what is computed from; [orig] is what the user wrote, which is what the labels show. */
    private fun fromEquation(eq: MathStatement.Equation, angle: AngleMode, orig: MathStatement.Equation = eq): List<GraphSeries>? {
        val vars = variables(eq.lhs) + variables(eq.rhs)
        if (vars.isEmpty() || !vars.all { it == "x" || it == "y" }) return null
        val l = strip(eq.lhs); val r = strip(eq.rhs)
        if (l == Var("y") && "y" !in variables(r)) return listOf(curve(r, angle, strip(orig.rhs)))
        if (r == Var("y") && "y" !in variables(l)) return listOf(curve(l, angle, strip(orig.lhs)))
        if (vars == setOf("x")) {
            // "x = 3" is a vertical line; any other one-unknown equation is drawn as y = lhs against y = rhs
            if (l == Var("x") && variables(r).isEmpty()) return constantOf(r)?.let { listOf(GraphSeries.VerticalLine("x = ${GraphMarker.number(it)}", it)) }
            if (r == Var("x") && variables(l).isEmpty()) return constantOf(l)?.let { listOf(GraphSeries.VerticalLine("x = ${GraphMarker.number(it)}", it)) }
            return listOf(curve(eq.lhs, angle, orig.lhs), curve(eq.rhs, angle, orig.rhs))
        }
        return try {
            val engine = ExactCalculationEngine(angle)
            val f = LinearForms.from(eq.lhs, engine) - LinearForms.from(eq.rhs, engine)                    // a x + b y + c = 0
            val a = f.coeffs["x"] ?: Rational.ZERO; val b = f.coeffs["y"] ?: Rational.ZERO
            when {
                !b.isZero -> listOf(linear(-a / b, -f.constant / b, angle))
                !a.isZero -> (-f.constant / a).toDouble().let { listOf(GraphSeries.VerticalLine("x = ${GraphMarker.number(it)}", it)) }
                else -> null
            }
        } catch (e: UnsupportedMath) {
            listOf(GraphSeries.Implicit(Printer.print(normalizeLiterals(orig.lhs)) + " = " + Printer.print(normalizeLiterals(orig.rhs)), Bin(Op.SUB, eq.lhs, eq.rhs), angle))
        } catch (e: InvalidMath) { null }
    }

    private fun curve(e: Expr, angle: AngleMode, label: Expr = e) = GraphSeries.Curve("y = " + Printer.print(normalizeLiterals(label)), e, angle)

    private fun linear(m: Rational, k: Rational, angle: AngleMode): GraphSeries {
        val expr: Expr = when {
            m.isZero -> Num(k, true)
            k.isZero -> Bin(Op.MUL, Num(m, true), Var("x"))
            k.signum > 0 -> Bin(Op.ADD, Bin(Op.MUL, Num(m, true), Var("x")), Num(k, true))
            else -> Bin(Op.SUB, Bin(Op.MUL, Num(m, true), Var("x")), Num(-k, true))
        }
        val text = when {
            m.isZero -> AlgebraText.rational(k, true)
            else -> AlgebraText.term(m, "x", true) + (if (k.isZero) "" else if (k.signum > 0) " + " + AlgebraText.rational(k, true) else " - " + AlgebraText.rational(k.abs(), true))
        }
        return GraphSeries.Curve("y = $text", expr, angle)
    }

    private fun strip(e: Expr): Expr = if (e is Group) strip(e.inner) else e
    private fun constantOf(e: Expr): Double? = ((ExactCalculationEngine().evaluate(e)) as? CalcResult.Success)?.value?.toDouble()

    fun variables(e: Expr, into: MutableSet<String> = linkedSetOf()): Set<String> {
        when (e) {
            is Var -> into += e.name
            is Neg -> variables(e.operand, into)
            is Group -> variables(e.inner, into)
            is Bin -> { variables(e.left, into); variables(e.right, into) }
            is Pow -> { variables(e.base, into); variables(e.exponent, into) }
            is Func -> e.args.forEach { variables(it, into) }
            is Num, is Const -> {}
        }
        return into
    }
}

private fun bisect(lo0: Double, hi0: Double, dLo0: Double, f: (Double) -> Double): Double {
    var lo = lo0; var hi = hi0; var dlo = dLo0
    repeat(80) { val mid = (lo + hi) / 2; val dm = f(mid); if (dm.isNaN()) return@repeat; if (dm * dlo <= 0) hi = mid else { lo = mid; dlo = dm } }
    return (lo + hi) / 2
}

/** Intersections between series, found numerically (sign changes of the difference, refined by bisection) on a wide range. */
internal object Intersections {
    private const val SAMPLES = 2001

    /** [range]: the x values from -range to +range are searched. */
    fun of(series: List<GraphSeries>, range: Double = 100.0): List<GraphMarker> {
        if (series.size < 2) return emptyList()                              // nothing to intersect
        if (series.none { it is GraphSeries.Curve || it is GraphSeries.VerticalLine }) return emptyList()   // two implicit curves: not supported yet
        val xs = DoubleArray(SAMPLES) { -range + 2 * range * it / (SAMPLES - 1) }
        val curves = series.filterIsInstance<GraphSeries.Curve>(); val hasImplicit = series.any { it is GraphSeries.Implicit }
        val cached = curves.map { c -> DoubleArray(SAMPLES) { c.valueAt(xs[it]) } }
        val out = ArrayList<GraphMarker>()
        fun add(x: Double, y: Double) { if (x.isFinite() && y.isFinite() && out.none { abs(it.x - x) < 1e-6 && abs(it.y - y) < 1e-6 } && out.size < 8) out += GraphMarker(GraphMarker.label(x, y), x, y) }
        for (i in curves.indices) for (j in i + 1 until curves.size) {
            for (k in 0 until SAMPLES - 1) {
                val d0 = cached[i][k] - cached[j][k]; val d1 = cached[i][k + 1] - cached[j][k + 1]
                if (!d0.isFinite() || !d1.isFinite()) continue
                if (d0 == 0.0) { add(xs[k], cached[i][k]); continue }
                if (d0 * d1 < 0) {
                    var lo = xs[k]; var hi = xs[k + 1]; var dlo = d0
                    repeat(80) { val mid = (lo + hi) / 2; val dm = curves[i].valueAt(mid) - curves[j].valueAt(mid); if (dm.isNaN()) return@repeat; if (dm * dlo <= 0) hi = mid else { lo = mid; dlo = dm } }
                    val x = (lo + hi) / 2; val yi = curves[i].valueAt(x); val yj = curves[j].valueAt(x)
                    if (abs(yi - yj) <= 1e-6 * (1 + abs(yi))) add(x, yi)         // a real crossing, not an asymptote jump
                }
            }
            if (cached[i].last() == cached[j].last() && cached[i].last().isFinite()) add(xs.last(), cached[i].last())
        }
        val verticals = series.filterIsInstance<GraphSeries.VerticalLine>()
        for (v in verticals) for (c in curves) add(v.x, c.valueAt(v.x))
        for (imp in series.filterIsInstance<GraphSeries.Implicit>()) {
            // implicit curve x explicit curve: sign changes of F(x, g(x)); implicit curve x vertical line: sign changes of F(c, y)
            for ((ci, c) in curves.withIndex()) {
                val d = DoubleArray(SAMPLES) { imp.valueAt(xs[it], cached[ci][it]) }
                for (k in 0 until SAMPLES - 1) {
                    if (!d[k].isFinite() || !d[k + 1].isFinite() || !cached[ci][k].isFinite() || !cached[ci][k + 1].isFinite()) continue
                    if (d[k] == 0.0) { add(xs[k], cached[ci][k]); continue }
                    if (d[k] * d[k + 1] < 0) {
                        val x = bisect(xs[k], xs[k + 1], d[k]) { imp.valueAt(it, c.valueAt(it)) }
                        if (abs(imp.valueAt(x, c.valueAt(x))) <= 1e-4 * maxOf(abs(d[k]), abs(d[k + 1])) + 1e-9) add(x, c.valueAt(x))
                    }
                }
            }
            for (v in verticals) {
                val d = DoubleArray(SAMPLES) { imp.valueAt(v.x, xs[it]) }
                for (k in 0 until SAMPLES - 1) {
                    if (!d[k].isFinite() || !d[k + 1].isFinite()) continue
                    if (d[k] == 0.0) { add(v.x, xs[k]); continue }
                    if (d[k] * d[k + 1] < 0) {
                        val y = bisect(xs[k], xs[k + 1], d[k]) { imp.valueAt(v.x, it) }
                        if (abs(imp.valueAt(v.x, y)) <= 1e-4 * maxOf(abs(d[k]), abs(d[k + 1])) + 1e-9) add(v.x, y)
                    }
                }
            }
        }
        return out.sortedBy { it.x }
    }
}

// ------------------------------------------------------------------------------------------ turning a spec into drawable geometry
data class GraphPoint(val x: Double, val y: Double)
data class GraphViewport(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double) {
    /** Finite, the right way round, and neither a speck nor astronomically wide. */
    val isValid: Boolean get() = listOf(xMin, xMax, yMin, yMax).all { it.isFinite() } && xMax - xMin in MIN_SPAN..MAX_SPAN && yMax - yMin in MIN_SPAN..MAX_SPAN

    /** Zoomed by [factor] (above 1 = closer) about the graph point ([gx], [gy]), which stays where it is on screen. Bounded; bad input changes nothing. */
    fun zoomedAbout(factor: Double, gx: Double, gy: Double): GraphViewport {
        if (!factor.isFinite() || factor <= 0.0 || !gx.isFinite() || !gy.isFinite() || !isValid) return this
        val xs = xMax - xMin; val ys = yMax - yMin
        val lo = maxOf(xs, ys) / MAX_SPAN; val hi = minOf(xs, ys) / MIN_SPAN
        if (lo > hi) return this
        val f = factor.coerceIn(lo, hi)
        return GraphViewport(gx - (gx - xMin) / f, gx + (xMax - gx) / f, gy - (gy - yMin) / f, gy + (yMax - gy) / f)
    }

    fun panned(dx: Double, dy: Double): GraphViewport = if (dx.isFinite() && dy.isFinite()) GraphViewport(xMin + dx, xMax + dx, yMin + dy, yMax + dy) else this

    companion object { const val MIN_SPAN = 1e-7; const val MAX_SPAN = 1e9 }
}
class GraphCurve(val label: String, val segments: List<List<GraphPoint>>)
data class Tick(val value: Double, val label: String)

class GraphData(
    val viewport: GraphViewport, val curves: List<GraphCurve>, val verticalLines: List<GraphSeries.VerticalLine>,
    val markers: List<GraphMarker>, val xTicks: List<Tick>, val yTicks: List<Tick>,
    /** True when the axes must use the same scale (circles must look round): the viewport already has the panel's aspect ratio. */
    val equalScale: Boolean = false,
    /** Something worth telling the reader, e.g. that an equation has no real points. */
    val note: String? = null,
)

object GraphModel {
    private const val SAMPLES = 401
    /** Height / width of the plotting area; implicit curves use equal scales so the viewport is given exactly this shape. */
    const val PLOT_ASPECT = 0.72
    internal const val IMPLICIT_GRID = 120

    /** Half-width of the x window shown for periodic graphs: one full turn each side (360 degrees, 400 gradians), or +-10 in radians. */
    fun trigWindow(angle: AngleMode): Double = when (angle) { AngleMode.DEGREES -> 360.0; AngleMode.GRADIANS -> 400.0; AngleMode.RADIANS -> 10.0 }

    /** [view]: a viewport chosen by hand (pan / zoom in the panel); null (or an invalid one) frames the graph automatically. */
    fun layout(spec: GraphSpec, chosen: GraphViewport? = null): GraphData {
        val view = chosen?.takeIf { it.isValid }
        val verticals = spec.series.filterIsInstance<GraphSeries.VerticalLine>()
        val curves = spec.series.filterIsInstance<GraphSeries.Curve>()
        val implicits = spec.series.filterIsInstance<GraphSeries.Implicit>()
        val implicitBoxes = if (view != null) emptyList() else implicits.map { imp -> ImplicitContour.bounds { x, y -> imp.valueAt(x, y) } }
        val boxes = implicitBoxes.filterNotNull()

        // 1. horizontal range
        val interestX = spec.markers.map { it.x } + verticals.map { it.x } + boxes.flatMap { listOf(it.xMin, it.xMax) }
        var (xMin, xMax) = if (view != null) view.xMin to view.xMax else when {
            implicits.isNotEmpty() -> tightRange(interestX)
            spec.usesTrig -> trigWindow(spec.angle).let { -it to it }                      // whole waves, in the chosen unit; intersections are already inside it
            else -> xRange(interestX)
        }

        // 2. vertical range from what is actually drawn
        fun sample(lo: Double, hi: Double) = DoubleArray(SAMPLES) { lo + (hi - lo) * it / (SAMPLES - 1) }.let { xs -> xs to curves.map { c -> DoubleArray(SAMPLES) { c.valueAt(xs[it]) } } }
        val (xs0, values0) = sample(xMin, xMax)
        val finite = (values0.flatMap { v -> v.filter { it.isFinite() } } + spec.markers.map { it.y } + boxes.flatMap { listOf(it.yMin, it.yMax) }).sorted()
        var lo = 0.0; var hi = 0.0
        if (view != null) { lo = view.yMin; hi = view.yMax } else {
        if (finite.isNotEmpty()) {
            if (finite.size >= 50) { lo = finite[(finite.size * 0.01).toInt()]; hi = finite[((finite.size - 1) * 0.99).toInt()] } else { lo = finite.first(); hi = finite.last() }
        }
        if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 } else { val pad = (hi - lo) * (if (implicits.isNotEmpty()) 0.12 else 0.12); lo -= pad; hi += pad }
        for (m in spec.markers) { if (m.y < lo) lo = m.y - (hi - lo) * 0.1; if (m.y > hi) hi = m.y + (hi - lo) * 0.1 }
        }

        // 3. equal scales for implicit curves: widen whichever side is too narrow
        if (implicits.isNotEmpty() && view == null) {
            var xSpan = xMax - xMin; var ySpan = hi - lo
            if (ySpan / xSpan < PLOT_ASPECT) { val c = (hi + lo) / 2; ySpan = xSpan * PLOT_ASPECT; lo = c - ySpan / 2; hi = c + ySpan / 2 }
            else { val c = (xMax + xMin) / 2; xSpan = ySpan / PLOT_ASPECT; xMin = c - xSpan / 2; xMax = c + xSpan / 2 }
        }
        val range = hi - lo

        // 4. draw: explicit curves sampled over the final range (broken at asymptotes and gaps), implicit curves traced
        val (xs, values) = sample(xMin, xMax)
        val data = ArrayList<GraphCurve>()
        curves.forEachIndexed { ci, c ->
            val segments = ArrayList<List<GraphPoint>>(); var cur = ArrayList<GraphPoint>(); var prev = Double.NaN
            for (i in 0 until SAMPLES) {
                val y = values[ci][i]
                val ok = y.isFinite() && abs(y) < abs(lo + hi) / 2 + 50 * range
                if (!ok || (prev.isFinite() && abs(y - prev) > 0.8 * range)) { if (cur.size >= 2) segments += cur; cur = ArrayList() }
                if (ok) { cur += GraphPoint(xs[i], y); prev = y } else prev = Double.NaN
            }
            if (cur.size >= 2) segments += cur
            data += GraphCurve(c.label, segments)
        }
        var note: String? = null
        for (imp in implicits) {
            val polylines = if (xMax > xMin) ImplicitContour.contour({ x, y -> imp.valueAt(x, y) }, xMin, xMax, lo, hi, IMPLICIT_GRID) else emptyList()
            data += GraphCurve(imp.label, polylines)
            if (polylines.isEmpty() && note == null) note = "No real points for ${imp.label}"
        }
        return GraphData(GraphViewport(xMin, xMax, lo, hi), data, verticals, spec.markers, NiceTicks.compute(xMin, xMax), NiceTicks.compute(lo, hi), implicits.isNotEmpty(), note)
    }

    /** [min, max] of the points of interest, with a little room around them. */
    private fun tightRange(points: List<Double>): Pair<Double, Double> {
        if (points.isEmpty()) return -10.0 to 10.0
        var lo = points.min(); var hi = points.max()
        val span = (hi - lo).let { if (it < 1e-12) maxOf(abs(lo) * 0.2, 1.0) else it }
        if (hi - lo < 1e-12) { lo -= span; hi += span }
        return (lo - span * 0.12) to (hi + span * 0.12)
    }

    /** [-10, 10] by default; widened to show every point of interest (intersections, vertical lines) with room around the origin. */
    private fun xRange(points: List<Double>): Pair<Double, Double> {
        if (points.isEmpty()) return -10.0 to 10.0
        var lo = minOf(0.0, points.min()); var hi = maxOf(0.0, points.max())
        val span = maxOf(hi - lo, 1.0)
        lo -= span * 0.8; hi += span * 0.8
        if (hi - lo < 10.0) { val c = (hi + lo) / 2; lo = c - 5; hi = c + 5 }
        return lo to hi
    }
}

object NiceTicks {
    /** Tick positions at "nice" multiples (1, 2, 5 x 10^k) inside [lo, hi], with labels free of floating-point noise. */
    fun compute(lo: Double, hi: Double, target: Int = 6): List<Tick> {
        if (!(hi > lo) || !lo.isFinite() || !hi.isFinite()) return emptyList()
        val raw = (hi - lo) / target
        val mag = 10.0.pow(floor(log10(raw)))
        val norm = raw / mag
        val step = (if (norm < 1.5) 1.0 else if (norm < 3.0) 2.0 else if (norm < 7.0) 5.0 else 10.0) * mag
        val first = ceil(lo / step - 1e-9).toLong(); val last = floor(hi / step + 1e-9).toLong()
        return (first..last).map { k -> val v = (k * step).let { if (abs(it) < step * 1e-9) 0.0 else it }; Tick(v, label(v, step)) }
    }

    private fun label(v: Double, step: Double): String {
        if (v == 0.0) return "0"
        if (abs(v) >= 1e6 || abs(v) < 1e-3) return GraphMarker.scientific(v, 3)
        val decimals = maxOf(0, -floor(log10(step)).toInt())
        return BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}

/** A point of interest and the dashed lines that connect it to the axes. Distances are in graph (math) units. */
data class Guide(
    val x: Double, val y: Double,
    /** Where the vertical guide ends: the x axis (y = 0), or the nearest edge of the view if the axis is off-screen. */
    val xAxisY: Double,
    /** Where the horizontal guide ends: the y axis (x = 0), or the nearest edge of the view if the axis is off-screen. */
    val yAxisX: Double,
    /** False for a point that sits on an axis: there is nothing to connect. */
    val hasGuides: Boolean,
    /** The x value, printed on the x axis (null for 0 or if an earlier point already printed it); likewise [yLabel] on the y axis. */
    val xLabel: String?, val yLabel: String?,
)

object GraphGuides {
    private const val EPS = 1e-9

    fun of(markers: List<GraphMarker>, v: GraphViewport): List<Guide> {
        val axisY = 0.0.coerceIn(v.yMin, v.yMax); val axisX = 0.0.coerceIn(v.xMin, v.xMax)
        val seenX = HashSet<String>(); val seenY = HashSet<String>()
        val out = ArrayList<Guide>()
        for (m in markers) {
            if (m.x < v.xMin || m.x > v.xMax || m.y < v.yMin || m.y > v.yMax) continue
            val onXAxis = abs(m.y - axisY) < EPS * (1 + abs(m.y)); val onYAxis = abs(m.x - axisX) < EPS * (1 + abs(m.x))
            val xText = GraphMarker.number(m.x).takeIf { abs(m.x) > EPS && seenX.add(it) }
            val yText = GraphMarker.number(m.y).takeIf { abs(m.y) > EPS && seenY.add(it) }
            out += Guide(m.x, m.y, axisY, axisX, hasGuides = !onXAxis && !onYAxis, xLabel = xText, yLabel = yText)
        }
        return out
    }
}
