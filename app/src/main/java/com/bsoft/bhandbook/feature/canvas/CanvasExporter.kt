package com.bsoft.bhandbook.feature.canvas

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.FileProvider
import com.bsoft.bhandbook.core.graphics.CanvasTransform
import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.domain.editor.CanvasExport
import com.bsoft.bhandbook.domain.editor.ExpressionLayout
import java.io.File

enum class ExportFormat(val extension: String, val mime: String) { PNG("png", "image/png"), PDF("pdf", "application/pdf") }

/**
 * Shares the whole canvas as an image (PNG with a transparent background) or a PDF. The drawing code is the one the screen uses, pointed at an
 * offscreen bitmap or a PDF page, so what is shared looks like what is on screen (without selection outlines, status hints or resize grips).
 * Files are written under the app's cache and handed to the share sheet through a FileProvider; nothing is uploaded by the app.
 */
object CanvasExporter {
    private const val TAG = "CanvasExporter"
    private const val DIR = "shared"
    private const val PNG_MAX_SIDE = 3072      // pixels: about 38 MB at the limit
    private const val PNG_MAX_SCALE = 2f
    private const val PDF_MAX_SIDE = 1800      // points
    private const val PDF_MAX_SCALE = 1f
    private const val KEEP_MS = 24L * 60 * 60 * 1000

    /** Dark ink on a light panel regardless of the app theme: a shared picture has no dark mode and must read on any backdrop. */
    private val PALETTE = RenderPalette(
        background = Color.White, grid = Color(0x22000000), ink = Color(0xFF111111), result = Color(0xFF1E7B34),
        steps = Color(0xFF555B66), warning = Color(0xFFB26A00), error = Color(0xFFB3261E), selection = Color(0xFF1A5FD0),
    )

    /** Renders and opens the share sheet. [density] must be the screen's density: it is the one the [measurer] converts text sizes with. */
    fun share(context: Context, state: CanvasUiState, measurer: TextMeasurer, density: Density, format: ExportFormat) {
        try {
            val file = render(context, state, measurer, density, format)
            if (file == null) { toast(context, "Nothing to share yet"); return }
            send(context, file, format, state.canvas?.name ?: "Calculations")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "export ran out of memory", e); toast(context, "The canvas is too large to export as an image")
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e); toast(context, "Could not create the file")
        }
    }

    private fun render(context: Context, state: CanvasUiState, measurer: TextMeasurer, density: Density, format: ExportFormat): File? {
        val layouts = ExpressionLayout.all(state.strokes, state.expressions, state.insights.systems, state.graphs, state.insights.graphable, state.graphOffsets, state.graphScales, state.settings.graph.defaultSize)
        val area = CanvasExport.area(state.strokes, layouts) ?: return null
        val plan = if (format == ExportFormat.PNG) CanvasExport.plan(area, PNG_MAX_SIDE, PNG_MAX_SCALE) else CanvasExport.plan(area, PDF_MAX_SIDE, PDF_MAX_SCALE)

        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        pruneOld(dir)
        val file = File(dir, CanvasExport.fileName(state.canvas?.name.orEmpty(), format.extension))

        val transform = CanvasTransform(plan.scale, Vec2(plan.offsetX, plan.offsetY))
        val texts = TextCache(measurer, density)
        val paths = StrokePathCache()
        val statuses = state.expressions.mapValues { it.value.status }
        val content: DrawScope.() -> Unit = {
            drawStrokes(state.strokes, paths, transform, PALETTE, emptySet(), Vec2.Zero)
            drawExpressionOverlays(
                layouts, statuses, null, transform, PALETTE, texts, emptySet(), Vec2.Zero, state.insights.systems, state.graphData,
                null, Vec2.Zero, state.settings.graph, null, 1f, forExport = true,
            )
        }
        val size = Size(plan.width.toFloat(), plan.height.toFloat())

        if (format == ExportFormat.PNG) {
            val image = ImageBitmap(plan.width, plan.height)                       // ARGB, starts fully transparent: no background is drawn
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), size, content)
            file.outputStream().use { check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" } }
        } else {
            val doc = PdfDocument()
            try {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(plan.width, plan.height, 1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)                // a PDF page cannot be transparent
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(page.canvas), size, content)
                doc.finishPage(page)
                file.outputStream().use { doc.writeTo(it) }
            } finally { doc.close() }
        }
        return file
    }

    /** Writes a backup file under the share folder (call off the main thread). */
    fun writeBackup(context: Context, fileName: String, text: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        pruneOld(dir)
        return File(dir, fileName).also { it.writeText(text, Charsets.UTF_8) }
    }

    /** Offers a backup file to the share sheet (so it can be saved to Drive, Files, sent to another phone ...). */
    fun sendBackup(context: Context, file: File, title: String) = sendFile(context, file, "application/octet-stream", title, "Back up canvas")

    private fun send(context: Context, file: File, format: ExportFormat, title: String) =
        sendFile(context, file, format.mime, title, "Share " + if (format == ExportFormat.PNG) "image" else "PDF")

    private fun sendFile(context: Context, file: File, mime: String, title: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shared files are only needed while the share sheet is open; older ones are removed so the cache does not grow. */
    private fun pruneOld(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
