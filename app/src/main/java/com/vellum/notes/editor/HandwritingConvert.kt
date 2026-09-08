package com.vellum.notes.editor

import com.vellum.notes.model.ShapeObject
import com.vellum.notes.model.Stroke
import com.vellum.notes.model.TextObject
import kotlin.math.max
import kotlin.math.min

/**
 * Handwriting-to-text conversion (Nebo-style select + Convert).
 *
 * The on-device engine is [InkRecognizer] ($1-style print matching, no
 * network); [Recognizer] stays a seam so tests and future engines can plug in,
 * and [StubRecognizer] keeps the "empty box for manual edit" path. All
 * geometry here is pure and unit-testable.
 */
object HandwritingConvert {

    /** Recognition engine seam; stubbed until an on-device model lands. */
    interface Recognizer {
        /** Returns recognized text for the selected ink (empty when unknown). */
        fun recognize(strokes: List<Stroke>): String
    }

    /** Offline stub: no engine yet, so the user edits the new box directly. */
    object StubRecognizer : Recognizer {
        override fun recognize(strokes: List<Stroke>): String = ""
    }

    /**
     * Pure bounding box (world mm). Kept framework-free (no android.graphics.RectF)
     * so conversion geometry stays JVM-unit-testable.
     */
    data class BoundsMm(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * Bounding box (world mm) of the selected ink + shapes, or null when the
     * selection holds no convertible geometry.
     */
    fun selectionBoundsMm(strokes: List<Stroke>, shapes: List<ShapeObject>): BoundsMm? {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var found = false
        for (s in strokes) {
            val pts = s.pointsPacked
            var i = 0
            while (i + 1 < pts.size) {
                val x = pts[i]; val y = pts[i + 1]
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
                found = true
                i += 2
            }
        }
        for (sh in shapes) {
            for (p in sh.points) {
                if (p.x < left) left = p.x
                if (p.x > right) right = p.x
                if (p.y < top) top = p.y
                if (p.y > bottom) bottom = p.y
                found = true
            }
        }
        if (!found) return null
        val pad = 2f
        return BoundsMm(left - pad, top - pad, right + pad, bottom + pad)
    }

    /**
     * Builds the editable text box replacing the selection. The box covers the
     * selection bounds (min 30 x 12 mm so it stays tappable) and carries the
     * recognized text, or empty text when the engine is stubbed.
     */
    fun buildTextObject(bounds: BoundsMm, id: Long, recognizedText: String): TextObject {
        val width = max(bounds.width, 30f)
        val lines = recognizedText.lines().size.coerceAtLeast(1)
        val fontSizeMm = 6f
        val height = max(bounds.height, fontSizeMm * 1.35f * (lines + 1))
        return TextObject(
            id = id,
            x = bounds.left,
            y = bounds.top,
            width = width,
            height = height,
            text = recognizedText,
            fontSizeMm = fontSizeMm,
        )
    }

    /** Smallest bound helper kept local to avoid leaking min/max imports. */
    @Suppress("unused")
    private fun lo(a: Float, b: Float): Float = min(a, b)
}
