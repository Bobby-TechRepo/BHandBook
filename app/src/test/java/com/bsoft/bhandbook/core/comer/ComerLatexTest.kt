package com.bsoft.bhandbook.core.comer

import org.junit.Assert.*
import org.junit.Test

class ComerLatexTest {
    private fun t(s: String) = if (s.isBlank()) emptyList() else s.trim().split(" ")

    // ports of the reference's own unit tests -----------------------------------------------------
    @Test fun balancedBracesAreUntouched() { assertEquals(t("\\frac { 1 } { 2 }"), ComerLatex.repair(t("\\frac { 1 } { 2 }"))) }
    @Test fun unmatchedClosingBraceIsDropped() { assertEquals(t("a b"), ComerLatex.repair(t("a } b"))) }
    @Test fun unclosedOpeningBraceIsClosed() { assertEquals(t("\\sqrt { 9 }"), ComerLatex.repair(t("\\sqrt { 9"))) }
    @Test fun extraFractionGroupsAreDropped() { assertEquals(t("\\frac { 1 } { 2 }"), ComerLatex.repair(t("\\frac { 1 } { 2 } { 3 }"))) }
    @Test fun extraSqrtGroupsAreDropped() { assertEquals(t("\\sqrt { 1 }"), ComerLatex.repair(t("\\sqrt { 1 } { 2 }"))) }
    @Test fun completeness() {
        assertFalse(ComerLatex.isComplete(emptyList())); assertFalse(ComerLatex.isComplete(t("\\frac"))); assertFalse(ComerLatex.isComplete(t("\\sqrt")))
        assertFalse(ComerLatex.isComplete(t("\\frac { 1 }"))); assertFalse(ComerLatex.isComplete(t("\\sin")))
        assertTrue(ComerLatex.isComplete(t("\\frac { 1 } { 2 }"))); assertTrue(ComerLatex.isComplete(t("2 + 3"))); assertTrue(ComerLatex.isComplete(t("\\sqrt { 9 }")))
    }
    @Test fun structuralValidity() {
        assertFalse(ComerLatex.isStructurallyValid("")); assertFalse(ComerLatex.isStructurallyValid("{ a")); assertFalse(ComerLatex.isStructurallyValid("a }"))
        assertFalse(ComerLatex.isStructurallyValid("\\frac { 1 }")); assertFalse(ComerLatex.isStructurallyValid("\\sqrt"))
        assertTrue(ComerLatex.isStructurallyValid("\\frac { 1 } { 2 }")); assertTrue(ComerLatex.isStructurallyValid("\\sqrt 9"))
    }

    // differential: 724 cases executed by the reference TypeScript ----------------------------------
    @Test fun matchesTheReference_onEveryRecordedCase() {
        val cases = ComerTestData.list(ComerTestData.json("latex_cases.json"))
        assertTrue(cases.size >= 700)
        for ((i, c) in cases.withIndex()) {
            val m = ComerTestData.map(c); val tokens = ComerTestData.strings(m["tokens"])
            val repaired = ComerLatex.repair(tokens)
            assertEquals("case $i repair of $tokens", ComerTestData.strings(m["repaired"]), repaired)
            assertEquals("case $i completeBefore $tokens", m["completeBefore"], ComerLatex.isComplete(tokens))
            assertEquals("case $i completeAfter $repaired", m["completeAfter"], ComerLatex.isComplete(repaired))
            assertEquals("case $i validBefore $tokens", m["validBefore"], ComerLatex.isStructurallyValid(tokens.joinToString(" ")))
            assertEquals("case $i validAfter $repaired", m["validAfter"], ComerLatex.isStructurallyValid(repaired.joinToString(" ")))
        }
    }

    // additions that are NOT in the reference (kept separate so the port stays exact) ---------------
    @Test fun collapsesRepeatedEqualsSigns_whichTheModelEmitsForAtwoStrokeEquals() {
        assertEquals(t("2 5 + 1 5 ="), ComerLatex.collapseRepeatedEquals(t("2 5 + 1 5 = =")))
        assertEquals(t("a = b"), ComerLatex.collapseRepeatedEquals(t("a = b")))
        assertEquals("a = b = c", ComerLatex.collapseRepeatedEquals(t("a = = b = c")).joinToString(" "))     // adjacent pair merged
        assertEquals("separated equals signs are real", t("a = b = c"), ComerLatex.collapseRepeatedEquals(t("a = b = c")))
    }
    @Test fun collapsingNeverTouchesOtherRepeats() { assertEquals(t("1 1 1"), ComerLatex.collapseRepeatedEquals(t("1 1 1"))); assertEquals(t("+ +"), ComerLatex.collapseRepeatedEquals(t("+ +"))) }
}
