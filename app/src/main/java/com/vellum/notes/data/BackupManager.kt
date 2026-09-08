// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Local backup export (S8): zips the Room database plus page asset dirs
 * (note-media/, pdf-pages/) into a user-chosen document via SAF. Fully offline,
 * no cloud, zero new dependencies. Restore = unzip + relaunch (documented in
 * README); the zip layout is versioned so future importers can migrate.
 */
object BackupManager {
    const val DB_NAME = "vellum.db"
    const val BACKUP_VERSION = 1

    /** Files included in the backup, relative to [Context.getFilesDir]. */
    fun collectFiles(context: Context): List<File> {
        val out = ArrayList<File>()
        val filesDir = context.filesDir
        // Database + journals (checkpoint first so -wal content is in the db file).
        out += File(filesDir.parentFile, "databases/$DB_NAME").takeIf { it.exists() }
            ?: return out
        out += File(filesDir, ImageStore.DIR).walkTopDown().filter { it.isFile }
        out += File(filesDir, com.vellum.notes.pdf.PdfImporter.DIR).walkTopDown().filter { it.isFile }
        return out
    }

    /**
     * Writes the backup zip to [uri]. Returns file count on success, null on failure.
     * The [checkpoint] hook lets callers flush Room's WAL into the db file first.
     */
    fun exportZip(
        context: Context,
        uri: Uri,
        checkpoint: () -> Unit = {},
    ): Int? {
        return try {
            runCatching { checkpoint() }
            val files = collectFiles(context)
            if (files.isEmpty()) return null
            val baseDirs = listOf(
                File(context.filesDir.parentFile, "databases"),
                context.filesDir,
            )
            context.contentResolver.openOutputStream(uri)?.use { raw ->
                writeZip(files, baseDirs, raw)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Pure zip writer (JVM-testable): returns entry count. */
    fun writeZip(files: List<File>, baseDirs: List<File>, out: java.io.OutputStream): Int {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("vellum-backup-version.txt"))
            zip.write(BACKUP_VERSION.toString().toByteArray())
            zip.closeEntry()
            var count = 0
            for (file in files) {
                val base = baseDirs.firstOrNull { file.absolutePath.startsWith(it.absolutePath) }
                val rel = if (base != null) {
                    file.absolutePath.removePrefix(base.absolutePath.trimEnd('/') + "/")
                } else {
                    file.name
                }
                // Never let absolute paths or ".." into the archive.
                if (rel.isBlank() || ".." in rel) continue
                zip.putNextEntry(ZipEntry(rel))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
            return count
        }
    }
}
