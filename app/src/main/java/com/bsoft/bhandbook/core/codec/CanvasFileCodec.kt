package com.bsoft.bhandbook.core.codec

import com.bsoft.bhandbook.core.graphics.CanvasTransform
import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.solver.SolutionStep

/** A canvas as it is written to (and read from) a backup file. */
data class CanvasFile(
    val name: String, val background: CanvasBackground, val scale: Float, val offsetX: Float, val offsetY: Float,
    val state: CanvasState, val graphs: GraphPanelState,
)

sealed interface CanvasFileResult {
    /** [skipped]: damaged items (strokes, expressions) that were left out. */
    data class Ok(val file: CanvasFile, val skipped: Int) : CanvasFileResult
    data class Failed(val reason: String) : CanvasFileResult
}

/**
 * The backup file: JSON with a format marker and a version, so that a canvas can be restored on another phone or shared as an editable file.
 * Reading is defensive because the file comes from outside the app: sizes are bounded, numbers must be finite and are clamped, references are
 * repaired or dropped, and nothing here throws. Importing always creates a NEW canvas ([withFreshIds]), so a file can never overwrite one.
 */
object CanvasFileCodec {
    const val FORMAT = "bhandbook-canvas"
    const val VERSION = 1
    const val MAX_CHARS = 30_000_000
    const val MAX_STROKES = 200_000
    const val MAX_POINTS = 6_000_000
    const val MAX_EXPRESSIONS = 50_000
    const val MAX_STEPS = 500
    const val MAX_TEXT = 10_000
    const val MAX_NAME = 80
    const val MIN_WIDTH = 0.05f
    const val MAX_WIDTH = 500f
    private const val DEFAULT_WIDTH = 3f
    private const val DEFAULT_NAME = "Imported canvas"
    private const val LIMIT = 1e9

    // ------------------------------------------------------------------------------------------------ writing
    fun encode(f: CanvasFile): String {
        val sb = StringBuilder(1 shl 16)
        sb.append("{\"format\":\"").append(FORMAT).append("\",\"v\":").append(VERSION)
        sb.append(",\"name\":").append(JsonWriter.quote(f.name)).append(",\"background\":").append(JsonWriter.quote(f.background.name))
        sb.append(",\"view\":{\"scale\":").append(num(f.scale, 1f)).append(",\"offsetX\":").append(num(f.offsetX, 0f)).append(",\"offsetY\":").append(num(f.offsetY, 0f)).append('}')
        sb.append(",\"strokes\":[")
        var first = true
        for (s in f.state.strokes) {
            val pts = s.points.filter { it.x.isFinite() && it.y.isFinite() }
            if (pts.isEmpty()) continue
            if (!first) sb.append(','); first = false
            sb.append("{\"id\":").append(JsonWriter.quote(s.id)).append(",\"e\":").append(s.expressionId?.let { JsonWriter.quote(it) } ?: "null")
            sb.append(",\"w\":").append(num(s.width, DEFAULT_WIDTH)).append(",\"c\":").append(s.color).append(",\"p\":[")
            pts.forEachIndexed { i, p -> if (i > 0) sb.append(','); sb.append(p.x).append(',').append(p.y).append(',').append(p.t).append(',').append(num(p.pressure, 1f)) }
            sb.append("]}")
        }
        sb.append("],\"expressions\":[")
        first = true
        for (e in f.state.expressions.values) {
            if (!first) sb.append(','); first = false
            sb.append("{\"id\":").append(JsonWriter.quote(e.id))
            sb.append(",\"recognized\":").append(nullable(e.recognized)).append(",\"display\":").append(nullable(e.displayText)).append(",\"result\":").append(nullable(e.result)).append(",\"error\":").append(nullable(e.error))
            sb.append(",\"inline\":").append(e.resultInline).append(",\"visible\":").append(e.solutionVisible).append(",\"status\":").append(JsonWriter.quote(e.status.name))
            sb.append(",\"confidence\":").append(num(e.confidence, 0f)).append(",\"modifiedAt\":").append(e.modifiedAt).append(",\"sig\":").append(e.strokeSignature).append(",\"steps\":[")
            e.steps.forEachIndexed { i, st ->
                if (i > 0) sb.append(',')
                sb.append("{\"n\":").append(st.number).append(",\"expr\":").append(JsonWriter.quote(st.expression)).append(",\"why\":").append(JsonWriter.quote(st.explanation)).append(",\"res\":").append(JsonWriter.quote(st.resulting)).append('}')
            }
            sb.append("]}")
        }
        sb.append("],\"graphs\":").append(JsonWriter.quote(GraphPanelCodec.encode(f.graphs))).append('}')
        return sb.toString()
    }

    private fun nullable(s: String?) = s?.let { JsonWriter.quote(it) } ?: "null"
    private fun num(v: Float, fallback: Float) = (if (v.isFinite()) v else fallback).toString()

    // ------------------------------------------------------------------------------------------------ reading
    fun decode(text: String): CanvasFileResult = try { read(text) } catch (e: Exception) { CanvasFileResult.Failed("This file is damaged or incomplete, so it cannot be imported.") } catch (e: StackOverflowError) { CanvasFileResult.Failed("This file is damaged.") }

    private fun fail(reason: String) = CanvasFileResult.Failed(reason)

    private fun read(text: String): CanvasFileResult {
        if (text.length > MAX_CHARS) return fail("This file is too large to import.")
        val root = MiniJson.parse(text.removePrefix("\uFEFF")) as? Map<*, *> ?: return fail("This is not a canvas file.")
        if (root["format"] != FORMAT) return fail("This is not a canvas file from this app.")
        val version = (root["v"] as? Double)?.toInt() ?: return fail("This is not a canvas file from this app.")
        if (version > VERSION) return fail("This file was made by a newer version of the app. Update the app to open it.")
        if (version != VERSION) return fail("This file version is not supported.")

        var skipped = 0
        val strokeList = root["strokes"] as? List<*> ?: emptyList<Any?>()
        val exprList = root["expressions"] as? List<*> ?: emptyList<Any?>()
        if (strokeList.size > MAX_STROKES) return fail("This file contains too many strokes.")
        if (exprList.size > MAX_EXPRESSIONS) return fail("This file contains too many expressions.")

        // expressions first, so strokes can be checked against them
        val expressions = LinkedHashMap<String, ExpressionObject>()
        for (raw in exprList) {
            val e = expression(raw as? Map<*, *>)
            if (e == null || e.id in expressions) { skipped++; continue }
            expressions[e.id] = e
        }
        val strokes = ArrayList<Stroke>(); val seen = HashSet<String>(); var totalPoints = 0L
        for (raw in strokeList) {
            val m = raw as? Map<*, *>
            val id = (m?.get("id") as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT }
            val flat = m?.get("p") as? List<*>
            if (m == null || id == null || flat == null || !seen.add(id)) { skipped++; continue }
            totalPoints += flat.size / 4
            if (totalPoints > MAX_POINTS) return fail("This file contains too many points.")
            val points = ArrayList<StrokePoint>(flat.size / 4)
            var i = 0
            while (i + 3 < flat.size) {
                val x = flat[i] as? Double; val y = flat[i + 1] as? Double; val t = flat[i + 2] as? Double; val pr = flat[i + 3] as? Double
                if (x != null && y != null && x.isFinite() && y.isFinite() && kotlin.math.abs(x) < LIMIT && kotlin.math.abs(y) < LIMIT)
                    points += StrokePoint(x.toFloat(), y.toFloat(), (t?.takeIf { it.isFinite() } ?: 0.0).toLong(), (pr?.takeIf { it.isFinite() } ?: 1.0).coerceIn(0.0, 1.0).toFloat())
                i += 4
            }
            if (points.isEmpty()) { skipped++; continue }
            val w = (m["w"] as? Double)?.takeIf { it.isFinite() }?.toFloat()?.coerceIn(MIN_WIDTH, MAX_WIDTH) ?: DEFAULT_WIDTH
            val ref = (m["e"] as? String)?.takeIf { it in expressions }               // an unknown expression: the ink is kept as plain ink
            strokes += Stroke(id, points, w, (m["c"] as? Double)?.takeIf { it.isFinite() }?.toLong() ?: 0xFF000000L, ref)
        }
        val used = strokes.mapNotNull { it.expressionId }.toSet()
        val kept = expressions.filterKeys { it in used }
        skipped += expressions.size - kept.size                                           // an expression with no ink is not an expression

        val view = root["view"] as? Map<*, *>
        fun viewNumber(key: String, fallback: Float) = (view?.get(key) as? Double)?.takeIf { it.isFinite() && kotlin.math.abs(it) < LIMIT }?.toFloat() ?: fallback
        val scale = viewNumber("scale", 1f).coerceIn(CanvasTransform.MIN_SCALE, CanvasTransform.MAX_SCALE)
        val rawPanels = (root["graphs"] as? String)?.let { GraphPanelCodec.decode(it) } ?: GraphPanelState()
        val panels = GraphPanelState(rawPanels.visible.filter { it in kept }.toSet(), rawPanels.offsets.filterKeys { it in kept }, rawPanels.scales.filterKeys { it in kept }, rawPanels.views.filterKeys { it in kept })
        val name = (root["name"] as? String)?.trim().orEmpty().take(MAX_NAME).trim().ifEmpty { DEFAULT_NAME }
        val background = runCatching { CanvasBackground.valueOf(root["background"] as? String ?: "") }.getOrDefault(CanvasBackground.GRID)
        return CanvasFileResult.Ok(CanvasFile(name, background, scale, viewNumber("offsetX", 0f), viewNumber("offsetY", 0f), CanvasState(strokes, kept), panels), skipped)
    }

    private fun expression(m: Map<*, *>?): ExpressionObject? {
        val id = (m?.get("id") as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT } ?: return null
        fun text(key: String) = (m[key] as? String)?.take(MAX_TEXT)
        val steps = ArrayList<SolutionStep>()
        for (raw in (m["steps"] as? List<*>).orEmpty().take(MAX_STEPS)) {
            val s = raw as? Map<*, *> ?: continue
            val n = (s["n"] as? Double)?.takeIf { it.isFinite() }?.toInt() ?: continue
            val expr = (s["expr"] as? String)?.take(MAX_TEXT) ?: continue; val why = (s["why"] as? String)?.take(MAX_TEXT) ?: continue; val res = (s["res"] as? String)?.take(MAX_TEXT) ?: continue
            steps += SolutionStep(n, expr, why, res)
        }
        return ExpressionObject(
            id = id, recognized = text("recognized"), confidence = ((m["confidence"] as? Double)?.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
            result = text("result"), steps = steps, solutionVisible = m["visible"] as? Boolean ?: true,
            status = runCatching { ProcessingStatus.valueOf(m["status"] as? String ?: "") }.getOrDefault(ProcessingStatus.IDLE), error = text("error"),
            modifiedAt = (m["modifiedAt"] as? Double)?.takeIf { it.isFinite() }?.toLong() ?: 0L, strokeSignature = (m["sig"] as? Double)?.takeIf { it.isFinite() }?.toInt() ?: 0,
            displayText = text("display"), resultInline = m["inline"] as? Boolean ?: false,
        )
    }

    // ------------------------------------------------------------------------------------------------ importing
    /** The same canvas with every stroke and expression id replaced (and the graph panels re-keyed), so that it can be added without touching anything. */
    fun withFreshIds(file: CanvasFile, newId: () -> String): CanvasFile {
        val map = file.state.expressions.keys.associateWith { newId() }
        val strokes = file.state.strokes.map { it.copy(id = newId(), expressionId = it.expressionId?.let(map::get)) }
        val expressions = file.state.expressions.entries.associate { (k, e) -> map.getValue(k) to e.copy(id = map.getValue(k)) }
        val g = file.graphs
        val graphs = GraphPanelState(g.visible.mapNotNull(map::get).toSet(), g.offsets.mapNotNull { (k, v) -> map[k]?.let { it to v } }.toMap(), g.scales.mapNotNull { (k, v) -> map[k]?.let { it to v } }.toMap(), g.views.mapNotNull { (k, v) -> map[k]?.let { it to v } }.toMap())
        return file.copy(state = CanvasState(strokes, expressions), graphs = graphs)
    }
}
