package com.vellum.notes.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup restore: payload validation, encrypted round-trips through the real
 * zip format, and file replacement with a safety copy (all on temp dirs —
 * no Android needed except ImageStore/PdfImporter dir names).
 */
class BackupRestoreTest {

    private val pass = "test passphrase 123".toCharArray()

    private fun tempRoots(): Triple<File, File, File> {
        val root = createTempDir("vellum-restore-test")
        val databasesDir = File(root, "databases").apply { mkdirs() }
        val filesDir = File(root, "files").apply { mkdirs() }
        return Triple(root, databasesDir, filesDir)
    }

    /** Builds a minimal valid backup ZIP in memory. */
    private fun validZip(dbBytes: ByteArray = "fakedb".toByteArray()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("vellum-backup-version.txt"))
            zip.write(BackupManager.BACKUP_VERSION.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("vellum.db"))
            zip.write(dbBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("note-media/pic.png"))
            zip.write("fakepng".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun parseBackupZip_acceptsValidPayload() {
        val parsed = BackupManager.parseBackupZip(validZip(), null)
        assertTrue(parsed != null)
        assertEquals(
            setOf("vellum.db", "note-media/pic.png"),
            parsed!!.files.keys,
        )
    }

    @Test
    fun parseBackupZip_rejectsBadPayloads() {
        // Not a zip at all.
        assertTrue(BackupManager.parseBackupZip("hello".toByteArray(), null) == null)
        // Encrypted without passphrase.
        val sealed = SyncCrypto.encrypt(validZip(), pass.copyOf())
        assertTrue(BackupManager.parseBackupZip(sealed, null) == null)
        // Wrong passphrase.
        assertTrue(BackupManager.parseBackupZip(sealed, "wrong passphrase!!".toCharArray()) == null)
        // Path traversal.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("vellum-backup-version.txt"))
            zip.write(BackupManager.BACKUP_VERSION.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("vellum.db"))
            zip.write("db".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../evil.txt"))
            zip.write("evil".toByteArray())
            zip.closeEntry()
        }
        assertTrue(BackupManager.parseBackupZip(out.toByteArray(), null) == null)
        // Missing database.
        val out2 = ByteArrayOutputStream()
        ZipOutputStream(out2).use { zip ->
            zip.putNextEntry(ZipEntry("vellum-backup-version.txt"))
            zip.write(BackupManager.BACKUP_VERSION.toString().toByteArray())
            zip.closeEntry()
        }
        assertTrue(BackupManager.parseBackupZip(out2.toByteArray(), null) == null)
    }

    @Test
    fun encryptedRoundTrip_throughRealZipFormat() {
        val zip = validZip()
        val sealed = SyncCrypto.encrypt(zip, pass.copyOf())
        assertTrue(SyncCrypto.isEncrypted(sealed))
        val parsed = BackupManager.parseBackupZip(sealed, pass.copyOf())
        assertTrue(parsed != null)
        assertTrue(parsed!!.files.containsKey("vellum.db"))
    }

    @Test
    fun applyRestore_replacesFilesAndKeepsSafetyCopy() {
        val (root, databasesDir, filesDir) = tempRoots()
        try {
            // Existing state that must be preserved in the safety copy.
            File(databasesDir, "vellum.db").writeBytes("olddb".toByteArray())
            File(filesDir, "note-media").mkdirs()
            File(filesDir, "note-media/old.png").writeBytes("old".toByteArray())

            val parsed = BackupManager.parseBackupZip(validZip("newdb".toByteArray()), null)!!
            assertTrue(BackupManager.applyRestore(parsed, databasesDir, filesDir))

            assertEquals("newdb", File(databasesDir, "vellum.db").readBytes().toString(Charsets.UTF_8))
            assertEquals("fakepng", File(filesDir, "note-media/pic.png").readBytes().toString(Charsets.UTF_8))

            // Safety copy holds the previous state.
            val safety = File(filesDir, "restore-safety").listFiles()
            assertTrue(safety != null && safety!!.size == 1)
            val kept = File(safety!![0], "databases/vellum.db").readBytes().toString(Charsets.UTF_8)
            assertEquals("olddb", kept)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applyRestore_rejectsUnknownLayout() {
        val (_, databasesDir, filesDir) = tempRoots()
        try {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("vellum-backup-version.txt"))
                zip.write(BackupManager.BACKUP_VERSION.toString().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("vellum.db"))
                zip.write("db".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("random/stuff.bin"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
            assertTrue(BackupManager.parseBackupZip(out.toByteArray(), null) == null)
        } finally {
            databasesDir.parentFile.deleteRecursively()
        }
    }
}
