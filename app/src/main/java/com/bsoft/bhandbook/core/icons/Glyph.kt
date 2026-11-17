package com.bsoft.bhandbook.core.icons

enum class StrokeCapKind { BUTT, ROUND, SQUARE }
enum class StrokeJoinKind { MITER, ROUND, BEVEL }

/**
 * One path of an icon, filled and/or stroked in the icon's single tint colour.
 * [evenOdd] mirrors SVG `fill-rule="evenodd"` (icons with holes); [strokeWidth] 0 means "not stroked".
 */
data class GlyphPath(
    val data: String,
    val evenOdd: Boolean = false,
    val fill: Boolean = true,
    val strokeWidth: Float = 0f,
    val cap: StrokeCapKind = StrokeCapKind.BUTT,
    val join: StrokeJoinKind = StrokeJoinKind.MITER,
)

/** A single-colour icon: SVG path data in a [viewportWidth] x [viewportHeight] box. Platform independent so it can be validated in JVM tests. */
data class Glyph(val source: String, val viewportWidth: Float, val viewportHeight: Float, val paths: List<GlyphPath>)

/** Strict check of SVG path syntax so a bad icon is caught by a unit test rather than crashing the UI at runtime. */
object PathSyntax {
    private val arity = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6, 'S' to 4, 'Q' to 4, 'T' to 2, 'A' to 7, 'Z' to 0)
    private val number = Regex("""[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?""")

    /** Returns a description of the first problem, or null if [d] is valid. */
    fun validate(d: String): String? {
        val t = d.trim()
        if (t.isEmpty()) return "empty path"
        if (t[0] != 'M' && t[0] != 'm') return "must start with M"
        var i = 0
        while (i < t.length) {
            while (i < t.length && (t[i] == ' ' || t[i] == ',' || t[i] == '\n')) i++
            if (i >= t.length) break
            val cmd = t[i]
            val n = arity[cmd.uppercaseChar()] ?: return "unexpected '${t[i]}' at $i"
            i++
            var count = 0
            if (n == 0) { continue }
            while (true) {
                while (i < t.length && (t[i] == ' ' || t[i] == ',')) i++
                if (i >= t.length || t[i].isLetter() && t[i] != 'e' && t[i] != 'E') break
                if (cmd.uppercaseChar() == 'A' && (count % 7 == 3 || count % 7 == 4)) {        // arc flags are single characters and may be packed
                    if (t[i] != '0' && t[i] != '1') return "bad arc flag at $i"
                    i++
                } else {
                    val m = number.matchAt(t, i) ?: return "bad number at $i"
                    i = m.range.last + 1
                }
                count++
            }
            if (count == 0 || count % n != 0) return "'$cmd' has $count values, needs a multiple of $n"
        }
        return null
    }

    /** All numeric values in the path (flags excluded approximately); used for sanity checks. */
    fun numbers(d: String): List<Float> = number.findAll(d).map { it.value.toFloat() }.toList()
}
