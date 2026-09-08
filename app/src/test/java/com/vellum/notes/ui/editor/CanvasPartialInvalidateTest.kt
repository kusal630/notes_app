package com.vellum.notes.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import com.vellum.notes.input.PalmRejectionEngine
import com.vellum.notes.input.testCapabilities
import com.vellum.notes.input.testSettings
import com.vellum.notes.model.PenStyle
import com.vellum.notes.model.PenType
import com.vellum.notes.model.Point
import com.vellum.notes.model.Stroke
import com.vellum.notes.render.StrokeCull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Composition proof for surgical invalidation: the view's real cached stroke
 * bounds stay visible under the real clip math for a full draw and for a
 * partial draw clipped to the stroke, and invisible under a far-away clip.
 * (Robolectric does not rasterize Canvas ops, so pixels are not observable;
 * the visibility decision itself is what is verified, plus no-crash draws.)
 *
 * Geometry: 10px/mm capabilities, zoom 1, no pan → screen = world * 10.
 * Stroke world (10,10)-(60,60) → screen (100,100)-(600,600).
 */
@RunWith(RobolectricTestRunner::class)
class CanvasPartialInvalidateTest {

    private lateinit var view: InkCanvasView
    private val stroke = Stroke(
        id = 1L,
        style = PenStyle(type = PenType.BALLPOINT, colorArgb = 0xFF000000, widthMm = 2f),
        pointsPacked = Stroke.pack(
            listOf(Point(10f, 10f), Point(30f, 30f), Point(60f, 60f)),
        ),
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        view = InkCanvasView(context)
        val caps = testCapabilities(pxPerMm = 10f)
        view.capabilities = caps
        view.engine = PalmRejectionEngine(caps) { testSettings() }
        view.strokes = listOf(stroke)
        view.layout(0, 0, 1000, 1000)
    }

    /** Real cached bounds of the committed stroke (white-box via reflection). */
    private fun cachedBounds(): RectF {
        val field = InkCanvasView::class.java.getDeclaredField("displayStrokes")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val items = field.get(view) as List<*>
        assertEquals(1, items.size)
        val boundsField = items[0]!!.javaClass.getDeclaredField("bounds")
        boundsField.isAccessible = true
        return boundsField.get(items[0]) as RectF
    }

    private fun worldClipFor(screen: Rect): RectF =
        view.worldClipForClip(screen, RectF())

    @Test
    fun cachedBounds_containTheStroke() {
        val b = cachedBounds()
        assertTrue(b.left <= 10f && b.top <= 10f)
        assertTrue(b.right >= 60f && b.bottom >= 60f)
    }

    @Test
    fun fullClip_strokeIsVisible() {
        val clip = worldClipFor(Rect(0, 0, 1000, 1000))
        assertEquals(RectF(0f, 0f, 100f, 100f), clip)
        assertTrue(StrokeCull.isVisible(cachedBounds(), clip))
    }

    @Test
    fun partialClip_onStroke_strokeStaysVisible() {
        // Dirty region around the middle segment, as surgical invalidation emits.
        val clip = worldClipFor(Rect(280, 280, 420, 420))
        assertTrue(StrokeCull.isVisible(cachedBounds(), clip))
    }

    @Test
    fun partialClip_farAway_strokeCulled() {
        val clip = worldClipFor(Rect(800, 800, 1000, 1000))
        assertFalse(StrokeCull.isVisible(cachedBounds(), clip))
    }

    @Test
    fun draw_fullAndClipped_doNotCrash() {
        fun drawWithClip(l: Int, t: Int, r: Int, b: Int) {
            val bmp = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val canvas = Canvas(bmp)
            canvas.save()
            canvas.clipRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
            view.draw(canvas)
            canvas.restore()
        }
        drawWithClip(0, 0, 1000, 1000)
        drawWithClip(280, 280, 420, 420)
        drawWithClip(800, 800, 1000, 1000)
    }
}
