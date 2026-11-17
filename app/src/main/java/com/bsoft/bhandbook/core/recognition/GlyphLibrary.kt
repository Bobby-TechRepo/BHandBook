package com.bsoft.bhandbook.core.recognition

import kotlin.math.cos
import kotlin.math.sin

private typealias Path = List<Pair<Float, Float>>

internal class Template(val label: String, val cloud: Cloud)

/**
 * Prototype shapes (y grows downward, height 1, widths are the glyph's natural aspect ratio). Several variants per
 * symbol cover common writing styles; the matcher ignores stroke order, count and direction.
 * Flat bars (− and fraction bar), dots, '=', '÷' and the radical are NOT here: they are recognised geometrically.
 */
internal object GlyphLibrary {
    private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, from: Float, to: Float, n: Int = 14): Path =
        (0..n).map { i ->
            val a = Math.toRadians((from + (to - from) * i / n).toDouble())
            (cx + rx * cos(a)).toFloat() to (cy - ry * sin(a)).toFloat()
        }

    private fun defs(): List<Pair<String, List<Path>>> {
        val l = mutableListOf<Pair<String, List<Path>>>()
        fun add(label: String, vararg strokes: Path) { l += label to strokes.toList() }
        // 0
        add("0", arc(.28f, .5f, .27f, .5f, 90f, 450f, 24))
        add("0", arc(.22f, .5f, .21f, .5f, 90f, 450f, 24))
        // 1
        add("1", listOf(.25f to 0f, .25f to 1f))
        add("1", listOf(.05f to .25f, .25f to 0f, .25f to 1f))
        add("1", listOf(.05f to .25f, .25f to 0f, .25f to 1f), listOf(.0f to 1f, .5f to 1f))
        // 2
        add("2", arc(.28f, .28f, .27f, .27f, 160f, -40f, 12) + listOf(.02f to 1f, .55f to 1f))
        add("2", arc(.28f, .28f, .27f, .27f, 150f, -20f, 12) + listOf(.02f to 1f) + listOf(.55f to 1f))
        // 3
        add("3", arc(.27f, .25f, .25f, .25f, 150f, -90f, 12) + arc(.27f, .75f, .29f, .25f, 90f, -150f, 12))
        add("3", listOf(.05f to 0f, .5f to 0f, .22f to .42f) + arc(.27f, .7f, .28f, .3f, 90f, -160f, 12))
        // 4
        add("4", listOf(.4f to 0f, .0f to .7f, .58f to .7f), listOf(.42f to .25f, .42f to 1f))
        add("4", listOf(.12f to 0f, .12f to .65f, .58f to .65f), listOf(.4f to 0f, .4f to 1f))
        // 5
        add("5", listOf(.5f to 0f, .08f to 0f, .05f to .46f) + arc(.28f, .7f, .27f, .3f, 110f, -150f, 12))
        add("5", listOf(.5f to 0f, .08f to 0f), listOf(.08f to 0f, .06f to .45f) + arc(.28f, .7f, .27f, .3f, 110f, -150f, 12))
        // 6
        add("6", arc(.34f, .5f, .3f, .5f, 65f, 195f, 10) + arc(.27f, .72f, .26f, .28f, 190f, -170f, 16))
        // 7
        add("7", listOf(.0f to 0f, .55f to 0f, .2f to 1f))
        add("7", listOf(.0f to 0f, .55f to 0f, .2f to 1f), listOf(.12f to .5f, .42f to .5f))
        // 8
        add("8", arc(.27f, .25f, .23f, .25f, 0f, 360f, 14) + arc(.27f, .75f, .28f, .25f, 0f, 360f, 16))
        add("8", arc(.27f, .26f, .22f, .25f, 270f, -90f, 14) + arc(.27f, .75f, .27f, .26f, 90f, 450f, 18))
        // 9
        add("9", arc(.28f, .28f, .26f, .28f, 0f, 360f, 16), listOf(.54f to .28f, .5f to .7f, .26f to 1f))
        add("9", arc(.28f, .28f, .26f, .28f, 0f, 360f, 16) + listOf(.54f to .28f, .5f to .65f, .25f to 1f))
        add("9", arc(.28f, .28f, .26f, .28f, 0f, 360f, 16), listOf(.54f to .28f, .54f to 1f))
        // operators and brackets
        add("+", listOf(.0f to .5f, .6f to .5f), listOf(.3f to .2f, .3f to .8f))
        add("+", listOf(.0f to .5f, .6f to .5f), listOf(.3f to .14f, .3f to .86f))
        add("X", listOf(.0f to .0f, .55f to .6f), listOf(.55f to .0f, .0f to .6f))
        add("X", listOf(.0f to .0f, .5f to .7f), listOf(.5f to .0f, .0f to .7f))
        add("(", arc(.55f, .5f, .5f, .55f, 128f, 232f, 12))
        add("(", arc(.7f, .5f, .6f, .55f, 140f, 220f, 12))
        add(")", arc(-.3f, .5f, .5f, .55f, 52f, -52f, 12))
        add(")", arc(-.45f, .5f, .6f, .55f, 40f, -40f, 12))
        add("/", listOf(.0f to 1f, .4f to 0f))
        add("/", listOf(.0f to 1f, .3f to 0f))
        return l
    }

    val templates: List<Template> by lazy {
        defs().map { (label, strokes) -> Template(label, PointCloud.build(strokes)) }
    }
}
