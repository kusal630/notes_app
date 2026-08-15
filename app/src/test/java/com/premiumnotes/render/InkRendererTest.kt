package com.premiumnotes.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.PenType
import com.premiumnotes.model.Point
import com.premiumnotes.model.Stroke
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InkRendererTest {

    private fun render(style: PenStyle) {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val renderer = InkRenderer()
        val pts = Stroke.pack(
            listOf(
                Point(10f, 10f),
                Point(20f, 40f),
                Point(30f, 20f),
                Point(40f, 50f),
                Point(50f, 30f),
                Point(60f, 60f),
                Point(70f, 40f),
                Point(80f, 70f),
                Point(90f, 50f),
                Point(100f, 80f),
                Point(110f, 60f),
                Point(120f, 90f),
            )
        )
        renderer.drawStroke(canvas, Stroke(id = 1, style = style, pointsPacked = pts), 1f)
    }

    @Test
    fun fountainStrokeRendersWithoutIndexError() {
        // Regression: packed-point indexing used to read pts[index-2] at point index 1,
        // throwing ArrayIndexOutOfBoundsException (length=44; index=-1).
        render(PenStyle(type = PenType.FOUNTAIN, widthMm = 1.5f))
    }

    @Test
    fun calligraphyStrokeRendersWithoutIndexError() {
        render(PenStyle(type = PenType.CALLIGRAPHY, widthMm = 2f))
    }

    @Test
    fun ballpointStrokeRenders() {
        render(PenStyle(type = PenType.BALLPOINT, widthMm = 1f))
    }
}