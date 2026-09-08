// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.File
import java.util.UUID

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
}
