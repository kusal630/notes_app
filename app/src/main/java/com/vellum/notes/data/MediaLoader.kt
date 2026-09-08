// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * OOM-safe bitmap decoding: reads bounds first, then downsamples so the longest
 * side fits [maxDimPx]. PDF underlays decode as RGB_565 (half the memory).
 */
object MediaLoader {
    fun decodeSampled(file: File, maxDimPx: Int = 2048, rgb565: Boolean = false): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxDimPx) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                if (rgb565) inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (t: Throwable) {
            null
        }
    }

    /** Width/height without a full decode (for placement math). */
    fun probeSize(file: File): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) null else opts.outWidth to opts.outHeight
        } catch (t: Throwable) {
            null
        }
    }
}
