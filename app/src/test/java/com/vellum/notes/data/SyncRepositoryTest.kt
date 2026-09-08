package com.vellum.notes.data

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncRepositoryTest {

    private fun repo(): SyncRepository {
        val ctx: Context = RuntimeEnvironment.getApplication()
        return SyncRepository(ctx.syncDataStore())
    }

    @Test
    fun deviceId_stableAcrossResolves() = runBlocking {
        // A fresh DataStore file per test run would be ideal; the id must at
        // least be stable within a process lifetime.
        val first = repo().resolveDeviceId()
        assertTrue(first.isNotBlank())
        assertEquals(first, repo().resolveDeviceId())
        assertEquals(first, repo().deviceId.first())
    }

    @Test
    fun syncDir_roundTripsAndClears() = runBlocking {
        val repo = repo()
        repo.setSyncDir("content://tree/1234")
        assertEquals("content://tree/1234", repo.syncDirUri.first())
        repo.clearSyncDir()
        assertNull(repo.syncDirUri.first())
    }
}
