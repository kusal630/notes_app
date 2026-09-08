package com.vellum.notes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncDataStoreInstance: DataStore<Preferences> by preferencesDataStore(
    name = "vellum_sync.preferences_pb",
)

/** Returns the application-scoped sync DataStore. */
fun Context.syncDataStore(): DataStore<Preferences> = syncDataStoreInstance

/**
 * Device-sync settings: the Syncthing-mirrored folder (SAF tree URI, persisted
 * permissions are taken by the UI at pick time) and the stable device id used
 * to tell snapshots apart. The sync passphrase is NEVER stored — it is asked
 * at export/import time.
 */
class SyncRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val SYNC_DIR_URI_KEY = stringPreferencesKey("sync_dir_uri")
        private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
    }

    val syncDirUri: Flow<String?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[SYNC_DIR_URI_KEY] }

    val deviceId: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[DEVICE_ID_KEY].orEmpty() }

    suspend fun setSyncDir(uri: String) {
        dataStore.edit { prefs -> prefs[SYNC_DIR_URI_KEY] = uri }
    }

    suspend fun clearSyncDir() {
        dataStore.edit { prefs -> prefs.remove(SYNC_DIR_URI_KEY) }
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
}
