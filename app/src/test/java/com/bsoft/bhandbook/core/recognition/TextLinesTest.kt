package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint
import com.bsoft.bhandbook.domain.model.rotated
import org.junit.Assert.*
import org.junit.Test

/** Lines of handwriting written close together must stay separate expressions; fractions, exponents, roots and columns must not be split. */
class TextLinesTest {
    private fun pen(seed: Long = 1) = Pen(seed, size = 70f, sloppiness = 0.5f)
    private fun line(text: String, baseline: Float, seed: Long = 1) = pen(seed).at(100f, baseline).text(text).strokes

    /** Draws the strokes one by one, exactly as the editor does, and returns the number of distinct expressions and whether any merge happened. */
    private fun drawInOrder(strokes: List<Stroke>): Pair<Int, Boolean> {
        val placed = ArrayList<Stroke>(); var next = 0; var merged = false
        for (s in strokes) {
            val id = when (val a = ExpressionAssigner.assign(s, placed)) {
                ExpressionAssignment.New -> "e${next++}"
                is ExpressionAssignment.Join -> a.expressionId
                is ExpressionAssignment.Merge -> { merged = true; val keep = a.ids.first(); for (i in placed.indices) if (placed[i].expressionId in a.ids) placed[i] = placed[i].copy(expressionId = keep); keep }
            }
            placed += s.copy(expressionId = id)
        }
        return placed.mapNotNull { it.expressionId }.distinct().size to merged
    }

    // ---------------------------------------------------------------- separate lines
    @Test fun twoCloseLinesOfWriting_areTwoExpressions_drawnStrokeByStroke() {
        val (n, merged) = drawInOrder(line("x+2=57", 200f, 1) + line("x-1=33", 310f, 2)); assertEquals(2, n); assertFalse("no merge ever happened", merged)
    }
    @Test fun threeLines() { assertEquals(3, drawInOrder(line("x+2+3=6", 200f) + line("2x-4=3", 310f, 2) + line("x+2=4", 420f, 3)).first) }
    @Test fun veryTightLines_stillSeparate() { assertEquals(2, drawInOrder(line("25+15=", 200f) + line("12+8=", 200f + 70f * 1.15f, 2)).first) }
    @Test fun aSecondLineThatStartsWithAMinusSign_isStillItsOwnLine() {
        assertEquals(2, drawInOrder(line("x+2=57", 200f, 1) + line("-x+4=11", 310f, 2)).first)
    }
    @Test fun batchGrouping_agrees() { assertEquals(2, StrokeGrouper.group(line("x+2=57", 200f, 1) + line("x-1=33", 310f, 2)).size) }
    @Test fun batchGrouping_ofALoneLine_isOneGroup() { assertEquals(1, StrokeGrouper.group(line("x+2=57", 200f)).size) }

    // ---------------------------------------------------------------- structures that must stay together
    @Test fun aFraction_staysOneExpression() { assertEquals(1, drawInOrder(pen().at(100f, 300f).frac("123", "456").strokes).first); assertEquals(1, drawInOrder(pen().frac("12", "34").strokes).first) }
    @Test fun aFractionInsideAnExpression() { assertEquals(1, drawInOrder(pen().at(100f, 300f).text("3+").frac("123", "456").text("=").strokes).first) }
    @Test fun exponents_andPowerChains_stayWithTheirBase() {
        assertEquals(1, drawInOrder(pen().text("x").sup("2").text("+3").strokes).first)
        assertEquals(1, drawInOrder(pen().text("2").sup("10").text("+3").sup("2").strokes).first)
    }
    @Test fun squareRoots() { assertEquals(1, drawInOrder(pen().sqrt("144").text("+10").strokes).first) }
    @Test fun decimalPointsAndSigns_stayInTheirLine() { assertEquals(1, drawInOrder(pen().text("3.14*2.5=").strokes).first) }

    // ---------------------------------------------------------------- columns still become one expression when the bar is drawn
    private fun bar(x0: Float, x1: Float, y: Float) = Stroke("bar", (0..12).map { StrokePoint(x0 + (x1 - x0) * it / 12f, y, 900_000L + it) }, 4f, 0)
    @Test fun aColumn_isTwoRows_thenTheBarMergesThem() {
        val ink = line("105", 200f, 1) + line("-37", 310f, 2)
        assertEquals("rows first", 2, drawInOrder(ink).first)
        val (n, merged) = drawInOrder(ink + bar(60f, 480f, 345f)); assertEquals(1, n); assertTrue("the bar merged the rows", merged)
    }
    @Test fun aHandWrittenAnswerUnderTheBar_joinsTheColumn() {
        val ink = line("105", 200f, 1) + line("-37", 310f, 2) + bar(60f, 480f, 345f)
        assertEquals(1, drawInOrder(ink + line("68", 440f, 3)).first)
    }

    // ---------------------------------------------------------------- the helper itself
    @Test fun aTinyMark_nearALine_doesNotStartANewLine() {
        val e = line("25+15=", 200f).map { it.copy(expressionId = "e") }
        val speck = Stroke("dot", listOf(StrokePoint(300f, 330f, 99), StrokePoint(302f, 331f, 100)), 3f, 0)
        assertFalse(TextLines.startsNewLine(speck, e))
    }
    @Test fun aSymbolOnTheSameLine_neverStartsANewLine() {
        val e = line("25+1", 200f).map { it.copy(expressionId = "e") }; val five = line("5", 200f, 9).first().translated(300f, 0f)
        assertFalse(TextLines.startsNewLine(five, e))
    }
    @Test fun anExpressionWithTooLittleInk_neverSplits() { val e = line("2+", 200f); assertFalse(TextLines.startsNewLine(line("3", 330f).first(), e)) }


    // ---------------------------------------------------------------- sideways writing stacks symbols vertically: it must stay ONE expression
    private fun turned(strokes: List<Stroke>, degrees: Double): List<Stroke> {
        val pts = strokes.flatMap { it.points }
        return strokes.rotated(degrees * Math.PI / 180, pts.map { it.x.toDouble() }.average(), pts.map { it.y.toDouble() }.average())
    }
    @Test fun sidewaysWriting_staysOneExpression_drawnStrokeByStroke() {
        for (angle in listOf(90.0, -90.0, 180.0, 60.0, -60.0)) for (text in listOf("25+15=", "2x+5=15", "12*8", "123456", "7-4")) {
            // (a wide gap along the vertical axis can briefly start a separate expression that merges back: pre-existing proximity rule, not line splitting)
            val (n, _) = drawInOrder(turned(line(text, 300f), angle)); assertEquals("'$text' written at $angle degrees ended as $n expressions", 1, n)
        }
    }
    @Test fun sidewaysWriting_isNotSplitInBatchGroupingEither() {
        for (angle in listOf(90.0, -90.0)) assertEquals(1, StrokeGrouper.group(turned(line("25+15=", 300f), angle)).size)
    }
}
