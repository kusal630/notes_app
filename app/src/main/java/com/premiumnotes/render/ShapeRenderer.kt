package com.premiumnotes.render

import android.graphics.Paint
import android.graphics.Path
import com.premiumnotes.model.ShapeKind
import com.premiumnotes.model.ShapeObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders geometric [ShapeObject]s in world coordinates (millimeters). The caller
 * applies the viewport transform before drawing; [buildPath] produces the outline path
 * from the shape's two drag corners, so a single cached path renders identically at any
 * zoom.
 */
object ShapeRenderer {

    /** Builds the world-space outline path for a shape (two drag corners in [points]). */
    fun buildPath(shape: ShapeObject): Path {
        val path = Path()
        if (shape.points.size < 2) return path
        val x1 = shape.points[0].x
        val y1 = shape.points[0].y
        val x2 = shape.points[1].x
        val y2 = shape.points[1].y
        val left = min(x1, x2)
        val right = kotlin.math.max(x1, x2)
        val top = min(y1, y2)
        val bottom = kotlin.math.max(y1, y2)
        val w = right - left
        val h = bottom - top
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f

        when (shape.kind) {
            ShapeKind.LINE -> {
                path.moveTo(x1, y1)
                path.lineTo(x2, y2)
            }

            ShapeKind.ARROW -> {
                val len = hypot(x2 - x1, y2 - y1).coerceAtLeast(0.1f)
                val ux = (x2 - x1) / len
                val uy = (y2 - y1) / len
                val nx = -uy
                val ny = ux
                val head = (w.coerceAtLeast(h) * 0.25f).coerceAtLeast(2f)
                path.moveTo(x1, y1)
                path.lineTo(x2, y2)
                path.moveTo(x2, y2)
                path.lineTo(x2 - ux * head + nx * head * 0.5f, y2 - uy * head + ny * head * 0.5f)
                path.moveTo(x2, y2)
                path.lineTo(x2 - ux * head - nx * head * 0.5f, y2 - uy * head - ny * head * 0.5f)
            }

            ShapeKind.RECT -> path.addRect(left, top, right, bottom, Path.Direction.CW)

            ShapeKind.ROUNDED_RECT -> path.addRoundRect(
                left, top, right, bottom, min(w, h) * 0.2f, min(w, h) * 0.2f, Path.Direction.CW,
            )

            ShapeKind.CIRCLE -> {
                val r = min(w, h) / 2f
                path.addCircle(cx, cy, r.coerceAtLeast(0.1f), Path.Direction.CW)
            }

            ShapeKind.ELLIPSE -> path.addOval(left, top, right, bottom, Path.Direction.CW)

            ShapeKind.TRIANGLE -> {
                path.moveTo(cx, top)
                path.lineTo(left, bottom)
                path.lineTo(right, bottom)
                path.close()
            }

            ShapeKind.POLYGON -> {
                val r = min(w, h) / 2f
                val n = 6
                for (i in 0 until n) {
                    val a = -PI / 2.0 + 2.0 * PI * i / n
                    val px = cx + r * cos(a)
                    val py = cy + r * sin(a)
                    if (i == 0) path.moveTo(px.toFloat(), py.toFloat())
                    else path.lineTo(px.toFloat(), py.toFloat())
                }
                path.close()
            }

            ShapeKind.STAR -> {
                val outer = min(w, h) / 2f
                val inner = outer * 0.4f
                val points = 5
                for (i in 0 until points * 2) {
                    val r = if (i % 2 == 0) outer else inner
                    val a = -PI / 2.0 + PI * i / points
                    val px = cx + r * cos(a)
                    val py = cy + r * sin(a)
                    if (i == 0) path.moveTo(px.toFloat(), py.toFloat())
                    else path.lineTo(px.toFloat(), py.toFloat())
                }
                path.close()
            }
        }
        return path
    }

    /** Outline paint for a shape (stroke, world-unit width). */
    fun outlinePaint(shape: ShapeObject): Paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = shape.strokeWidthMm.coerceAtLeast(0.2f)
        color = (shape.colorArgb and 0xFFFFFFFF.toLong()).toInt()
    }
}