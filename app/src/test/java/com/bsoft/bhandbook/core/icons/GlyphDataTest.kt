package com.bsoft.bhandbook.core.icons

import org.junit.Assert.*
import org.junit.Test

class GlyphDataTest {
    private val required = listOf("PENCIL", "ERASER", "SELECT", "HAND", "UNDO", "TRASH", "PLUS", "SAVE", "THEME", "SETTINGS",
        "CHEVRON", "RECALCULATE", "EDIT", "ZOOM_IN", "EYE", "EYE_OFF", "FOLDER_OPEN", "GRAPH", "SHARE")

    @Test fun everyIconTheUiNeeds_exists() {
        val names = Glyphs.all.keys
        required.forEach { assertTrue("missing glyph $it", it in names) }
    }
    @Test fun allGlyphs_haveViewportAndAtLeastOnePath() {
        for ((key, g) in Glyphs.all) {
            assertTrue("$key viewport", g.viewportWidth > 0f && g.viewportHeight > 0f)
            assertTrue("$key has no paths", g.paths.isNotEmpty())
            assertTrue("$key source name", g.source.isNotBlank())
        }
    }
    @Test fun allPathData_isWellFormedSvgPathSyntax() {
        for ((key, g) in Glyphs.all) for (p in g.paths) {
            val problem = PathSyntax.validate(p.data)
            assertNull("$key: $problem in '${p.data.take(60)}'", problem)
        }
    }
    @Test fun validator_rejectsBrokenPaths() {
        assertNotNull(PathSyntax.validate("")); assertNotNull(PathSyntax.validate("L 1 2")); assertNotNull(PathSyntax.validate("M 1 2 Q"))
        assertNotNull(PathSyntax.validate("M 1 x")); assertNotNull(PathSyntax.validate("M 1 2 3"))
        assertNull(PathSyntax.validate("M10 10h5v5z")); assertNull(PathSyntax.validate("m1.5-2.5l-.5.5A3 3 0 0 1 4 4z"))
    }
    @Test fun coordinates_stayNearTheViewport() {
        for ((key, g) in Glyphs.all) for (p in g.paths) for (v in PathSyntax.numbers(p.data)) assertTrue("$key coordinate $v", v > -200f && v < 400f)
    }
    @Test fun everyPath_paintsSomething_andStrokesHaveAWidth() {
        for ((key, g) in Glyphs.all) for (p in g.paths) {
            assertTrue("$key path neither fills nor strokes", p.fill || p.strokeWidth > 0f)
            if (p.strokeWidth > 0f) assertTrue("$key stroke width", p.strokeWidth < 20f)
        }
    }
    @Test fun strokedIcons_keepTheirRoundCapsAndJoins() {
        val stroked = Glyphs.all.values.flatMap { it.paths }.filter { it.strokeWidth > 0f }
        assertTrue("expected some stroked paths among the chosen icons", stroked.isNotEmpty())
        assertTrue(stroked.any { it.cap == StrokeCapKind.ROUND && it.join == StrokeJoinKind.ROUND })
    }
    @Test fun solidIcons_stayFilled() { assertTrue(Glyphs.PENCIL.paths.any { it.fill }); assertTrue(Glyphs.ERASER.paths.all { it.fill }) }
    @Test fun licenceAttribution_isAvailableInCode() { assertTrue(Glyphs.LICENSE.contains("MIT")); assertTrue(Glyphs.AUTHOR.contains("Spasojevic")) }
}
