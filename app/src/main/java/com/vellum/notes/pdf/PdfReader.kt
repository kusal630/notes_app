// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.pdf

/**
 * Pure, JVM-testable PDF reader logic: themes, crop-rect math, content scan.
 * No Android imports — [PdfImporter] hosts the Bitmap/Context wrappers.
 */
enum class PdfReaderTheme { ORIGINAL, SEPIA, NIGHT }

object PdfReaderTint {
    /** Overlay ARGB drawn over (a copy of) the rasterized page; 0 = no overlay. */
    fun overlayArgb(theme: PdfReaderTheme): Long = when (theme) {
        PdfReaderTheme.ORIGINAL -> 0x00000000L
        PdfReaderTheme.SEPIA -> 0x33C8A84BL
        PdfReaderTheme.NIGHT -> 0xCC101418L
    }

    fun hasOverlay(theme: PdfReaderTheme): Boolean = overlayArgb(theme) != 0L
}

/** Normalized crop rect (0..1 fractions of page size); null rect = no crop. */
data class PdfCropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun normalized(): PdfCropRect = PdfCropRect(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
    )

    fun isValid(): Boolean {
        val n = normalized()
        return n.right - n.left > 0.01f && n.bottom - n.top > 0.01f
    }

    /** Pixel bounds for a page of [pageWidth]x[pageHeight]. */
    fun toPixels(pageWidth: Int, pageHeight: Int): IntArray {
        val n = normalized()
        return intArrayOf(
            (n.left * pageWidth).toInt().coerceIn(0, pageWidth - 1),
            (n.top * pageHeight).toInt().coerceIn(0, pageHeight - 1),
            (n.right * pageWidth).toInt().coerceIn(1, pageWidth),
            (n.bottom * pageHeight).toInt().coerceIn(1, pageHeight),
        )
    }

    fun toJson(): String = "{\"l\":$left,\"t\":$top,\"r\":$right,\"b\":$bottom}"

    companion object {
        fun parse(json: String): PdfCropRect? = runCatching {
            fun num(key: String): Float {
                val i = json.indexOf("\"$key\"")
                if (i < 0) throw IllegalArgumentException("missing $key")
                val colon = json.indexOf(":", i)
                var j = colon + 1
                while (j < json.length && json[j] != ',' && json[j] != '}') j++
                return json.substring(colon + 1, j).trim().toFloat()
            }
            PdfCropRect(num("l"), num("t"), num("r"), num("b")).normalized().takeIf { it.isValid() }
        }.getOrNull()
    }
}

/** Content-bound scan + crop helpers (pure; callers supply pixel predicates). */
object PdfCrop {
    /**
     * Scans [width]x[height] with [isContent] at [stride] steps, returns the tight
     * normalized bounds plus a 1% padding margin, or null when blank.
     */
    fun detect(
        width: Int,
        height: Int,
        isContent: (x: Int, y: Int) -> Boolean,
        stride: Int = 4,
    ): PdfCropRect? {
        if (width <= 0 || height <= 0) return null
        val step = stride.coerceAtLeast(1)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (isContent(x, y)) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
                x += step
            }
            y += step
        }
        if (maxX < 0) return null
        val padX = width * 0.01f
        val padY = height * 0.01f
        return PdfCropRect(
            ((minX - padX) / width).coerceIn(0f, 1f),
            ((minY - padY) / height).coerceIn(0f, 1f),
            ((maxX + padX + step) / width).coerceIn(0f, 1f),
            ((maxY + padY + step) / height).coerceIn(0f, 1f),
        ).takeIf { it.isValid() }
    }
}
