package com.premiumnotes.export

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.graphics.RectF
import com.premiumnotes.model.PageBackground
import com.premiumnotes.model.PageContent
import com.premiumnotes.model.PenType
import com.premiumnotes.model.Stroke
import com.premiumnotes.render.InkRenderer
import com.premiumnotes.render.PageBackgroundRenderer
import java.io.File

/**
 * Exports a page to PDF using [android.graphics.pdf.PdfDocument]. The document model is
 * stored in world millimeters, so rendering is a direct 1:1 mapping into PDF points
 * (72 pt / 25.4 mm) — no rasterization, vector output keeps handwriting crisp at any
 * zoom. Highlighter strokes render below ink, matching on-canvas z-order.
 */
object PdfExporter {

    private const val MM_TO_PT = 72f / 25.4f
    private const val MARGIN_MM = 20f

    fun export(
        context: Context,
        pageId: Long,
        content: PageContent,
        background: PageBackground,
    ): File? {
        val bounds = contentBounds(content)
        val widthMm = (bounds.width() + MARGIN_MM * 2f).coerceIn(160f, 600f)
        val heightMm = (bounds.height() + MARGIN_MM * 2f).coerceIn(120f, 1400f)
        val left = bounds.left - MARGIN_MM
        val top = bounds.top - MARGIN_MM

        val dir = context.filesDir.resolve("exports")
        dir.mkdirs()
        val file = File(dir, "page-$pageId-${System.currentTimeMillis()}.pdf")

        return try {
            val document = PdfDocument()
            try {
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(
                        (widthMm * MM_TO_PT).toInt(),
                        (heightMm * MM_TO_PT).toInt(),
                        1,
                    ).create()
                )
                val canvas = page.canvas

                // World mm -> PDF points, anchored so content bounds start at the page origin.
                canvas.save()
                canvas.translate(-left * MM_TO_PT, -top * MM_TO_PT)
                canvas.scale(MM_TO_PT, MM_TO_PT)

                PageBackgroundRenderer.drawBackground(
                    canvas,
                    background,
                    pxPerMm = 1f,
                    worldClip = RectF(left, top, left + widthMm, top + heightMm),
                )

                // Z-order: highlighters below ink (same rule as the canvas).
                val highlighters = ArrayList<Stroke>()
                val ink = ArrayList<Stroke>()
                for (stroke in content.strokes) {
                    if (stroke.style.type == PenType.HIGHLIGHTER) highlighters += stroke else ink += stroke
                }
                val renderer = InkRenderer()
                for (stroke in highlighters) renderer.drawStroke(canvas, stroke, 1f)
                for (stroke in ink) renderer.drawStroke(canvas, stroke, 1f)

                canvas.restore()
                document.finishPage(page)
                file.outputStream().use { document.writeTo(it) }
            } finally {
                document.close()
            }
            file
        } catch (t: Throwable) {
            null
        }
    }

    /** Bounding box of all page content in world mm; falls back to an A4-ish region. */
    private fun contentBounds(content: PageContent): RectF {
        val rect = RectF()
        var set = false
        for (stroke in content.strokes) {
            val pts = stroke.pointsPacked
            var i = 0
            while (i + 1 < pts.size) {
                if (!set) {
                    rect.set(pts[i], pts[i + 1], pts[i], pts[i + 1])
                    set = true
                } else {
                    rect.union(pts[i], pts[i + 1])
                }
                i += 2
            }
        }
        if (!set) {
            rect.set(0f, 0f, 210f, 297f)
        }
        return rect
    }
}