package com.bsoft.bhandbook.domain.repository

import com.bsoft.bhandbook.domain.model.CanvasBackground
import com.bsoft.bhandbook.domain.model.CanvasState
import com.bsoft.bhandbook.domain.model.ExpressionObject
import com.bsoft.bhandbook.domain.model.GraphPanelState
import com.bsoft.bhandbook.domain.model.InkSample
import com.bsoft.bhandbook.domain.model.Stroke

data class CanvasInfo(val id: String, val name: String, val createdAt: Long, val updatedAt: Long)

/** Per-canvas view settings that are restored when the canvas is reopened. */
data class CanvasViewSettings(val background: CanvasBackground, val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)

data class CanvasDocument(val info: CanvasInfo, val view: CanvasViewSettings, val state: CanvasState)

interface CanvasRepository {
    suspend fun listCanvases(): List<CanvasInfo>
    suspend fun create(name: String, background: CanvasBackground, now: Long): CanvasInfo
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun load(id: String): CanvasDocument?
    /** Persists [state] incrementally (only changed strokes are written). */
    suspend fun save(id: String, view: CanvasViewSettings, state: CanvasState, now: Long)
    suspend fun mostRecentId(): String?
}

interface StrokeRepository {
    suspend fun strokes(canvasId: String): List<Stroke>
    /** Writes only the difference between the stored strokes and [strokes] (order preserved). */
    suspend fun sync(canvasId: String, strokes: List<Stroke>)
}

interface ExpressionRepository {
    suspend fun expressions(canvasId: String): List<ExpressionObject>
    suspend fun sync(canvasId: String, expressions: Collection<ExpressionObject>)
}

/** Remembers each canvas's graph panels (shown, placed, sized) between launches. Small and synchronous; never throws. */
interface GraphPanelRepository {
    fun load(canvasId: String): GraphPanelState
    fun save(canvasId: String, state: GraphPanelState)
    fun delete(canvasId: String)
}

/** Opt-in local collection of labelled handwriting, used to measure and tune recognition on real writing. Never uploaded. */
interface SampleRepository {
    /** Returns where the sample was stored, or null if it could not be written. */
    suspend fun save(sample: InkSample): String?
}
