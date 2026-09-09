package com.vellum.notes.data

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Background sync verification with exponential-backoff retry.
 *
 * Lightweight by design: it does NOT auto-import (SQLite files cannot be
 * auto-merged — imports stay explicit in [com.vellum.notes.ui.sync.SyncSection]).
 * It verifies the sync folder is still reachable, refreshes conflict state
 * against the newest inbound snapshot, and records [SyncStatus] so the
 * CanvasTopBar indicator stays truthful.
 *
 * Retry policy: transient I/O (SecurityException = lost SAF permission,
 * IOException) → [Result.retry] so WorkManager applies EXPONENTIAL backoff
 * (30s base, see [SyncRepository.schedulePeriodicSync]). After
 * [SyncRepository.MAX_RETRY_ATTEMPTS] attempts the worker records FAILED
 * instead of retrying forever.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = SyncRepository(applicationContext.syncDataStore())
        val attempt = runAttemptCount
        if (attempt >= SyncRepository.MAX_RETRY_ATTEMPTS) {
            repo.markSyncFailed("Sync unavailable after $attempt attempts.")
            return Result.failure()
        }
        return try {
            val dir = repo.syncDirUri.first()
            if (dir.isNullOrBlank()) {
                repo.setStatus(SyncStatus.DISABLED)
                return Result.success()
            }
            repo.markSyncStarted()
            val outcome = withContext(Dispatchers.IO) {
                val treeUri = runCatching { Uri.parse(dir) }.getOrNull()
                    ?: return@withContext ScanOutcome.Unreadable("Invalid sync folder URI.")
                val files = try {
                    SyncFolder.listFiles(applicationContext.contentResolver, treeUri)
                } catch (e: SecurityException) {
                    return@withContext ScanOutcome.Retryable(e)
                } catch (e: IOException) {
                    return@withContext ScanOutcome.Retryable(e)
                }
                ScanOutcome.Ok(files)
            }
            when (outcome) {
                is ScanOutcome.Ok -> {
                    val ownId = repo.resolveDeviceId()
                    val all = SyncFolder.collectSnapshots(outcome.files) { doc ->
                        SyncFolder.readBytes(applicationContext.contentResolver, doc.uri)
                    }
                    val inbound = SyncSnapshot.inboundSnapshots(all, ownId)
                    val lastSync = repo.lastSyncAtMs.first()
                    val newest = inbound.maxByOrNull { it.createdAt }
                    if (newest != null && lastSync != null && newest.createdAt > lastSync) {
                        // Local-modification time is unknown to the worker; flag a
                        // conflict only when we already had a successful sync and a
                        // strictly newer inbound exists. The settings UI re-checks
                        // with local timestamps via detectConflict before dialoging.
                        repo.setConflict(newest)
                    } else if (repo.syncStatus.first() == SyncStatus.SYNCING) {
                        repo.setStatus(SyncStatus.IDLE)
                    }
                    Result.success()
                }
                is ScanOutcome.Unreadable -> {
                    repo.markSyncFailed(outcome.reason)
                    Result.failure()
                }
                is ScanOutcome.Retryable -> {
                    // Transient: let WorkManager back off exponentially and retry.
                    // Record SYNCING (not FAILED) so the indicator doesn't flap.
                    Result.retry()
                }
            }
        } catch (t: IOException) {
            Result.retry()
        } catch (t: SecurityException) {
            Result.retry()
        } catch (t: Throwable) {
            repo.markSyncFailed(t.message ?: "Sync check failed.")
            Result.failure()
        }
    }

    private sealed interface ScanOutcome {
        data class Ok(val files: List<SyncFolder.DocFile>) : ScanOutcome
        data class Unreadable(val reason: String) : ScanOutcome
        data class Retryable(val cause: Throwable) : ScanOutcome
    }
}
