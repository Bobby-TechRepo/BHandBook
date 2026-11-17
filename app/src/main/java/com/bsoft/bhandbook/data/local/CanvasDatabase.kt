package com.bsoft.bhandbook.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "canvases")
data class CanvasEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val background: String,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/** Points are stored as one compact blob per stroke (see StrokeCodec); no per-point rows, no transient pointer events. */
@Entity(
    tableName = "strokes",
    foreignKeys = [ForeignKey(CanvasEntity::class, parentColumns = ["id"], childColumns = ["canvasId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("canvasId")],
)
class StrokeEntity(
    @androidx.room.PrimaryKey val id: String,
    val canvasId: String,
    val expressionId: String?,
    val width: Float,
    val color: Long,
    val orderIndex: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val points: ByteArray,
)

@Entity(
    tableName = "expressions",
    primaryKeys = ["canvasId", "id"],
    foreignKeys = [ForeignKey(CanvasEntity::class, parentColumns = ["id"], childColumns = ["canvasId"], onDelete = ForeignKey.CASCADE)],
)
data class ExpressionEntity(
    val canvasId: String,
    val id: String,
    val recognized: String?,
    val confidence: Float,
    val result: String?,
    val steps: String,
    val solutionVisible: Boolean,
    val status: String,
    val error: String?,
    val modifiedAt: Long,
    val strokeSignature: Int,
    val displayText: String?,
    val resultInline: Boolean,
)

data class StrokeOrder(val id: String, val orderIndex: Int)

@Dao
interface CanvasDao {
    @Query("SELECT * FROM canvases ORDER BY updatedAt DESC") suspend fun all(): List<CanvasEntity>
    @Query("SELECT * FROM canvases WHERE id = :id") suspend fun get(id: String): CanvasEntity?
    @Query("SELECT id FROM canvases ORDER BY updatedAt DESC LIMIT 1") suspend fun mostRecentId(): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: CanvasEntity)
    @Query("UPDATE canvases SET name = :name, updatedAt = :now WHERE id = :id") suspend fun rename(id: String, name: String, now: Long)
    @Query("UPDATE canvases SET updatedAt = :now, scale = :scale, offsetX = :ox, offsetY = :oy, background = :bg WHERE id = :id")
    suspend fun touch(id: String, now: Long, scale: Float, ox: Float, oy: Float, bg: String)
    @Query("DELETE FROM canvases WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface StrokeDao {
    @Query("SELECT * FROM strokes WHERE canvasId = :canvasId ORDER BY orderIndex") suspend fun all(canvasId: String): List<StrokeEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(items: List<StrokeEntity>)
    @Query("DELETE FROM strokes WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<String>)
    @Query("DELETE FROM strokes WHERE canvasId = :canvasId") suspend fun deleteAll(canvasId: String)
    @Query("UPDATE strokes SET orderIndex = :order WHERE id = :id") suspend fun updateOrder(id: String, order: Int)
}

@Dao
interface ExpressionDao {
    @Query("SELECT * FROM expressions WHERE canvasId = :canvasId") suspend fun all(canvasId: String): List<ExpressionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(items: List<ExpressionEntity>)
    @Query("DELETE FROM expressions WHERE canvasId = :canvasId AND id IN (:ids)") suspend fun deleteByIds(canvasId: String, ids: List<String>)
    @Query("SELECT id FROM expressions WHERE canvasId = :canvasId") suspend fun ids(canvasId: String): List<String>
}

@Database(entities = [CanvasEntity::class, StrokeEntity::class, ExpressionEntity::class], version = 1, exportSchema = true)
abstract class CanvasDatabase : RoomDatabase() {
    abstract fun canvasDao(): CanvasDao
    abstract fun strokeDao(): StrokeDao
    abstract fun expressionDao(): ExpressionDao
}
