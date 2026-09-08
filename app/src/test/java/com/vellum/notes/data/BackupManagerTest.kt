package com.vellum.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class BackupManagerTest {
    @Test
    fun writeZip_preservesRelativePaths_andVersionEntry() {
        val root = createTempDir("backup-test")
        try {
            val dbDir = File(root, "databases").apply { mkdirs() }
            val mediaDir = File(root, "files/note-media").apply { mkdirs() }
            val db = File(dbDir, "vellum.db").apply { writeText("fake-db") }
            val img = File(mediaDir, "img-1.jpg").apply { writeText("fake-img") }

            val out = ByteArrayOutputStream()
            val count = BackupManager.writeZip(
                listOf(db, img),
                listOf(dbDir, File(root, "files")),
                out,
            )
            assertEquals(2, count)

            val entries = mutableMapOf<String, String>()
            ZipInputStream(out.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    entries[e.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            assertEquals("1", entries["vellum-backup-version.txt"])
            assertEquals("fake-db", entries["vellum.db"])
            assertEquals("fake-img", entries["note-media/img-1.jpg"])
            assertTrue(entries.keys.none { it.startsWith("/") || ".." in it })
        } finally {
            root.deleteRecursively()
        }
    }
}
