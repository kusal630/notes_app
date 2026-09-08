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
 *
 * End-to-end encryption: [exportEncrypted] wraps the same zip in AES-256-GCM
 * ([SyncCrypto]) so sync-folder snapshots and carried copies stay private even
 * on untrusted storage. [importBackup] restores plain or encrypted payloads
 * after keeping a safety copy of the current state.
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
            val bytes = buildZipBytes(context, checkpoint) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { raw ->
                raw.write(bytes)
            }
            // Count entries for the confirmation toast.
            countZipEntries(bytes)
        } catch (t: Throwable) {
            null
        }
    }

    /** Encrypted variant of [exportZip]: the ZIP is AES-256-GCM sealed. */
    fun exportEncrypted(
        context: Context,
        uri: Uri,
        passphrase: CharArray,
        checkpoint: () -> Unit = {},
    ): Boolean {
        return try {
            val bytes = buildZipBytes(context, checkpoint) ?: return false
            val sealed = SyncCrypto.encrypt(bytes, passphrase)
            context.contentResolver.openOutputStream(uri)?.use { raw ->
                raw.write(sealed)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Builds the backup ZIP in memory (shared by plain + encrypted export). */
    fun buildZipBytes(context: Context, checkpoint: () -> Unit = {}): ByteArray? {
        return try {
            runCatching { checkpoint() }
            val files = collectFiles(context)
            if (files.isEmpty()) return null
            val baseDirs = listOf(
                File(context.filesDir.parentFile, "databases"),
                context.filesDir,
            )
            val out = java.io.ByteArrayOutputStream()
            writeZip(files, baseDirs, out)
            out.toByteArray()
        } catch (t: Throwable) {
            null
        }
    }

    private fun countZipEntries(bytes: ByteArray): Int {
        var count = 0
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name != "vellum-backup-version.txt") count++
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return count
    }

    /** Outcome of [importBackup]. */
    sealed interface RestoreResult {
        /** Files replaced; the caller must restart the app before touching the DB. */
        data object Success : RestoreResult
        data object WrongPassphrase : RestoreResult
        data object InvalidBackup : RestoreResult
        data class Error(val message: String?) : RestoreResult
    }

    /** Validated backup payload: relative path → bytes. */
    data class ParsedBackup(val files: Map<String, ByteArray>)

    /**
     * Parses + validates a backup payload (plain ZIP or encrypted). Pure logic,
     * JVM-testable. Returns null for wrong passphrases and invalid payloads
     * alike; [importBackup] separates the two cases. Entry names are confined
     * to the known layout (`vellum.db`, `note-media/…`, `pdf-pages/…`);
     * anything else rejects the whole payload.
     */
    fun parseBackupZip(zipBytes: ByteArray, passphrase: CharArray?): ParsedBackup? {
        val raw: ByteArray = try {
            if (SyncCrypto.isEncrypted(zipBytes)) {
                if (passphrase == null) return null
                SyncCrypto.decrypt(zipBytes, passphrase)
            } else {
                zipBytes
            }
        } catch (t: Throwable) {
            return null
        }
        return parseZipBody(raw)
    }

    /** Validates a decrypted/plain ZIP body. Null = not a Vellum backup. */
    fun parseZipBody(raw: ByteArray): ParsedBackup? {
        return try {
            val files = LinkedHashMap<String, ByteArray>()
            java.util.zip.ZipInputStream(raw.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        files[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val version = files["vellum-backup-version.txt"]?.toString(Charsets.UTF_8)?.trim()
            if (version != BACKUP_VERSION.toString()) return null
            files.remove("vellum-backup-version.txt")
            if (files.isEmpty() || !files.containsKey(DB_NAME)) return null
            for (name in files.keys) {
                if (name.isBlank() || ".." in name || name.startsWith("/") || "\\" in name) return null
                val ok = name == DB_NAME ||
                    name.startsWith(ImageStore.DIR + "/") ||
                    name.startsWith(com.vellum.notes.pdf.PdfImporter.DIR + "/")
                if (!ok) return null
            }
            ParsedBackup(files)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Applies a parsed backup: stages a safety copy of the current state, then
     * replaces the database + media. Returns false (leaving everything
     * untouched) when the safety copy itself cannot be made.
     */
    fun applyRestore(
        parsed: ParsedBackup,
        databasesDir: File,
        filesDir: File,
    ): Boolean {
        return try {
            // Safety copy first: the current db + media, so a bad import is recoverable.
            val safety = File(filesDir, "restore-safety/${System.currentTimeMillis()}")
            val dbFile = File(databasesDir, DB_NAME)
            if (dbFile.exists()) {
                val dest = File(safety, "databases/$DB_NAME")
                dest.parentFile?.mkdirs()
                dbFile.copyTo(dest, overwrite = true)
            }
            for (dir in listOf(ImageStore.DIR, com.vellum.notes.pdf.PdfImporter.DIR)) {
                val src = File(filesDir, dir)
                if (src.exists()) {
                    src.copyRecursively(File(safety, dir), overwrite = true)
                }
            }
            // Keep only the latest safety copy.
            File(filesDir, "restore-safety").listFiles()
                ?.sortedBy { it.name }
                ?.dropLast(1)
                ?.forEach { it.deleteRecursively() }

            // Replace: database, then media (wipe-then-write per directory).
            databasesDir.mkdirs()
            for ((name, bytes) in parsed.files) {
                val dest = if (name == DB_NAME) File(databasesDir, name) else File(filesDir, name)
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
            }
            // Stale journals must never shadow the restored db.
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                File(databasesDir, "$DB_NAME$suffix").delete()
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Full restore from a backup payload: decrypts (when needed), validates,
     * closes the live database, replaces files. The caller MUST restart the
     * app afterwards (all DAO caches + the Room instance are stale).
     */
    fun importBackup(
        context: Context,
        payload: ByteArray,
        passphrase: CharArray?,
        closeDatabase: () -> Unit = {},
    ): RestoreResult {
        // Decrypt first so a wrong passphrase is reported distinctly from a
        // corrupt backup.
        val raw: ByteArray = if (SyncCrypto.isEncrypted(payload)) {
            if (passphrase == null) return RestoreResult.WrongPassphrase
            try {
                SyncCrypto.decrypt(payload, passphrase)
            } catch (e: javax.crypto.AEADBadTagException) {
                return RestoreResult.WrongPassphrase
            } catch (t: Throwable) {
                return RestoreResult.Error(t.message)
            }
        } else {
            payload
        }
        val parsed = parseZipBody(raw) ?: return RestoreResult.InvalidBackup
        return try {
            runCatching { closeDatabase() }
            val databasesDir = File(context.filesDir.parentFile, "databases")
            val ok = applyRestore(parsed, databasesDir, context.filesDir)
            if (ok) RestoreResult.Success else RestoreResult.Error(null)
        } catch (t: Throwable) {
            RestoreResult.Error(t.message)
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
