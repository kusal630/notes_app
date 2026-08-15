package com.premiumnotes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.premiumnotes.input.CalibrationData
import com.premiumnotes.input.PalmRejectionMode
import com.premiumnotes.input.PalmRejectionSettings
import com.premiumnotes.input.SmoothingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStoreInstance: DataStore<Preferences> by preferencesDataStore(
    name = "premium_notes_settings.preferences_pb",
)

/** Returns the application-scoped settings DataStore. */
fun Context.settingsDataStore(): DataStore<Preferences> = settingsDataStoreInstance

/**
 * Persists [PalmRejectionSettings] using Preferences DataStore.
 * Exposes a [Flow] of settings that the UI can observe.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val MODE_KEY = intPreferencesKey("mode")
        private val SENSITIVITY_KEY = floatPreferencesKey("sensitivity")
        private val WRITING_MAX_MM_KEY = floatPreferencesKey("writing_max_mm")
        private val FINGER_MAX_MM_KEY = floatPreferencesKey("finger_max_mm")
        private val RELAXED_PALM_MM_KEY = floatPreferencesKey("relaxed_palm_mm")
        private val WRITING_HOLDOFF_MS_KEY = longPreferencesKey("writing_holdoff_ms")
        private val PALM_PROXIMITY_MM_KEY = floatPreferencesKey("palm_proximity_mm")
        private val SMOOTHING_KEY = intPreferencesKey("smoothing")
        private val FINGER_WRITING_KEY = booleanPreferencesKey("finger_writing")
        private val CALIBRATION_FINGER_KEY = floatPreferencesKey("calibration_finger")
        private val CALIBRATION_PEN_KEY = floatPreferencesKey("calibration_pen")
        private val CALIBRATION_PALM_KEY = floatPreferencesKey("calibration_palm")
    }

    val settingsFlow: Flow<PalmRejectionSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefsToSettings(prefs) }

    /** Applies [block] to the current settings and persists the result atomically. */
    suspend fun updateSettings(block: PalmRejectionSettings.() -> Unit) {
        val current = settingsFlow.first()
        current.block()
        dataStore.edit { prefs ->
            prefs[MODE_KEY] = current.mode.ordinal
            prefs[SENSITIVITY_KEY] = current.sensitivity
            prefs[WRITING_MAX_MM_KEY] = current.writingMaxMm
            prefs[FINGER_MAX_MM_KEY] = current.fingerMaxMm
            prefs[RELAXED_PALM_MM_KEY] = current.relaxedPalmMm
            prefs[WRITING_HOLDOFF_MS_KEY] = current.writingHoldoffMs
            prefs[PALM_PROXIMITY_MM_KEY] = current.palmProximityMm
            prefs[SMOOTHING_KEY] = current.smoothing.ordinal
            prefs[FINGER_WRITING_KEY] = current.enableFingerWriting
            current.calibration.fingerMaxDimMm?.let { prefs[CALIBRATION_FINGER_KEY] = it }
            current.calibration.penMaxDimMm?.let { prefs[CALIBRATION_PEN_KEY] = it }
            current.calibration.palmMaxDimMm?.let { prefs[CALIBRATION_PALM_KEY] = it }
        }
    }

    suspend fun resetToDefaults() {
        dataStore.edit { it.clear() }
    }

    private fun prefsToSettings(prefs: Preferences): PalmRejectionSettings =
        PalmRejectionSettings(
            mode = PalmRejectionMode.entries.getOrNull(prefs[MODE_KEY] ?: PalmRejectionMode.WRITING.ordinal)
                ?: PalmRejectionMode.WRITING,
            sensitivity = prefs[SENSITIVITY_KEY] ?: 0.5f,
            writingMaxMm = prefs[WRITING_MAX_MM_KEY] ?: 9f,
            fingerMaxMm = prefs[FINGER_MAX_MM_KEY] ?: 14f,
            relaxedPalmMm = prefs[RELAXED_PALM_MM_KEY] ?: 30f,
            writingHoldoffMs = prefs[WRITING_HOLDOFF_MS_KEY] ?: 120L,
            palmProximityMm = prefs[PALM_PROXIMITY_MM_KEY] ?: 8f,
            calibration = CalibrationData(
                fingerMaxDimMm = prefs[CALIBRATION_FINGER_KEY],
                penMaxDimMm = prefs[CALIBRATION_PEN_KEY],
                palmMaxDimMm = prefs[CALIBRATION_PALM_KEY],
            ),
            smoothing = SmoothingMode.entries.getOrNull(prefs[SMOOTHING_KEY] ?: SmoothingMode.MEDIUM.ordinal)
                ?: SmoothingMode.MEDIUM,
            enableFingerWriting = prefs[FINGER_WRITING_KEY] ?: true,
        )
}