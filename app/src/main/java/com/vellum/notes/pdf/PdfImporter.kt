// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Offline PDF import + annotation backing (wave 1, feature C).
 *
 * Each source PDF page is rasterized once with [PdfRenderer] into app-private
 * storage (`<filesDir>/pdf-pages/`); notebook pages reference the bitmap via
 * `PageEntity.pdfBackgroundPath` + `pdfPageIndex` and keep ink strokes on top.
 * No storage permission: the source comes from `ACTION_OPEN_DOCUMENT`.
 *
 * [PdfPagePlan] is the pure, unit-testable part (ordering + file layout).
 */
object PdfPagePlan {
    data class PageSpec(val order: Int, val pdfPageIndex: Int, val fileName: String)

    /** One spec per source page, preserving source order. */
    fun plan(pageCount: Int, notebookId: Long, tag: String = "pdf"): List<PageSpec> {
        if (pageCount <= 0) return emptyList()
        return (0 until pageCount).map { i ->
            PageSpec(order = i, pdfPageIndex = i, fileName = "$tag-$notebookId-p$i.png")
        }
    }

    fun isPdfBacked(pdfPageIndex: Int): Boolean = pdfPageIndex >= 0
}

object PdfImporter {
    const val DIR = "pdf-pages"
    /** Maximum pages rasterized per import (disk/OOM guard). */
    const val MAX_PAGES = 50

    fun pdfDir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Resolves a stored pdf background name to a [File], basename-stripped so DB values like `../../x` can never escape [pdfDir]. */
    fun resolveFile(context: Context, storedName: String): File? {
        if (storedName.isBlank()) return null
        val name = storedName.substringAfterLast("/").substringAfterLast(File.separator)
        if (name.isBlank() || name.contains("..")) return null
        val file = File(pdfDir(context), name)
        return if (file.exists()) file else null
    }

    fun pageCountOf(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            } ?: 0
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * Rasterizes every page of [uri] at [dpiScale] and writes PNGs into [pdfDir].
     * Returns the written files in source order (empty on failure).
     */
    fun rasterize(context: Context, uri: Uri, notebookId: Long, dpiScale: Float = 2f): List<File> {
        val out = ArrayList<File>()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val count = renderer.pageCount.coerceAtMost(MAX_PAGES)
                    val specs = PdfPagePlan.plan(count, notebookId, UUID.randomUUID().toString().take(8))
                    for (spec in specs) {
                        renderer.openPage(spec.pdfPageIndex).use { page ->
                            val w = (page.width * dpiScale).toInt().coerceIn(1, 4096)
                            val h = (page.height * dpiScale).toInt().coerceIn(1, 4096)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            // White paper base so transparent PDFs export/print correctly.
                            bmp.eraseColor(0xFFFFFFFF.toInt())
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val file = File(pdfDir(context), spec.fileName)
                            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                            bmp.recycle()
                            out += file
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // Partial imports are discarded by the caller.
        }
        return out
    }

    /** Page width in world mm for a bitmap of [bitmapWidthPx]x[bitmapHeightPx] fitted to [fitWidthMm]. */
    fun worldSizeMm(bitmapWidthPx: Int, bitmapHeightPx: Int, fitWidthMm: Float = 210f): Pair<Float, Float> {
        if (bitmapWidthPx <= 0 || bitmapHeightPx <= 0) return fitWidthMm to fitWidthMm * 1.414f
        return fitWidthMm to (fitWidthMm * bitmapHeightPx / bitmapWidthPx)
    }

    // ---------- Reader mode: themes, thumbnails, crop (Nebo/NoteShelf-grade) ----------

    const val THUMB_DIR = "pdf-thumbs"
    /** Longest side of cached filmstrip thumbnails. */
    const val THUMB_MAX_PX = 256

    fun thumbDir(context: Context): File = File(context.filesDir, THUMB_DIR).apply { mkdirs() }

    /** Downsampled thumbnail name for a rasterized page file (pure, testable). */
    fun thumbNameFor(pageFileName: String): String {
        val base = pageFileName.substringAfterLast("/").substringAfterLast(File.separator)
        val stem = base.substringBeforeLast(".")
        return "$stem-thumb.png"
    }

    /** Power-of-two inSampleSize so the longest side fits [maxPx] (pure, testable). */
    fun sampleSizeFor(srcWidth: Int, srcHeight: Int, maxPx: Int = THUMB_MAX_PX): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxPx <= 0) return 1
        var sample = 1
        val longest = maxOf(srcWidth, srcHeight)
        while (longest / sample > maxPx) sample *= 2
        return sample
    }

    /**
     * Builds/refreshes downsampled thumbnails for every PNG in [pdfDir], cached in
     * [thumbDir]. Returns (pageFile, thumbFile) pairs in name order. Originals untouched.
     */
    fun thumbnails(context: Context): List<Pair<File, File>> {
        val out = ArrayList<Pair<File, File>>()
        try {
            val dir = pdfDir(context)
            val tdir = thumbDir(context)
            val pages = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
                ?.sortedBy { it.name }?.take(MAX_PAGES) ?: return out
            for (page in pages) {
                val thumb = File(tdir, thumbNameFor(page.name))
                val needsBuild = !thumb.exists() || thumb.lastModified() < page.lastModified()
                if (needsBuild) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(page.absolutePath, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = BitmapFactory.decodeFile(page.absolutePath, opts) ?: continue
                    // Cap longest side exactly at THUMB_MAX_PX when sampling leaves it larger.
                    val longest = maxOf(bmp.width, bmp.height)
                    val final = if (longest > THUMB_MAX_PX) {
                        val scale = THUMB_MAX_PX / longest.toFloat()
                        Bitmap.createScaledBitmap(
                            bmp, (bmp.width * scale).roundToInt().coerceAtLeast(1),
                            (bmp.height * scale).roundToInt().coerceAtLeast(1), true,
                        ).also { bmp.recycle() }
                    } else bmp
                    try {
                        thumb.outputStream().use { final.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    } catch (t: Throwable) {
                        thumb.delete()
                    } finally {
                        final.recycle()
                    }
                }
                if (thumb.exists()) out += page to thumb
            }
        } catch (t: Throwable) {
            // Best effort: filmstrip never breaks reading.
        }
        return out
    }

    /**
     * Returns a tinted copy of [src] for reader themes; the original is never mutated.
     * Sepia warms the page, night inverts toward a dark background for low-light reading.
     */
    fun tintedCopy(src: Bitmap, theme: PdfReaderTheme): Bitmap {
        if (theme == PdfReaderTheme.ORIGINAL || src.isRecycled) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, false) ?: return src
        val canvas = Canvas(out)
        val paint = Paint().apply {
            color = when (theme) {
                PdfReaderTheme.SEPIA -> 0x33C8A84B.toInt() // warm translucent wash
                PdfReaderTheme.NIGHT -> 0xCC101418.toInt() // near-opaque dark veil
                PdfReaderTheme.ORIGINAL -> 0x00000000
            }
        }
        canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), paint)
        return out
    }

    // ---------- Crop / trim white margins (sidecar, no DB migration) ----------

    /** Sidecar file holding the crop rect JSON for a rasterized page (pure name mapping). */
    fun cropSidecarNameFor(pageFileName: String): String {
        val base = pageFileName.substringAfterLast("/").substringAfterLast(File.separator)
        return base.substringBeforeLast(".") + ".crop.json"
    }

    fun cropSidecar(context: Context, pageFileName: String): File =
        File(pdfDir(context), cropSidecarNameFor(pageFileName))

    fun saveCropRect(context: Context, pageFileName: String, rect: PdfCropRect?) {
        val sidecar = cropSidecar(context, pageFileName)
        try {
            if (rect == null) sidecar.delete()
            else sidecar.writeText(
                "{\"l\":${rect.left},\"t\":${rect.top},\"r\":${rect.right},\"b\":${rect.bottom}}",
            )
        } catch (t: Throwable) {
            // Best effort.
        }
    }

    fun loadCropRect(context: Context, pageFileName: String): PdfCropRect? {
        return try {
            val sidecar = cropSidecar(context, pageFileName)
            if (!sidecar.exists()) return null
            PdfCropRect.parse(sidecar.readText())
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Auto-detects content bounds by scanning for non-white pixels (luminance < [whiteThreshold]).
     * Returns null when the page is blank. Stride keeps it cheap on large raster pages.
     */
    fun detectContentBounds(
        bitmap: Bitmap,
        whiteThreshold: Int = 245,
        stride: Int = 4,
    ): PdfCropRect? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        try {
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        } catch (t: Throwable) {
            return null
        }
        val isContent = { x: Int, y: Int ->
            val c = pixels[y * w + x]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            lum < whiteThreshold
        }
        return PdfCrop.detect(w, h, isContent, stride)
    }
}
