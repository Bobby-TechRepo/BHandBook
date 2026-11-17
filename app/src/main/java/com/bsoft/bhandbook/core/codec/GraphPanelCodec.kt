package com.bsoft.bhandbook.core.codec

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.domain.editor.ExpressionLayout
import com.bsoft.bhandbook.domain.model.GraphPanelState

/**
 * JSON for [GraphPanelState]: {"v":1,"visible":["id"],"offsets":{"id":[x,y]},"scales":{"id":s}}.
 * Decoding is defensive (this is read at every launch): anything unreadable, from a different version, or not finite is dropped, and damaged input
 * gives an empty state; it never throws.
 */
object GraphPanelCodec {
    private const val VERSION = 1

    fun encode(s: GraphPanelState): String {
        val sb = StringBuilder("{\"v\":$VERSION,\"visible\":[")
        sb.append(s.visible.joinToString(",") { JsonWriter.quote(it) })
        sb.append("],\"offsets\":{")
        sb.append(s.offsets.filterValues { it.x.isFinite() && it.y.isFinite() }.entries.joinToString(",") { "${JsonWriter.quote(it.key)}:[${it.value.x},${it.value.y}]" })
        sb.append("},\"scales\":{")
        sb.append(s.scales.filterValues { it.isFinite() }.entries.joinToString(",") { "${JsonWriter.quote(it.key)}:${it.value}" })
        sb.append("},\"views\":{")
        sb.append(s.views.filterValues { it.isValid }.entries.joinToString(",") { "${JsonWriter.quote(it.key)}:[${it.value.xMin},${it.value.xMax},${it.value.yMin},${it.value.yMax}]" })
        return sb.append("}}").toString()
    }

    fun decode(text: String): GraphPanelState = try { read(text) } catch (e: Exception) { GraphPanelState() }

    private fun read(text: String): GraphPanelState {
        val root = MiniJson.parse(text) as? Map<*, *> ?: return GraphPanelState()
        if ((root["v"] as? Double)?.toInt() != VERSION) return GraphPanelState()
        val visible = (root["visible"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        val offsets = HashMap<String, Vec2>()
        (root["offsets"] as? Map<*, *>)?.forEach { (k, v) ->
            val l = v as? List<*>; val x = (l?.getOrNull(0) as? Double); val y = (l?.getOrNull(1) as? Double)
            if (k is String && x != null && y != null && x.isFinite() && y.isFinite() && kotlin.math.abs(x) < 1e9 && kotlin.math.abs(y) < 1e9) offsets[k] = Vec2(x.toFloat(), y.toFloat())
        }
        val scales = HashMap<String, Float>()
        (root["scales"] as? Map<*, *>)?.forEach { (k, v) -> val d = v as? Double; if (k is String && d != null && d.isFinite()) scales[k] = ExpressionLayout.clampGraphScale(d.toFloat()) }
        val views = HashMap<String, com.bsoft.bhandbook.domain.graph.GraphViewport>()
        (root["views"] as? Map<*, *>)?.forEach { (k, v) ->
            val l = (v as? List<*>)?.map { it as? Double }
            if (k is String && l != null && l.size == 4 && l.all { it != null }) com.bsoft.bhandbook.domain.graph.GraphViewport(l[0]!!, l[1]!!, l[2]!!, l[3]!!).takeIf { it.isValid }?.let { views[k] = it }
        }
        return GraphPanelState(visible, offsets, scales, views)
    }
}
