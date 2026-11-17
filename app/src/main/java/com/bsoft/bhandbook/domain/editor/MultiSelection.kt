package com.bsoft.bhandbook.domain.editor

/** Selecting several expressions at once: while [active], a tap adds or removes an expression. Pure. */
data class MultiSelection(val active: Boolean = false, val ids: Set<String> = emptySet()) {
    /** Enters the mode, keeping the expression that was selected (if any). */
    fun start(from: String?) = MultiSelection(true, setOfNotNull(from))
    /** Adds [id], or removes it if it is already in; tapping empty space ([id] null) or outside the mode changes nothing. */
    fun tap(id: String?): MultiSelection = if (!active || id == null) this else copy(ids = if (id in ids) ids - id else ids + id)
    fun selectAll(all: Collection<String>): MultiSelection = if (active) copy(ids = all.toSet()) else this
    /** Forgets expressions that no longer exist. */
    fun prune(existing: Set<String>): MultiSelection = if (ids.all { it in existing }) this else copy(ids = ids.filterTo(HashSet()) { it in existing })
    fun stop() = MultiSelection()
}
