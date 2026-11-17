package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.recognition.ExpressionAssigner
import com.bsoft.bhandbook.core.recognition.ExpressionAssignment
import com.bsoft.bhandbook.core.recognition.InkSignature
import com.bsoft.bhandbook.core.recognition.TextLines
import com.bsoft.bhandbook.domain.history.*
import com.bsoft.bhandbook.domain.model.*

/**
 * All canvas editing rules in one pure, synchronous, thread-confined class: stroke -> expression association,
 * merging, erasing, moving, undo/redo, and bookkeeping of which expressions need (re)recognition.
 * ViewModels only call this and schedule background work for [expressionsNeedingRecognition].
 */
class CanvasEditor(
    initial: CanvasState = CanvasState(),
    private val idGenerator: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    historyLimit: Int = 500,
) {
    var state: CanvasState = initial
        private set
    private val history = History(historyLimit)
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo

    fun load(newState: CanvasState) { state = newState; history.clear(); pendingErase = null }

    // ------------------------------------------------------------------ drawing
    private fun invalidate(now: Long): (ExpressionObject) -> ExpressionObject = {
        it.copy(recognized = null, confidence = 0f, result = null, steps = emptyList(), error = null, status = ProcessingStatus.PENDING, modifiedAt = now)
    }

    /** Adds a finished stroke (world coordinates) and associates it with a new, existing or merged expression. */
    fun addStroke(stroke: Stroke) {
        val now = clock()
        val cmds = mutableListOf<Command>()
        val target: String
        when (val a = ExpressionAssigner.assign(stroke, state.strokes)) {
            ExpressionAssignment.New -> {
                target = idGenerator()
                cmds += AddExpression(ExpressionObject(id = target, status = ProcessingStatus.PENDING, modifiedAt = now))
            }
            is ExpressionAssignment.Join -> { target = a.expressionId; cmds += UpdateExpression(target, invalidate(now)) }
            is ExpressionAssignment.Merge -> {
                target = a.ids.first()
                for (other in a.ids.drop(1)) {
                    cmds += AssignStrokes(state.strokesOf(other).map { it.id }.toSet(), target)
                    cmds += RemoveExpression(other)
                }
                cmds += UpdateExpression(target, invalidate(now))
            }
        }
        cmds += AddStroke(stroke.withExpression(target))
        state = history.execute(state, CompositeCommand(cmds))
    }

    /**
     * Splits expressions that contain several lines of writing (ink merged before grouping was line-aware, or lines that grew together) into one
     * expression per line; the top line keeps the original id. One undo step. Returns whether anything changed.
     */
    fun splitMergedLines(ids: Collection<String> = state.expressions.keys): Boolean {
        val now = clock(); val cmds = mutableListOf<Command>()
        for (id in ids.toList()) {
            if (state.expressions[id] == null) continue
            val parts = TextLines.split(state.strokesOf(id))
            if (parts.size < 2) continue
            cmds += UpdateExpression(id, invalidate(now))
            for (part in parts.drop(1)) {
                val newId = idGenerator()
                cmds += AddExpression(ExpressionObject(id = newId, status = ProcessingStatus.PENDING, modifiedAt = now))
                cmds += AssignStrokes(part.map { it.id }.toSet(), newId)
            }
        }
        if (cmds.isEmpty()) return false
        state = history.execute(state, CompositeCommand(cmds))
        return true
    }

    // ------------------------------------------------------------------ erasing (one gesture = one undo step)
    private var pendingErase: MutableList<Command>? = null

    fun beginErase() { pendingErase = mutableListOf() }

    fun eraseAt(center: Vec2, radius: Float) {
        val pending = pendingErase ?: return
        for (s in state.strokes.toList()) {
            if (!com.bsoft.bhandbook.domain.model.StrokeEraser.touches(s, center, radius)) continue
            val parts = com.bsoft.bhandbook.domain.model.StrokeEraser.erase(s, center, radius)
            val cmd: Command = if (parts.isEmpty()) RemoveStrokes(listOf(s.id)) else ReplaceStroke(s.id, parts)
            state = cmd.apply(state); pending += cmd
        }
    }

    fun endErase() {
        val pending = pendingErase ?: return
        pendingErase = null
        if (pending.isEmpty()) return
        val now = clock()
        val affected = state.expressions.keys.toSet()
        val withInk = state.strokes.mapNotNull { it.expressionId }.toSet()
        for (id in affected) {
            val cmd: Command = when {
                id !in withInk -> RemoveExpression(id)
                state.expressions[id]!!.strokeSignature != InkSignature.of(state.strokesOf(id)) && state.expressions[id]!!.status != ProcessingStatus.PENDING -> UpdateExpression(id, invalidate(now))
                else -> continue
            }
            state = cmd.apply(state); pending += cmd
        }
        history.record(CompositeCommand(pending))
    }

    // ------------------------------------------------------------------ expression operations
    fun moveExpression(id: String, dx: Float, dy: Float) {
        if (state.expressions[id] == null || (dx == 0f && dy == 0f)) return
        state = history.execute(state, MoveExpression(id, dx, dy))
    }

    /**
     * Resizes expressions by [factor] about ([ax], [ay]) as ONE undoable step (bounded per step; bad factors, unknown ids and no-ops are ignored).
     * The ink grows, and so do the answer and steps drawn from it; the expression is not read again.
     */
    fun scaleExpressions(ids: Collection<String>, factor: Float, ax: Float, ay: Float) {
        if (!factor.isFinite() || factor <= 0f || !ax.isFinite() || !ay.isFinite()) return
        val f = factor.coerceIn(MIN_SCALE_STEP, MAX_SCALE_STEP)
        if (kotlin.math.abs(f - 1f) < 0.001f) return
        val known = ids.filter { state.expressions[it] != null }.distinct()
        if (known.isEmpty()) return
        state = history.execute(state, ScaleExpressions(known, f, ax, ay))
    }

    /** Moves several expressions together as ONE undoable step; unknown ids and no-op moves are ignored. */
    fun moveExpressions(ids: Collection<String>, dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite() || (dx == 0f && dy == 0f)) return
        val known = ids.filter { state.expressions[it] != null }
        if (known.isEmpty()) return
        state = history.execute(state, CompositeCommand(known.map { MoveExpression(it, dx, dy) }))
    }

    /** Deletes several expressions (with their ink) as ONE undoable step; unknown ids are ignored. */
    fun deleteExpressions(ids: Collection<String>) {
        val known = ids.filter { state.expressions[it] != null }
        if (known.isEmpty()) return
        state = history.execute(state, CompositeCommand(known.map { DeleteExpression(it) }))
    }

    fun deleteExpression(id: String) {
        if (state.expressions[id] == null) return
        state = history.execute(state, DeleteExpression(id))
    }

    fun clearAll() {
        if (state.strokes.isEmpty() && state.expressions.isEmpty()) return
        val cmds = mutableListOf<Command>()
        state.expressions.keys.forEach { cmds += DeleteExpression(it) }
        val orphans = state.strokes.filter { it.expressionId == null }.map { it.id }
        if (orphans.isNotEmpty()) cmds += RemoveStrokes(orphans)
        state = history.execute(state, CompositeCommand(cmds))
    }

    /** User-driven change (toggle solution, corrected text...). Undoable. */
    fun updateExpression(id: String, transform: (ExpressionObject) -> ExpressionObject) {
        if (state.expressions[id] == null) return
        state = history.execute(state, UpdateExpression(id, transform))
    }

    /** Marks an expression for recomputation, e.g. after the user corrects its text or asks to recalculate. Undoable. */
    fun invalidateExpression(id: String) = updateExpression(id) { invalidate(clock())(it).copy(strokeSignature = 0) }

    fun undo(): CanvasState? = history.undo(state)?.also { state = it }
    fun redo(): CanvasState? = history.redo(state)?.also { state = it }

    // ------------------------------------------------------------------ recognition bookkeeping
    fun signatureOf(id: String): Int = InkSignature.of(state.strokesOf(id))

    /** Expressions whose ink differs from what their stored result was computed from. */
    fun expressionsNeedingRecognition(): Set<String> = state.expressions.filter { (id, e) ->
        val sig = signatureOf(id); sig != 0 && sig != e.strokeSignature
    }.keys

    /**
     * Marks an expression as being recognised right now (progress feedback). Not an undo step. Ignored, returning false, if the ink changed
     * since [forSignature] or the expression already has a finished state, so it can never overwrite newer data.
     */
    fun markRecognizing(id: String, forSignature: Int): Boolean {
        val e = state.expressions[id] ?: return false
        if (signatureOf(id) != forSignature) return false
        if (e.status != ProcessingStatus.PENDING && e.status != ProcessingStatus.IDLE) return false
        state = state.copy(expressions = state.expressions + (id to e.copy(status = ProcessingStatus.RECOGNIZING)))
        return true
    }

    /**
     * Applies a background result (not an undo step). Rejected, returning false, if the expression changed since
     * [forSignature] was taken, so results of outdated requests can never overwrite newer state.
     */
    fun applyRecognition(id: String, forSignature: Int, transform: (ExpressionObject) -> ExpressionObject): Boolean {
        val e = state.expressions[id] ?: return false
        if (signatureOf(id) != forSignature) return false
        state = state.copy(expressions = state.expressions + (id to transform(e).copy(strokeSignature = forSignature)))
        return true
    }

    companion object {
        const val MIN_SCALE_STEP = 0.05f
        const val MAX_SCALE_STEP = 20f
    }
}
