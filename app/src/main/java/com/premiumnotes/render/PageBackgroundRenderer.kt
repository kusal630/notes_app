package com.premiumnotes.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.premiumnotes.model.PageBackground
import com.premiumnotes.model.PageBackgroundType

/**
 * Renders paper templates (ruled, grid, dotted, etc.) in world coordinates
 * (millimeters). The caller applies the viewport transform before drawing, and passes
 * the visible world region so only on-screen pattern lines are generated.
 */
object PageBackgroundRenderer {

    // Reused across frames to avoid per-draw allocations on the UI thread.
    private val linePaint = Paint().apply { isAntiAlias = true }

    fun drawBackground(canvas: Canvas, bg: PageBackground, pxPerMm: Float, worldClip: RectF) {
        val bgColor = (bg.colorArgb and 0xFFFFFFFF.toLong()).toInt()
        canvas.drawColor(bgColor)

        linePaint.color = bg.lineColorArgb.toInt()

        val fromX = worldClip.left.coerceAtLeast(0f)
        val fromY = worldClip.top.coerceAtLeast(0f)
        val toX = worldClip.right
        val toY = worldClip.bottom

        when (bg.type) {
            PageBackgroundType.BLANK -> Unit

            PageBackgroundType.RULED,
            PageBackgroundType.NARROW_RULED,
            PageBackgroundType.WIDE_RULED,
            -> {
                val spacing = spacingFor(bg)
                linePaint.strokeWidth = (0.4f * pxPerMm).coerceAtLeast(1f)
                var y = (fromY / spacing).toInt() * spacing
                while (y < toY) {
                    canvas.drawLine(fromX, y, toX, y, linePaint)
                    y += spacing
                }
                linePaint.strokeWidth = (0.6f * pxPerMm).coerceAtLeast(1f)
                val margin = 24f * pxPerMm
                if (margin >= fromX && margin <= toX) {
                    canvas.drawLine(margin, fromY, margin, toY, linePaint)
                }
            }

            PageBackgroundType.GRID,
            PageBackgroundType.SMALL_GRID,
            PageBackgroundType.GRAPH,
            PageBackgroundType.MATH,
            -> {
                val size = gridSizeFor(bg)
                linePaint.strokeWidth = (0.3f * pxPerMm).coerceAtLeast(1f)
                var x = (fromX / size).toInt() * size
                while (x <= toX) {
                    canvas.drawLine(x, fromY, x, toY, linePaint)
                    x += size
                }
                var y = (fromY / size).toInt() * size
                while (y <= toY) {
                    canvas.drawLine(fromX, y, toX, y, linePaint)
                    y += size
                }
            }

            PageBackgroundType.DOTTED -> {
                val spacing = bg.dotSpacingMm
                var x = (fromX / spacing).toInt() * spacing
                while (x <= toX) {
                    var y = (fromY / spacing).toInt() * spacing
                    while (y <= toY) {
                        canvas.drawCircle(x, y, (0.25f * pxPerMm).coerceAtLeast(0.8f), linePaint)
                        y += spacing
                    }
                    x += spacing
                }
            }

            PageBackgroundType.CORNELL -> {
                val spacing = spacingFor(bg)
                linePaint.strokeWidth = (0.4f * pxPerMm).coerceAtLeast(1f)
                var y = (fromY / spacing).toInt() * spacing
                while (y < toY) {
                    canvas.drawLine(fromX, y, toX, y, linePaint)
                    y += spacing
                }
                linePaint.strokeWidth = (0.8f * pxPerMm).coerceAtLeast(2f)
                val keyCol = 56f * pxPerMm
                canvas.drawLine(keyCol, fromY, keyCol, toY, linePaint)
                val headerRow = 56f * pxPerMm
                if (headerRow >= fromY && headerRow <= toY) {
                    canvas.drawLine(fromX, headerRow, toX, headerRow, linePaint)
                }
            }

            PageBackgroundType.MUSIC -> {
                linePaint.strokeWidth = (0.3f * pxPerMm).coerceAtLeast(1f)
                val staffGap = 1.6f * pxPerMm
                val staffHeight = 4 * staffGap
                var y = (fromY / staffHeight).toInt() * staffHeight
                while (y < toY) {
                    for (i in 0 until 5) {
                        val ly = y + i * staffGap
                        canvas.drawLine(fromX, ly, toX, ly, linePaint)
                    }
                    y += staffHeight + 4f * pxPerMm
                }
            }
        }
    }

    private fun spacingFor(bg: PageBackground): Float = when (bg.type) {
        PageBackgroundType.NARROW_RULED -> bg.lineSpacingMm * 0.6f
        PageBackgroundType.WIDE_RULED -> bg.lineSpacingMm * 1.5f
        else -> bg.lineSpacingMm
    }

    private fun gridSizeFor(bg: PageBackground): Float = when (bg.type) {
        PageBackgroundType.SMALL_GRID -> bg.gridSizeMm * 0.5f
        else -> bg.gridSizeMm
    }
}