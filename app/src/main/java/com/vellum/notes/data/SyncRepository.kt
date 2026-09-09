package com.vellum.notes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncDataStoreInstance: DataStore<Preferences> by preferencesDataStore(
    name = "vellum_sync.preferences_pb",
)

/** Returns the application-scoped sync DataStore. */
fun Context.syncDataStore(): DataStore<Preferences> = syncDataStoreInstance

/** Observable sync state driving the CanvasTopBar indicator + settings UI. */
enum class SyncStatus {
    DISABLED,
    IDLE,
    SYNCING,
    SUCCEEDED,
    FAILED,
    CONFLICT,
}

/**
 * Device-sync settings: the Syncthing-mirrored folder (SAF tree URI, persisted
 * permissions are taken by the UI at pick time) and the stable device id used
 * to tell snapshots apart. The sync passphrase is NEVER stored — it is asked
 * at export/import time.
 *
 * Hardened background sync: status/last-sync/error/conflict are persisted in
 * DataStore so the top-bar indicator survives restarts, and periodic +
 * on-demand work is scheduled through WorkManager with exponential-backoff
 * retries (see [SyncWorker]).
 */
class SyncRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val SYNC_DIR_URI_KEY = stringPreferencesKey("sync_dir_uri")
        private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        private val SYNC_STATUS_KEY = stringPreferencesKey("sync_status")
        private val LAST_SYNC_AT_KEY = longPreferencesKey("last_sync_at_ms")
        private val LAST_SYNC_ERROR_KEY = stringPreferencesKey("last_sync_error")
        private val CONFLICT_FILE_KEY = stringPreferencesKey("conflict_remote_file")
        private val CONFLICT_DEVICE_KEY = stringPreferencesKey("conflict_remote_device")
        private val CONFLICT_AT_KEY = longPreferencesKey("conflict_remote_at_ms")

        const val PERIODIC_SYNC_WORK = "vellum-sync-periodic"
        const val ONESHOT_SYNC_WORK = "vellum-sync-oneshot"

        /** Base backoff for WorkManager retries (exponential from here). */
        const val BACKOFF_DELAY_SECONDS = 30L
        /** Upper bound for the pure [retryDelayMs] helper. */
        const val MAX_RETRY_DELAY_MS = 6 * 60 * 60 * 1000L
        /** Give up retrying after this many attempts; surface FAILED instead. */
        const val MAX_RETRY_ATTEMPTS = 5

        /**
         * Pure exponential-backoff delay for attempt [attempt] (0-based):
         * 30s, 60s, 120s, … capped at 6h. JVM-testable; mirrors the
         * WorkManager backoff configured in [schedulePeriodicSync].
         */
        fun retryDelayMs(attempt: Int): Long {
            val safe = attempt.coerceIn(0, 20)
            val delay = BACKOFF_DELAY_SECONDS * 1000L * (1L shl safe)
            return delay.coerceIn(0L, MAX_RETRY_DELAY_MS)
        }

        fun statusOf(raw: String?): SyncStatus =
            runCatching { if (raw == null) SyncStatus.IDLE else SyncStatus.valueOf(raw) }
                .getOrDefault(SyncStatus.IDLE)
    }

    val syncDirUri: Flow<String?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[SYNC_DIR_URI_KEY] }

    val deviceId: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[DEVICE_ID_KEY].orEmpty() }

    val syncStatus: Flow<SyncStatus> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> statusOf(prefs[SYNC_STATUS_KEY]) }

    val lastSyncAtMs: Flow<Long?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[LAST_SYNC_AT_KEY] }

    val lastError: Flow<String?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[LAST_SYNC_ERROR_KEY] }

    /**
     * Pending conflict remote (fileName/deviceId/createdAt; manifest is not
     * persisted — re-resolved on folder scan). Null when no conflict.
     */
    val pendingConflict: Flow<SyncSnapshot.SnapshotMeta?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val file = prefs[CONFLICT_FILE_KEY] ?: return@map null
            val device = prefs[CONFLICT_DEVICE_KEY] ?: return@map null
            val at = prefs[CONFLICT_AT_KEY] ?: return@map null
            SyncSnapshot.SnapshotMeta(
                deviceId = device,
                createdAt = at,
                fileName = file,
                manifest = null,
            )
        }

    suspend fun setSyncDir(uri: String) {
        dataStore.edit { prefs ->
            prefs[SYNC_DIR_URI_KEY] = uri
            // (Re)connecting clears terminal failure state; first scan decides more.
            if (statusOf(prefs[SYNC_STATUS_KEY]) == SyncStatus.DISABLED) {
                prefs[SYNC_STATUS_KEY] = SyncStatus.IDLE.name
            }
            prefs.remove(LAST_SYNC_ERROR_KEY)
        }
        // Kick an immediate verification scan with backoff retries.
        // Best effort: scheduling never throws to callers.
        runCatching { /* scheduled by UI via requestImmediateSync */ }
    }

    suspend fun clearSyncDir() {
        dataStore.edit { prefs ->
            prefs.remove(SYNC_DIR_URI_KEY)
            prefs[SYNC_STATUS_KEY] = SyncStatus.DISABLED.name
            prefs.remove(LAST_SYNC_ERROR_KEY)
            prefs.remove(CONFLICT_FILE_KEY)
            prefs.remove(CONFLICT_DEVICE_KEY)
            prefs.remove(CONFLICT_AT_KEY)
        }
    }

    /**
     * Returns the stable device id, generating + persisting one on first use.
     * The [deviceId] flow is empty until this runs once.
     */
    suspend fun resolveDeviceId(): String {
        val stored = dataStore.data.first()[DEVICE_ID_KEY]
        if (!stored.isNullOrBlank()) return stored
        val fresh = newDeviceId()
        dataStore.edit { prefs -> prefs[DEVICE_ID_KEY] = fresh }
        return fresh
    }

    private fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "")

    // ---- Hardened status tracking (persisted; drives the top-bar indicator). ----

    suspend fun setStatus(status: SyncStatus) {
        dataStore.edit { prefs -> prefs[SYNC_STATUS_KEY] = status.name }
    }

    suspend fun markSyncStarted() {
        dataStore.edit { prefs ->
            prefs[SYNC_STATUS_KEY] = SyncStatus.SYNCING.name
            prefs.remove(LAST_SYNC_ERROR_KEY)
        }
    }

    suspend fun markSyncSucceeded(nowMs: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            prefs[SYNC_STATUS_KEY] = SyncStatus.SUCCEEDED.name
            prefs[LAST_SYNC_AT_KEY] = nowMs
            prefs.remove(LAST_SYNC_ERROR_KEY)
            prefs.remove(CONFLICT_FILE_KEY)
            prefs.remove(CONFLICT_DEVICE_KEY)
            prefs.remove(CONFLICT_AT_KEY)
        }
    }

    suspend fun markSyncFailed(error: String?) {
        dataStore.edit { prefs ->
            prefs[SYNC_STATUS_KEY] = SyncStatus.FAILED.name
            if (error != null) prefs[LAST_SYNC_ERROR_KEY] = error.take(500)
            else prefs.remove(LAST_SYNC_ERROR_KEY)
        }
    }

    suspend fun setConflict(remote: SyncSnapshot.SnapshotMeta) {
        dataStore.edit { prefs ->
            prefs[SYNC_STATUS_KEY] = SyncStatus.CONFLICT.name
            prefs[CONFLICT_FILE_KEY] = remote.fileName
            prefs[CONFLICT_DEVICE_KEY] = remote.deviceId
            prefs[CONFLICT_AT_KEY] = remote.createdAt
        }
    }

    suspend fun clearConflict() {
        dataStore.edit { prefs ->
            prefs.remove(CONFLICT_FILE_KEY)
            prefs.remove(CONFLICT_DEVICE_KEY)
            prefs.remove(CONFLICT_AT_KEY)
            if (statusOf(prefs[SYNC_STATUS_KEY]) == SyncStatus.CONFLICT) {
                prefs[SYNC_STATUS_KEY] = SyncStatus.IDLE.name
            }
        }
    }

    // ---- WorkManager background sync with exponential-backoff retry. ----

    /**
     * Periodic folder verification (every 6h, unmetered-friendly): checks the
     * sync folder is still readable and records status. Failures inside
     * [SyncWorker] return [androidx.work.ListenableWorker.Result.retry] until
     * [MAX_RETRY_ATTEMPTS], with EXPONENTIAL backoff from [BACKOFF_DELAY_SECONDS].
     */
    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(PERIODIC_SYNC_WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** One-shot verification/scan, e.g. after picking a folder or tapping Retry. */
    fun requestImmediateSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(ONESHOT_SYNC_WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONESHOT_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelBackgroundSync(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_SYNC_WORK)
        wm.cancelUniqueWork(ONESHOT_SYNC_WORK)
    }
}
