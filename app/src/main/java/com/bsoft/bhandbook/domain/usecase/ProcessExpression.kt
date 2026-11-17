package com.bsoft.bhandbook.domain.usecase

import com.bsoft.bhandbook.core.math.*
import com.bsoft.bhandbook.core.recognition.ColumnArithmetic
import com.bsoft.bhandbook.core.recognition.ColumnLayout
import com.bsoft.bhandbook.core.recognition.MathRecognitionEngine
import com.bsoft.bhandbook.core.recognition.RecognitionCancelled
import com.bsoft.bhandbook.core.recognition.RecognitionHint
import com.bsoft.bhandbook.core.recognition.RecognitionResult
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.solver.DefinitionParser
import com.bsoft.bhandbook.domain.solver.Definitions
import com.bsoft.bhandbook.domain.solver.FunctionDefinition
import com.bsoft.bhandbook.domain.solver.MathSolutionEngine
import com.bsoft.bhandbook.domain.solver.Substitution
import com.bsoft.bhandbook.domain.solver.ValueDefinition
import com.bsoft.bhandbook.domain.solver.SolutionStep
import com.bsoft.bhandbook.domain.solver.SolveOutcome
import com.bsoft.bhandbook.domain.solver.normalizeLiterals

/** [calc]: the angle unit, and how answers are written (fractions or decimals, decimal places). [definitions]: the named values and functions written on the canvas. */
data class ProcessingSettings(val autoEvaluate: Boolean = true, val calc: CalcSettings = CalcSettings(), val definitions: Definitions = Definitions.EMPTY)

sealed interface ProcessingOutcome {
    val recognized: String
    val confidence: Float
    /** Other readings of the ink the user may have meant (calculator text, best first). Empty for typed or corrected text. */
    val alternatives: List<String>

    /** Recognition is empty or unreliable: nothing is evaluated until the user confirms or corrects the text. */
    data class NeedsReview(override val recognized: String, override val confidence: Float, val warnings: List<String>, override val alternatives: List<String> = emptyList()) : ProcessingOutcome
    /** The text is not a valid expression (e.g. "25+"). */
    data class Invalid(override val recognized: String, override val confidence: Float, val message: String, val position: Int, override val alternatives: List<String> = emptyList()) : ProcessingOutcome
    /** Valid expression, shown but not evaluated (auto-evaluate off and no '='). */
    data class RecognizedOnly(override val recognized: String, override val confidence: Float, val displayText: String, override val alternatives: List<String> = emptyList()) : ProcessingOutcome
    data class Evaluated(override val recognized: String, override val confidence: Float, val displayText: String, val result: String,
        val decimal: String?, val explicit: Boolean, val steps: List<SolutionStep>, override val alternatives: List<String> = emptyList()) : ProcessingOutcome
    data class Solved(override val recognized: String, override val confidence: Float, val displayText: String, val finalExpression: String,
        val solutions: List<String>, val steps: List<SolutionStep>, override val alternatives: List<String> = emptyList()) : ProcessingOutcome
    /** Valid input that cannot be computed (division by zero, unsupported equation...). */
    data class Failed(override val recognized: String, override val confidence: Float, val displayText: String, val message: String, override val alternatives: List<String> = emptyList()) : ProcessingOutcome
}

/** The same outcome with [alternatives] attached (everything else unchanged). */
fun ProcessingOutcome.withAlternatives(alternatives: List<String>): ProcessingOutcome = when (this) {
    is ProcessingOutcome.NeedsReview -> copy(alternatives = alternatives)
    is ProcessingOutcome.Invalid -> copy(alternatives = alternatives)
    is ProcessingOutcome.RecognizedOnly -> copy(alternatives = alternatives)
    is ProcessingOutcome.Evaluated -> copy(alternatives = alternatives)
    is ProcessingOutcome.Solved -> copy(alternatives = alternatives)
    is ProcessingOutcome.Failed -> copy(alternatives = alternatives)
}

/**
 * Ink -> recognised text -> validated AST -> numeric evaluation or symbolic solution.
 * Results are only produced from a successfully parsed expression, and never from an unreliable recognition.
 */
class ProcessExpressionUseCase(
    private val recognizer: MathRecognitionEngine,
    private val parser: MathExpressionParser,
    private val calculator: MathCalculationEngine,
    private val solver: MathSolutionEngine,
) {
    operator fun invoke(strokes: List<Stroke>, settings: ProcessingSettings = ProcessingSettings(), isCancelled: () -> Boolean = { false }): ProcessingOutcome {
        ColumnArithmetic.detect(strokes)?.let { return column(it, settings, isCancelled) }           // grocery-style column over a bar
        val r = recognizer.recognize(strokes, isCancelled)
        val text = r.expression.linear
        if (text.isEmpty() || !r.reliable) return ProcessingOutcome.NeedsReview(text, r.confidence, r.warnings, r.alternatives)
        return fromText(text, r.confidence, settings).withAlternatives(r.alternatives)
    }

    /**
     * Evaluates already-recognised text again, e.g. after the angle unit or the result format changed (the ink did not, so nothing is re-read).
     * [resultInline] says the answer was explicitly asked for (the user wrote "=", or drew the bar of a column), which is not always visible in [text].
     */
    fun reevaluate(text: String, confidence: Float, resultInline: Boolean, settings: ProcessingSettings): ProcessingOutcome {
        val t = text.trimEnd()
        return fromText(if (resultInline && !t.endsWith("=")) "$t=" else t, confidence, settings)
    }

    /** Entry point for user-corrected text: trusted over ink, same validation path. */
    fun fromText(text: String, confidence: Float, settings: ProcessingSettings = ProcessingSettings()): ProcessingOutcome {
        val parsed = when (val p = parser.parse(text)) {
            is ParseResult.Failure -> return ProcessingOutcome.Invalid(text, confidence, p.error.message, p.error.position)
            is ParseResult.Success -> p
        }
        return when (val st = parsed.statement) {
            is MathStatement.Equation -> equation(text, confidence, st, settings)
            is MathStatement.Expression -> expression(text, confidence, st, parsed.requestedExplicitly, settings)
        }
    }

    /** Each row is read on its own (rows are plain numbers, and unchanged rows come straight from the recognition cache); the bar is the "=". */
    private fun column(layout: ColumnLayout, settings: ProcessingSettings, isCancelled: () -> Boolean): ProcessingOutcome {
        val readings = ArrayList<RecognitionResult>()
        for (row in layout.rows) { if (isCancelled()) throw RecognitionCancelled(); readings += recognizer.recognize(row, RecognitionHint.NUMBER, isCancelled) }
        val confidence = readings.minOf { it.confidence }
        val joined = readings.joinToString(" | ") { it.expression.linear }
        val unreliable = readings.indexOfFirst { it.expression.linear.isEmpty() || !it.reliable }
        if (unreliable >= 0) return ProcessingOutcome.NeedsReview(joined, confidence, listOf("Check row ${unreliable + 1} (${readings[unreliable].expression.linear.ifEmpty { "unreadable" }})") + readings[unreliable].warnings)
        return when (val t = ColumnTotal.compute(readings.map { ColumnTotal.RowReading(it.latex?.split(" ")?.filter { x -> x.isNotEmpty() }, it.expression.linear) }, settings.calc)) {
            is ColumnTotal.Problem -> ProcessingOutcome.NeedsReview(joined, confidence, listOf(t.message.replaceFirstChar { it.uppercase() }))
            is ColumnTotal.Result -> ProcessingOutcome.Evaluated(t.linear, confidence, t.display, MoneyFormat.decorate(t.total, settings.calc), null, explicit = true, steps = emptyList())
        }
    }

    private fun isBareNumber(e: Expr): Boolean = when (e) { is Num -> true; is Group -> isBareNumber(e.inner); is Neg -> isBareNumber(e.operand); else -> false }

    private fun display(e: Expr) = Printer.print(normalizeLiterals(e))

    private fun expression(text: String, confidence: Float, st: MathStatement.Expression, explicit: Boolean, settings: ProcessingSettings): ProcessingOutcome {
        val shown = display(st.expr)
        if (!explicit && (!settings.autoEvaluate || isBareNumber(st.expr))) return ProcessingOutcome.RecognizedOnly(text, confidence, shown)   // a lone number needs no "answer"
        val used = usedDefinitions(Substitution.namesIn(st.expr), settings.definitions)
        val prepared = if (used != null) prepare(st.expr, settings.definitions) else st.expr
        return when (val c = calculator.withAngle(settings.calc.angle).evaluate(prepared)) {
            is CalcResult.Failure ->
                if (c.error == CalcError.UNDEFINED_VARIABLE && !explicit) ProcessingOutcome.RecognizedOnly(text, confidence, shown)
                else ProcessingOutcome.Failed(text, confidence, shown, hint(c.message))
            is CalcResult.Success -> {
                val f = NumberFormatter.format(c.value, settings.calc)
                val steps = (solver.solve(MathStatement.Expression(prepared), settings.calc) as? SolveOutcome.Solved)?.steps.orEmpty()
                ProcessingOutcome.Evaluated(text, confidence, shown, MoneyFormat.decorate(f.primary, settings.calc), f.decimal, explicit,
                    if (used != null) withSubstitutionStep(steps, SolutionStep(1, shown, used, display(prepared))) else steps)
            }
        }
    }

    // ---------------------------------------------------------------- named values and functions
    /** Defined functions expanded, defined values replaced by their numbers (x, y and z are never replaced). */
    private fun prepare(e: Expr, defs: Definitions): Expr = Substitution.withValues(Substitution.expandCalls(e, defs.functions), defs)

    /** "Use the values you defined: a = 5, f(x) = 2x + 1" for the defined names among [names]; null if none is defined. */
    private fun usedDefinitions(names: Set<String>, defs: Definitions): String? {
        if (defs.isEmpty) return null
        val parts = names.sorted().mapNotNull { n ->
            val f = defs.functions[n]
            when {
                f != null -> "$n(${f.param}) = ${display(f.body)}"
                n in defs.values && n !in Definitions.RESERVED -> "$n = ${NumberFormatter.format(defs.values.getValue(n)).primary}"
                else -> null
            }
        }
        return if (parts.isEmpty()) null else "Use the values you defined: " + parts.joinToString(", ")
    }

    private fun withSubstitutionStep(steps: List<SolutionStep>, first: SolutionStep) = listOf(first) + steps.map { it.copy(number = it.number + 1) }

    /** Tells the user how to give a missing name a value, instead of only saying that it has none. */
    private fun hint(message: String): String {
        val name = Regex("^'(.)' has no value").find(message)?.groupValues?.get(1) ?: return message
        return if (name in Definitions.RESERVED) "'$name' is an unknown: write an equation (like 2$name + 1 = 7) to solve for it."
        else "'$name' has no value yet. Write $name = … somewhere on the canvas to define it."
    }

    private fun equation(text: String, confidence: Float, st: MathStatement.Equation, settings: ProcessingSettings): ProcessingOutcome {
        val shown = display(st.lhs) + " = " + display(st.rhs)
        val definition = DefinitionParser.parse(st)
        if (definition is FunctionDefinition) return ProcessingOutcome.RecognizedOnly(text, confidence, "${definition.name}(${definition.param}) = ${display(definition.body)}")   // recorded, not solved

        // Defined names are known, so with two or more names the defined ones are replaced and the rest are solved for; one name alone is the unknown
        // (a = 5 stays "a = 5", and so does a + 2 = 9 even if a is defined elsewhere). A value definition keeps its own name on the left.
        val defs = settings.definitions
        val names = if (definition is ValueDefinition) Substitution.namesIn(st.rhs) + definition.name else Substitution.namesIn(st.lhs) + Substitution.namesIn(st.rhs)
        val used = if (names.size >= 2) usedDefinitions(if (definition is ValueDefinition) Substitution.namesIn(st.rhs) else names, defs) else null
        val eq = if (used == null) st else MathStatement.Equation(if (definition is ValueDefinition) st.lhs else prepare(st.lhs, defs), prepare(st.rhs, defs))
        val decided = used != null && definition !is ValueDefinition && (Substitution.namesIn(eq.lhs) + Substitution.namesIn(eq.rhs)).isEmpty()      // nothing left to solve: it is true or false
        fun steps(s: List<SolutionStep>) = if (used == null) s else withSubstitutionStep(s, SolutionStep(1, shown, used, display(eq.lhs) + " = " + display(eq.rhs)))
        return when (val o = solver.solve(eq, settings.calc)) {
            is SolveOutcome.Solved -> ProcessingOutcome.Solved(text, confidence, shown, o.finalExpression, o.solutions, steps(o.steps))
            is SolveOutcome.NoSolution -> ProcessingOutcome.Solved(text, confidence, shown, if (decided) "false" else "no solution", emptyList(), steps(o.steps))
            is SolveOutcome.Identity -> ProcessingOutcome.Solved(text, confidence, shown, if (decided) "true" else "true for all values", emptyList(), steps(o.steps))
            is SolveOutcome.Unsupported -> ProcessingOutcome.Failed(text, confidence, shown, o.reason)
            is SolveOutcome.Invalid -> ProcessingOutcome.Failed(text, confidence, shown, o.reason)
        }
    }
}
