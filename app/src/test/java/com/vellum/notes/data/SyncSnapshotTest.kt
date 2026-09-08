package com.vellum.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSnapshotTest {

    @Test
    fun fileName_roundTrips() {
        val name = SyncSnapshot.fileName("abc123", 1700000000000L)
        assertEquals("vellum-snapshot-abc123-1700000000000.zip", name)
        assertEquals("abc123" to 1700000000000L, SyncSnapshot.parseFileName(name))
    }

    @Test
    fun parseFileName_rejectsNonSnapshots() {
        assertNull(SyncSnapshot.parseFileName("vellum-backup-20240101.zip"))
        assertNull(SyncSnapshot.parseFileName("vellum-snapshot-abc.zip"))
        assertNull(SyncSnapshot.parseFileName("random.txt"))
        assertNull(SyncSnapshot.parseFileName("vellum-snapshot-abc-xyz.zip"))
    }

    @Test
    fun manifest_roundTrips() {
        val manifest = SyncSnapshot.Manifest(
            deviceId = "device1",
            createdAt = 1700000000000L,
            notebookCount = 3,
            pageCount = 12,
            encrypted = true,
        )
        val parsed = SyncSnapshot.manifestFromJson(SyncSnapshot.manifestToJson(manifest))
        assertEquals(manifest, parsed)
    }

    @Test
    fun manifest_toleratesMissingAndCorruptSidecars() {
        assertNull(SyncSnapshot.manifestFromJson("not json"))
        assertNull(SyncSnapshot.manifestFromJson("{}"))
        val partial = SyncSnapshot.manifestFromJson(
            """{"deviceId":"d","createdAt":5}""",
        )
        assertEquals("d", partial?.deviceId)
        assertEquals(5L, partial?.createdAt)
        assertEquals(false, partial?.encrypted)
    }

    @Test
    fun inboundSnapshots_newestPerDeviceExcludingSelf() {
        fun meta(device: String, at: Long) = SyncSnapshot.SnapshotMeta(
            deviceId = device,
            createdAt = at,
            fileName = SyncSnapshot.fileName(device, at),
            manifest = null,
        )
        val files = listOf(
            meta("self", 100L),
            meta("self", 300L),
            meta("phone", 100L),
            meta("phone", 200L),
            meta("tablet", 150L),
        )
        val inbound = SyncSnapshot.inboundSnapshots(files, ownDeviceId = "self")
        assertEquals(listOf("phone", "tablet"), inbound.map { it.deviceId })
        assertEquals(200L, inbound[0].createdAt)
        assertEquals(150L, inbound[1].createdAt)
    }

    @Test
    fun collectSnapshots_resolvesManifestsAndSkipsJunk() {
        val zipA = SyncSnapshot.fileName("phone", 100L)
        val manifest = SyncSnapshot.Manifest("phone", 120L, 2, 5, true)
        val files = listOf(
            SyncFolder.DocFile(
                uri = android.net.Uri.parse("content://x/$zipA"),
                name = zipA, lastModified = 0L, size = 10L,
            ),
            SyncFolder.DocFile(
                uri = android.net.Uri.parse("content://x/${zipA}.json"),
                name = "${zipA}.json", lastModified = 0L, size = 5L,
            ),
            SyncFolder.DocFile(
                uri = android.net.Uri.parse("content://x/junk.txt"),
                name = "junk.txt", lastModified = 0L, size = 1L,
            ),
        )
        val manifestBytes = SyncSnapshot.manifestToJson(manifest).toByteArray()
        val metas = SyncFolder.collectSnapshots(files) { doc ->
            if (doc.name.endsWith(".json")) manifestBytes else null
        }
        assertEquals(1, metas.size)
        // Manifest timestamp wins over the file-name timestamp.
        assertEquals(120L, metas[0].createdAt)
        assertEquals(true, metas[0].manifest?.encrypted)
    }

    @Test
    fun shortDeviceId_truncates() {
        assertEquals("abcdefgh", SyncSnapshot.shortDeviceId("abcdefgh123456"))
        assertEquals("abc", SyncSnapshot.shortDeviceId("abc"))
    }
}
