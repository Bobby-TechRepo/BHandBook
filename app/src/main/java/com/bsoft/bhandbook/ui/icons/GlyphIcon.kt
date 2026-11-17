package com.bsoft.bhandbook.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import com.bsoft.bhandbook.core.icons.Glyph
import com.bsoft.bhandbook.core.icons.StrokeCapKind
import com.bsoft.bhandbook.core.icons.StrokeJoinKind

/** Converts a platform-independent [Glyph] into a Compose vector. Paint is black; [Icon]'s tint recolours it. */
fun Glyph.toImageVector(name: String = source, defaultSize: Dp = 24.dp): ImageVector {
    val b = ImageVector.Builder(name, defaultSize, defaultSize, viewportWidth, viewportHeight)
    for (p in paths) {
        b.addPath(
            pathData = PathParser().parsePathString(p.data).toNodes(),
            pathFillType = if (p.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            fill = if (p.fill) SolidColor(Color.Black) else null,
            stroke = if (p.strokeWidth > 0f) SolidColor(Color.Black) else null,
            strokeLineWidth = p.strokeWidth,
            strokeLineCap = when (p.cap) { StrokeCapKind.BUTT -> StrokeCap.Butt; StrokeCapKind.ROUND -> StrokeCap.Round; StrokeCapKind.SQUARE -> StrokeCap.Square },
            strokeLineJoin = when (p.join) { StrokeJoinKind.MITER -> StrokeJoin.Miter; StrokeJoinKind.ROUND -> StrokeJoin.Round; StrokeJoinKind.BEVEL -> StrokeJoin.Bevel },
        )
    }
    return b.build()
}

/**
 * Glyphs icon (MIT, Goran Spasojevic). Decorative by default: the enclosing button carries the accessibility label.
 * [mirrored] flips horizontally (redo = mirrored undo); [rotation] is in degrees.
 */
@Composable
fun GlyphIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    mirrored: Boolean = false,
    rotation: Float = 0f,
    contentDescription: String? = null,
) {
    val vector = remember(glyph) { glyph.toImageVector() }
    Icon(
        vector, contentDescription,
        modifier.size(size).graphicsLayer { scaleX = if (mirrored) -1f else 1f; rotationZ = rotation },
        tint,
    )
}
