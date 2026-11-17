package com.bsoft.bhandbook.domain.history

import com.bsoft.bhandbook.core.recognition.InkSignature
import com.bsoft.bhandbook.domain.model.CanvasState
import com.bsoft.bhandbook.domain.model.ExpressionObject
import com.bsoft.bhandbook.domain.model.Stroke

/** Reversible edit. Commands keep only the delta needed to revert, never a copy of the canvas. */
interface Command {
    fun apply(s: CanvasState): CanvasState
    fun revert(s: CanvasState): CanvasState
    /** Number of strokes retained by this command (for tests / memory accounting). */
    val retainedStrokeCount: Int get() = 0
}

class History(private val limit: Int = 200) {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun execute(state: CanvasState, c: Command): CanvasState {
        val next = c.apply(state)
        undoStack.addLast(c)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        return next
    }
    /** Pushes an already-applied command (used for gestures whose effect was applied incrementally, e.g. erasing). */
    fun record(c: Command) {
        undoStack.addLast(c)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }
    fun undo(state: CanvasState): CanvasState? {
        val c = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(c); return c.revert(state)
    }
    fun redo(state: CanvasState): CanvasState? {
        val c = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(c); return c.apply(state)
    }
    fun clear() { undoStack.clear(); redoStack.clear() }
}

class AddStroke(private val stroke: Stroke) : Command {
    override fun apply(s: CanvasState) = s.copy(strokes = s.strokes + stroke)
    override fun revert(s: CanvasState) = s.copy(strokes = s.strokes.filterNot { it.id == stroke.id })
    override val retainedStrokeCount get() = 1
}

class RemoveStrokes(private val ids: List<String>) : Command {
    private var removed: List<Pair<Int, Stroke>> = emptyList()
    override fun apply(s: CanvasState): CanvasState {
        val set = ids.toSet()
        removed = s.strokes.withIndex().filter { it.value.id in set }.map { it.index to it.value }
        return s.copy(strokes = s.strokes.filterNot { it.id in set })
    }
    override fun revert(s: CanvasState): CanvasState {
        val list = s.strokes.toMutableList()
        for ((i, st) in removed) list.add(i.coerceAtMost(list.size), st)   // ascending indices restore exact order
        return s.copy(strokes = list)
    }
    override val retainedStrokeCount get() = removed.size
}

class ReplaceStroke(private val oldId: String, private val parts: List<Stroke>) : Command {
    private var index = -1
    private var original: Stroke? = null
    override fun apply(s: CanvasState): CanvasState {
        index = s.strokes.indexOfFirst { it.id == oldId }
        if (index < 0) return s
        original = s.strokes[index]
        val l = s.strokes.toMutableList(); l.removeAt(index); l.addAll(index, parts)
        return s.copy(strokes = l)
    }
    override fun revert(s: CanvasState): CanvasState {
        val orig = original ?: return s
        val partIds = parts.map { it.id }.toSet()
        val l = s.strokes.filterNot { it.id in partIds }.toMutableList()
        l.add(index.coerceAtMost(l.size), orig)
        return s.copy(strokes = l)
    }
    override val retainedStrokeCount get() = parts.size + 1
}

class MoveExpression(private val id: String, private val dx: Float, private val dy: Float) : Command {
    private fun shift(s: CanvasState, ddx: Float, ddy: Float) =
        s.copy(strokes = s.strokes.map { if (it.expressionId == id) it.translated(ddx, ddy) else it })
    override fun apply(s: CanvasState) = shift(s, dx, dy)
    override fun revert(s: CanvasState) = shift(s, -dx, -dy)
}

/**
 * Scales the ink of [ids] by [factor] about the point ([ax], [ay]) (stroke widths too). The original strokes are kept so that undo is exact. An expression
 * whose answer was up to date stays up to date (its ink signature is refreshed), so a resized expression is not read again.
 */
class ScaleExpressions(private val ids: List<String>, private val factor: Float, private val ax: Float, private val ay: Float) : Command {
    private var originalStrokes: Map<String, Stroke> = emptyMap()
    private var originalSignatures: Map<String, Int> = emptyMap()
    override fun apply(s: CanvasState): CanvasState {
        val chosen = ids.toSet()
        originalStrokes = s.strokes.filter { it.expressionId in chosen }.associateBy { it.id }
        originalSignatures = chosen.mapNotNull { id -> s.expressions[id]?.let { id to it.strokeSignature } }.toMap()
        val strokes = s.strokes.map { st ->
            if (st.expressionId !in chosen) st
            else st.copy(points = st.points.map { p -> p.copy(x = ax + (p.x - ax) * factor, y = ay + (p.y - ay) * factor) }, width = st.width * factor)
        }
        val expressions = s.expressions.mapValues { (id, e) ->
            if (id !in chosen || e.strokeSignature != InkSignature.of(s.strokes.filter { it.expressionId == id })) e
            else e.copy(strokeSignature = InkSignature.of(strokes.filter { it.expressionId == id }))
        }
        return s.copy(strokes = strokes, expressions = expressions)
    }
    override fun revert(s: CanvasState): CanvasState = s.copy(
        strokes = s.strokes.map { originalStrokes[it.id] ?: it },
        expressions = s.expressions.mapValues { (id, e) -> originalSignatures[id]?.let { e.copy(strokeSignature = it) } ?: e },
    )
    override val retainedStrokeCount get() = originalStrokes.size
}

class DeleteExpression(private val id: String) : Command {
    private var removedStrokes: List<Pair<Int, Stroke>> = emptyList()
    private var removedObject: ExpressionObject? = null
    override fun apply(s: CanvasState): CanvasState {
        removedStrokes = s.strokes.withIndex().filter { it.value.expressionId == id }.map { it.index to it.value }
        removedObject = s.expressions[id]
        return s.copy(strokes = s.strokes.filterNot { it.expressionId == id }, expressions = s.expressions - id)
    }
    override fun revert(s: CanvasState): CanvasState {
        val l = s.strokes.toMutableList()
        for ((i, st) in removedStrokes) l.add(i.coerceAtMost(l.size), st)
        val obj = removedObject
        return s.copy(strokes = l, expressions = if (obj != null) s.expressions + (id to obj) else s.expressions)
    }
    override val retainedStrokeCount get() = removedStrokes.size
}

class UpdateExpression(private val id: String, private val transform: (ExpressionObject) -> ExpressionObject) : Command {
    private var before: ExpressionObject? = null
    override fun apply(s: CanvasState): CanvasState {
        val cur = s.expressions[id] ?: return s
        before = cur
        return s.copy(expressions = s.expressions + (id to transform(cur)))
    }
    override fun revert(s: CanvasState): CanvasState {
        val b = before ?: return s
        return s.copy(expressions = s.expressions + (id to b))
    }
}

class CompositeCommand(private val commands: List<Command>) : Command {
    override fun apply(s: CanvasState) = commands.fold(s) { acc, c -> c.apply(acc) }
    override fun revert(s: CanvasState) = commands.asReversed().fold(s) { acc, c -> c.revert(acc) }
    override val retainedStrokeCount get() = commands.sumOf { it.retainedStrokeCount }
}

class AddExpression(private val obj: ExpressionObject) : Command {
    override fun apply(s: CanvasState) = s.copy(expressions = s.expressions + (obj.id to obj))
    override fun revert(s: CanvasState) = s.copy(expressions = s.expressions - obj.id)
}

/** Removes only the expression object (its strokes are handled by other commands). */
class RemoveExpression(private val id: String) : Command {
    private var removed: ExpressionObject? = null
    override fun apply(s: CanvasState): CanvasState { removed = s.expressions[id]; return s.copy(expressions = s.expressions - id) }
    override fun revert(s: CanvasState): CanvasState = removed?.let { s.copy(expressions = s.expressions + (id to it)) } ?: s
}

/** Re-assigns strokes to another expression (used when two expressions are merged). Remembers each stroke's previous owner. */
class AssignStrokes(private val strokeIds: Set<String>, private val toExpressionId: String) : Command {
    private var previous: Map<String, String?> = emptyMap()
    override fun apply(s: CanvasState): CanvasState {
        previous = s.strokes.filter { it.id in strokeIds }.associate { it.id to it.expressionId }
        return s.copy(strokes = s.strokes.map { if (it.id in strokeIds) it.withExpression(toExpressionId) else it })
    }
    override fun revert(s: CanvasState) = s.copy(strokes = s.strokes.map { st -> if (st.id in previous) st.withExpression(previous[st.id]) else st })
}
