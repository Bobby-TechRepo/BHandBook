package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint
import org.junit.Assert.*
import org.junit.Test

class ColumnArithmeticTest {
    private var clockMs = 0L
    private fun rightAligned(text: String, baseline: Float, rightX: Float, size: Float = 70f, seed: Long = 1): List<Stroke> {
        val s = Pen(seed, size = size, sloppiness = 0.4f).at(0f, baseline).text(text).strokes
        val shift = rightX - s.maxOf { it.bounds.right }
        return s.map { it.translated(shift, 0f) }
    }
    private fun bar(left: Float, right: Float, y: Float, id: String = "bar") =
        Stroke(id, (0..12).map { StrokePoint(left + (right - left) * it / 12f, y + (it % 2) * 1.5f, 900_000L + it) }, 4f, 0)

    private fun column(vararg rows: String, size: Float = 70f, rowGap: Float = 1.4f, barPad: Float = 10f): List<Stroke> {
        val strokes = ArrayList<Stroke>(); var baseline = 300f
        for ((i, t) in rows.withIndex()) { strokes += rightAligned(t, baseline, 500f, size, seed = 10L + i); baseline += size * rowGap }
        val barY = baseline - size * rowGap + size * 0.35f
        return strokes + bar(500f - 4.7f * size, 500f + barPad, barY)
    }

    @Test fun twoRowsOverABar_areAColumn() {
        val c = ColumnArithmetic.detect(column("12.50", "3.75"))!!
        assertEquals(2, c.rows.size); assertEquals("bar", c.bar.id)
    }
    @Test fun threeRowsWithOperators_areAColumn_inReadingOrder() {
        val c = ColumnArithmetic.detect(column("12.50", "3.75", "+8"))!!
        assertEquals(3, c.rows.size)
        assertTrue("rows are ordered top to bottom", c.rows.map { r -> r.map { it.bounds.centerY }.average() }.zipWithNext().all { (a, b) -> a < b })
    }
    @Test fun everyStrokeOfARow_staysInThatRow_decimalPointsAndMultiStrokeDigitsIncluded() {
        val ink = column("40.25", "7.50"); val c = ColumnArithmetic.detect(ink)!!
        assertEquals(ink.size - 1, c.rows.sumOf { it.size }); assertEquals(c.rows[0].size, Pen(1).text("40.25").strokes.size)
    }
    @Test fun resultIsAnchoredAtTheBarsRightEnd_belowTheBar() {
        val ink = column("12.50", "3.75"); val c = ColumnArithmetic.detect(ink)!!
        assertEquals(c.bar.bounds.right, c.anchorX, 0.01f); assertEquals("the layout adds the gap below this line", c.bar.bounds.bottom, c.anchorTop, 0.01f)
    }
    @Test fun worksAtAnySize_soZoomDoesNotMatter() {
        for (size in listOf(20f, 70f, 300f)) assertNotNull("size $size", ColumnArithmetic.detect(column("12.50", "3.75", size = size, barPad = size * 0.15f)))
    }
    @Test fun noBar_isNotAColumn() { assertNull(ColumnArithmetic.detect(column("12.50", "3.75").dropLast(1))) }
    @Test fun aShortDash_isNotABar() {
        val rows = column("12.50", "3.75").dropLast(1); val y = rows.maxOf { it.bounds.bottom } + 30f
        assertNull(ColumnArithmetic.detect(rows + bar(470f, 520f, y)))
    }
    @Test fun oneRowOverABar_isAnUnfinishedFraction_notAColumn() { assertNull(ColumnArithmetic.detect(column("12.50"))) }
    @Test fun oneRowUnderABarWithOnlyOneRowAbove_makesItAFraction_notAColumn() {
        val frac = Pen(1).frac("12", "34").strokes; assertNull(ColumnArithmetic.detect(frac))
    }
    @Test fun aWrongHandWrittenAnswer_isStillJustTheUsersAnswer_notADenominator() {
        val ink = column("12", "3"); val barBottom = ink.last().bounds.bottom
        val c = ColumnArithmetic.detect(ink + rightAligned("4", barBottom + 90f, 500f, seed = 99))!!
        assertEquals("both rows are summed", 2, c.rows.size); assertTrue("the 4 is kept aside as the user's answer", c.answer.isNotEmpty())
    }
    @Test fun aBarThatIsMuchNarrowerThanTheRows_isNotAColumn() {
        val rows = column("123456.78", "98765.43").dropLast(1); val y = rows.maxOf { it.bounds.bottom } + 30f
        assertNull(ColumnArithmetic.detect(rows + bar(500f - 100f, 500f, y)))
    }
    @Test fun aBarAboveTheNumbers_isNotAColumn() {
        val rows = column("12.50", "3.75").dropLast(1); val top = rows.minOf { it.bounds.top }
        assertNull(ColumnArithmetic.detect(rows + bar(170f, 510f, top - 40f)))
    }
    @Test fun aSingleLineExpression_isNeverAColumn() { assertNull(ColumnArithmetic.detect(Pen.write("25+15=", 1))); assertNull(ColumnArithmetic.detect(emptyList())) }
    @Test fun aRealStackedFraction_isNotAColumn() { assertNull(ColumnArithmetic.detect(Pen(1).frac("12", "34").strokes)) }


    // ---------------------------------------------------------------- the user writes the answer themselves (to check their work)
    @Test fun aHandWrittenAnswerUnderTheBar_isPartOfTheColumn_notARow() {
        val ink = column("125", "47"); val barBottom = ink.last().bounds.bottom
        val answer = rightAligned("172", barBottom + 80f, 500f, seed = 77)
        val c = ColumnArithmetic.detect(ink + answer)!!
        assertEquals("the answer is not added into the sum", 2, c.rows.size); assertEquals(answer.size, c.answer.size)
        assertEquals("the app's total goes under the user's answer", answer.maxOf { it.bounds.bottom }, c.anchorTop, 0.01f)
    }
    @Test fun withoutAnAnswer_theAnswerIsEmpty_andTheTotalGoesUnderTheBar() {
        val c = ColumnArithmetic.detect(column("125", "47"))!!; assertTrue(c.answer.isEmpty()); assertEquals(c.bar.bounds.bottom, c.anchorTop, 0.01f)
    }
    @Test fun twoLinesOfInkUnderTheBar_isNotAColumn() {
        val ink = column("125", "47"); val bb = ink.last().bounds.bottom
        assertNull(ColumnArithmetic.detect(ink + rightAligned("17", bb + 80f, 500f, seed = 1) + rightAligned("2", bb + 190f, 500f, seed = 2)))
    }
    @Test fun anAnswerFarToTheSideOfTheBar_isNotPartOfIt() {
        val ink = column("125", "47"); val bb = ink.last().bounds.bottom
        assertNull(ColumnArithmetic.detect(ink + rightAligned("172", bb + 80f, 1800f, seed = 3)))
    }
    @Test fun oneRowOverABarWithOneRowUnder_isStillAFraction() { assertNull(ColumnArithmetic.detect(Pen(1).frac("12", "34").strokes)) }

    // ---------------------------------------------------------------- real handwriting: the layout of a photographed column (105 - 37 = 68)
    private fun box(id: String, x0: Float, x1: Float, y0: Float, y1: Float, t: Long) = Stroke(id, listOf(StrokePoint(x0, y0, t), StrokePoint(x1, y1, t + 5)), 8f, 0)
    private val photoRows = listOf(
        box("1", 786f, 832f, 213f, 443f, 1), box("0", 953f, 1064f, 234f, 430f, 10), box("0a", 1019f, 1105f, 262f, 416f, 20), box("0b", 1021f, 1053f, 278f, 320f, 30), box("5", 1193f, 1334f, 199f, 418f, 40),
        box("-", 425f, 550f, 702f, 738f, 100), box("3", 849f, 989f, 614f, 802f, 110), box("7", 1134f, 1223f, 570f, 803f, 120),
    )
    private val photoBar = box("bar", 331f, 1575f, 884f, 945f, 200)
    private val photoAnswer = listOf(box("6", 852f, 994f, 978f, 1272f, 300), box("8", 1147f, 1291f, 990f, 1228f, 310), box("8a", 1153f, 1177f, 1138f, 1194f, 320))

    @Test fun aPhotographedColumn_isRecognisedAsTwoRows_withTheMinusStayingInItsRow() {
        val c = ColumnArithmetic.detect(photoRows + photoBar)!!
        assertEquals(2, c.rows.size); assertEquals(setOf("1", "0", "0a", "0b", "5"), c.rows[0].map { it.id }.toSet()); assertEquals(setOf("-", "3", "7"), c.rows[1].map { it.id }.toSet())
        assertEquals(1575f, c.anchorX, 0.01f)
    }
    @Test fun aPhotographedColumn_withTheHandWrittenAnswer_isStillAColumn() {
        val c = ColumnArithmetic.detect(photoRows + photoBar + photoAnswer)!!
        assertEquals(2, c.rows.size); assertEquals(setOf("6", "8", "8a"), c.answer.map { it.id }.toSet()); assertEquals(1272f, c.anchorTop, 0.01f)
    }
}
