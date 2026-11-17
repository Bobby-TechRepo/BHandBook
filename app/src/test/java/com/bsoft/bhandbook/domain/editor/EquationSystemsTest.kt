package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.math.MathStatement
import com.bsoft.bhandbook.core.math.ParseResult
import org.junit.Assert.*
import org.junit.Test

class EquationSystemsTest {
    private val parser = DefaultMathParser()
    private fun block(id: String, text: String, left: Float, top: Float, w: Float = 300f, h: Float = 60f) =
        EquationBlock(id, WorldRect(left, top, left + w, top + h), (parser.parse(text) as ParseResult.Success).statement as MathStatement.Equation)

    @Test fun twoStackedEquations_inTwoUnknowns_formASystem_anchoredAtTheLowerOne() {
        val g = EquationSystems.detect(listOf(block("a", "x+y=5", 100f, 100f), block("b", "x-y=1", 100f, 190f)))
        assertEquals(1, g.size); assertEquals(listOf("a", "b"), g[0].ids); assertEquals("b", g[0].anchorId)
    }
    @Test fun order_isTopToBottom_whateverTheInputOrder() {
        val g = EquationSystems.detect(listOf(block("c", "x+y+z=6", 100f, 300f), block("a", "x+y=5", 100f, 100f), block("b", "x-y=1", 100f, 200f)))
        assertEquals(listOf("a", "b", "c"), g.single().ids); assertEquals("c", g.single().anchorId)
    }
    @Test fun equationsFarApartVertically_areNotASystem() {
        assertTrue(EquationSystems.detect(listOf(block("a", "x+y=5", 100f, 100f), block("b", "x-y=1", 100f, 900f))).isEmpty())
    }
    @Test fun equationsSideBySide_areNotASystem() {
        assertTrue(EquationSystems.detect(listOf(block("a", "x+y=5", 100f, 100f), block("b", "x-y=1", 1200f, 100f))).isEmpty())
    }
    @Test fun leftAlignedEquations_ofDifferentWidth_areGrouped() {
        assertEquals(1, EquationSystems.detect(listOf(block("a", "x+y=5", 100f, 100f, w = 200f), block("b", "2x-3y=1", 110f, 190f, w = 420f))).size)
    }
    @Test fun twoIndependentSingleUnknownEquations_areSolvedOnTheirOwn_notGrouped() {
        assertTrue(EquationSystems.detect(listOf(block("a", "2x+5=15", 100f, 100f), block("b", "3y=9", 100f, 190f))).isEmpty())
    }
    @Test fun anEquationThatFixesOneUnknown_joinsTheOneThatNeedsIt() {
        assertEquals(listOf("a", "b"), EquationSystems.detect(listOf(block("a", "x=2", 100f, 100f), block("b", "x+y=5", 100f, 190f))).single().ids)
    }
    @Test fun twoSeparateSystems_areTwoGroups() {
        val g = EquationSystems.detect(listOf(block("a", "x+y=5", 100f, 100f), block("b", "x-y=1", 100f, 190f), block("c", "a+b=3", 100f, 1000f), block("d", "a-b=1", 100f, 1090f)))
        assertEquals(2, g.size); assertEquals(setOf(listOf("a", "b"), listOf("c", "d")), g.map { it.ids }.toSet())
    }
    @Test fun spacingScalesWithTheSizeOfTheHandwriting() {
        // same layout, 10x bigger writing: still one system; 10x bigger gap relative to the text: not one
        assertEquals(1, EquationSystems.detect(listOf(block("a", "x+y=5", 0f, 0f, 3000f, 600f), block("b", "x-y=1", 0f, 900f, 3000f, 600f))).size)
        assertTrue(EquationSystems.detect(listOf(block("a", "x+y=5", 0f, 0f, 300f, 60f), block("b", "x-y=1", 0f, 900f, 300f, 60f))).isEmpty())
    }
    @Test fun aSingleEquation_isNeverASystem() { assertTrue(EquationSystems.detect(listOf(block("a", "x+y=5", 100f, 100f))).isEmpty()) }
    @Test fun aChainOfThree_isOneGroup_evenIfTheFirstAndLastAreFarApart() {
        assertEquals(3, EquationSystems.detect(listOf(block("a", "x+y+z=6", 100f, 100f), block("b", "x-y=0", 100f, 190f), block("c", "y-z=-1", 100f, 280f))).single().ids.size)
    }
    @Test fun solve_usesTheMembersInReadingOrder() {
        val blocks = listOf(block("b", "x-y=1", 100f, 190f), block("a", "x+y=5", 100f, 100f))
        val g = EquationSystems.detect(blocks).single()
        val r = EquationSystems.solve(g, blocks, com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine())
        assertTrue(r is com.bsoft.bhandbook.domain.solver.SolveOutcome.Solved); assertEquals("x = 3, y = 2", (r as com.bsoft.bhandbook.domain.solver.SolveOutcome.Solved).finalExpression)
    }
}
