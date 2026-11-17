package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.model.ExpressionObject
import com.bsoft.bhandbook.domain.model.rotated
import com.bsoft.bhandbook.domain.model.ProcessingStatus
import com.bsoft.bhandbook.domain.solver.SolutionStep
import org.junit.Assert.*
import org.junit.Test

class ExpressionLayoutTest {
    private val ink = WorldRect(100f, 200f, 400f, 260f)
    private val steps = listOf(SolutionStep(1, "25 + 15 × 2", "Multiply", "25 + 30"), SolutionStep(2, "25 + 30", "Add", "55"))
    private fun expr(result: String? = "40", inline: Boolean = false, steps: List<SolutionStep> = emptyList(), visible: Boolean = true) =
        ExpressionObject("e", result = result, resultInline = inline, steps = steps, solutionVisible = visible, status = ProcessingStatus.EVALUATED)

    @Test fun inlineResult_sitsRightOfTheInk_onItsMidline() {
        val l = ExpressionLayout.of(ink, expr(inline = true))!!
        assertTrue(l.result!!.origin.x > ink.right)
        assertEquals(ink.centerY, l.result.origin.y + l.fontSize / 2f, l.fontSize * 0.35f)
    }
    @Test fun belowResult_isLeftAlignedUnderTheInk() {
        val l = ExpressionLayout.of(ink, expr(inline = false))!!
        assertEquals(ink.left, l.result!!.origin.x, 0.01f); assertTrue(l.result.origin.y > ink.bottom)
    }
    @Test fun fontSize_scalesWithInk_andIsClamped() {
        val small = ExpressionLayout.of(WorldRect(0f, 0f, 10f, 4f), expr())!!.fontSize
        val big = ExpressionLayout.of(WorldRect(0f, 0f, 10f, 4000f), expr())!!.fontSize
        assertEquals(ExpressionLayout.MIN_FONT, small, 0.01f); assertEquals(ExpressionLayout.MAX_FONT, big, 0.01f)
    }
    @Test fun steps_replaceTheAnswer_oneLinePerStep_inOrder() {
        val l = ExpressionLayout.of(ink, expr(steps = steps))!!
        assertEquals(2, l.stepLines.size); assertNull("the answer is the last step, so it is not shown twice", l.result)
        assertTrue(l.stepLines[0].origin.y > ink.bottom); assertTrue(l.stepLines[1].origin.y > l.stepLines[0].origin.y)
        assertEquals(listOf("= 25 + 30", "= 55"), l.stepLines.map { it.text })
    }
    @Test fun collapsedSolution_hasNoStepLines() { assertTrue(ExpressionLayout.of(ink, expr(steps = steps, visible = false))!!.stepLines.isEmpty()) }
    @Test fun noResult_noSteps_meansNothingExtraToDraw() {
        val l = ExpressionLayout.of(ink, expr(result = null))!!; assertNull(l.result); assertTrue(l.stepLines.isEmpty())
    }
    @Test fun errorOrReviewStatus_getsAStatusLine() {
        val l = ExpressionLayout.of(ink, ExpressionObject("e", status = ProcessingStatus.ERROR, error = "incomplete expression"))!!
        assertEquals("incomplete expression", l.status!!.text)
        val r = ExpressionLayout.of(ink, ExpressionObject("e", recognized = "2?5", status = ProcessingStatus.NEEDS_REVIEW))!!
        assertTrue(r.status!!.text.contains("check", ignoreCase = true))
    }
    @Test fun recognizingStatus_isShownWhileTheModelRuns() {
        val l = ExpressionLayout.of(ink, ExpressionObject("e", status = ProcessingStatus.RECOGNIZING))!!
        assertEquals("Recognizing…", l.status!!.text)
    }
    @Test fun pendingStatus_showsNothingSoDrawingIsNotDisturbed() { assertNull(ExpressionLayout.of(ink, ExpressionObject("e", status = ProcessingStatus.PENDING))!!.status) }
    @Test fun hitBounds_coverInkAndAllDrawnText() {
        val l = ExpressionLayout.of(ink, expr(steps = steps))!!
        val hit = l.hitBounds
        assertTrue(hit.left <= ink.left && hit.right >= ink.right && hit.top <= ink.top)
        assertTrue(hit.bottom >= l.stepLines.last().origin.y)
    }
    @Test fun hitTest_picksTopmost_andMissesEmptySpace() {
        val a = ExpressionLayout.of(WorldRect(0f, 0f, 100f, 50f), expr(result = null))!!
        assertTrue(ExpressionLayout.contains(a, Vec2(50f, 25f))); assertFalse(ExpressionLayout.contains(a, Vec2(5000f, 5000f)))
    }
    @Test fun emptyBounds_giveNoLayout() { assertNull(ExpressionLayout.of(WorldRect.Empty, expr())) }

    @Test fun all_buildsOneLayoutPerExpressionThatHasInk() {
        val strokes = listOf(
            com.bsoft.bhandbook.domain.model.Stroke("a", listOf(com.bsoft.bhandbook.domain.model.StrokePoint(0f, 0f), com.bsoft.bhandbook.domain.model.StrokePoint(50f, 40f)), 3f, 0, "e1"),
            com.bsoft.bhandbook.domain.model.Stroke("b", listOf(com.bsoft.bhandbook.domain.model.StrokePoint(60f, 0f), com.bsoft.bhandbook.domain.model.StrokePoint(90f, 60f)), 3f, 0, "e1"))
        val exprs = mapOf("e1" to ExpressionObject("e1"), "ghost" to ExpressionObject("ghost"))
        val all = ExpressionLayout.all(strokes, exprs)
        assertEquals(setOf("e1"), all.keys)
        assertEquals(WorldRect(0f, 0f, 90f, 60f), all.getValue("e1").ink)
    }


    // ---- answers are written at the size of the user's handwriting
    private fun digitStroke(id: String, x: Float, top: Float, height: Float, expr: String = "e1") =
        com.bsoft.bhandbook.domain.model.Stroke(id, listOf(com.bsoft.bhandbook.domain.model.StrokePoint(x, top), com.bsoft.bhandbook.domain.model.StrokePoint(x + height * 0.1f, top + height)), 3f, 0, expr)
    private fun evaluated(inline: Boolean = true) = ExpressionObject("e1", result = "40", resultInline = inline, status = ProcessingStatus.EVALUATED)

    @Test fun answerFontMatchesTheHandwritingDigitHeight() {
        val strokes = (0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }
        val l = ExpressionLayout.all(strokes, mapOf("e1" to evaluated()))["e1"]!!
        assertEquals("a 60 px tall digit needs a font of about 60 / 0.72", 60f / ExpressionLayout.DIGIT_HEIGHT_RATIO, l.fontSize, 1.5f)
    }
    @Test fun answerIsNotClampedToAFixedRange_soHugeAndTinyWritingBothMatch() {
        for (h in listOf(6f, 60f, 600f, 6000f)) {
            val strokes = (0 until 3).map { digitStroke("s$it", it * h, 0f, h) }
            assertEquals("height $h", h / ExpressionLayout.DIGIT_HEIGHT_RATIO, ExpressionLayout.all(strokes, mapOf("e1" to evaluated()))["e1"]!!.fontSize, h * 0.03f)
        }
    }
    @Test fun aTallStructure_doesNotInflateTheAnswer() {
        // digits 40 px tall, but the expression is 400 px tall (an exponent tower or a stacked fraction)
        val strokes = (0 until 4).map { digitStroke("s$it", it * 30f, 300f, 40f) } + digitStroke("tower", 200f, 0f, 400f)
        val small = ExpressionLayout.all((0 until 4).map { digitStroke("s$it", it * 30f, 300f, 40f) }, mapOf("e1" to evaluated()))["e1"]!!.fontSize
        assertEquals("median glyph height, not bounding-box height", small, ExpressionLayout.all(strokes, mapOf("e1" to evaluated()))["e1"]!!.fontSize, small * 0.2f)
    }
    @Test fun inlineAnswerSitsOnTheHandwritingBaseline() {
        val strokes = (0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }                      // digits centred on y = 330
        val l = ExpressionLayout.all(strokes, mapOf("e1" to evaluated()))["e1"]!!
        assertEquals("digit centre of the answer equals digit centre of the writing", 330f, l.result!!.origin.y + ExpressionLayout.DIGIT_CENTER_FROM_TOP * l.fontSize, 3f)
    }
    @Test fun stepsAndStatusUseProportionalSizes() {
        assertTrue(ExpressionLayout.STEP_FONT_SCALE in 0.6f..1f); assertTrue(ExpressionLayout.STATUS_FONT_SCALE < ExpressionLayout.STEP_FONT_SCALE)
    }
    @Test fun layoutWithoutHandwritingMetrics_keepsTheOldBehaviour() {
        assertEquals((ink.height * 0.7f).coerceIn(ExpressionLayout.MIN_FONT, ExpressionLayout.MAX_FONT), ExpressionLayout.of(ink, expr())!!.fontSize, 0.01f)
    }


    // ---- a grocery-style column: the total goes under the bar, right-aligned, at handwriting size
    @Test fun columnTotal_isWrittenUnderTheBar_rightAligned() {
        val rows = (0 until 2).flatMap { r -> (0 until 4).map { i -> digitStroke("r${r}s$i", 100f + i * 40f, 300f + r * 100f, 60f) } }
        val bar = com.bsoft.bhandbook.domain.model.Stroke("bar", (0..10).map { com.bsoft.bhandbook.domain.model.StrokePoint(60f + 34f * it, 490f, it.toLong() + 9_000) }, 4f, 0, "e1")
        val l = ExpressionLayout.all(rows + bar, mapOf("e1" to ExpressionObject("e1", result = "24.25", resultInline = true, status = ProcessingStatus.EVALUATED)))["e1"]!!
        assertTrue(l.result!!.alignRight); assertEquals("right edge of the bar", bar.bounds.right, l.result.origin.x, 0.5f); assertTrue(l.result.origin.y > bar.bounds.bottom)
        assertEquals("handwriting-sized", 60f / ExpressionLayout.DIGIT_HEIGHT_RATIO, l.fontSize, 2f)
        assertTrue("tapping the total selects the column", l.hitBounds.right >= bar.bounds.right && l.hitBounds.bottom >= l.result.origin.y)
    }
    @Test fun anOrdinaryExpression_keepsItsInlineAnswerOnTheRight() {
        val strokes = (0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }
        assertFalse(ExpressionLayout.all(strokes, mapOf("e1" to evaluated()))["e1"]!!.result!!.alignRight)
    }


    // ---- simultaneous equations and graphs
    private fun errorExpr(id: String) = ExpressionObject(id, recognized = "x+y=5", status = ProcessingStatus.ERROR, error = "Equations with more than one variable are not supported yet.")
    private fun sys() = SystemSolution(listOf("a", "b"), "b", "x = 3, y = 2", listOf(com.bsoft.bhandbook.domain.solver.SolutionStep(1, "x + y = 5", "Subtract equation 1 from equation 2", "-2y = -4")), false)
    private fun twoEquations() = listOf(digitStroke("s1", 100f, 100f, 60f, "a"), digitStroke("s2", 140f, 100f, 60f, "a"), digitStroke("s3", 100f, 220f, 60f, "b"), digitStroke("s4", 140f, 220f, 60f, "b"))
    private val pair = mapOf("a" to errorExpr("a"), "b" to errorExpr("b"))

    @Test fun systemSteps_replaceTheAnswer_whileTheyShow() {
        val b = ExpressionLayout.all(twoEquations(), pair, mapOf("a" to sys(), "b" to sys()))["b"]!!
        assertNull(b.systemSummary); assertTrue(b.systemSteps.isNotEmpty() && b.systemSteps.first().origin.y > b.ink.bottom)
        assertTrue("tapping the steps selects the equation", b.hitBounds.bottom >= b.systemSteps.last().origin.y)
    }
    @Test fun systemAnswer_isWrittenUnderTheLowestEquation_atHandwritingSize() {
        val hidden = pair.mapValues { it.value.copy(solutionVisible = false) }
        val all = ExpressionLayout.all(twoEquations(), hidden, mapOf("a" to sys(), "b" to sys()))
        assertNull("only the anchor carries the answer", all["a"]!!.systemSummary)
        val b = all["b"]!!; val line = b.systemSummary!!
        assertEquals("x = 3, y = 2", line.text); assertTrue(line.origin.y > b.ink.bottom); assertEquals(b.ink.left, line.origin.x, 0.5f)
        assertEquals(60f / ExpressionLayout.DIGIT_HEIGHT_RATIO, b.fontSize, 2f)
        assertTrue("no steps while the answer shows", b.systemSteps.isEmpty()); assertTrue("tapping the answer selects the equation", b.hitBounds.bottom >= line.origin.y)
    }
    @Test fun systemMembers_doNotShowThe_oneVariableErrorOnTheirOwn() {
        val plain = ExpressionLayout.all(twoEquations(), pair); val grouped = ExpressionLayout.all(twoEquations(), pair, mapOf("a" to sys(), "b" to sys()))
        assertNotNull(plain["a"]!!.status); assertNull(grouped["a"]!!.status); assertNull(grouped["b"]!!.status)
    }
    @Test fun hidingSteps_alsoHidesTheSystemSteps() {
        val hidden = pair.mapValues { it.value.copy(solutionVisible = false) }
        val b = ExpressionLayout.all(twoEquations(), hidden, mapOf("a" to sys(), "b" to sys()))["b"]!!
        assertNotNull(b.systemSummary); assertTrue(b.systemSteps.isEmpty())
    }
    @Test fun aSystemError_isShownAsTheSummary() {
        val bad = SystemSolution(listOf("a", "b"), "b", "not supported", emptyList(), true)
        assertEquals("not supported", ExpressionLayout.all(twoEquations(), pair, mapOf("a" to bad, "b" to bad))["b"]!!.systemSummary!!.text)
    }
    @Test fun graphPanel_sitsBesideTheExpression_scalesWithHandwriting_andIsPartOfTheHitArea() {
        val strokes = (0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }
        val l = ExpressionLayout.all(strokes, mapOf("e1" to evaluated()), graphs = setOf("e1"))["e1"]!!; val g = l.graphRect!!
        assertTrue(g.left > l.ink.right); assertEquals(l.ink.top, g.top, 0.5f); assertTrue(g.width >= 12f * l.fontSize); assertTrue(g.height > 0.5f * g.width)
        assertTrue("the graph is hit-testable (contains), but does not stretch the expression's own area", ExpressionLayout.contains(l, Vec2(g.centerX, g.centerY)) && l.hitBounds.right < g.right)
        val big = (0 until 4).map { digitStroke("s$it", it * 500f, 3000f, 600f) }
        assertEquals("ten times the handwriting, ten times the graph", 10f, ExpressionLayout.all(big, mapOf("e1" to evaluated()), graphs = setOf("e1"))["e1"]!!.graphRect!!.width / g.width, 0.5f)
    }
    @Test fun noGraphUnlessRequested() { assertNull(ExpressionLayout.all((0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }, mapOf("e1" to evaluated()))["e1"]!!.graphRect) }
    @Test fun aSystemsGraph_isDrawnOnlyNextToItsAnchor() {
        val all = ExpressionLayout.all(twoEquations(), pair, mapOf("a" to sys(), "b" to sys()), graphs = setOf("b"))
        assertNull(all["a"]!!.graphRect); assertNotNull(all["b"]!!.graphRect)
    }

    @Test fun columnTotal_goesBelowTheUsersOwnHandWrittenAnswer() {
        val rows = (0 until 2).flatMap { r -> (0 until 4).map { i -> digitStroke("r${r}s$i", 100f + i * 40f, 300f + r * 100f, 60f) } }
        val bar = com.bsoft.bhandbook.domain.model.Stroke("bar", (0..10).map { com.bsoft.bhandbook.domain.model.StrokePoint(60f + 34f * it, 490f, it.toLong() + 9_000) }, 4f, 0, "e1")
        val mine = (0 until 3).map { digitStroke("a$it", 140f + it * 40f, 530f, 60f) }
        val l = ExpressionLayout.all(rows + bar + mine, mapOf("e1" to ExpressionObject("e1", result = "24.25", resultInline = true, status = ProcessingStatus.EVALUATED)))["e1"]!!
        assertTrue("below the user's answer (bottom ${mine.maxOf { it.bounds.bottom }})", l.result!!.origin.y > mine.maxOf { it.bounds.bottom })
    }


    // ---- answers follow the direction the user wrote in (device turned, app not)
    private fun written(text: String, turn: Double, id: String = "e1"): List<com.bsoft.bhandbook.domain.model.Stroke> {
        val ink = com.bsoft.bhandbook.core.recognition.Pen(1, size = 70f, sloppiness = 0.5f).at(500f, 800f).text(text).strokes.map { it.copy(expressionId = id) }
        val pts = ink.flatMap { it.points }
        return if (turn == 0.0) ink else ink.rotated(turn * Math.PI / 180, pts.map { it.x.toDouble() }.average(), pts.map { it.y.toDouble() }.average())
    }
    private fun layoutOf(strokes: List<com.bsoft.bhandbook.domain.model.Stroke>) = ExpressionLayout.all(strokes, mapOf("e1" to evaluated()))["e1"]!!

    @Test fun upright_writing_hasUnrotatedText() {
        val l = layoutOf(written("25+15=", 0.0)); assertEquals(0.0, l.result!!.rotation, 0.0); assertEquals(0.0, l.status?.rotation ?: 0.0, 0.0)
    }
    @Test fun writingTopToBottom_putsTheAnswerBelow_turnedTheSameWay() {
        val l = layoutOf(written("25+15=", 90.0)); val r = l.result!!
        assertEquals(90.0, r.rotation, 15.0); assertTrue("below the ink (y ${r.origin.y} vs bottom ${l.ink.bottom})", r.origin.y >= l.ink.bottom - 1f)
    }
    @Test fun writingBottomToTop_putsTheAnswerAbove() {
        val l = layoutOf(written("25+15=", -90.0)); val r = l.result!!
        assertEquals(-90.0, r.rotation, 15.0); assertTrue("above the ink (y ${r.origin.y} vs top ${l.ink.top})", r.origin.y <= l.ink.top + 1f)
    }
    @Test fun upsideDownWriting_putsTheAnswerOnTheLeft() {
        val l = layoutOf(written("25+15=", 180.0)); val r = l.result!!
        assertEquals(180.0, Math.abs(r.rotation), 15.0); assertTrue(r.origin.x <= l.ink.left + 1f)
    }
    @Test fun theAnswerIsStillHandwritingSized_whenTheInkIsTurned() {
        val upright = layoutOf(written("25+15=", 0.0)).fontSize; val turned = layoutOf(written("25+15=", 90.0)).fontSize
        assertEquals(upright, turned, upright * 0.15f)
    }
    @Test fun theHitAreaCoversTheTurnedAnswer() {
        val l = layoutOf(written("25+15=", 90.0)); val r = l.result!!
        assertTrue(l.hitBounds.bottom >= r.origin.y); assertTrue(l.hitBounds.top <= l.ink.top)
    }
    @Test fun stepsAreTurnedTooAndStayClearOfTheInk() {
        val e = evaluated().copy(steps = listOf(com.bsoft.bhandbook.domain.solver.SolutionStep(1, "25 + 15", "Add", "40")))
        val l = ExpressionLayout.all(written("25+15=", 90.0), mapOf("e1" to e))["e1"]!!
        assertTrue(l.stepLines.isNotEmpty()); assertEquals(90.0, l.stepLines.first().rotation, 15.0)
    }
    @Test fun aFraction_orAColumn_isNeverTurned() {
        val frac = com.bsoft.bhandbook.core.recognition.Pen(1, size = 70f).at(500f, 800f).frac("123", "456").strokes.map { it.copy(expressionId = "e1") }
        assertEquals(0.0, layoutOf(frac).result!!.rotation, 0.0)
    }
    @Test fun turnedWritingGetsItsGraphBesideTheWholeThing() {
        val l = ExpressionLayout.all(written("25+15=", 90.0), mapOf("e1" to evaluated()), graphs = setOf("e1"))["e1"]!!
        assertTrue(l.graphRect!!.left > l.ink.right)
    }


    // ---- a graphable equation gets a hint, not a red error
    @Test fun aGraphableEquation_showsAHintInsteadOfTheNotSupportedError() {
        val strokes = (0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }
        val ex = mapOf("e1" to errorExpr("e1"))
        val plain = ExpressionLayout.all(strokes, ex)["e1"]!!; val hinted = ExpressionLayout.all(strokes, ex, graphable = setOf("e1"))["e1"]!!
        assertFalse(plain.statusIsHint); assertTrue(plain.status!!.text.contains("more than one variable"))
        assertTrue(hinted.statusIsHint); assertTrue(hinted.status!!.text.contains("Graph", ignoreCase = true)); assertFalse(hinted.status!!.text.contains("not supported"))
    }
    @Test fun otherErrors_areNeverHidden_evenOnGraphableExpressions() {
        val strokes = (0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }
        val other = mapOf("e1" to errorExpr("e1").copy(error = "Division by zero"))
        assertEquals("Division by zero", ExpressionLayout.all(strokes, other, graphable = setOf("e1"))["e1"]!!.status!!.text)
    }


    // ---- the graph panel can be moved on its own
    private fun withGraph(offsets: Map<String, Vec2> = emptyMap(), shift: Float = 0f): ExpressionLayout {
        val strokes = (0 until 4).map { digitStroke("s$it", it * 50f + shift, 300f, 60f) }
        return ExpressionLayout.all(strokes, mapOf("e1" to evaluated()), graphs = setOf("e1"), graphOffsets = offsets)["e1"]!!
    }
    @Test fun graphOffset_movesThePanel_notTheExpression() {
        val a = withGraph(); val b = withGraph(mapOf("e1" to Vec2(400f, -250f)))
        assertEquals(a.graphRect!!.left + 400f, b.graphRect!!.left, 0.01f); assertEquals(a.graphRect.top - 250f, b.graphRect.top, 0.01f)
        assertEquals(a.ink, b.ink); assertEquals(a.hitBounds, b.hitBounds); assertEquals(a.result, b.result)
    }
    @Test fun aGraphDraggedFarAway_doesNotMakeTheExpressionHugeToTap() {
        val far = withGraph(mapOf("e1" to Vec2(5000f, 5000f))); assertTrue(far.hitBounds.right < 1500f && far.hitBounds.bottom < 1500f)
        assertTrue("but the graph itself can still be tapped", ExpressionLayout.contains(far, Vec2(far.graphRect!!.centerX, far.graphRect.centerY)))
    }
    @Test fun movingTheExpression_carriesItsGraphWithIt_keepingTheOffset() {
        val a = withGraph(mapOf("e1" to Vec2(100f, 50f))); val b = withGraph(mapOf("e1" to Vec2(100f, 50f)), shift = 700f)
        assertEquals(a.graphRect!!.left + 700f, b.graphRect!!.left, 0.5f); assertEquals(a.graphRect.top, b.graphRect.top, 0.5f)
    }
    @Test fun touchingAnywhereOnTheGraph_findsIt_andOutsideDoesNot() {
        val l = withGraph(); val g = l.graphRect!!; val all = mapOf("e1" to l)
        assertEquals("e1", ExpressionLayout.graphAt(all, Vec2(g.centerX, g.centerY))); assertEquals("e1", ExpressionLayout.graphAt(all, Vec2(g.left + 1f, g.top + 1f)))
        assertNull(ExpressionLayout.graphAt(all, Vec2(g.left - 5f, g.centerY))); assertNull(ExpressionLayout.graphAt(all, Vec2(g.centerX, g.bottom + 5f)))
    }
    @Test fun graphAt_findsTheTopmostWhenPanelsOverlap() {
        val a = withGraph(); val b = ExpressionLayout.all((0 until 4).map { digitStroke("t$it", it * 50f, 300f, 60f, "e2") }, mapOf("e2" to evaluated().copy(id = "e2")), graphs = setOf("e2"))["e2"]!!
        val p = Vec2(a.graphRect!!.centerX, a.graphRect.centerY)
        assertTrue(b.graphRect!!.left < p.x && p.x < b.graphRect.right)
        assertEquals("e2", ExpressionLayout.graphAt(linkedMapOf("e1" to a, "e2" to b), p)); assertEquals("e1", ExpressionLayout.graphAt(linkedMapOf("e2" to b, "e1" to a), p))
    }
    @Test fun withoutAGraph_nothingIsFound() { assertNull(ExpressionLayout.graphAt(mapOf("e1" to ExpressionLayout.all((0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }, mapOf("e1" to evaluated()))["e1"]!!), Vec2(10f, 10f))) }
    @Test fun anOffsetForAnotherExpression_isIgnored() { assertEquals(withGraph().graphRect, withGraph(mapOf("other" to Vec2(99f, 99f))).graphRect) }


    // ---- graphs can be enlarged and shrunk independently
    private fun sized(scales: Map<String, Float> = emptyMap(), default: Float = 1f): ExpressionLayout {
        val strokes = (0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }
        return ExpressionLayout.all(strokes, mapOf("e1" to evaluated()), graphs = setOf("e1"), graphScales = scales, defaultGraphScale = default)["e1"]!!
    }
    @Test fun graphScale_scalesThePanel_keepingItsTopLeftCorner() {
        val a = sized(); val b = sized(mapOf("e1" to 2f))
        assertEquals(2f, b.graphRect!!.width / a.graphRect!!.width, 0.001f); assertEquals(2f, b.graphRect.height / a.graphRect.height, 0.001f)
        assertEquals(a.graphRect.left, b.graphRect.left, 0.01f); assertEquals(a.graphRect.top, b.graphRect.top, 0.01f)
    }
    @Test fun theDefaultSizeFromSettings_appliesToGraphsThatWereNotResized() {
        assertEquals(1.5f, sized(default = 1.5f).graphRect!!.width / sized().graphRect!!.width, 0.001f)
        assertEquals("an explicit size wins", sized(mapOf("e1" to 0.5f), default = 3f).graphRect!!.width / sized().graphRect!!.width, 0.5f, 0.001f)
    }
    @Test fun graphScale_isClamped() {
        assertEquals(ExpressionLayout.MIN_GRAPH_SCALE, sized(mapOf("e1" to 0.0001f)).graphRect!!.width / sized().graphRect!!.width, 0.001f)
        assertEquals(ExpressionLayout.MAX_GRAPH_SCALE, sized(mapOf("e1" to 500f)).graphRect!!.width / sized().graphRect!!.width, 0.01f)
        assertEquals(1f, sized(mapOf("e1" to Float.NaN)).graphRect!!.width / sized().graphRect!!.width, 0.001f)
    }
    @Test fun theResizeGrip_isTheBottomRightCornerOfThePanel() {
        val l = sized(); val g = l.graphRect!!; val r = l.graphResizeGrip!!
        assertEquals(g.right, r.right, 0.01f); assertEquals(g.bottom, r.bottom, 0.01f); assertTrue(r.width > 0f && r.width < g.width / 3)
        assertNull(ExpressionLayout.all((0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }, mapOf("e1" to evaluated()))["e1"]!!.graphResizeGrip)
    }
    @Test fun theGripStaysUsable_whenThePanelIsShrunk() { val l = sized(mapOf("e1" to ExpressionLayout.MIN_GRAPH_SCALE)); assertTrue(l.graphResizeGrip!!.width >= 0.5f * l.fontSize) }
    @Test fun aResizedGraph_stillMovesWithItsOffset() {
        val a = ExpressionLayout.all((0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }, mapOf("e1" to evaluated()), graphs = setOf("e1"), graphOffsets = mapOf("e1" to Vec2(30f, 40f)), graphScales = mapOf("e1" to 2f))["e1"]!!
        assertEquals(sized(mapOf("e1" to 2f)).graphRect!!.left + 30f, a.graphRect!!.left, 0.01f)
    }


    // ---- the "tap Graph" hint goes away once the graph is showing, and the old error must not come back
    @Test fun theGraphHint_isGoneWhileTheGraphIsShowing_andTheErrorDoesNotReturnInItsPlace() {
        val strokes = (0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }; val ex = mapOf("e1" to errorExpr("e1"))
        assertTrue(ExpressionLayout.all(strokes, ex, graphable = setOf("e1"))["e1"]!!.statusIsHint)
        val shown = ExpressionLayout.all(strokes, ex, graphs = setOf("e1"), graphable = setOf("e1"))["e1"]!!
        assertNull("no hint and no 'not supported' error while the graph is visible", shown.status); assertNotNull(shown.graphRect)
    }
    @Test fun hidingTheGraph_bringsTheHintBack() {
        val strokes = (0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }; val ex = mapOf("e1" to errorExpr("e1"))
        assertTrue(ExpressionLayout.all(strokes, ex, graphs = emptySet(), graphable = setOf("e1"))["e1"]!!.statusIsHint)
    }
    @Test fun otherErrors_stillShowWhileTheGraphIsVisible() {
        val strokes = (0 until 4).map { digitStroke("s$it", it * 50f, 300f, 60f) }; val ex = mapOf("e1" to errorExpr("e1").copy(error = "Division by zero"))
        assertEquals("Division by zero", ExpressionLayout.all(strokes, ex, graphs = setOf("e1"), graphable = setOf("e1"))["e1"]!!.status!!.text)
    }
}
