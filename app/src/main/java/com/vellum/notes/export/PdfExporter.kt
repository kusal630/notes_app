package com.vellum.notes.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.RectF
import com.vellum.notes.model.ImageObject
import com.vellum.notes.model.PageBackground
import com.vellum.notes.model.PageContent
import com.vellum.notes.model.PenType
import com.vellum.notes.model.ShapeObject
import com.vellum.notes.model.Stroke
import com.vellum.notes.render.InkRenderer
import com.vellum.notes.render.PageBackgroundRenderer
import com.vellum.notes.render.ShapeRenderer
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
    ): File? = export(context, pageId, content, background, null, null)

    /**
     * Full export: paper background (or rasterized PDF page beneath), then images
     * (between paper and ink), then highlighters/shapes/ink on top. For PDF-backed
     * notes [pdfBackground] is the original page and ink draws over it.
     *
     * [imageBitmaps] maps [ImageObject.fileRef] to a decoded bitmap; missing entries
     * are skipped so export never fails on a deleted file.
     */
    fun export(
        context: Context,
        pageId: Long,
        content: PageContent,
        background: PageBackground,
        pdfBackground: Bitmap?,
        imageBitmaps: Map<String, Bitmap>?,
    ): File? {
        val bounds = contentBounds(content)
        val widthMm: Float
        val heightMm: Float
        val left: Float
        val top: Float
        if (pdfBackground != null) {
            widthMm = 210f
            heightMm = 210f * pdfBackground.height / pdfBackground.width.toFloat()
            left = 0f
            top = 0f
        } else {
            widthMm = (bounds.width() + MARGIN_MM * 2f).coerceIn(160f, 600f)
            heightMm = (bounds.height() + MARGIN_MM * 2f).coerceIn(120f, 1400f)
            left = bounds.left - MARGIN_MM
            top = bounds.top - MARGIN_MM
        }

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

                if (pdfBackground != null && !pdfBackground.isRecycled) {
                    val dst = RectF(0f, 0f, widthMm, heightMm)
                    canvas.drawBitmap(
                        pdfBackground,
                        Rect(0, 0, pdfBackground.width, pdfBackground.height),
                        dst,
                        null,
                    )
                }

                // Z-order: paper/pdf < images < text < highlighters < shapes < ink.
                if (imageBitmaps != null) {
                    for (im in content.imageObjects.sortedBy { it.zOrder }) {
                        val bmp = imageBitmaps[im.fileRef] ?: continue
                        if (bmp.isRecycled) continue
                        canvas.drawBitmap(
                            bmp,
                            Rect(0, 0, bmp.width, bmp.height),
                            RectF(im.x, im.y, im.x + im.width, im.y + im.height),
                            null,
                        )
                    }
                }

                // Text objects: world-space boxes drawn between images and ink.
                for (t in content.textObjects) {
                    if (t.text.isBlank()) continue
                    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = t.colorArgb.toInt()
                        textSize = t.fontSizeMm
                        typeface = if (t.bold) android.graphics.Typeface.create(t.fontFamily, android.graphics.Typeface.BOLD)
                                   else android.graphics.Typeface.create(t.fontFamily, android.graphics.Typeface.NORMAL)
                        isSubpixelText = true
                    }
                    canvas.save()
                    canvas.rotate(t.rotation, t.x, t.y)
                    val maxW = t.width.coerceAtLeast(1f)
                    val lines = ArrayList<String>()
                    for (raw in t.text.split('\n')) {
                        var line = ""
                        for (word in raw.split(' ')) {
                            val candidate = if (line.isEmpty()) word else "$line $word"
                            if (textPaint.measureText(candidate) > maxW && line.isNotEmpty()) {
                                lines += line; line = word
                            } else line = candidate
                        }
                        lines += line
                    }
                    var y = t.y + t.fontSizeMm
                    for (line in lines) {
                        canvas.drawText(line, t.x, y, textPaint)
                        y += t.fontSizeMm * 1.35f
                    }
                    canvas.restore()
                }

                // Z-order: highlighters below ink (same rule as the canvas).
                val highlighters = ArrayList<Stroke>()
                val ink = ArrayList<Stroke>()
                for (stroke in content.strokes) {
                    if (stroke.style.type == PenType.HIGHLIGHTER) highlighters += stroke else ink += stroke
                }
                val renderer = InkRenderer()
                for (stroke in highlighters) renderer.drawStroke(canvas, stroke, 1f)
                for (shape in content.shapeObjects) {
                    val path = ShapeRenderer.buildPath(shape)
                    ShapeRenderer.fillPaint(shape)?.let { canvas.drawPath(path, it) }
                    canvas.drawPath(path, ShapeRenderer.outlinePaint(shape))
                }
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
    fun contentBounds(content: PageContent): RectF {
        val rect = RectF()
        var set = false
        fun include(left: Float, top: Float, right: Float, bottom: Float) {
            if (!set) {
                rect.set(left, top, right, bottom)
                set = true
            } else {
                rect.union(left, top)
                rect.union(right, bottom)
            }
        }
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
        for (shape in content.shapeObjects) {
            val a = shape.points.getOrNull(0)
            val b = shape.points.getOrNull(1)
            if (a != null && b != null) {
                val left = kotlin.math.min(a.x, b.x)
                val top = kotlin.math.min(a.y, b.y)
                val right = kotlin.math.max(a.x, b.x)
                val bottom = kotlin.math.max(a.y, b.y)
                include(left, top, right, bottom)
            }
        }
        for (im in content.imageObjects) {
            include(im.x, im.y, im.x + im.width, im.y + im.height)
        }
        for (t in content.textObjects) {
            include(t.x, t.y, t.x + t.width, t.y + t.height)
        }
        if (!set) {
            rect.set(0f, 0f, 210f, 297f)
        }
        return rect
    }
}