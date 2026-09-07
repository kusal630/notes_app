// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * App-private storage for images inserted into notes (wave 1, feature D).
 *
 * Files live under `<filesDir>/note-media/`. [PageContent.imageObjects][com.vellum.notes.model.ImageObject]
 * reference them via a relative [com.vellum.notes.model.ImageObject.fileRef] so the
 * database stays portable across installs. Fully offline; no permissions needed
 * because reads come through the Storage Access Framework picker.
 */
object ImageStore {
    const val DIR = "note-media"

    fun mediaDir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Relative fileRef stored in [com.vellum.notes.model.ImageObject]. */
    fun fileRefFor(fileName: String): String = "$DIR/$fileName"

    fun newFileName(mimeType: String?): String {
        val ext = when {
            mimeType?.contains("png", ignoreCase = true) == true -> "png"
            mimeType?.contains("webp", ignoreCase = true) == true -> "webp"
            else -> "jpg"
        }
        return "img-${UUID.randomUUID()}.$ext"
    }

    /** Copies [uri] into app-private storage; returns the stored fileRef or null. */
    fun importUri(context: Context, uri: Uri, mimeType: String? = null): String? {
        return try {
            val dir = mediaDir(context)
            val file = File(dir, newFileName(mimeType ?: context.contentResolver.getType(uri)))
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: return null
            fileRefFor(file.name)
        } catch (t: Throwable) {
            null
        }
    }

    /** Resolves a stored fileRef back to a [File] (null when missing/blank). */
    fun resolveFile(context: Context, fileRef: String): File? {
        if (fileRef.isBlank()) return null
        val name = fileRef.substringAfterLast("/")
        if (name.isBlank() || "/" in name) return null
        val file = File(mediaDir(context), name)
        return if (file.exists()) file else null
    }

    /** Deletes an image file from storage if present. */
    fun deleteFile(context: Context, fileRef: String): Boolean {
        if (fileRef.isBlank()) return false
        val name = fileRef.substringAfterLast("/")
        if (name.isBlank() || "/" in name) return false
        val file = File(mediaDir(context), name)
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /**
     * Default placement for a newly inserted image: centered on the visible viewport,
     * capped to [maxWidthMm] wide preserving [aspect] (w/h). Pure math for tests.
     */
    fun defaultPlacement(
        viewportLeftMm: Float,
        viewportTopMm: Float,
        viewportWidthMm: Float,
        viewportHeightMm: Float,
        aspect: Float,
        maxWidthMm: Float = 120f,
    ): FloatArray {
        val safeAspect = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        val w = minOf(viewportWidthMm * 0.6f, maxWidthMm).coerceAtLeast(10f)
        val h = (w / safeAspect).coerceAtLeast(10f)
        val x = viewportLeftMm + (viewportWidthMm - w) / 2f
        val y = viewportTopMm + (viewportHeightMm - h) / 2f
        return floatArrayOf(x, y, w, h)
    }
}
