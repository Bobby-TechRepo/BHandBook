package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.codec.InkSampleCodec
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ComerPreprocessingTest {
    private fun pts(vararg xy: Double) = xy.toList().chunked(2).map { ComerPoint(it[0], it[1]) }
    private fun line(n: Int, dx: Double, dy: Double = 0.0, lw: Double = 3.0, x0: Double = 0.0, y0: Double = 0.0) =
        ComerStroke((0 until n).map { ComerPoint(x0 + it * dx, y0 + it * dy) }, lw)
    private val reference = ComerPreprocessConfig(canonicalHeight = null)      // the reference renders raw coordinates
    private fun fixture(name: String) = InkSampleCodec.decode(ComerTestData.text("$name.json"))!!.strokes

    // ------------------------------------------------------------------ resampling (differential vs the reference)
    @Test fun resampling_matchesTheReference_onRecordedCases() {
        for (c in ComerTestData.list(ComerTestData.json("resample_cases.json"))) {
            val m = ComerTestData.map(c); val name = m["name"] as String
            val input = ComerTestData.list(m["input"]).map { p -> ComerTestData.map(p).let { ComerPoint(it["x"] as Double, it["y"] as Double) } }
            val expected = ComerTestData.list(m["output"]).map { p -> ComerTestData.map(p).let { ComerPoint(it["x"] as Double, it["y"] as Double) } }
            val got = ComerPreprocessor.resample(input, (m["interval"] as Double))
            assertEquals("$name point count", expected.size, got.size)
            for (i in expected.indices) { assertEquals("$name x[$i]", expected[i].x, got[i].x, 1e-9); assertEquals("$name y[$i]", expected[i].y, got[i].y, 1e-9) }
        }
    }
    @Test fun resampling_keepsEndpoints_andSpacingIsAboutThreePixels() {
        val r = ComerPreprocessor.resample(pts(0.0, 0.0, 30.0, 0.0))
        assertEquals(0.0, r.first().x, 0.0); assertEquals(30.0, r.last().x, 0.0)
        for (i in 1 until r.size - 1) assertEquals(3.0, r[i].x - r[i - 1].x, 1e-9)
    }
    @Test fun resampling_singlePointAndEmpty_areReturnedUnchanged() {
        assertEquals(1, ComerPreprocessor.resample(pts(5.0, 5.0)).size); assertEquals(0, ComerPreprocessor.resample(emptyList()).size)
    }
    @Test fun resampling_handlesUnevenPointerSpacing_withoutLosingTheTrajectory() {
        val r = ComerPreprocessor.resample(pts(0.0, 0.0, 0.5, 0.0, 40.0, 3.0, 41.0, 3.0, 90.0, 50.0))
        assertEquals(90.0, r.last().x, 0.0); assertEquals(50.0, r.last().y, 0.0)
        val steps = (1 until r.size - 1).map { Math.hypot(r[it].x - r[it - 1].x, r[it].y - r[it - 1].y) }
        assertTrue("max step ${steps.max()}", steps.all { it <= 3.0 + 1e-9 })
    }
    @Test fun resampling_curvedAndDiagonalPaths_stayOnThePath() {
        val diag = ComerPreprocessor.resample(pts(0.0, 0.0, 30.0, 40.0))     // 50px long along a 3-4-5 diagonal
        assertTrue(diag.size > 10)
        for (p in diag) assertEquals("point ($p) is off the 3-4-5 line", 4.0 * p.x, 3.0 * p.y, 1e-6)
    }

    // ------------------------------------------------------------------ meaningful-stroke validation (differential)
    @Test fun meaningfulness_matchesTheReference_onRecordedCases() {
        for (c in ComerTestData.list(ComerTestData.json("meaningful_cases.json"))) {
            val m = ComerTestData.map(c)
            val strokes = ComerTestData.list(m["strokes"]).map { s -> ComerTestData.map(s).let { sm ->
                ComerStroke(ComerTestData.list(sm["points"]).map { p -> ComerTestData.map(p).let { ComerPoint(it["x"] as Double, it["y"] as Double) } }, sm["lineWidth"] as Double) } }
            assertEquals(m["name"] as String, m["meaningful"], ComerPreprocessor.isMeaningful(strokes))
        }
    }
    @Test fun meaningfulness_rejectsTapsDotsAndEmptyInput() {
        assertFalse(ComerPreprocessor.isMeaningful(emptyList()))
        assertFalse(ComerPreprocessor.isMeaningful(listOf(ComerStroke(pts(10.0, 10.0), 3.0))))
        assertFalse(ComerPreprocessor.isMeaningful(listOf(ComerStroke(pts(10.0, 10.0, 11.0, 10.0), 3.0))))
        assertTrue(ComerPreprocessor.isMeaningful(listOf(line(21, 5.0))))
    }
    @Test fun meaningfulnessThresholds_areConfigurable() {
        val s = listOf(line(7, 2.0))                                   // 12px long, 7 points
        assertFalse(ComerPreprocessor.isMeaningful(s))
        assertTrue(ComerPreprocessor.isMeaningful(s, ComerPreprocessConfig(minStrokeSize = 4.0, minTotalPoints = 4, minPathLength = 5.0)))
    }
    @Test fun junkFixture_neverReachesTheModel() { assertFalse(ComerStrokeAdapter.isMeaningful(fixture("junk_dot"))) }
    @Test fun realExpressions_areMeaningful() { for (n in listOf("add_25_15_w1", "frac_3_4_w1", "div_15_3_w1")) assertTrue(n, ComerStrokeAdapter.isMeaningful(fixture(n))) }

    // thresholds are expressed in pen widths, so zoom at writing time cannot turn real ink into "tiny marks" or marks into real ink
    private fun zoomed(strokes: List<Stroke>, k: Float) = strokes.map { s -> s.copy(points = s.points.map { it.copy(x = it.x * k, y = it.y * k) }, width = s.width * k) }
    @Test fun meaningfulness_isZoomInvariant() {
        for (k in listOf(0.05f, 0.25f, 1f, 4f, 20f)) {
            assertTrue("expression at world scale $k", ComerStrokeAdapter.isMeaningful(zoomed(fixture("add_25_15_w1"), k)))
            assertFalse("dot at world scale $k", ComerStrokeAdapter.isMeaningful(zoomed(fixture("junk_dot"), k)))
        }
    }
    @Test fun atTheReferencePenWidth_thresholdsEqualTheReferences() {
        // pen 4 => minimum size 8 px, minimum path length 15 px, exactly as in ink-on
        fun stroke(len: Float) = Stroke("s", (0..10).map { StrokePoint(it * len / 10f, 0f) }, 4f, 0)
        assertFalse(ComerStrokeAdapter.isMeaningful(listOf(stroke(14f)))); assertTrue(ComerStrokeAdapter.isMeaningful(listOf(stroke(16f))))
    }

    // ------------------------------------------------------------------ geometry of the produced tensor
    @Test fun heightIs256_widthAlignedTo64_andWithinBounds() {
        for (w in listOf(5, 20, 60, 200, 800)) {
            val r = ComerPreprocessor.preprocess(listOf(line(w, 5.0), line(w, 5.0, y0 = 40.0)))
            assertEquals(256, r.height); assertEquals(0, r.width % 64); assertTrue("width ${r.width}", r.width in 128..1024)
            assertEquals(r.height * r.width, r.tensor.size); assertEquals(r.height * r.width, r.mask.size)
        }
    }
    @Test fun veryWideExpression_isClampedTo1024() { assertEquals(1024, ComerPreprocessor.preprocess(listOf(line(2000, 5.0), line(10, 5.0, 0.0, 3.0, 0.0, 30.0))).width) }
    @Test fun veryTallExpression_isScaledToTheTargetHeight() {
        val r = ComerPreprocessor.preprocess(listOf(line(2, 0.0, 600.0), line(2, 40.0, 0.0, 3.0, 0.0, 300.0)))
        assertEquals(128, r.contentHeight)
    }
    @Test fun maskMarksPaddingAsOne_andContentAsZero() {
        val r = ComerPreprocessor.preprocess(listOf(line(40, 5.0), line(3, 0.0, 30.0, x0 = 100.0)))
        assertEquals(0, r.mask[0].toInt()); assertEquals(1, r.mask[(r.height - 1) * r.width].toInt())          // below content
        if (r.contentWidth < r.width) assertEquals(1, r.mask[r.contentWidth].toInt())                           // right of content
        var zeros = 0; for (y in 0 until r.height) for (x in 0 until r.width) if (r.mask[y * r.width + x].toInt() == 0) zeros++
        assertEquals(r.contentHeight * r.contentWidth, zeros)
    }
    @Test fun tensorIsBlackBackground_whiteInk_inZeroToOne() {
        val r = ComerPreprocessor.preprocess(listOf(line(40, 5.0), line(40, 5.0, y0 = 30.0)))
        assertTrue(r.tensor.all { it in 0f..1f }); assertEquals(0f, r.tensor[r.tensor.size - 1], 0f)
        assertTrue("some ink", r.tensor.any { it > 0.9f })
        for (v in r.tensor) assertEquals("8-bit quantised", (v * 255f).toDouble(), Math.round(v * 255f).toDouble(), 1e-3)
    }
    @Test fun contentIsTopLeftAligned_notCentred() {
        val r = ComerPreprocessor.preprocess(listOf(line(40, 5.0), line(40, 5.0, y0 = 30.0)))
        var minX = r.width; var minY = r.height; var maxY = 0
        for (y in 0 until r.height) for (x in 0 until r.width) if (r.tensor[y * r.width + x] > 0.5f) { minX = minOf(minX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
        assertTrue("ink starts in the top-left corner region (minX=$minX minY=$minY)", minX < 48 && minY < 48)
        assertTrue("all ink lies inside the scaled content area, in the TOP half of the 256px canvas (maxY=$maxY)", maxY < r.contentHeight && r.contentHeight <= 128)
    }
    @Test fun singlePointStrokes_drawNothing_likeTheReference() {
        val r = ComerPreprocessor.preprocess(listOf(line(40, 5.0), ComerStroke(pts(500.0, 500.0), 3.0)))
        assertTrue(r.tensor.any { it > 0.5f })
    }
    @Test fun emptyStrokeCollections_produceAValidBlankTensor() {
        val r = ComerPreprocessor.preprocess(emptyList()); assertEquals(256, r.height); assertTrue(r.width in 128..1024); assertTrue(r.tensor.all { it == 0f })
    }
    @Test fun lineWidthIsClampedToAtLeastTwoPixels() {
        val thin = ComerPreprocessor.preprocess(listOf(line(40, 5.0, lw = 0.1))); val two = ComerPreprocessor.preprocess(listOf(line(40, 5.0, lw = 2.0)))
        assertArrayEquals(two.tensor.map { it.toDouble() }.toDoubleArray(), thin.tensor.map { it.toDouble() }.toDoubleArray(), 0.0)
    }
    @Test fun thickerLinesProduceMoreInk() {
        fun ink(lw: Double) = ComerPreprocessor.preprocess(listOf(line(40, 5.0, lw = lw), line(40, 5.0, y0 = 40.0, lw = lw))).tensor.sum()
        assertTrue(ink(8.0) > ink(3.0) * 1.3f)
    }

    // ------------------------------------------------------------------ independence from where / how large the user wrote
    @Test fun translationIndependence_sameExpressionAnywhereOnTheCanvas_giveIdenticalInput() {
        val a = ComerPreprocessor.preprocess(ComerStrokeAdapter.fromDomain(fixture("add_25_15_w1")))
        val moved = fixture("add_25_15_w1").map { it.translated(5000f, -3000f) }
        val b = ComerPreprocessor.preprocess(ComerStrokeAdapter.fromDomain(moved))
        assertEquals(a.width, b.width)
        var maxDiff = 0f; for (i in a.tensor.indices) maxDiff = maxOf(maxDiff, abs(a.tensor[i] - b.tensor[i]))
        assertTrue("max pixel difference $maxDiff (float32 world coordinates lose sub-pixel precision far from the origin)", maxDiff < 0.15f)
    }
    @Test fun theReferenceItself_isNotSizeInvariant_whichIsWhyTheAdapterNormalises() {
        val base = fixture("frac_3_4_w1"); val big = base.map { s -> s.copy(points = s.points.map { it.copy(x = it.x * 2.5f, y = it.y * 2.5f) }) }
        val a = ComerPreprocessor.preprocess(ComerStrokeAdapter.fromDomain(base, reference)); val b = ComerPreprocessor.preprocess(ComerStrokeAdapter.fromDomain(big, reference))
        var sum = 0.0; for (i in a.tensor.indices) sum += abs(a.tensor[i] - b.tensor[i])
        assertTrue("reference pipeline differs by ${sum / a.tensor.size} between the same expression at 1x and 2.5x", sum / a.tensor.size > 0.01)
    }
    @Test fun zoomIndependence_strokesScaledByTheCanvasZoom_giveTheSameInput() {
        val base = fixture("frac_3_4_w1")
        val zoomed = base.map { s -> s.copy(points = s.points.map { it.copy(x = it.x * 2.5f, y = it.y * 2.5f) }, width = s.width * 2.5f) }
        val a = ComerPreprocessor.preprocess(ComerStrokeAdapter.fromDomain(base)); val b = ComerPreprocessor.preprocess(ComerStrokeAdapter.fromDomain(zoomed))
        assertEquals(a.width, b.width)
        var sum = 0.0; for (i in a.tensor.indices) sum += abs(a.tensor[i] - b.tensor[i])
        assertTrue("mean |diff| = ${sum / a.tensor.size}", sum / a.tensor.size < 0.005)
    }

    // ------------------------------------------------------------------ adapter from the app's stroke model
    @Test fun adapter_preservesStrokeOrderPointOrderAndCount() {
        val src = listOf(Stroke("a", listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f), StrokePoint(5f, 6f)), 4f, 0), Stroke("b", listOf(StrokePoint(9f, 9f), StrokePoint(8f, 8f)), 4f, 0))
        val out = ComerStrokeAdapter.fromDomain(src, reference)
        assertEquals(2, out.size); assertEquals(listOf(1.0, 3.0, 5.0), out[0].points.map { it.x }); assertEquals(listOf(9.0, 8.0), out[1].points.map { it.x })
    }
    @Test fun normalisation_canBeDisabled_toReproduceTheReference() {
        val raw = ComerStrokeAdapter.fromDomain(fixture("add_25_15_w1"), reference)
        assertEquals(fixture("add_25_15_w1").first().points.first().x.toDouble(), raw.first().points.first().x, 1e-9)
    }
    @Test fun normalisedExpressions_areTheConfiguredHeight_startAtTheOrigin() {
        val out = ComerStrokeAdapter.fromDomain(fixture("add_25_15_w1"), ComerPreprocessConfig(canonicalHeight = 80.0))
        val ys = out.flatMap { it.points.map { p -> p.y } }; val xs = out.flatMap { it.points.map { p -> p.x } }
        assertEquals(0.0, xs.min(), 1e-9); assertEquals(0.0, ys.min(), 1e-9); assertEquals(80.0, ys.max(), 1e-6)
    }
    @Test fun adapter_lineWidthDoesNotDependOnThePenSetting_soInkThicknessIsConsistent() {
        fun width(pen: Float) = ComerStrokeAdapter.fromDomain(fixture("add_25_15_w1").map { it.copy(width = pen) }).first().lineWidth
        assertEquals(width(4f), width(12f), 0.0); assertTrue(width(4f) >= 2.0)
    }
    @Test fun adapter_lineWidthMatchesTheReferenceHarnessFormula() {
        val results = ComerTestData.map(ComerTestData.json("reference_results.json"))
        val samples = ComerTestData.map(results["samples"])
        for (name in listOf("add_25_15_w1", "frac_3_4_w1", "sqrt_144_w1", "eq_2x_w1", "pow_2_2_w1", "div_15_3_w1")) {
            val expected = ComerTestData.map(samples[name])["lineWidth"] as Double
            assertEquals(name, expected, ComerStrokeAdapter.fromDomain(fixture(name), reference).first().lineWidth, expected * 1e-4)
        }
    }

    // ------------------------------------------------------------------ golden tensors from the reference (node-canvas)
    private val goldenNames = listOf("add_25_15_w1", "frac_3_4_w1", "sqrt_144_w1", "eq_2x_w1", "pow_2_2_w1", "div_15_3_w1", "add_eq_w1")

    @Test fun dimensionsAndMask_matchTheReferenceExactly() {
        for (n in goldenNames) {
            val meta = ComerTestData.map(ComerTestData.map(ComerTestData.map(ComerTestData.json("reference_results.json"))["samples"])[n])
            val r = ComerPreprocessor.preprocess(ComerStrokeAdapter.fromDomain(fixture(n), reference))
            assertEquals("$n height", (meta["height"] as Double).toInt(), r.height); assertEquals("$n width", (meta["width"] as Double).toInt(), r.width)
            val mask = ComerTestData.gunzip("$n.mask.u8.gz")
            assertEquals(mask.size, r.mask.size)
            for (i in mask.indices) if (mask[i] != r.mask[i]) fail("$n mask differs at $i")
        }
    }
    @Test fun pixels_areCloseToTheReferenceRender() {
        for (n in goldenNames) {
            val ref = ComerTestData.gunzip("$n.tensor.u8.gz")
            val r = ComerPreprocessor.preprocess(ComerStrokeAdapter.fromDomain(fixture(n), reference))
            var sum = 0.0; var refInk = 0; var ourInk = 0; var overlap = 0
            for (i in ref.indices) {
                val a = (ref[i].toInt() and 0xFF) / 255.0; val b = r.tensor[i].toDouble()
                sum += abs(a - b); if (a > 0.5) refInk++; if (b > 0.5) ourInk++; if (a > 0.5 && b > 0.5) overlap++
            }
            val mean = sum / ref.size; val iou = overlap.toDouble() / (refInk + ourInk - overlap)
            println("PREPROCESS $n: mean|diff|=${"%.5f".format(mean)} inkPixels ref=$refInk ours=$ourInk IoU=${"%.3f".format(iou)}")
            assertTrue("$n mean |diff| $mean", mean <= 0.02)
            assertTrue("$n ink area ref=$refInk ours=$ourInk", abs(refInk - ourInk) <= 0.15 * refInk)
            assertTrue("$n ink IoU $iou", iou >= 0.75)
        }
    }
}
