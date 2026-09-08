package com.vellum.notes.render

import android.graphics.RectF

/**
 * Surgical invalidation for the writing path (60fps workstream).
 *
 * While a stroke is in progress only the newly added segment changed, so the
 * view invalidates just that segment's screen-space bbox instead of the whole
 * canvas. onDraw derives its culling clip from [android.graphics.Canvas.getClipBounds],
 * so a partial invalidate automatically skips every cached item outside the
 * dirty region. Anything that moves existing content (scroll, zoom, pan,
 * tool/style changes, commit) still uses a full invalidate.
 */
object DirtyRect {

    /**
     * Screen-px bbox covering the world-space segment (x0, y0)–(x1, y1) plus
     * [padMm] of padding on every side (pen half-width, anti-aliasing bleed,
     * variable-width overshoot). The viewport transform is
     * screen = world * [scale] + offset.
     */
    fun segment(
        x0: Float, y0: Float, x1: Float, y1: Float,
        scale: Float, offsetX: Float, offsetY: Float,
        padMm: Float,
    ): RectF {
        val l = kotlin.math.min(x0, x1) - padMm
        val t = kotlin.math.min(y0, y1) - padMm
        val r = kotlin.math.max(x0, x1) + padMm
        val b = kotlin.math.max(y0, y1) + padMm
        return RectF(
            l * scale + offsetX,
            t * scale + offsetY,
            r * scale + offsetX,
            b * scale + offsetY,
        )
    }

    /** Padding that always contains a fresh segment of [widthMm] ink. */
    fun padForWidth(widthMm: Float): Float = StrokeCull.padForWidth(widthMm) + 1f
}
