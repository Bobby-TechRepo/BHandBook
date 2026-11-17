package com.bsoft.bhandbook.core.comer

import org.junit.Assert.*
import org.junit.Test

class ComerAlternativesTest {
    private fun cand(rank: Int, latex: String, usable: Boolean = true) = ComerCandidateReport(rank, emptyList(), latex, -0.1 * rank, 0.9, usable, null)
    private fun alt(chosen: String, vararg latex: String, max: Int = 3) = ComerAlternatives.of(latex.mapIndexed { i, l -> cand(i, l) }, chosen, max)

    @Test fun runnersUp_areShownAsCalculatorText_inRankOrder() { assertEquals(listOf("25+16", "25-15"), alt("25+15", "2 5 + 1 5", "2 5 + 1 6", "2 5 - 1 5")) }
    @Test fun theChosenReading_isNeverOffered() { assertEquals(listOf("25+16"), alt("25+15", "2 5 + 1 5", "2 5 + 1 5", "2 5 + 1 6")) }
    @Test fun duplicatesAfterConversion_appearOnce() { assertEquals(listOf("12×8"), alt("12+8", "1 2 + 8", "1 2 \\times 8", "1 2 \\times 8", "1 2 \\cdot 8")) }
    @Test fun readingsThatCannotBeCalculated_areNotOffered() { assertEquals(listOf("25+16"), alt("25+15", "2 5 + 1 5", "\\sum a", "2 5 + 1 6", "{ 1 ^")) }
    @Test fun unusableCandidates_areNotOffered() { assertEquals(emptyList<String>(), ComerAlternatives.of(listOf(cand(0, "2 5 + 1 5"), cand(1, "2 5 + 1 6", usable = false)), "25+15", 3)) }
    @Test fun atMostMaxAreReturned() { assertEquals(listOf("1+1", "1+2"), alt("1+3", "1 + 3", "1 + 1", "1 + 2", "1 + 4", "1 + 5", max = 2)) }
    @Test fun emptyAndBlankReadings_areIgnored() { assertEquals(emptyList<String>(), alt("1+1", "", "   ", "1 + 1")) }
    @Test fun noCandidates_noAlternatives() { assertEquals(emptyList<String>(), ComerAlternatives.of(emptyList(), "1+1", 3)) }
    @Test fun fractionsAndRoots_areReadable() { assertEquals(listOf("((3)/(4))", "√(144)"), alt("5", "5", "\\frac { 3 } { 4 }", "\\sqrt { 1 4 4 }")) }
    @Test fun anAlternativeThatDiffersOnlyInTheRequestingEqualsSign_isNotAnAlternative() { assertEquals(emptyList<String>(), alt("25+15=", "2 5 + 1 5 =", "2 5 + 1 5")) }
}
