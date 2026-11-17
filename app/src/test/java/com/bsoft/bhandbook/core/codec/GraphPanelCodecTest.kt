package com.bsoft.bhandbook.core.codec

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.domain.editor.ExpressionLayout
import com.bsoft.bhandbook.domain.model.GraphPanelState
import org.junit.Assert.*
import org.junit.Test

class GraphPanelCodecTest {
    private val full = GraphPanelState(
        visible = setOf("a", "b"), offsets = mapOf("a" to Vec2(120.5f, -40f), "c" to Vec2(0f, 9f)), scales = mapOf("a" to 2.5f, "b" to 0.5f),
    )

    @Test fun roundTrip_keepsEverything() { assertEquals(full, GraphPanelCodec.decode(GraphPanelCodec.encode(full))) }
    @Test fun emptyState_roundTrips() { assertEquals(GraphPanelState(), GraphPanelCodec.decode(GraphPanelCodec.encode(GraphPanelState()))) }
    @Test fun theFormatIsVersioned_andReadable() { val s = GraphPanelCodec.encode(full); assertTrue(s, s.contains("\"v\":1")) }
    @Test fun idsWithAwkwardCharacters_roundTrip() {
        val ids = listOf("he said \"hi\"", "back\\slash", "line\nbreak", "tab\there", "emoji 😀", "ünï", "{}[],:", "")
        val st = GraphPanelState(visible = ids.toSet(), offsets = ids.associateWith { Vec2(1f, 2f) }, scales = ids.associateWith { 1.5f })
        assertEquals(st, GraphPanelCodec.decode(GraphPanelCodec.encode(st)))
    }

    // ---------------------------------------------------------------- hostile input: never throws, never returns garbage
    @Test fun garbage_givesAnEmptyState() {
        val garbage = listOf("", " ", "null", "[]", "42", "\"x\"", "{", "}", "{\"v\":", "{\"v\":1", "not json", "\u0000\u0001", "{\"visible\":[\"a\"]}" /* no version */, "{\"v\":1,\"visible\":[1]}")
        for (g in garbage) try { assertEquals("'$g'", GraphPanelState(), GraphPanelCodec.decode(g)) } catch (t: Throwable) { fail("'$g' threw $t") }
    }
    @Test fun aFutureVersion_isIgnoredRatherThanMisread() { assertEquals(GraphPanelState(), GraphPanelCodec.decode("{\"v\":2,\"visible\":[\"a\"],\"offsets\":{\"a\":[1,2]},\"scales\":{\"a\":3}}")) }
    @Test fun truncatedJson_givesAnEmptyState() { val s = GraphPanelCodec.encode(full); for (n in listOf(1, s.length / 2, s.length - 1)) assertEquals(GraphPanelState(), GraphPanelCodec.decode(s.take(n))) }
    @Test fun badValues_areDroppedOrClamped_notTrusted() {
        val d = GraphPanelCodec.decode("{\"v\":1,\"visible\":[\"a\",5,null],\"offsets\":{\"a\":[1,2],\"b\":[1],\"c\":[\"x\",2],\"d\":[1e999,2],\"e\":[1,2,3]},\"scales\":{\"a\":999999,\"b\":-3,\"c\":\"big\",\"d\":0}}")
        assertEquals(setOf("a"), d.visible)
        assertEquals(mapOf("a" to Vec2(1f, 2f), "e" to Vec2(1f, 2f)), d.offsets)
        assertEquals(ExpressionLayout.MAX_GRAPH_SCALE, d.scales.getValue("a"), 0f); assertEquals(ExpressionLayout.MIN_GRAPH_SCALE, d.scales.getValue("b"), 0f); assertEquals(ExpressionLayout.MIN_GRAPH_SCALE, d.scales.getValue("d"), 0f)
        assertFalse("c" in d.scales)
    }
    @Test fun unknownFields_areIgnored_forForwardCompatibility() { assertEquals(full, GraphPanelCodec.decode(GraphPanelCodec.encode(full).dropLast(1) + ",\"future\":{\"x\":[1,2,3]}}")) }
    @Test fun nonFiniteNumbersNeverReachTheCanvas() {
        val s = GraphPanelCodec.decode(GraphPanelCodec.encode(GraphPanelState(offsets = mapOf("a" to Vec2(Float.NaN, 1f), "b" to Vec2(Float.POSITIVE_INFINITY, 1f), "c" to Vec2(3f, 4f)), scales = mapOf("a" to Float.NaN, "b" to Float.POSITIVE_INFINITY, "c" to 2f))))
        assertEquals(setOf("c"), s.offsets.keys); assertEquals(2f, s.scales["c"]!!, 0f); assertTrue(s.scales.values.all { it.isFinite() })
    }
    @Test fun aLargeStateIsFast() {
        val ids = (0 until 2000).map { "id-$it" }; val st = GraphPanelState(ids.toSet(), ids.associateWith { Vec2(it.length.toFloat(), 1f) }, ids.associateWith { 1.25f })
        val t0 = System.nanoTime(); val back = GraphPanelCodec.decode(GraphPanelCodec.encode(st)); val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(st, back); assertTrue("$ms ms", ms < 500)
    }
}
