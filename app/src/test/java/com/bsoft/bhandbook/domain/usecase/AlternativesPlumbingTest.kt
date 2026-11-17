package com.bsoft.bhandbook.domain.usecase

import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.math.ExactCalculationEngine
import com.bsoft.bhandbook.core.recognition.*
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import org.junit.Assert.*
import org.junit.Test

class AlternativesPlumbingTest {
    private val ink = Pen.write("25+15=", 1)
    private fun reading(text: String, reliable: Boolean = true, alternatives: List<String>) =
        RecognitionResult(MathExpression(MathStructure.Row(emptyList()), text, emptyList()), if (reliable) 0.95f else 0.3f, reliable, if (reliable) emptyList() else listOf("low confidence"), alternatives = alternatives)
    private fun useCase(r: RecognitionResult) = ProcessExpressionUseCase(object : MathRecognitionEngine { override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean) = r }, DefaultMathParser(), ExactCalculationEngine(), DefaultSolutionEngine())
    private val alts = listOf("25+16", "25-15")

    @Test fun anEvaluatedExpression_carriesTheAlternatives() { val o = useCase(reading("25+15=", alternatives = alts))(ink); assertTrue(o is ProcessingOutcome.Evaluated); assertEquals(alts, o.alternatives) }
    @Test fun aSolvedEquation_carriesThem() { val o = useCase(reading("2x+5=15", alternatives = alts))(ink); assertTrue(o is ProcessingOutcome.Solved); assertEquals(alts, o.alternatives) }
    @Test fun anUnreliableReading_carriesThem_soTheUserCanPickOne() { val o = useCase(reading("25+15=", reliable = false, alternatives = alts))(ink); assertTrue(o is ProcessingOutcome.NeedsReview); assertEquals(alts, o.alternatives) }
    @Test fun aReadingThatDoesNotParse_carriesThem() { val o = useCase(reading("25+", alternatives = alts))(ink); assertTrue(o is ProcessingOutcome.Invalid); assertEquals(alts, o.alternatives) }
    @Test fun aReadingThatCannotBeComputed_carriesThem() { val o = useCase(reading("1/0=", alternatives = alts))(ink); assertTrue(o is ProcessingOutcome.Failed); assertEquals(alts, o.alternatives) }
    @Test fun aRecognisedOnlyExpression_carriesThem() { val o = useCase(reading("25+15", alternatives = alts))(ink, ProcessingSettings(autoEvaluate = false)); assertTrue(o is ProcessingOutcome.RecognizedOnly); assertEquals(alts, o.alternatives) }
    @Test fun noAlternatives_isTheDefault() { assertEquals(emptyList<String>(), useCase(reading("25+15=", alternatives = emptyList()))(ink).alternatives); assertEquals(emptyList<String>(), ProcessingOutcome.NeedsReview("x", 0f, emptyList()).alternatives) }
    @Test fun correctedText_hasNoAlternatives() { assertEquals(emptyList<String>(), useCase(reading("1+1=", alternatives = alts)).fromText("2+2=", 1f).alternatives) }

    @Test fun withAlternatives_keepsEveryOtherField_forEveryKind() {
        val kinds: List<ProcessingOutcome> = listOf(
            ProcessingOutcome.NeedsReview("a", 0.1f, listOf("w")), ProcessingOutcome.Invalid("b", 0.2f, "m", 3), ProcessingOutcome.RecognizedOnly("c", 0.3f, "C"),
            ProcessingOutcome.Evaluated("d", 0.4f, "D", "4", "4.0", true, emptyList()), ProcessingOutcome.Solved("e", 0.5f, "E", "x = 1", listOf("1"), emptyList()), ProcessingOutcome.Failed("f", 0.6f, "F", "bad"),
        )
        for (k in kinds) {
            val w = k.withAlternatives(alts); assertEquals(alts, w.alternatives); assertEquals(k.recognized, w.recognized); assertEquals(k.confidence, w.confidence, 0f)
            assertEquals("everything but the alternatives is unchanged", k, w.withAlternatives(emptyList()))
        }
    }
}
