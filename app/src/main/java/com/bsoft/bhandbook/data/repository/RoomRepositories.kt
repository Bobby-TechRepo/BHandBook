package com.bsoft.bhandbook.data.repository

import androidx.room.withTransaction
import com.bsoft.bhandbook.core.codec.StepsCodec
import com.bsoft.bhandbook.core.codec.StrokeCodec
import com.bsoft.bhandbook.data.local.*
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.repository.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val SQL_VARIABLE_CHUNK = 500   // SQLite limits bound variables per statement

@Singleton
class RoomStrokeRepository @Inject constructor(private val db: CanvasDatabase) : StrokeRepository {
    private class Snapshot(val strokes: Map<String, Stroke>, val order: Map<String, Int>)
    private val snapshots = HashMap<String, Snapshot>()
    private val lock = Mutex()
    private val dao get() = db.strokeDao()

    override suspend fun strokes(canvasId: String): List<Stroke> = lock.withLock {
        val list = dao.all(canvasId).mapNotNull { e ->
            val pts = StrokeCodec.decode(e.points) ?: return@mapNotNull null      // damaged stroke is skipped, the rest survives
            Stroke(e.id, pts, e.width, e.color, e.expressionId)
        }
        snapshots[canvasId] = Snapshot(list.associateBy { it.id }, list.withIndex().associate { it.value.id to it.index })
        list
    }

    override suspend fun sync(canvasId: String, strokes: List<Stroke>) = lock.withLock {
        val prev = snapshots[canvasId]
        db.withTransaction {
            if (prev == null) {
                dao.deleteAll(canvasId)
                dao.upsert(strokes.mapIndexed { i, s -> s.toEntity(canvasId, i) })
            } else {
                val newIds = strokes.mapTo(HashSet()) { it.id }
                (prev.strokes.keys - newIds).chunked(SQL_VARIABLE_CHUNK).forEach { dao.deleteByIds(it) }
                val changed = ArrayList<StrokeEntity>()
                for ((i, s) in strokes.withIndex()) {
                    if (prev.strokes[s.id] !== s) changed += s.toEntity(canvasId, i)          // new or edited ink only
                    else if (prev.order[s.id] != i) dao.updateOrder(s.id, i)                    // only its position moved
                }
                if (changed.isNotEmpty()) dao.upsert(changed)
            }
        }
        snapshots[canvasId] = Snapshot(strokes.associateBy { it.id }, strokes.withIndex().associate { it.value.id to it.index })
    }

    fun forget(canvasId: String) { snapshots.remove(canvasId) }

    private fun Stroke.toEntity(canvasId: String, order: Int) = StrokeEntity(id, canvasId, expressionId, width, color, order, StrokeCodec.encode(points))
}

@Singleton
class RoomExpressionRepository @Inject constructor(private val db: CanvasDatabase) : ExpressionRepository {
    private val dao get() = db.expressionDao()

    override suspend fun expressions(canvasId: String): List<ExpressionObject> = dao.all(canvasId).map { e ->
        ExpressionObject(e.id, e.recognized, e.confidence, e.result, StepsCodec.decode(e.steps), e.solutionVisible,
            runCatching { ProcessingStatus.valueOf(e.status) }.getOrDefault(ProcessingStatus.IDLE), e.error, e.modifiedAt, e.strokeSignature, e.displayText, e.resultInline)
    }

    override suspend fun sync(canvasId: String, expressions: Collection<ExpressionObject>) {
        db.withTransaction {
            val keep = expressions.mapTo(HashSet()) { it.id }
            (dao.ids(canvasId).toSet() - keep).toList().chunked(SQL_VARIABLE_CHUNK).forEach { dao.deleteByIds(canvasId, it) }
            // expression rows are small (one per expression), so they are simply upserted
            dao.upsert(expressions.map { e ->
                ExpressionEntity(canvasId, e.id, e.recognized, e.confidence, e.result, StepsCodec.encode(e.steps), e.solutionVisible,
                    e.status.name, e.error, e.modifiedAt, e.strokeSignature, e.displayText, e.resultInline)
            })
        }
    }
}

@Singleton
class RoomCanvasRepository @Inject constructor(
    private val db: CanvasDatabase,
    private val strokes: RoomStrokeRepository,
    private val expressions: RoomExpressionRepository,
) : CanvasRepository {
    private val dao get() = db.canvasDao()

    override suspend fun listCanvases() = dao.all().map { CanvasInfo(it.id, it.name, it.createdAt, it.updatedAt) }

    override suspend fun create(name: String, background: CanvasBackground, now: Long): CanvasInfo {
        val id = UUID.randomUUID().toString()
        dao.upsert(CanvasEntity(id, name, now, now, background.name, 1f, 0f, 0f))
        return CanvasInfo(id, name, now, now)
    }

    override suspend fun rename(id: String, name: String) = dao.rename(id, name.trim().ifEmpty { "Untitled" }, System.currentTimeMillis())

    override suspend fun delete(id: String) { dao.delete(id); strokes.forget(id) }       // rows cascade

    override suspend fun load(id: String): CanvasDocument? {
        val e = dao.get(id) ?: return null
        val state = CanvasState(strokes.strokes(id), expressions.expressions(id).associateBy { it.id })
        return CanvasDocument(
            CanvasInfo(e.id, e.name, e.createdAt, e.updatedAt),
            CanvasViewSettings(runCatching { CanvasBackground.valueOf(e.background) }.getOrDefault(CanvasBackground.GRID), e.scale, e.offsetX, e.offsetY),
            state,
        )
    }

    override suspend fun save(id: String, view: CanvasViewSettings, state: CanvasState, now: Long) {
        strokes.sync(id, state.strokes)
        expressions.sync(id, state.expressions.values)
        dao.touch(id, now, view.scale, view.offsetX, view.offsetY, view.background.name)
    }

    override suspend fun mostRecentId() = dao.mostRecentId()
}
