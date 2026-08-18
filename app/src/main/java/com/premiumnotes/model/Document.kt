package com.premiumnotes.model

import com.premiumnotes.input.SmoothingMode
import kotlinx.serialization.Serializable

/** Supported pen brushes. */
@Serializable
enum class PenType { BALLPOINT, FOUNTAIN, PENCIL, MARKER, HIGHLIGHTER, MONOLINE, CALLIGRAPHY }

/** Paper template for a page. */
@Serializable
enum class PageBackgroundType {
    BLANK, RULED, NARROW_RULED, WIDE_RULED, GRID, SMALL_GRID, DOTTED, GRAPH, CORNELL, MUSIC, MATH
}

/** A lightweight 2D point in world (document) coordinates. */
@Serializable
data class Point(
    val x: Float,
    val y: Float,
)

/** Visual parameters of a pen. */
@Serializable
data class PenStyle(
    val type: PenType = PenType.BALLPOINT,
    val colorArgb: Long = 0xFF000000,
    val widthMm: Float = 1.2f,
    val opacity: Float = 1f,
    val smoothing: SmoothingMode = SmoothingMode.MEDIUM,
    val nibAngleDegrees: Float = 60f,
)

/** A committed handwriting stroke. Points stored packed as a flat [FloatArray]. */
@Serializable
data class Stroke(
    val id: Long,
    val style: PenStyle,
    val pointsPacked: FloatArray,
    val widthMm: Float = style.widthMm,
) {
    val points: List<Point>
        get() {
            val out = ArrayList<Point>(pointsPacked.size / 2)
            var i = 0
            while (i + 1 < pointsPacked.size) {
                out += Point(pointsPacked[i], pointsPacked[i + 1])
                i += 2
            }
            return out
        }

    companion object {
        fun pack(points: List<Point>): FloatArray {
            val arr = FloatArray(points.size * 2)
            for ((i, p) in points.withIndex()) {
                arr[i * 2] = p.x
                arr[i * 2 + 1] = p.y
            }
            return arr
        }
    }
}

/** A text box placed on a page. */
@Serializable
data class TextObject(
    val id: Long,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val text: String = "",
    val fontFamily: String = "sans-serif",
    val fontSizeMm: Float = 8f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val colorArgb: Long = 0xFF000000,
    val alignment: TextAlign = TextAlign.LEFT,
    val zOrder: Int = 0,
)

/** An image placed on a page (file under app-internal storage). */
@Serializable
data class ImageObject(
    val id: Long,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val zOrder: Int = 0,
    /** Reference to the stored file (relative path under note-media/). */
    val fileRef: String = "",
)

/** Geometric shapes. */
@Serializable
enum class ShapeKind { LINE, ARROW, RECT, ROUNDED_RECT, CIRCLE, ELLIPSE, TRIANGLE, POLYGON, STAR }

@Serializable
data class ShapeObject(
    val id: Long,
    val kind: ShapeKind,
    val points: List<Point> = emptyList(),
    val x: Float,
    val y: Float,
    val rotation: Float = 0f,
    val strokeWidthMm: Float = 1.5f,
    val colorArgb: Long = 0xFF000000,
    val fillColorArgb: Long? = null,
    val fillEnabled: Boolean = false,
    val zOrder: Int = 0,
)

@Serializable
enum class TextAlign { LEFT, CENTER, RIGHT }

@Serializable
enum class HorizontalAlignment { LEFT, CENTER, RIGHT }

@Serializable
enum class VerticalAlignment { TOP, CENTER, BOTTOM }

/** One recognized speech segment in a Classroom Notes transcript. */
@Serializable
data class TranscriptSegment(
    val id: Long,
    /** Elapsed ms from the start of the recording. */
    val startMs: Long,
    /** Elapsed ms when the segment was finalized (0 while still live). */
    val endMs: Long = 0L,
    val text: String = "",
)

/** Full content of a page. */
@Serializable
data class PageContent(
    val contentVersion: Int = 2,
    val strokes: List<Stroke> = emptyList(),
    val textObjects: List<TextObject> = emptyList(),
    val imageObjects: List<ImageObject> = emptyList(),
    val shapeObjects: List<ShapeObject> = emptyList(),
    /** Classroom Notes transcript (timestamps are ms from recording start). */
    val transcript: List<TranscriptSegment> = emptyList(),
    /** Placeholder for a future on-device summary; always null/empty for now. */
    val summary: String? = null,
)

/** Page background configuration. */
@Serializable
data class PageBackground(
    val type: PageBackgroundType = PageBackgroundType.BLANK,
    val colorArgb: Long = 0xFFFFFFFF,
    val lineColorArgb: Long = 0xFFB8C6E0,
    val lineSpacingMm: Float = 8f,
    val gridSizeMm: Float = 5f,
    val dotSpacingMm: Float = 5f,
)
