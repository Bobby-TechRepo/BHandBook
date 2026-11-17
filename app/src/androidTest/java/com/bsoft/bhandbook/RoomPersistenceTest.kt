package com.bsoft.bhandbook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bsoft.bhandbook.data.local.CanvasDatabase
import com.bsoft.bhandbook.data.repository.RoomCanvasRepository
import com.bsoft.bhandbook.data.repository.RoomExpressionRepository
import com.bsoft.bhandbook.data.repository.RoomStrokeRepository
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.repository.CanvasViewSettings
import com.bsoft.bhandbook.domain.solver.SolutionStep
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {
    private lateinit var db: CanvasDatabase
    private lateinit var repo: RoomCanvasRepository
    private lateinit var strokes: RoomStrokeRepository

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CanvasDatabase::class.java).allowMainThreadQueries().build()
        strokes = RoomStrokeRepository(db)
        repo = RoomCanvasRepository(db, strokes, RoomExpressionRepository(db))
    }
    @After fun close() = db.close()

    private fun stroke(id: String, expr: String? = "e1", x: Float = 0f) =
        Stroke(id, (0 until 12).map { StrokePoint(x + it * 2f, it * 1f, it * 10L, 0.5f) }, 3f, 0, expr)
    private val view = CanvasViewSettings(CanvasBackground.GRID, 1.5f, 10f, -20f)

    private fun sampleState() = CanvasState(
        strokes = listOf(stroke("a"), stroke("b", x = 40f), stroke("c", null, 80f)),
        expressions = mapOf("e1" to ExpressionObject("e1", "25+15=", 0.92f, "40", listOf(SolutionStep(1, "25 + 15", "Add", "40")), displayText = "25 + 15", resultInline = true, status = ProcessingStatus.EVALUATED, strokeSignature = 77)),
    )

    @Test fun saveThenRestore_returnsTheSameWorkspace() = runBlocking {
        val info = repo.create("Test", CanvasBackground.GRID, 1L)
        repo.save(info.id, view, sampleState(), 2L)
        val doc = repo.load(info.id)!!
        assertEquals(sampleState().strokes.map { it.id }, doc.state.strokes.map { it.id })
        assertEquals(sampleState().expressions, doc.state.expressions)
        assertEquals(view, doc.view)
    }

    @Test fun strokePoints_surviveExactly() = runBlocking {
        val info = repo.create("Test", CanvasBackground.WHITE, 1L); val s = stroke("a")
        repo.save(info.id, view, CanvasState(listOf(s)), 2L)
        val back = repo.load(info.id)!!.state.strokes.single()
        assertEquals(s.points.map { it.x to it.y }, back.points.map { it.x to it.y })
    }

    @Test fun incrementalSave_removesErasedStrokes_andAddsNewOnes() = runBlocking {
        val info = repo.create("Test", CanvasBackground.WHITE, 1L)
        val s0 = sampleState(); repo.save(info.id, view, s0, 2L); repo.load(info.id)
        val s1 = s0.copy(strokes = s0.strokes.filterNot { it.id == "b" } + stroke("d", x = 120f))
        repo.save(info.id, view, s1, 3L)
        assertEquals(listOf("a", "c", "d"), repo.load(info.id)!!.state.strokes.map { it.id })
    }

    @Test fun movedInk_isRewritten() = runBlocking {
        val info = repo.create("Test", CanvasBackground.WHITE, 1L)
        val s0 = sampleState(); repo.save(info.id, view, s0, 2L); repo.load(info.id)
        val moved = s0.copy(strokes = s0.strokes.map { if (it.id == "a") it.translated(500f, 0f) else it })
        repo.save(info.id, view, moved, 3L)
        assertEquals(500f, repo.load(info.id)!!.state.strokes.first { it.id == "a" }.points.first().x, 0.01f)
    }

    @Test fun deletingExpression_removesItsRow() = runBlocking {
        val info = repo.create("Test", CanvasBackground.WHITE, 1L)
        repo.save(info.id, view, sampleState(), 2L)
        repo.save(info.id, view, CanvasState(), 3L)
        assertTrue(repo.load(info.id)!!.state.expressions.isEmpty())
    }

    @Test fun deletingACanvas_removesEverythingBelongingToIt() = runBlocking {
        val info = repo.create("Test", CanvasBackground.WHITE, 1L)
        repo.save(info.id, view, sampleState(), 2L)
        repo.delete(info.id)
        assertNull(repo.load(info.id)); assertTrue(db.strokeDao().all(info.id).isEmpty()); assertTrue(db.expressionDao().all(info.id).isEmpty())
    }

    @Test fun renameAndList_orderedByMostRecent() = runBlocking {
        val a = repo.create("A", CanvasBackground.WHITE, 1L); val b = repo.create("B", CanvasBackground.WHITE, 2L)
        repo.rename(a.id, "Renamed")
        val names = repo.listCanvases().map { it.name }
        assertEquals("Renamed", names.first()); assertEquals(b.id, repo.listCanvases().last().id)
    }

    @Test fun damagedStrokeBlob_isSkipped_restSurvives() = runBlocking {
        val info = repo.create("Test", CanvasBackground.WHITE, 1L)
        repo.save(info.id, view, CanvasState(listOf(stroke("ok"), stroke("bad", x = 30f))), 2L)
        val bad = db.strokeDao().all(info.id).first { it.id == "bad" }
        db.strokeDao().upsert(listOf(com.bsoft.bhandbook.data.local.StrokeEntity(bad.id, bad.canvasId, bad.expressionId, bad.width, bad.color, bad.orderIndex, byteArrayOf(1, 2, 3))))
        assertEquals(listOf("ok"), RoomStrokeRepository(db).strokes(info.id).map { it.id })
    }

    @Test fun largeCanvas_restores() = runBlocking {
        val info = repo.create("Big", CanvasBackground.WHITE, 1L)
        val many = (0 until 3_000).map { stroke("s$it", "e${it / 10}", it * 3f) }
        repo.save(info.id, view, CanvasState(many), 2L)
        assertEquals(3_000, repo.load(info.id)!!.state.strokes.size)
    }

    @Test fun mostRecent_isTheLastSaved() = runBlocking {
        val a = repo.create("A", CanvasBackground.WHITE, 1L); val b = repo.create("B", CanvasBackground.WHITE, 2L)
        repo.save(a.id, view, CanvasState(), 99L)
        assertEquals(a.id, repo.mostRecentId()); assertNotEquals(b.id, repo.mostRecentId())
    }
}
