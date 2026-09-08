// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.editor

/**
 * Pen-following auto-scroll math (pure, JVM-testable).
 *
 * The canvas used to shift the viewport by the raw overshoot every MOVE frame
 * with no clamp and no deadband: the page bounced up and down around the edge
 * margin and could scroll past the content extent. These helpers add a deadband
 * (ignore sub-[deadbandPx] jitter) and clamp the offset into the content range.
 */
object ViewportScroll {
    /** Viewport shift (px, signed) for a pen at [penYPx]; 0 when inside margins. */
    fun shiftFor(
        penYPx: Float,
        heightPx: Float,
        marginPx: Float = 150f,
        deadbandPx: Float = 4f,
    ): Float {
        if (heightPx <= 0f) return 0f
        val raw = when {
            penYPx > heightPx - marginPx -> penYPx - (heightPx - marginPx)
            penYPx < marginPx -> penYPx - marginPx
            else -> 0f
        }
        return if (kotlin.math.abs(raw) < deadbandPx) 0f else raw
    }

    /** Clamps a viewport offset (px) into [0, contentExtent - height]. */
    fun clampOffset(offsetPx: Float, extentMm: Float, scale: Float, heightPx: Float): Float {
        if (heightPx <= 0f || scale <= 0f) return 0f
        return offsetPx.coerceIn(0f, (extentMm * scale - heightPx).coerceAtLeast(0f))
    }
}
