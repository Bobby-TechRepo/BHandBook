package com.bsoft.bhandbook.core.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasTransformTest {
    private val eps = 1e-3f

    private fun assertVec(e: Vec2, a: Vec2) {
        assertEquals(e.x, a.x, eps); assertEquals(e.y, a.y, eps)
    }

    @Test fun identity_mapsScreenToSameWorld() {
        assertVec(Vec2(10f, 20f), CanvasTransform().screenToWorld(Vec2(10f, 20f)))
    }

    @Test fun screenToWorld_andBack_roundTrips() {
        val t = CanvasTransform(scale = 2.5f, offset = Vec2(-120f, 340f))
        val p = Vec2(77f, -13f)
        assertVec(p, t.worldToScreen(t.screenToWorld(p)))
        assertVec(p, t.screenToWorld(t.worldToScreen(p)))
    }

    @Test fun worldToScreen_appliesScaleThenOffset() {
        val t = CanvasTransform(scale = 2f, offset = Vec2(100f, 50f))
        assertVec(Vec2(120f, 90f), t.worldToScreen(Vec2(10f, 20f)))
    }

    @Test fun panBy_movesContentWithFinger() {
        val t = CanvasTransform().panBy(30f, -10f)
        assertVec(Vec2(30f, -10f), t.worldToScreen(Vec2(0f, 0f)))
    }

    @Test fun panBy_isUnbounded() {
        var t = CanvasTransform()
        repeat(1000) { t = t.panBy(1_000_000f, -1_000_000f) }
        assertVec(Vec2(1e9f, -1e9f), t.worldToScreen(Vec2.Zero))
    }

    @Test fun zoomAround_keepsFocalWorldPointFixedOnScreen() {
        val t = CanvasTransform(scale = 1.3f, offset = Vec2(40f, -25f))
        val focal = Vec2(300f, 500f)
        val worldBefore = t.screenToWorld(focal)
        val z = t.zoomAround(focal, 2.2f)
        assertVec(focal, z.worldToScreen(worldBefore))
        assertEquals(1.3f * 2.2f, z.scale, eps)
    }

    @Test fun zoomAround_clampsToMaxScale() {
        val z = CanvasTransform().zoomAround(Vec2(10f, 10f), 1000f)
        assertEquals(CanvasTransform.MAX_SCALE, z.scale, eps)
    }

    @Test fun zoomAround_clampsToMinScale_andStillPreservesFocal() {
        val t = CanvasTransform()
        val focal = Vec2(200f, 200f)
        val w = t.screenToWorld(focal)
        val z = t.zoomAround(focal, 0.00001f)
        assertEquals(CanvasTransform.MIN_SCALE, z.scale, 1e-6f)
        assertVec(focal, z.worldToScreen(w))
    }

    @Test fun applyGesture_combinesPanAndZoomAtCentroid() {
        val t = CanvasTransform()
        val centroid = Vec2(100f, 100f)
        val w = t.screenToWorld(centroid)
        val g = t.applyGesture(centroid = centroid, pan = Vec2(15f, 5f), zoom = 2f)
        // world point under the old centroid follows the finger by the pan amount
        assertVec(Vec2(115f, 105f), g.worldToScreen(w))
        assertEquals(2f, g.scale, eps)
    }

    @Test fun visibleWorldRect_matchesViewportCorners() {
        val t = CanvasTransform(scale = 2f, offset = Vec2(-100f, -200f))
        val r = t.visibleWorldRect(width = 400f, height = 800f)
        assertEquals(50f, r.left, eps); assertEquals(100f, r.top, eps)
        assertEquals(250f, r.right, eps); assertEquals(500f, r.bottom, eps)
    }

    @Test fun doubleTapZoom_zoomsInThenResets() {
        val t = CanvasTransform()
        val z = t.doubleTapZoom(Vec2(50f, 50f))
        assertTrue(z.scale > t.scale)
        val back = z.doubleTapZoom(Vec2(50f, 50f))
        assertEquals(1f, back.scale, eps)
    }

    @Test fun gridSpacing_staysWithinReadableScreenRange_acrossZooms() {
        for (s in listOf(0.05f, 0.3f, 1f, 3.7f, 12f)) {
            val spacingWorld = CanvasTransform(scale = s).gridSpacingWorld(baseSpacing = 40f)
            val onScreen = spacingWorld * s
            assertTrue("scale $s -> $onScreen px", onScreen >= 20f && onScreen < 80f)
        }
    }

    @Test fun fitTo_centresTheRect_andKeepsItInsideTheViewport() {
        val rect = WorldRect(1000f, 2000f, 1400f, 2100f)
        val t = CanvasTransform().fitTo(rect, width = 800f, height = 1200f)
        val tl = t.worldToScreen(Vec2(rect.left, rect.top)); val br = t.worldToScreen(Vec2(rect.right, rect.bottom))
        assertTrue(tl.x >= 0f && tl.y >= 0f && br.x <= 800f && br.y <= 1200f)
        assertEquals(400f, (tl.x + br.x) / 2f, 0.5f); assertEquals(600f, (tl.y + br.y) / 2f, 0.5f)
    }
    @Test fun fitTo_usesTheLimitingDimension() {
        val wide = CanvasTransform().fitTo(WorldRect(0f, 0f, 1000f, 10f), 500f, 1000f)
        assertTrue(wide.scale < 0.5f)             // width is the constraint
    }
    @Test fun fitTo_clampsScale_forTinyOrHugeRects() {
        assertEquals(CanvasTransform.MAX_SCALE, CanvasTransform().fitTo(WorldRect(0f, 0f, 0.01f, 0.01f), 800f, 800f).scale, 1e-3f)
        assertEquals(CanvasTransform.MIN_SCALE, CanvasTransform().fitTo(WorldRect(0f, 0f, 1e9f, 1e9f), 800f, 800f).scale, 1e-6f)
    }
}
