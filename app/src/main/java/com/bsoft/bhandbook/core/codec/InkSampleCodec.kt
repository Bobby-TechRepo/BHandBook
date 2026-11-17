package com.bsoft.bhandbook.core.codec

import com.bsoft.bhandbook.domain.model.InkSample
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint

/**
 * Plain-JSON file format for handwriting samples, readable by any tool (no dependency on org.json, so it also runs in JVM tests):
 * {"version":1,"label":"25+15=","recognizedAs":"25+16=","createdAt":123,"strokes":[{"width":3.0,"points":[[x,y,t,pressure],...]}]}
 */
object InkSampleCodec {
    private const val VERSION = 1

    fun encode(s: InkSample): String = buildString {
        append("{\"version\":").append(VERSION)
        append(",\"label\":"); quote(s.label)
        append(",\"recognizedAs\":"); if (s.recognizedAs == null) append("null") else quote(s.recognizedAs)
        append(",\"createdAt\":").append(s.createdAt)
        append(",\"strokes\":[")
        s.strokes.forEachIndexed { i, st ->
            if (i > 0) append(',')
            append("{\"width\":").append(num(st.width)).append(",\"points\":[")
            st.points.forEachIndexed { j, p ->
                if (j > 0) append(',')
                append('[').append(num(p.x)).append(',').append(num(p.y)).append(',').append(p.t).append(',').append(num(p.pressure)).append(']')
            }
            append("]}")
        }
        append("]}")
    }

    private fun num(f: Float) = if (f.isNaN() || f.isInfinite()) "0" else f.toString()

    private fun StringBuilder.quote(s: String) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c == '\n' -> append("\\n"); c == '\r' -> append("\\r"); c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /** Returns null for anything that is not a valid version-1 sample. Never throws. */
    fun decode(json: String): InkSample? = try {
        val root = MiniJson.parse(json) as? Map<*, *>
        if (root == null || (root["version"] as? Double)?.toInt() != VERSION) null else {
            val label = root["label"] as? String ?: return null
            val rec = if (root["recognizedAs"] == null) null else (root["recognizedAs"] as? String ?: return null)
            val created = (root["createdAt"] as? Double)?.toLong() ?: 0L
            val strokes = (root["strokes"] as? List<*> ?: return null).mapIndexed { i, any ->
                val m = any as? Map<*, *> ?: return null
                val pts = (m["points"] as? List<*> ?: return null).map { p ->
                    val a = p as? List<*> ?: return null
                    if (a.size != 4 || a.any { it !is Double }) return null
                    StrokePoint((a[0] as Double).toFloat(), (a[1] as Double).toFloat(), (a[2] as Double).toLong(), (a[3] as Double).toFloat())
                }
                Stroke("sample_$i", pts, ((m["width"] as? Double) ?: return null).toFloat(), 0L)
            }
            InkSample(label, rec, strokes, created)
        }
    } catch (e: MiniJson.ParseFailure) { null }
}
