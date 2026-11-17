package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.core.math.CalcSettings
import com.bsoft.bhandbook.core.math.MathExpressionParser
import com.bsoft.bhandbook.core.math.MathStatement
import com.bsoft.bhandbook.core.math.ParseResult
import com.bsoft.bhandbook.domain.graph.GraphBuilder
import com.bsoft.bhandbook.domain.graph.GraphData
import com.bsoft.bhandbook.domain.graph.GraphMarker
import com.bsoft.bhandbook.domain.graph.GraphModel
import com.bsoft.bhandbook.domain.model.CanvasState
import com.bsoft.bhandbook.domain.model.ProcessingStatus
import com.bsoft.bhandbook.domain.solver.Definition
import com.bsoft.bhandbook.domain.solver.DefinitionParser
import com.bsoft.bhandbook.domain.solver.DefinitionResolver
import com.bsoft.bhandbook.domain.solver.Definitions
import com.bsoft.bhandbook.domain.solver.MathSolutionEngine
import com.bsoft.bhandbook.domain.solver.Substitution
import com.bsoft.bhandbook.domain.solver.SolutionStep
import com.bsoft.bhandbook.domain.solver.SolveOutcome

/** The answer to equations that belong together. It is written under the lowest equation ([anchorId]). */
class SystemSolution(val memberIds: List<String>, val anchorId: String, val summary: String, val steps: List<SolutionStep>, val isError: Boolean)

/** [systems] is keyed by EVERY member id (all members of a system share one [SystemSolution]); [graphable] are the expressions that can be plotted; [definitions] the named values and functions written on the canvas. */
class Insights(val systems: Map<String, SystemSolution>, val graphable: Set<String>, val definitions: Definitions = Definitions.EMPTY)

/**
 * Things that depend on several expressions at once, derived from the canvas (never stored): simultaneous equations written one under the other,
 * and which expressions can be graphed. Pure and fast enough to run on every change.
 */
class CanvasInsights(private val parser: MathExpressionParser, private val solver: MathSolutionEngine) {
    private val parsed = object : LinkedHashMap<String, MathStatement?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MathStatement?>?) = size > 512
    }
    private val graphableByText = HashMap<String, Boolean>()

    private fun statementOf(text: String?): MathStatement? {
        if (text == null) return null
        return parsed.getOrPut(text) { (parser.parse(text) as? ParseResult.Success)?.statement }
    }

    private fun eligible(status: ProcessingStatus) = status == ProcessingStatus.EVALUATED || status == ProcessingStatus.ERROR || status == ProcessingStatus.RECOGNIZED

    /** Whether [text] mentions a name (a letter that could be a defined value or function), so its answer can change when definitions change. */
    fun usesNames(text: String?): Boolean = statementOf(text)?.let { st ->
        when (st) { is MathStatement.Expression -> Substitution.namesIn(st.expr); is MathStatement.Equation -> Substitution.namesIn(st.lhs) + Substitution.namesIn(st.rhs) }.isNotEmpty()
    } ?: false

    /** The named values and functions written on the canvas (`a = 5`, `f(x) = 2x + 1`); where a name is written twice, the more recently written one counts. */
    fun definitionsOf(state: CanvasState, calc: CalcSettings = CalcSettings()): Definitions {
        val found = ArrayList<Definition>()
        for (e in state.expressions.values) {
            if (!eligible(e.status)) continue
            statementOf(e.recognized)?.let { st -> DefinitionParser.parse(st, e.modifiedAt)?.let { found += it } }
        }
        return DefinitionResolver.resolve(found, calc)
    }

    fun analyse(state: CanvasState, calc: CalcSettings = CalcSettings()): Insights {
        val defs = definitionsOf(state, calc)
        val bounds = HashMap<String, WorldRect>()
        for (s in state.strokes) s.expressionId?.let { id -> bounds[id] = bounds[id]?.union(s.bounds) ?: s.bounds }
        val statements = HashMap<String, MathStatement>()
        val blocks = ArrayList<EquationBlock>()
        for ((id, e) in state.expressions) {
            if (!eligible(e.status)) continue
            val st = statementOf(e.recognized) ?: continue
            statements[id] = st
            val b = bounds[id] ?: continue
            if (st is MathStatement.Equation) blocks += EquationBlock(id, b, st)
        }

        val systems = HashMap<String, SystemSolution>(); val graphable = HashSet<String>()
        for (group in EquationSystems.detect(blocks)) {
            val sol = summarise(group, EquationSystems.solve(group, blocks, solver, calc), group.ids.mapNotNull { statements[it] }, calc, defs)
            for (id in group.ids) systems[id] = sol
            if (GraphBuilder.canGraph(group.ids.mapNotNull { statements[it] }, calc.angle, defs)) graphable += group.ids
        }
        for ((id, st) in statements) {
            if (id in graphable) continue
            val text = state.expressions[id]?.recognized ?: continue
            if (graphableByText.getOrPut(text + "|" + calc.angle + "|" + defs.signature()) { GraphBuilder.canGraph(listOf(st), calc.angle, defs) }) graphable += id
        }
        return Insights(systems, graphable, defs)
    }

    /**
     * Linear systems are solved exactly. For a non-linear one the graph code can still find where the curves cross (numerically), so those points
     * are reported as approximate solutions instead of just "not supported".
     */
    private fun approximate(group: SystemGroup, statements: List<MathStatement>, calc: CalcSettings, defs: Definitions): SystemSolution? {
        if (statements.size != group.ids.size) return null
        val spec = GraphBuilder.build(statements, calc.angle, defs)
        if (spec == null || spec.usesTrig) return null                      // periodic curves cross endlessly often: there is no single list of answers
        val markers = spec.markers
        if (markers.isEmpty()) return null
        val text = markers.joinToString(";  ") { "x ≈ ${GraphMarker.number(it.x)}, y ≈ ${GraphMarker.number(it.y)}" }
        return SystemSolution(group.ids, group.anchorId, text, emptyList(), false)
    }

    private fun summarise(group: SystemGroup, o: SolveOutcome, statements: List<MathStatement>, calc: CalcSettings, defs: Definitions): SystemSolution = when (o) {
        is SolveOutcome.Solved -> SystemSolution(group.ids, group.anchorId, o.finalExpression, o.steps, false)
        is SolveOutcome.NoSolution -> SystemSolution(group.ids, group.anchorId, "no solution", o.steps, false)
        is SolveOutcome.Identity -> SystemSolution(group.ids, group.anchorId, "all values work", o.steps, false)
        is SolveOutcome.Unsupported -> approximate(group, statements, calc, defs) ?: SystemSolution(group.ids, group.anchorId, o.reason, emptyList(), true)
        is SolveOutcome.Invalid -> SystemSolution(group.ids, group.anchorId, o.reason, emptyList(), true)
    }

    /** Where a graph of [id] is drawn: next to the lowest equation of its system, or next to the expression itself. */
    fun graphKey(id: String, insights: Insights): String = insights.systems[id]?.anchorId ?: id

    /** [view]: the part of the graph chosen by hand (null: framed automatically). */
    fun graphFor(id: String, state: CanvasState, insights: Insights = analyse(state), calc: CalcSettings = CalcSettings(), view: com.bsoft.bhandbook.domain.graph.GraphViewport? = null): GraphData? {
        val ids = insights.systems[id]?.memberIds ?: listOf(id)
        val statements = ids.mapNotNull { statementOf(state.expressions[it]?.recognized) }
        if (statements.size != ids.size) return null
        return GraphBuilder.build(statements, calc.angle, insights.definitions)?.let { GraphModel.layout(it, view) }
    }
}
