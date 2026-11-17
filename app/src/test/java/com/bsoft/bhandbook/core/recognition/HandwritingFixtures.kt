package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import java.util.Random

/**
 * Synthetic "handwriting" generator for tests. Its glyph shapes are DELIBERATELY written independently of the
 * recognizer's template library (different control points, stroke splits and styles), and every glyph instance gets
 * random rotation, shear, aspect/scale change, point jitter and sampling density, so a passing test means the
 * recogniser generalises across plausible variation rather than matching its own templates.
 *
 * Coordinates: y grows downward. A glyph occupies [0,w]x[0,1] in its own box (1 = full digit height).
 */
private typealias Poly = List<Pair<Float, Float>>

class Pen(seed: Long = 1L, private val size: Float = 60f, private val sloppiness: Float = 1f) {
    private val rnd = Random(seed)
    private val out = mutableListOf<Stroke>()
    private var nextId = 1
    private val instance = INSTANCES.incrementAndGet()          // ids are unique across Pen instances, like the app's UUIDs
    private var clock = 0L
    var cursorX = 100f
    var baseline = 300f
    val strokes: List<Stroke> get() = out.toList()

    private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, from: Float, to: Float, n: Int = 14): Poly =
        (0..n).map { i ->
            val a = Math.toRadians((from + (to - from) * i / n).toDouble())
            (cx + rx * cos(a)).toFloat() to (cy - ry * sin(a)).toFloat()
        }

    // ---- the writer's glyphs (width, strokes) -- shapes intentionally unlike the recogniser's templates ----
    private fun glyph(c: Char): Pair<Float, List<Poly>>? = when (c) {
        '0' -> 0.55f to listOf(arc(.28f, .5f, .26f, .5f, 100f, 460f, 20))
        '1' -> 0.30f to listOf(listOf(.05f to .22f, .22f to 0f, .22f to 1f))
        '2' -> 0.55f to listOf(arc(.28f, .27f, .25f, .26f, 170f, -50f, 10) + listOf(.0f to 1f, .55f to .98f))
        '3' -> 0.55f to listOf(arc(.26f, .25f, .24f, .25f, 160f, -80f, 9) + arc(.27f, .74f, .27f, .26f, 80f, -160f, 10))
        '4' -> 0.58f to listOf(listOf(.40f to 0f, .0f to .70f, .58f to .70f), listOf(.42f to .30f, .42f to 1f))
        '5' -> 0.55f to listOf(listOf(.5f to 0f, .08f to 0f), listOf(.08f to 0f, .04f to .46f) + arc(.28f, .70f, .27f, .30f, 105f, -145f, 12))
        '6' -> 0.55f to listOf(arc(.34f, .5f, .30f, .5f, 62f, 195f, 10) + arc(.27f, .72f, .26f, .28f, 190f, -170f, 16))
        '7' -> 0.55f to listOf(listOf(.0f to .02f, .55f to 0f, .20f to 1f))
        '8' -> 0.55f to listOf(arc(.27f, .26f, .22f, .25f, 270f, -90f, 14) + arc(.27f, .75f, .27f, .26f, 90f, 450f, 18))
        '9' -> 0.55f to listOf(arc(.28f, .28f, .26f, .28f, 0f, 360f, 16), listOf(.54f to .28f, .50f to .7f, .24f to 1f))
        '+' -> 0.60f to listOf(listOf(.0f to .52f, .60f to .50f), listOf(.30f to .14f, .31f to .86f))
        '*' -> 0.55f to listOf(listOf(.02f to .22f, .53f to .78f), listOf(.53f to .22f, .02f to .78f))          // × (cross)
        'x' -> 0.50f to listOf(listOf(.02f to .30f, .48f to .98f), listOf(.48f to .30f, .02f to .98f))          // variable x: lowercase height
        '(' -> 0.22f to listOf(arc(.55f, .5f, .50f, .55f, 128f, 232f, 12))
        ')' -> 0.22f to listOf(arc(-.30f, .5f, .50f, .55f, 52f, -52f, 12))
        '/' -> 0.35f to listOf(listOf(.0f to 1f, .35f to 0f))
        '-' -> 0.50f to listOf(listOf(.0f to .5f, .5f to .5f))
        '.' -> 0.10f to listOf(listOf(.05f to .94f, .08f to .98f, .05f to 1f))
        '=' -> 0.55f to listOf(listOf(.0f to .38f, .55f to .38f), listOf(.02f to .62f, .55f to .62f))
        else -> null
    }

    private fun emit(poly: Poly, tx: (Float, Float) -> Pair<Float, Float>, width: Float = 4f) {
        val j = 0.012f * sloppiness
        // random re-sampling density so strokes have different point counts
        val dens = 2 + rnd.nextInt(3)
        val pts = ArrayList<Pair<Float, Float>>()
        for (i in 0 until poly.size - 1) for (k in 0 until dens) {
            val f = k.toFloat() / dens
            pts += (poly[i].first + (poly[i + 1].first - poly[i].first) * f) to (poly[i].second + (poly[i + 1].second - poly[i].second) * f)
        }
        pts += poly.last()
        val sp = pts.mapIndexed { i, p ->
            val (x, y) = tx(p.first, p.second)
            StrokePoint(x + (rnd.nextGaussian() * j * size).toFloat(), y + (rnd.nextGaussian() * j * size).toFloat(), clock + i * 8L, 0.5f + rnd.nextFloat() * 0.3f)
        }
        clock += pts.size * 8L + 250
        out += Stroke("p${instance}_s${nextId++}", sp, width, 0xFF000000)
    }

    /** Draws one glyph with its box's top-left at (x, topY), scaled by [scale]; returns glyph width in px. */
    private fun draw(c: Char, x: Float, topY: Float, scale: Float): Float {
        val (w, strokes) = glyph(c) ?: error("no glyph for '$c'")
        val rot = ((rnd.nextGaussian() * 3.5) * sloppiness).coerceIn(-9.0, 9.0) * PI / 180
        val shear = (rnd.nextGaussian() * 0.06 * sloppiness).coerceIn(-0.15, 0.15).toFloat()
        val sx = 1f + (rnd.nextGaussian() * 0.07).toFloat().coerceIn(-0.15f, 0.15f)
        val sy = 1f + (rnd.nextGaussian() * 0.05).toFloat().coerceIn(-0.1f, 0.1f)
        val cr = cos(rot).toFloat(); val sr = sin(rot).toFloat()
        val h = size * scale
        val tx = { gx: Float, gy: Float ->
            val ux = (gx - w / 2) * sx + shear * (gy - .5f); val uy = (gy - .5f) * sy
            val rx = ux * cr - uy * sr; val ry = ux * sr + uy * cr
            (x + (w / 2 + rx) * h) to (topY + (0.5f + ry) * h)
        }
        strokes.forEach { emit(it, tx) }
        return w * h
    }

    /** Writes plain characters left-to-right on the baseline. Use 'D' for ÷ (drawn as bar with dots). */
    fun text(s: String, scale: Float = 1f, raise: Float = 0f): Pen {
        for (c in s) {
            if (c == ' ') { cursorX += size * 0.35f; continue }
            if (c == 'D') { divide(scale); continue }
            val h = size * scale
            // lowercase x is x-height; '.' sits on the baseline; everything else spans the full digit height
            val (top, drawScale) = when (c) {
                'x' -> baseline - raise - h * 0.62f to scale * 0.62f
                else -> baseline - raise - h to scale
            }
            val w = draw(c, cursorX, top, drawScale)
            cursorX += w + size * 0.22f * scale
        }
        return this
    }

    private fun divide(scale: Float) {
        val h = size * scale; val w = h * 0.6f; val y = baseline - h * 0.5f
        emit(listOf(0f to 0f, 1f to 0f), { gx, _ -> (cursorX + gx * w) to y })
        emit(listOf(0f to 0f, .1f to .1f), { gx, gy -> (cursorX + w / 2 + gx * h * .3f) to (y - h * .30f + gy * h * .3f) }, 6f)
        emit(listOf(0f to 0f, .1f to .1f), { gx, gy -> (cursorX + w / 2 + gx * h * .3f) to (y + h * .22f + gy * h * .3f) }, 6f)
        cursorX += w + size * 0.22f * scale
    }

    /** base then exponent drawn smaller and raised (superscript). */
    fun sup(exp: String): Pen {
        val saveBase = baseline
        val raise = size * 0.55f
        text(exp, scale = 0.6f, raise = raise)
        baseline = saveBase
        return this
    }

    /** A stacked fraction on the math axis of the current line. */
    fun frac(num: String, den: String): Pen {
        val axis = baseline - size * 0.38f
        val wn = measure(num, 0.85f); val wd = measure(den, 0.85f)
        val barW = maxOf(wn, wd) + size * 0.45f
        val x0 = cursorX
        emit(listOf(0f to 0f, 1f to 0f), { gx, _ -> (x0 + gx * barW) to axis })
        val saveBase = baseline; val saveCur = cursorX
        baseline = axis - size * 0.22f; cursorX = x0 + (barW - wn) / 2; text(num, 0.85f)
        baseline = axis + size * 0.22f + size * 0.85f; cursorX = x0 + (barW - wd) / 2; text(den, 0.85f)
        baseline = saveBase; cursorX = x0 + barW + size * 0.3f
        return this
    }

    /** Radical with [inner] under its vinculum. */
    fun sqrt(inner: String): Pen {
        val wIn = measure(inner, 1f)
        val h = size * 1.3f; val top = baseline - h + size * 0.05f
        val x0 = cursorX; val tick = size * 0.5f
        val total = tick + wIn + size * 0.25f
        emit(listOf(0f to .55f, .12f to .48f, .30f to 1f, .55f to 0f, 1f to 0f), { gx, gy ->
            val px = if (gx <= .55f) x0 + gx * tick * 1.8f else x0 + tick + (gx - .55f) / .45f * (total - tick)
            px to (top + gy * h)
        })
        cursorX = x0 + tick + size * 0.1f; text(inner, 1f); cursorX = x0 + total + size * 0.3f
        return this
    }

    private fun measure(s: String, scale: Float): Float {
        var w = 0f
        for (c in s) w += if (c == ' ') size * .35f else if (c == 'D') size * scale * 0.6f + size * .22f * scale else (glyph(c)!!.first * size * scale + size * 0.22f * scale)
        return w - size * 0.22f * scale
    }

    fun gap(k: Float = 1f): Pen { cursorX += size * k; return this }
    fun newLine(k: Float = 1.9f): Pen { baseline += size * k; cursorX = 100f; return this }
    fun at(x: Float, y: Float): Pen { cursorX = x; baseline = y; return this }

    companion object {
        private val INSTANCES = java.util.concurrent.atomic.AtomicInteger()
        fun write(s: String, seed: Long = 1L, sloppiness: Float = 1f) = Pen(seed, sloppiness = sloppiness).text(s).strokes
    }
}
