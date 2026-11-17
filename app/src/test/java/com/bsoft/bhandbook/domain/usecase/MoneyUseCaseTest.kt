package com.bsoft.bhandbook.domain.usecase

import com.bsoft.bhandbook.core.math.*
import com.bsoft.bhandbook.core.recognition.*
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import org.junit.Assert.*
import org.junit.Test

class MoneyUseCaseTest {
    private val none = object : MathRecognitionEngine { override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean) = throw AssertionError("not used") }
    private val useCase = ProcessExpressionUseCase(none, DefaultMathParser(), ExactCalculationEngine(), DefaultSolutionEngine())
    private fun result(text: String, calc: CalcSettings) = (useCase.fromText(text, 1f, ProcessingSettings(calc = calc)) as ProcessingOutcome.Evaluated).result

    @Test fun anAnswerIsGroupedAndPrefixedWithTheCurrency() {
        assertEquals("1234000", result("1234*1000=", CalcSettings()))
        assertEquals("1,234,000", result("1234*1000=", CalcSettings(grouping = true)))
        assertEquals("₦1,234,000.00", result("1234*1000=", CalcSettings(grouping = true, currency = "₦", places = 2)))
    }
    @Test fun aFractionalAnswerIsNotGivenACurrency() { assertEquals("10/3", result("10/3=", CalcSettings(currency = "₦", grouping = true))) }
    @Test fun anEquationsSolution_isNeverDecorated_becauseItIsReParsed() {
        val o = useCase.fromText("x=1234567", 1f, ProcessingSettings(calc = CalcSettings(grouping = true, currency = "₦"))) as ProcessingOutcome.Solved
        assertEquals("x = 1234567", o.finalExpression); assertEquals(listOf("1234567"), o.solutions)
    }
    @Test fun stepsStayPlain() {
        val o = useCase.fromText("1000*1000+1=", 1f, ProcessingSettings(calc = CalcSettings(grouping = true, currency = "₦"))) as ProcessingOutcome.Evaluated
        assertEquals("₦1,000,001", o.result); assertTrue(o.steps.all { !it.resulting.contains(',') && !it.resulting.contains('₦') })
    }
    @Test fun aColumnTotalIsDecorated() {
        val total = ColumnTotal.compute(listOf("1 2 5 0 0", "+ 7 5 0 0").map { ColumnTotal.RowReading(it.split(" "), null) }, CalcSettings(places = 2)) as ColumnTotal.Result
        assertEquals("20000.00", total.total)
        assertEquals("₦20,000.00", MoneyFormat.decorate(total.total, CalcSettings(grouping = true, currency = "₦")))
    }
    @Test fun reevaluating_picksUpTheNewMoneySettings() {
        val a = useCase.reevaluate("1234*1000=", 1f, true, ProcessingSettings(calc = CalcSettings(grouping = true))) as ProcessingOutcome.Evaluated
        val b = useCase.reevaluate("1234*1000=", 1f, true, ProcessingSettings(calc = CalcSettings(grouping = true, currency = "$"))) as ProcessingOutcome.Evaluated
        assertEquals("1,234,000", a.result); assertEquals("$1,234,000", b.result)
    }
    @Test fun aWrittenColumn_isDecoratedEndToEnd() {
        val rows = listOf("125", "+75").mapIndexed { i, t -> RecognitionResult(MathExpression(MathStructure.Row(emptyList()), t, emptyList()), 0.95f, true, latex = t.toCharArray().joinToString(" ")) }
        var n = 0; val engine = object : MathRecognitionEngine { override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean) = rows[n++] }
        val ink = ArrayList<Stroke>(); var baseline = 300f
        for ((i, t) in listOf("125", "75").withIndex()) { val s = Pen(10L + i, size = 70f, sloppiness = 0.4f).at(0f, baseline).text(t).strokes; ink += s.map { it.translated(500f - s.maxOf { x -> x.bounds.right }, 0f) }; baseline += 98f }
        val y = baseline - 98f + 25f
        ink += Stroke("bar", (0..12).map { StrokePoint(170f + 340f * it / 12f, y, 900_000L + it) }, 4f, 0)
        val o = ProcessExpressionUseCase(engine, DefaultMathParser(), ExactCalculationEngine(), DefaultSolutionEngine())(ink, ProcessingSettings(calc = CalcSettings(currency = "₦", places = 2))) as ProcessingOutcome.Evaluated
        assertEquals("₦200.00", o.result)
    }
}
