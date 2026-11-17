package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.domain.model.Stroke
import org.junit.Assert.*
import org.junit.Test

class StrokeGrouperTest {
    private fun groups(strokes: List<Stroke>) = StrokeGrouper.group(strokes)

    @Test fun oneExpression_isOneGroup() {
        val s = Pen.write("25+15=")
        assertEquals(1, groups(s).size)
        assertEquals(s.size, groups(s).single().size)
    }
    @Test fun twoLines_areTwoGroups() {
        val p = Pen(1).text("25+15=").newLine(2.2f).text("7x3")
        val g = groups(p.strokes); assertEquals(2, g.size)
    }
    @Test fun farApartHorizontally_areTwoGroups() {
        val p = Pen(1).text("12+3").gap(4.5f).text("8x2")
        assertEquals(2, groups(p.strokes).size)
    }
    @Test fun fraction_staysTogether() {
        val p = Pen(2).text("5+").frac("1", "2")
        assertEquals(1, groups(p.strokes).size)
    }
    @Test fun superscript_staysTogether() { assertEquals(1, groups(Pen(3).text("x").sup("2").text("+1").strokes).size) }
    @Test fun squareRoot_staysTogether() { assertEquals(1, groups(Pen(3).sqrt("16").text("+2").strokes).size) }
    @Test fun grouping_isDeterministic_andIndependentOfStrokeOrder() {
        val s = Pen(4).text("12+3").gap(5f).text("9-4").strokes
        val a = groups(s).map { g -> g.map { it.id }.toSet() }.toSet()
        val b = groups(s.reversed()).map { g -> g.map { it.id }.toSet() }.toSet()
        assertEquals(a, b)
    }
    @Test fun emptyInput() { assertTrue(groups(emptyList()).isEmpty()) }

    // incremental assignment of a newly drawn stroke
    @Test fun newStroke_joinsNearbyExpression() {
        val existing = Pen(5).text("25+1").strokes.map { it.withExpression("e1") }
        val next = Pen(6).at(existing.maxOf { it.bounds.right } + 15f, 300f).text("7").strokes.single()
        assertEquals(ExpressionAssignment.Join("e1"), ExpressionAssigner.assign(next, existing))
    }
    @Test fun newStroke_farAway_startsNewExpression() {
        val existing = Pen(5).text("25+1").strokes.map { it.withExpression("e1") }
        val next = Pen(6).at(1500f, 900f).text("7").strokes.single()
        assertEquals(ExpressionAssignment.New, ExpressionAssigner.assign(next, existing))
    }
    @Test fun newStroke_bridgingTwoExpressions_mergesThem() {
        val a = Pen(7).at(100f, 300f).text("12").strokes.map { it.withExpression("a") }
        val b = Pen(8).at(a.maxOf { it.bounds.right } + 170f, 300f).text("34").strokes.map { it.withExpression("b") }
        val bridge = Pen(9).at(a.maxOf { it.bounds.right } + 55f, 300f).text("+").strokes.first()
        val r = ExpressionAssigner.assign(bridge, a + b)
        assertTrue(r is ExpressionAssignment.Merge)
        assertEquals(setOf("a", "b"), (r as ExpressionAssignment.Merge).ids.toSet())
    }
}
