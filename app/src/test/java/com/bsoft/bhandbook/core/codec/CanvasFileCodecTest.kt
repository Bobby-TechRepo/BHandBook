package com.bsoft.bhandbook.core.codec

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.solver.SolutionStep
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class CanvasFileCodecTest {
    private fun stroke(id: String, expr: String?, vararg pts: Triple<Float, Float, Long>, w: Float = 3f, color: Long = 0xFF112233L) =
        Stroke(id, pts.map { (x, y, t) -> StrokePoint(x, y, t, 0.75f) }, w, color, expr)
    private val steps = listOf(SolutionStep(1, "2x + 5 = 15", "Subtract 5 from both sides", "2x = 10"), SolutionStep(2, "2x = 10", "Divide by 2", "x = 5"))
    private val expr = ExpressionObject("e1", recognized = "2x+5=15", confidence = 0.93f, result = "x = 5", steps = steps, solutionVisible = false, status = ProcessingStatus.EVALUATED,
        error = null, modifiedAt = 1731300000000L, strokeSignature = -123456, displayText = "2x + 5 = 15", resultInline = false)
    private val state = CanvasState(
        strokes = listOf(stroke("s1", "e1", Triple(1.5f, 2.25f, 10L), Triple(3f, 4f, 18L), Triple(-5.5f, 1e-3f, 26L)), stroke("s2", "e1", Triple(10f, 10f, 30L), w = 4.5f), stroke("s3", null, Triple(0f, 0f, 0L), Triple(1f, 1f, 5L))),
        expressions = mapOf("e1" to expr, "e2" to ExpressionObject("e2", recognized = "25+15=", result = "40", status = ProcessingStatus.EVALUATED, resultInline = true, error = "oops \"quoted\" ₦ √ ×\n")),
    ).let { it.copy(strokes = it.strokes + stroke("s4", "e2", Triple(7f, 8f, 40L), Triple(9f, 9f, 45L))) }
    private val graphs = GraphPanelState(setOf("e1"), mapOf("e1" to Vec2(10f, -20f)), mapOf("e1" to 2f))
    private val file = CanvasFile("Maths ₦ \"homework\"", CanvasBackground.DARK, 1.5f, -300.5f, 42f, state, graphs)

    private fun ok(text: String) = (CanvasFileCodec.decode(text) as CanvasFileResult.Ok)
    private fun failed(text: String): String {
        val r = CanvasFileCodec.decode(text)
        assertTrue("expected a refusal but it decoded: ${text.take(60)}", r is CanvasFileResult.Failed)
        return (r as CanvasFileResult.Failed).reason
    }

    // ---------------------------------------------------------------- round trip
    @Test fun roundTrip_keepsEverything() { val r = ok(CanvasFileCodec.encode(file)); assertEquals(0, r.skipped); assertEquals(file, r.file) }
    @Test fun roundTrip_ofAnEmptyCanvas() { val e = CanvasFile("Empty", CanvasBackground.WHITE, 1f, 0f, 0f, CanvasState(), GraphPanelState()); assertEquals(e, ok(CanvasFileCodec.encode(e)).file) }
    @Test fun encodingIsStable() { val once = CanvasFileCodec.encode(file); assertEquals(once, CanvasFileCodec.encode(ok(once).file)) }
    @Test fun theFileCarriesAFormatMarkerAndAVersion() { val s = CanvasFileCodec.encode(file); assertTrue(s.contains("\"format\":\"bhandbook-canvas\"") && s.contains("\"v\":1")) }
    @Test fun manyRandomPoints_roundTripExactly() {
        val rnd = Random(1); val pts = (0 until 500).map { StrokePoint((rnd.nextFloat() - 0.5f) * 1e5f, (rnd.nextFloat() - 0.5f) * 1e5f, rnd.nextInt(1_000_000).toLong(), rnd.nextFloat()) }
        val f = file.copy(state = CanvasState(listOf(Stroke("a", pts, 2.5f, -1L, null))))
        assertEquals(pts, ok(CanvasFileCodec.encode(f)).file.state.strokes.single().points)
    }
    @Test fun everyStatusSurvives() { for (s in ProcessingStatus.values()) { val f = file.copy(state = CanvasState(listOf(stroke("a", "x", Triple(0f, 0f, 0L))), mapOf("x" to ExpressionObject("x", status = s)))); assertEquals(s, ok(CanvasFileCodec.encode(f)).file.state.expressions.getValue("x").status) } }
    @Test fun everyBackgroundSurvives() { for (b in CanvasBackground.values()) assertEquals(b, ok(CanvasFileCodec.encode(file.copy(background = b))).file.background) }

    // ---------------------------------------------------------------- not a canvas file / damaged / too new / too big
    @Test fun nonsenseIsRefused_withAReason() {
        for (g in listOf("", " ", "null", "[]", "42", "\"x\"", "{", "}", "not json at all", "\u0000\u0001", "{\"format\":\"something-else\",\"v\":1}", "{\"v\":1}", "{\"format\":\"bhandbook-canvas\"}"))
            assertTrue("'$g': ${failed(g)}", failed(g).isNotBlank())
    }
    @Test fun aNewerFileVersion_isRefusedWithAClearMessage() { assertTrue(failed("{\"format\":\"bhandbook-canvas\",\"v\":2,\"strokes\":[],\"expressions\":[]}").contains("newer", ignoreCase = true)) }
    @Test fun aTruncatedFile_isRefused_neverCrashes() { val s = CanvasFileCodec.encode(file); for (n in listOf(1, 10, s.length / 3, s.length / 2, s.length - 2)) assertTrue("cut at $n", failed(s.take(n)).isNotBlank()) }
    @Test fun aFileThatIsTooLarge_isRefusedBeforeBeingParsed() { assertTrue(failed("x".repeat(CanvasFileCodec.MAX_CHARS + 1)).contains("large", ignoreCase = true)) }
    @Test fun tooManyStrokes_areRefused() {
        val many = (0..CanvasFileCodec.MAX_STROKES).joinToString(",") { "{\"id\":\"s$it\",\"w\":1,\"c\":0,\"p\":[0,0,0,1]}" }
        assertTrue(failed("{\"format\":\"bhandbook-canvas\",\"v\":1,\"strokes\":[$many],\"expressions\":[]}").contains("many", ignoreCase = true))
    }

    // ---------------------------------------------------------------- damaged parts are repaired or dropped, never trusted
    private fun withStrokesAndExprs(strokes: String, exprs: String = "[]", extra: String = "") = "{\"format\":\"bhandbook-canvas\",\"v\":1,\"name\":\"n\"$extra,\"strokes\":$strokes,\"expressions\":$exprs}"
    @Test fun nonFinitePointsAreDropped_andAStrokeLeftWithNoPointsIsDropped() {
        val r = ok(withStrokesAndExprs("[{\"id\":\"a\",\"w\":2,\"c\":0,\"p\":[1,1,0,1, 1e999,5,0,1, 3,3,1,1]},{\"id\":\"b\",\"w\":2,\"c\":0,\"p\":[1e999,1,0,1]}]"))
        assertEquals(listOf("a"), r.file.state.strokes.map { it.id }); assertEquals(2, r.file.state.strokes.single().points.size); assertTrue(r.skipped >= 1)
    }
    @Test fun aStrokeWithNoPointsOrAnId_isDropped() { val r = ok(withStrokesAndExprs("[{\"id\":\"a\",\"w\":2,\"c\":0,\"p\":[]},{\"w\":2,\"c\":0,\"p\":[1,1,0,1]},{\"id\":\"\",\"p\":[1,1,0,1]}]")); assertTrue(r.file.state.strokes.isEmpty()); assertEquals(3, r.skipped) }
    @Test fun trailingNumbersThatDoNotFormAPoint_areIgnored() { assertEquals(1, ok(withStrokesAndExprs("[{\"id\":\"a\",\"w\":2,\"c\":0,\"p\":[1,1,0,1,9,9]}]")).file.state.strokes.single().points.size) }
    @Test fun widthAndPressureAreClamped() {
        val s = ok(withStrokesAndExprs("[{\"id\":\"a\",\"w\":99999,\"c\":0,\"p\":[1,1,0,5,2,2,1,-3]},{\"id\":\"b\",\"w\":-4,\"c\":0,\"p\":[1,1,0,1]},{\"id\":\"c\",\"c\":0,\"p\":[1,1,0,1]}]")).file.state.strokes
        assertTrue(s[0].width <= CanvasFileCodec.MAX_WIDTH); assertTrue(s[0].points.all { it.pressure in 0f..1f }); assertTrue(s[1].width >= CanvasFileCodec.MIN_WIDTH); assertTrue(s[2].width > 0f)
    }
    @Test fun aStrokeNamingAnUnknownExpression_becomesPlainInk() {
        val r = ok(withStrokesAndExprs("[{\"id\":\"a\",\"e\":\"ghost\",\"w\":2,\"c\":0,\"p\":[1,1,0,1]}]")); assertNull(r.file.state.strokes.single().expressionId)
    }
    @Test fun anExpressionWithoutInk_isDropped() { val r = ok(withStrokesAndExprs("[]", "[{\"id\":\"e\",\"recognized\":\"1+1=\"}]")); assertTrue(r.file.state.expressions.isEmpty()); assertEquals(1, r.skipped) }
    @Test fun duplicateIds_keepTheFirst() {
        val r = ok(withStrokesAndExprs("[{\"id\":\"a\",\"e\":\"e\",\"w\":2,\"c\":0,\"p\":[1,1,0,1]},{\"id\":\"a\",\"w\":2,\"c\":0,\"p\":[9,9,0,1]}]", "[{\"id\":\"e\",\"recognized\":\"1\"},{\"id\":\"e\",\"recognized\":\"2\"}]"))
        assertEquals(1, r.file.state.strokes.size); assertEquals(1f, r.file.state.strokes[0].points[0].x, 0f); assertEquals("1", r.file.state.expressions.getValue("e").recognized)
    }
    @Test fun unknownStatuses_andBadNumbers_fallBackToSafeValues() {
        val r = ok(withStrokesAndExprs("[{\"id\":\"a\",\"e\":\"e\",\"w\":2,\"c\":0,\"p\":[1,1,0,1]}]", "[{\"id\":\"e\",\"status\":\"WHATEVER\",\"confidence\":7,\"modifiedAt\":\"x\",\"steps\":[{\"n\":1},\"junk\",{\"n\":2,\"expr\":\"a\",\"why\":\"b\",\"res\":\"c\"}]}]"))
        val e = r.file.state.expressions.getValue("e"); assertEquals(ProcessingStatus.IDLE, e.status); assertEquals(1f, e.confidence, 0f); assertEquals(0L, e.modifiedAt); assertEquals(listOf(SolutionStep(2, "a", "b", "c")), e.steps)
    }
    @Test fun aMissingOrAbsurdViewIsRepaired() {
        val r = ok(withStrokesAndExprs("[]", "[]", ",\"view\":{\"scale\":1e30,\"offsetX\":\"x\",\"offsetY\":1e999}")).file; assertTrue(r.scale in 0.0001f..100f); assertEquals(0f, r.offsetX, 0f); assertEquals(0f, r.offsetY, 0f)
        assertEquals(1f, ok(withStrokesAndExprs("[]")).file.scale, 0f)
    }
    @Test fun theName_isTrimmed_limited_andNeverBlank() {
        assertEquals("Imported canvas", ok("{\"format\":\"bhandbook-canvas\",\"v\":1,\"name\":\"   \",\"strokes\":[],\"expressions\":[]}").file.name)
        assertEquals("Imported canvas", ok("{\"format\":\"bhandbook-canvas\",\"v\":1,\"strokes\":[],\"expressions\":[]}").file.name)
        assertEquals(CanvasFileCodec.MAX_NAME, ok("{\"format\":\"bhandbook-canvas\",\"v\":1,\"name\":\"${"x".repeat(500)}\",\"strokes\":[],\"expressions\":[]}").file.name.length)
    }
    @Test fun anUnknownBackground_isTheDefaultGrid() { assertEquals(CanvasBackground.GRID, ok(withStrokesAndExprs("[]", "[]", ",\"background\":\"NEON\"")).file.background) }
    @Test fun unknownFieldsAreIgnored_forForwardCompatibility() { assertEquals(file, ok(CanvasFileCodec.encode(file).dropLast(1) + ",\"futureThing\":{\"a\":[1,2,3]}}").file) }
    @Test fun graphPanelsForExpressionsThatDoNotExist_areDropped() {
        val f = file.copy(graphs = GraphPanelState(setOf("e1", "ghost"), mapOf("ghost" to Vec2(1f, 1f)), mapOf("ghost" to 2f)))
        val back = ok(CanvasFileCodec.encode(f)).file.graphs; assertEquals(setOf("e1"), back.visible); assertTrue(back.offsets.isEmpty() && back.scales.isEmpty())
    }

    // ---------------------------------------------------------------- importing makes a NEW canvas: every id is replaced
    @Test fun freshIds_replaceEveryStrokeExpressionAndGraphKey_consistently() {
        var n = 0; val fresh = CanvasFileCodec.withFreshIds(file) { "N${n++}" }
        val oldIds = file.state.strokes.map { it.id }.toSet() + file.state.expressions.keys
        assertTrue((fresh.state.strokes.map { it.id } + fresh.state.expressions.keys).none { it in oldIds })
        for (s in fresh.state.strokes) assertTrue(s.expressionId == null || s.expressionId in fresh.state.expressions)
        for ((k, e) in fresh.state.expressions) assertEquals(k, e.id)
        assertEquals(fresh.state.expressions.keys.toSet(), fresh.state.strokes.mapNotNull { it.expressionId }.toSet())
        assertTrue(fresh.graphs.visible.all { it in fresh.state.expressions } && fresh.graphs.visible.size == 1)
        assertEquals(file.graphs.offsets.values.toList(), fresh.graphs.offsets.values.toList())
    }
    @Test fun freshIds_changeNothingElse() {
        val fresh = CanvasFileCodec.withFreshIds(file) { java.util.UUID.randomUUID().toString() }
        assertEquals(file.state.strokes.map { it.points }, fresh.state.strokes.map { it.points }); assertEquals(file.state.expressions.values.map { it.copy(id = "") }.toSet(), fresh.state.expressions.values.map { it.copy(id = "") }.toSet())
        assertEquals(file.name, fresh.name); assertEquals(file.background, fresh.background)
    }
    @Test fun freshIds_areUnique() { val fresh = CanvasFileCodec.withFreshIds(file) { java.util.UUID.randomUUID().toString() }; assertEquals(fresh.state.strokes.size, fresh.state.strokes.map { it.id }.toSet().size) }

    // ---------------------------------------------------------------- encoding awkward data, and speed
    @Test fun encodingNeverThrows_andLeavesOutNonFinitePoints() {
        val f = file.copy(state = CanvasState(listOf(Stroke("a", listOf(StrokePoint(Float.NaN, 1f), StrokePoint(2f, 2f), StrokePoint(Float.POSITIVE_INFINITY, 3f)), Float.NaN, 0L, null))), scale = Float.NaN, offsetX = Float.POSITIVE_INFINITY)
        val back = ok(CanvasFileCodec.encode(f)).file; assertEquals(1, back.state.strokes.single().points.size); assertTrue(back.scale.isFinite() && back.offsetX.isFinite())
    }
    @Test fun aBigCanvasIsFast() {
        val rnd = Random(2); val strokes = (0 until 5000).map { i -> Stroke("s$i", (0 until 100).map { StrokePoint(rnd.nextFloat() * 1000, rnd.nextFloat() * 1000, it * 8L, 0.5f) }, 3f, 0xFF000000, null) }
        val big = file.copy(state = CanvasState(strokes)); val t0 = System.nanoTime(); val text = CanvasFileCodec.encode(big); val back = ok(text); val ms = (System.nanoTime() - t0) / 1_000_000
        println("PERF canvas file 5000x100 points: ${ms} ms, ${text.length / 1_000_000} MB"); assertEquals(5000, back.file.state.strokes.size); assertTrue("$ms ms", ms < 6000)
    }
}
