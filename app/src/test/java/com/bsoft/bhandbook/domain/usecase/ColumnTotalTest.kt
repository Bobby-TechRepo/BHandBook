package com.bsoft.bhandbook.domain.usecase

import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.math.ExactCalculationEngine
import com.bsoft.bhandbook.core.recognition.*
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import org.junit.Assert.*
import org.junit.Test

class ColumnRowsTest {
    private fun tokens(s: String) = s.trim().split(" ").filter { it.isNotEmpty() }
    private fun total(vararg rows: String): ColumnTotal.Result = ColumnTotal.compute(rows.map { ColumnTotal.RowReading(tokens(it), null) }) as ColumnTotal.Result

    @Test fun groceries_decimalsAreExact() { val r = total("1 2 . 5 0", "3 . 7 5", "+ 8"); assertEquals("24.25", r.total); assertEquals("12.5+3.75+8", r.linear) }
    @Test fun noOperatorMeansAddition() { assertEquals("60", total("1 0", "2 0", "3 0").total) }
    @Test fun thousandsSeparators_areUnderstood() { assertEquals("3750.5", total("1 , 5 0 0", "2 , 2 5 0 . 5 0").total) }
    @Test fun subtractionRows() { assertEquals("65", total("1 0 0", "- 2 5", "- 1 0").total) }
    @Test fun rowsAreAppliedLeftToRight_likeColumnArithmetic_notWithOperatorPrecedence() { assertEquals("20", total("2", "+ 3", "\\times 4").total); assertEquals("20", total("1 0 0", "\\div 4", "- 5").total) }
    @Test fun aNegativeFirstRow() { assertEquals("-2", total("- 5", "+ 3").total) }
    @Test fun fractionsStayExact() { assertEquals("1/3", total("1", "\\div 3").total) }
    @Test fun theLinearTextIsSomethingTheParserAcceptsAndEvaluatesToTheSameTotal() {
        val r = total("2", "+ 3", "\\times 4"); val st = (DefaultMathParser().parse(r.linear) as com.bsoft.bhandbook.core.math.ParseResult.Success).statement as com.bsoft.bhandbook.core.math.MathStatement.Expression
        assertEquals(20.0, (ExactCalculationEngine().evaluate(st.expr) as com.bsoft.bhandbook.core.math.CalcResult.Success).value.toDouble(), 0.0)
    }
    @Test fun unreadableRows_reportWhichOne() {
        for ((rows, bad) in listOf(listOf("1 2", "a b") to 1, listOf("1 . 2 . 3", "4") to 0, listOf("1 2", "") to 1, listOf("1 2", "+") to 1, listOf("1 2", "\\sum") to 1)) {
            val e = ColumnTotal.compute(rows.map { ColumnTotal.RowReading(tokens(it), null) }); assertTrue("$rows -> $e", e is ColumnTotal.Problem); assertEquals(bad, (e as ColumnTotal.Problem).row)
        }
    }
    @Test fun divisionByZero_isAProblem() { assertTrue(ColumnTotal.compute(listOf("5", "\\div 0").map { ColumnTotal.RowReading(tokens(it), null) }) is ColumnTotal.Problem) }
    @Test fun rowsReadByTheFallbackRecogniser_useTheirPlainText() {
        val e = ColumnTotal.compute(listOf(ColumnTotal.RowReading(null, "12.5"), ColumnTotal.RowReading(null, "+7.5"), ColumnTotal.RowReading(null, "×2"))) as ColumnTotal.Result
        assertEquals("40", e.total)
    }
}

class ColumnUseCaseTest {
    private val parser = DefaultMathParser()
    private fun column(vararg rows: String): List<Stroke> {
        val out = ArrayList<Stroke>(); var baseline = 300f
        for ((i, t) in rows.withIndex()) {
            val s = Pen(10L + i, size = 70f, sloppiness = 0.4f).at(0f, baseline).text(t).strokes; val shift = 500f - s.maxOf { it.bounds.right }
            out += s.map { it.translated(shift, 0f) }; baseline += 98f
        }
        val y = baseline - 98f + 25f
        return out + Stroke("bar", (0..12).map { StrokePoint(170f + 340f * it / 12f, y, 900_000L + it) }, 4f, 0)
    }
    private fun read(text: String, reliable: Boolean = true, latex: String? = null) =
        RecognitionResult(MathExpression(MathStructure.Row(emptyList()), text, emptyList()), if (reliable) 0.95f else 0.3f, reliable, if (reliable) emptyList() else listOf("low confidence"), latex = latex)
    private class RowEngine(val answers: List<RecognitionResult>) : MathRecognitionEngine {
        var calls = 0; val rowSizes = ArrayList<Int>(); val hints = ArrayList<RecognitionHint>()
        override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean) = recognize(strokes, RecognitionHint.ANY, isCancelled)
        override fun recognize(strokes: List<Stroke>, hint: RecognitionHint, isCancelled: () -> Boolean): RecognitionResult { rowSizes += strokes.size; hints += hint; return answers[calls++] }
    }
    private fun useCase(engine: MathRecognitionEngine) = ProcessExpressionUseCase(engine, parser, ExactCalculationEngine(), DefaultSolutionEngine())

    @Test fun aColumnOverABar_isTotalledImmediately_rowByRow() {
        val engine = RowEngine(listOf(read("12.5", latex = "1 2 . 5 0"), read("3.75", latex = "3 . 7 5"), read("+8", latex = "+ 8")))
        val o = useCase(engine)(column("12.50", "3.75", "+8")) as ProcessingOutcome.Evaluated
        assertEquals("24.25", o.result); assertTrue("the bar is an explicit request", o.explicit)
        assertEquals("12.5+3.75+8", o.recognized); assertEquals("12.5 + 3.75 + 8", o.displayText)
        assertEquals("each row is recognised on its own", 3, engine.calls)
        assertEquals("rows are known to be numbers, so the model is told so", List(3) { RecognitionHint.NUMBER }, engine.hints)
    }
    @Test fun autoEvaluateOff_doesNotStopAColumn_becauseTheBarIsAnExplicitRequest() {
        val engine = RowEngine(listOf(read("1", latex = "1"), read("2", latex = "+ 2")))
        assertTrue(useCase(engine)(column("1", "2"), ProcessingSettings(autoEvaluate = false)) is ProcessingOutcome.Evaluated)
    }
    @Test fun anUnreliableRow_asksForReview_insteadOfAddingAGuess() {
        val engine = RowEngine(listOf(read("12.5", latex = "1 2 . 5"), read("3?5", reliable = false)))
        val o = useCase(engine)(column("12.5", "3.75")) as ProcessingOutcome.NeedsReview
        assertTrue(o.warnings.any { it.contains("row 2", ignoreCase = true) })
    }
    @Test fun aRowThatIsNotANumber_isReportedWithItsRowNumber() {
        val engine = RowEngine(listOf(read("12", latex = "1 2"), read("a+b", latex = "a + b")))
        val o = useCase(engine)(column("12", "34")); assertTrue("$o", o is ProcessingOutcome.NeedsReview || o is ProcessingOutcome.Invalid)
        assertTrue(o.recognized.isNotBlank())
    }
    @Test fun aCorrectedColumnText_isEvaluatedLikeAnyOtherExpression() {
        val o = useCase(RowEngine(emptyList())).fromText("12.5+3.75+8", 1f) as ProcessingOutcome.Evaluated; assertEquals("24.25", o.result)
    }
    @Test fun aLoneNumber_doesNotGetARedundantAnswerPrintedUnderIt() {
        for (n in listOf("12.50", "7", "-3")) {
            val o = useCase(RowEngine(emptyList())).fromText(n, 1f); assertTrue("$n -> $o", o is ProcessingOutcome.RecognizedOnly)
        }
        assertTrue(useCase(RowEngine(emptyList())).fromText("7=", 1f) is ProcessingOutcome.Evaluated)           // asking explicitly still answers
    }
    @Test fun anOrdinaryExpression_isUnchangedByTheColumnPath() {
        val engine = RowEngine(listOf(read("25+15=")))
        val o = useCase(engine)(Pen.write("25+15=", 1)); assertEquals(1, engine.calls); assertEquals(listOf(RecognitionHint.ANY), engine.hints); assertTrue(o is ProcessingOutcome.Evaluated)
    }

}

class ColumnWithWrittenAnswerTest {
    private val parser = DefaultMathParser()
    @Test fun theHandWrittenAnswer_isNeverRecognisedAsARow_orAddedIntoTheSum() {
        val ink = ArrayList<Stroke>(); var baseline = 300f
        for ((i, t) in listOf("125", "47").withIndex()) { val s = Pen(10L + i, size = 70f, sloppiness = 0.4f).at(0f, baseline).text(t).strokes; ink += s.map { it.translated(500f - s.maxOf { x -> x.bounds.right }, 0f) }; baseline += 98f }
        val y = baseline - 98f + 25f
        val bar = Stroke("bar", (0..12).map { StrokePoint(170f + 340f * it / 12f, y, 900_000L + it) }, 4f, 0)
        val ans = Pen(99, size = 70f, sloppiness = 0.4f).at(0f, y + 110f).text("172").strokes.let { s -> s.map { it.translated(500f - s.maxOf { x -> x.bounds.right }, 0f) } }
        val answers = ArrayDeque(listOf("125", "+47").map { RecognitionResult(MathExpression(MathStructure.Row(emptyList()), it, emptyList()), 0.95f, true, latex = it.toCharArray().joinToString(" ")) })
        var calls = 0
        val engine = object : MathRecognitionEngine { override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean): RecognitionResult { calls++; return answers.removeFirst() } }
        val o = ProcessExpressionUseCase(engine, parser, ExactCalculationEngine(), DefaultSolutionEngine())(ink + bar + ans) as ProcessingOutcome.Evaluated
        assertEquals("172", o.result); assertEquals("only the two rows are recognised", 2, calls)
    }
}
