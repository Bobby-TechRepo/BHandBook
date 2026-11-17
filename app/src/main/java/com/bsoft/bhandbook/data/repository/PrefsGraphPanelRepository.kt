package com.bsoft.bhandbook.data.repository

import android.content.Context
import com.bsoft.bhandbook.core.codec.GraphPanelCodec
import com.bsoft.bhandbook.domain.model.GraphPanelState
import com.bsoft.bhandbook.domain.repository.GraphPanelRepository

/** One small JSON string per canvas in SharedPreferences (next to the app settings): no database schema involved. */
class PrefsGraphPanelRepository(context: Context) : GraphPanelRepository {
    private val prefs = context.getSharedPreferences("graph_panels", Context.MODE_PRIVATE)

    override fun load(canvasId: String): GraphPanelState = runCatching { GraphPanelCodec.decode(prefs.getString(key(canvasId), null).orEmpty()) }.getOrDefault(GraphPanelState())

    override fun save(canvasId: String, state: GraphPanelState) {
        runCatching {
            val e = prefs.edit()
            if (state == GraphPanelState()) e.remove(key(canvasId)) else e.putString(key(canvasId), GraphPanelCodec.encode(state))
            e.apply()
        }
    }

    override fun delete(canvasId: String) { runCatching { prefs.edit().remove(key(canvasId)).apply() } }

    private fun key(canvasId: String) = "canvas_$canvasId"
}
