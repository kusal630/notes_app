package com.premiumnotes.render

import android.graphics.RectF
import com.premiumnotes.model.Point
import com.premiumnotes.model.ShapeKind
import com.premiumnotes.model.ShapeObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShapeRendererTest {

    private fun bounds(kind: ShapeKind, x1: Float, y1: Float, x2: Float, y2: Float): RectF {
        val shape = ShapeObject(
            id = 1, kind = kind, x = x1, y = y1, points = listOf(Point(x1, y1), Point(x2, y2)),
        )
        val rect = RectF()
        ShapeRenderer.buildPath(shape).computeBounds(rect, true)
        return rect
    }

    @Test
    fun rectangleFillsTheDragBox() {
        val b = bounds(ShapeKind.RECT, 10f, 20f, 50f, 80f)
        assertEquals(10f, b.left, 0.01f)
        assertEquals(20f, b.top, 0.01f)
        assertEquals(50f, b.right, 0.01f)
        assertEquals(80f, b.bottom, 0.01f)
    }

    @Test
    fun triangleFillsTheDragBox() {
        val b = bounds(ShapeKind.TRIANGLE, 0f, 0f, 20f, 20f)
        assertEquals(0f, b.left, 0.01f)
        assertEquals(0f, b.top, 0.01f)
        assertEquals(20f, b.right, 0.01f)
        assertEquals(20f, b.bottom, 0.01f)
    }

    @Test
    fun circleFitsInsideTheDragBox() {
        val b = bounds(ShapeKind.CIRCLE, 0f, 0f, 40f, 20f)
        // Center at (20,10), radius 10.
        assertEquals(10f, b.left, 0.5f)
        assertEquals(0f, b.top, 0.5f)
        assertEquals(30f, b.right, 0.5f)
        assertEquals(20f, b.bottom, 0.5f)
    }

    @Test
    fun reversedDragNormalizesBounds() {
        val b = bounds(ShapeKind.ELLIPSE, 50f, 80f, 10f, 20f)
        assertEquals(10f, b.left, 0.01f)
        assertEquals(20f, b.top, 0.01f)
        assertEquals(50f, b.right, 0.01f)
        assertEquals(80f, b.bottom, 0.01f)
    }

    @Test
    fun lineConnectsBothDragPoints() {
        val shape = ShapeObject(
            id = 1, kind = ShapeKind.LINE, x = 5f, y = 6f, points = listOf(Point(5f, 6f), Point(15f, 26f)),
        )
        val rect = RectF()
        ShapeRenderer.buildPath(shape).computeBounds(rect, true)
        assertEquals(5f, rect.left, 0.01f)
        assertEquals(6f, rect.top, 0.01f)
        assertEquals(15f, rect.right, 0.01f)
        assertEquals(26f, rect.bottom, 0.01f)
    }
}
