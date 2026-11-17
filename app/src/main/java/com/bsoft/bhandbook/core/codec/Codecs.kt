package com.bsoft.bhandbook.core.codec

import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.solver.SolutionStep
import java.nio.ByteBuffer

/** Compact binary form for stroke points (13 bytes/point). Decoding is defensive: damaged data yields null, never a crash. */
object StrokeCodec {
    private const val MAGIC: Byte = 0x53
    private const val HEADER = 1 + 4 + 8

    fun encode(points: List<StrokePoint>): ByteArray {
        val buf = ByteBuffer.allocate(HEADER + points.size * 13)
        buf.put(MAGIC).putInt(points.size).putLong(points.firstOrNull()?.t ?: 0L)
        var prevT = points.firstOrNull()?.t ?: 0L
        for (p in points) {
            buf.putFloat(p.x).putFloat(p.y)
            buf.putInt((p.t - prevT).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()); prevT = p.t
            buf.put((p.pressure.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        }
        return buf.array()
    }

    fun decode(bytes: ByteArray): List<StrokePoint>? {
        if (bytes.size < HEADER) return null
        val buf = ByteBuffer.wrap(bytes)
        if (buf.get() != MAGIC) return null
        val n = buf.getInt()
        if (n < 0 || bytes.size.toLong() != HEADER + n.toLong() * 13) return null
        var t = buf.getLong()
        return List(n) {
            val x = buf.getFloat(); val y = buf.getFloat(); t += buf.getInt()
            StrokePoint(x, y, t, (buf.get().toInt() and 0xFF) / 255f)
        }
    }
}

object StepsCodec {
    private const val RS = '\u001E'; private const val US = '\u001F'
    private fun esc(s: String) = s.replace("\\", "\\\\").replace(RS.toString(), "\\r").replace(US.toString(), "\\u")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) { sb.append(when (s[i + 1]) { 'r' -> RS; 'u' -> US; else -> s[i + 1] }); i += 2 } else { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }

    fun encode(steps: List<SolutionStep>): String = steps.joinToString(RS.toString()) { s ->
        listOf(s.number.toString(), esc(s.expression), esc(s.explanation), esc(s.resulting), s.highlight?.let { "${it.first}-${it.last}" } ?: "").joinToString(US.toString())
    }

    fun decode(text: String): List<SolutionStep> {
        if (text.isEmpty()) return emptyList()
        return text.split(RS).mapNotNull { rec ->
            val f = rec.split(US)
            if (f.size != 5) return@mapNotNull null
            val num = f[0].toIntOrNull() ?: return@mapNotNull null
            val hl = f[4].takeIf { it.isNotEmpty() }?.split('-')?.let { p -> if (p.size == 2) p[0].toIntOrNull()?.let { a -> p[1].toIntOrNull()?.let { b -> a..b } } else null }
            SolutionStep(num, unesc(f[1]), unesc(f[2]), unesc(f[3]), hl)
        }
    }
}

class StoredStroke(val id: String, val expressionId: String?, val width: Float, val color: Long, val order: Int, val points: ByteArray) {
    fun copy(points: ByteArray = this.points) = StoredStroke(id, expressionId, width, color, order, points)
}
data class StoredExpression(val id: String, val recognized: String?, val confidence: Float, val result: String?, val steps: String,
    val solutionVisible: Boolean, val status: String, val error: String?, val modifiedAt: Long, val strokeSignature: Int,
    val displayText: String? = null, val resultInline: Boolean = false)
class StoredCanvas(val strokes: List<StoredStroke>, val expressions: List<StoredExpression>) {
    fun copy(strokes: List<StoredStroke> = this.strokes) = StoredCanvas(strokes, expressions)
}

/** CanvasState <-> flat storable rows (what Room persists). Only durable data: no pointer events or render caches. */
object CanvasCodec {
    fun toStored(s: CanvasState) = StoredCanvas(
        strokes = s.strokes.mapIndexed { i, st -> StoredStroke(st.id, st.expressionId, st.width, st.color, i, StrokeCodec.encode(st.points)) },
        expressions = s.expressions.values.map { e ->
            StoredExpression(e.id, e.recognized, e.confidence, e.result, StepsCodec.encode(e.steps), e.solutionVisible, e.status.name, e.error, e.modifiedAt, e.strokeSignature, e.displayText, e.resultInline)
        },
    )

    fun fromStored(c: StoredCanvas) = CanvasState(
        strokes = c.strokes.sortedBy { it.order }.mapNotNull { st ->
            val pts = StrokeCodec.decode(st.points) ?: return@mapNotNull null          // skip damaged strokes, keep the rest
            Stroke(st.id, pts, st.width, st.color, st.expressionId)
        },
        expressions = c.expressions.associate { e ->
            e.id to ExpressionObject(e.id, e.recognized, e.confidence, e.result, StepsCodec.decode(e.steps), e.solutionVisible,
                runCatching { ProcessingStatus.valueOf(e.status) }.getOrDefault(ProcessingStatus.IDLE), e.error, e.modifiedAt, e.strokeSignature, e.displayText, e.resultInline)
        },
    )
}
