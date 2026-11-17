package com.bsoft.bhandbook

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bsoft.bhandbook.core.icons.Glyphs
import com.bsoft.bhandbook.ui.icons.GlyphIcon
import com.bsoft.bhandbook.ui.icons.toImageVector
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlyphIconTest {
    @get:Rule val rule = createComposeRule()

    @Test fun everyGlyph_convertsToAVectorWithAllItsPaths() {
        Glyphs.all.forEach { (key, g) ->
            val v = g.toImageVector(key)      // PathParser rejects malformed data here
            assertEquals(key, g.viewportWidth, v.viewportWidth, 0f)
            assertEquals("$key path count", g.paths.size, v.root.size)
        }
    }

    @Test fun everyGlyph_rendersOnScreen() {
        rule.setContent { Column { Glyphs.all.forEach { (k, g) -> GlyphIcon(g, Modifier.testTag("glyph_$k"), size = 12.dp, contentDescription = k) } } }
        Glyphs.all.keys.forEach { rule.onNodeWithTag("glyph_$it").assertIsDisplayed() }
    }
}
