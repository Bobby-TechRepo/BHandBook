package com.bsoft.bhandbook.domain.model

import com.bsoft.bhandbook.core.graphics.Vec2

/**
 * Which graph panels of a canvas are showing, and where / how large each one is. Keyed by expression id (a graph of simultaneous equations is
 * keyed by its lowest equation). Kept separate from the canvas tables, so it needs no database change.
 */
data class GraphPanelState(
    val visible: Set<String> = emptySet(),
    /** How far (world units) a panel was dragged from its default place beside its expression. */
    val offsets: Map<String, Vec2> = emptyMap(),
    /** Size multiplier chosen by hand for a panel (panels not listed use the default size from settings). */
    val scales: Map<String, Float> = emptyMap(),
    /** The part of the graph chosen by hand (pan / zoom inside the panel); a panel not listed here is framed automatically. */
    val views: Map<String, com.bsoft.bhandbook.domain.graph.GraphViewport> = emptyMap(),
)
