package com.premiumnotes.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.PenType
import com.premiumnotes.model.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Renders strokes onto a Canvas. Strokes are defined in world coordinates (document
 * millimeters); the caller applies the viewport transform via [Canvas.scale]/[Canvas.translate]
 * or passes an explicit [scalePxPerMm].
 *
 * Pen rendering:
 *  - BALLPOINT / MONOLINE / MARKER / HIGHLIGHTER / PENCIL: stroked Path.
 *  - FOUNTAIN: variable width (velocity-based) filled polygon.
 *  - CALLIGRAPHY: variable width driven by stroke direction vs a fixed nib angle.
 */
class InkRenderer {

    fun drawStroke(canvas: Canvas, stroke: Stroke, scalePxPerMm: Float) {
        val paint = paintFor(stroke.style)
        when (stroke.style.type) {
            PenType.FOUNTAIN -> drawVariableWidth(canvas, stroke, scalePxPerMm, fountainWidth(stroke))
            PenType.CALLIGRAPHY -> drawVariableWidth(canvas, stroke, scalePxPerMm, calligraphyWidth(stroke))
            PenType.PENCIL -> drawPencil(canvas, stroke, scalePxPerMm, paint)
            else -> {
                val path = buildPath(stroke)
                paint.strokeWidth = stroke.style.widthMm * scalePxPerMm
                if (paint.strokeWidth < 0.5f) paint.strokeWidth = 0.5f
                canvas.drawPath(path, paint)
            }
        }
    }

    /** Builds a plain stroked Path from a stroke's packed points. */
    fun buildPath(stroke: Stroke): Path {
        val path = Path()
        val pts = stroke.pointsPacked
        var i = 0
        var started = false
        while (i + 1 < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
            i += 2
        }
        return path
    }

    fun paintFor(penStyle: PenStyle): Paint {
        val alpha = (penStyle.opacity.coerceIn(0f, 1f) * 255).toInt()
        return Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = (penStyle.colorArgb and 0xFFFFFF).toInt() or (alpha shl 24)
        }
    }

    // --- Variable width pens -------------------------------------------------

    private fun fountainWidth(stroke: Stroke): (Int, FloatArray, Int) -> Float {
        val base = stroke.style.widthMm
        val pts = stroke.pointsPacked
        return { index, _, n ->
            if (index <= 0 || index >= n - 1) {
                base
            } else {
                val dx = pts[index * 2] - pts[index * 2 - 2]
                val dy = pts[index * 2 + 1] - pts[index * 2 - 1]
                val speed = hypot(dx, dy)
                val factor = (0.65f + 0.7f * (speed / 6f)).coerceIn(0.45f, 1.4f)
                base * factor
            }
        }
    }

    private fun calligraphyWidth(stroke: Stroke): (Int, FloatArray, Int) -> Float {
        val base = stroke.style.widthMm
        val nibAngleRad = stroke.style.nibAngleDegrees.toDouble() * PI / 180.0
        val nibDirX = cos(nibAngleRad).toFloat()
        val nibDirY = sin(nibAngleRad).toFloat()
        val pts = stroke.pointsPacked
        return { index, _, n ->
            if (index <= 0 || index >= n - 1) {
                base
            } else {
                val dirX = pts[index * 2] - pts[index * 2 - 2]
                val dirY = pts[index * 2 + 1] - pts[index * 2 - 1]
                val len = hypot(dirX, dirY)
                if (len < 0.0001f) {
                    base * 0.2f
                } else {
                    val ux = dirX / len
                    val uy = dirY / len
                    // Component of motion along the nib: thin when writing along the nib.
                    val along = abs(ux * nibDirX + uy * nibDirY)
                    base * (0.25f + 0.85f * (1f - along))
                }
            }
        }
    }

    /**
     * Renders a stroke as a filled polygon between the left/right edges defined by a
     * per-sample width function. Used for fountain/calligraphy variable width.
     */
    private fun drawVariableWidth(
        canvas: Canvas,
        stroke: Stroke,
        scalePxPerMm: Float,
        widthAt: (Int, FloatArray, Int) -> Float,
    ) {
        val pts = stroke.pointsPacked
        val n = pts.size / 2
        if (n < 2) return
        val paint = Paint(paintFor(stroke.style)).apply {
            style = Paint.Style.FILL
        }
        val path = Path()
        val leftX = FloatArray(n)
        val leftY = FloatArray(n)
        val rightX = FloatArray(n)
        val rightY = FloatArray(n)

        // Precompute normals from mid-segment directions.
        for (i in 0 until n) {
            val ix = i * 2
            val iy = i * 2 + 1
            val prev = if (i > 0) i - 1 else 0
            val next = if (i < n - 1) i + 1 else n - 1
            val dx = pts[next * 2] - pts[prev * 2]
            val dy = pts[next * 2 + 1] - pts[prev * 2 + 1]
            val len = hypot(dx, dy).coerceAtLeast(0.0001f)
            val nx = -dy / len
            val ny = dx / len
            val half = widthAt(i, pts, n) * scalePxPerMm / 2f
            leftX[i] = pts[ix] + nx * half
            leftY[i] = pts[iy] + ny * half
            rightX[i] = pts[ix] - nx * half
            rightY[i] = pts[iy] - ny * half
        }

        path.moveTo(leftX[0], leftY[0])
        for (i in 1 until n) path.lineTo(leftX[i], leftY[i])
        for (i in n - 1 downTo 0) path.lineTo(rightX[i], rightY[i])
        path.close()
        canvas.drawPath(path, paint)

        // Round caps at both ends for a natural look.
        if (n > 1) {
            val capRadius = widthAt(0, pts, n) * scalePxPerMm / 2f
            canvas.drawCircle(pts[0], pts[1], capRadius.coerceAtLeast(0.2f), paint)
            val lastW = widthAt(n - 1, pts, n) * scalePxPerMm / 2f
            canvas.drawCircle(pts[pts.size - 2], pts[pts.size - 1], lastW.coerceAtLeast(0.2f), paint)
        }
    }

    private fun drawPencil(canvas: Canvas, stroke: Stroke, scalePxPerMm: Float, base: Paint) {
        val path = buildPath(stroke)
        base.strokeWidth = stroke.style.widthMm * scalePxPerMm
        // Graphite grain: two low-alpha offset passes with deterministic jitter derived
        // from the stroke id, so export reproduces the same texture.
        val seed = (stroke.id * 7919L).toInt()
        val jitter = (0.06f + (seed and 0x1F) * 0.002f) * scalePxPerMm
        val grain = Paint(base).apply { alpha = (alpha * 0.5f).toInt() }
        canvas.drawPath(path, grain)
        canvas.save()
        canvas.translate(jitter, jitter * 0.5f)
        canvas.drawPath(path, grain)
        canvas.restore()
        canvas.drawPath(path, base)
    }
}