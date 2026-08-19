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
import com.premiumnotes.input.PalmZone
import com.premiumnotes.input.PalmZoneMode
import com.premiumnotes.input.PalmZoneSide
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
        private val AUTO_CONVERT_HANDWRITING_KEY = booleanPreferencesKey("auto_convert_handwriting")
        private val AUTO_ERASE_KEY = booleanPreferencesKey("auto_erase_enabled")
        private val CALIBRATION_FINGER_KEY = floatPreferencesKey("calibration_finger")
        private val CALIBRATION_PEN_KEY = floatPreferencesKey("calibration_pen")
        private val CALIBRATION_PALM_KEY = floatPreferencesKey("calibration_palm")
        private val PALM_ZONE_MODE_KEY = intPreferencesKey("palm_zone_mode")
        private val PALM_ZONE_SIDE_KEY = intPreferencesKey("palm_zone_side")
        private val PALM_ZONE_CX_KEY = floatPreferencesKey("palm_zone_cx")
        private val PALM_ZONE_CY_KEY = floatPreferencesKey("palm_zone_cy")
        private val PALM_ZONE_W_KEY = floatPreferencesKey("palm_zone_w")
        private val PALM_ZONE_H_KEY = floatPreferencesKey("palm_zone_h")
        private val PALM_REJECTION_ENABLED_KEY = booleanPreferencesKey("palm_rejection_enabled")
        private val RESTING_HAND_ENABLED_KEY = booleanPreferencesKey("resting_hand_enabled")
        private val PALM_SIZE_THRESHOLD_KEY = floatPreferencesKey("palm_size_threshold_mm")
        private val SUSPICIOUS_SIZE_THRESHOLD_KEY = floatPreferencesKey("suspicious_size_threshold_mm")
        private val MOVEMENT_PROMOTE_KEY = floatPreferencesKey("movement_promote_mm")
        private val STATIONARY_REST_MS_KEY = longPreferencesKey("stationary_rest_ms")
        private val CANDIDATE_WINDOW_MS_KEY = longPreferencesKey("candidate_window_ms")
        private val EDGE_MARGIN_KEY = floatPreferencesKey("edge_margin_mm")
        private val CLUSTER_DIST_KEY = floatPreferencesKey("cluster_dist_mm")
        private val CLUSTER_STATIONARY_MS_KEY = longPreferencesKey("cluster_stationary_ms")
        private val PALM_GROWTH_CANCEL_KEY = booleanPreferencesKey("palm_growth_cancel")
        private val PALM_GROWTH_FACTOR_KEY = floatPreferencesKey("palm_growth_factor")
        private val DEBUG_OVERLAY_KEY = booleanPreferencesKey("debug_overlay")
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
            prefs[AUTO_CONVERT_HANDWRITING_KEY] = current.autoConvertHandwritingToText
            prefs[AUTO_ERASE_KEY] = current.autoEraseEnabled
            current.calibration.fingerMaxDimMm?.let { prefs[CALIBRATION_FINGER_KEY] = it }
            current.calibration.penMaxDimMm?.let { prefs[CALIBRATION_PEN_KEY] = it }
            current.calibration.palmMaxDimMm?.let { prefs[CALIBRATION_PALM_KEY] = it }
            prefs[PALM_ZONE_MODE_KEY] = current.palmZone.mode.ordinal
            prefs[PALM_ZONE_SIDE_KEY] = current.palmZone.side.ordinal
            prefs[PALM_ZONE_CX_KEY] = current.palmZone.centerXFrac
            prefs[PALM_ZONE_CY_KEY] = current.palmZone.centerYFrac
            prefs[PALM_ZONE_W_KEY] = current.palmZone.widthMm
            prefs[PALM_ZONE_H_KEY] = current.palmZone.heightMm
            prefs[PALM_REJECTION_ENABLED_KEY] = current.palmRejectionEnabled
            prefs[RESTING_HAND_ENABLED_KEY] = current.restingHandModeEnabled
            prefs[PALM_SIZE_THRESHOLD_KEY] = current.palmSizeThresholdMm
            prefs[SUSPICIOUS_SIZE_THRESHOLD_KEY] = current.suspiciousSizeThresholdMm
            prefs[MOVEMENT_PROMOTE_KEY] = current.movementPromoteThresholdMm
            prefs[STATIONARY_REST_MS_KEY] = current.stationaryRestTimeMs
            prefs[CANDIDATE_WINDOW_MS_KEY] = current.candidateEvaluationWindowMs
            prefs[EDGE_MARGIN_KEY] = current.edgeMarginMm
            prefs[CLUSTER_DIST_KEY] = current.clusterDistanceThresholdMm
            prefs[CLUSTER_STATIONARY_MS_KEY] = current.clusterStationaryThresholdMs
            prefs[PALM_GROWTH_CANCEL_KEY] = current.palmGrowthCancelEnabled
            prefs[PALM_GROWTH_FACTOR_KEY] = current.palmGrowthFactor
            prefs[DEBUG_OVERLAY_KEY] = current.debugOverlayEnabled
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
            autoConvertHandwritingToText = prefs[AUTO_CONVERT_HANDWRITING_KEY] ?: false,
            autoEraseEnabled = prefs[AUTO_ERASE_KEY] ?: false,
            palmZone = PalmZone(
                mode = PalmZoneMode.entries.getOrNull(prefs[PALM_ZONE_MODE_KEY] ?: PalmZoneMode.AUTO.ordinal)
                    ?: PalmZoneMode.AUTO,
                side = PalmZoneSide.entries.getOrNull(prefs[PALM_ZONE_SIDE_KEY] ?: PalmZoneSide.LEFT.ordinal)
                    ?: PalmZoneSide.LEFT,
                centerXFrac = prefs[PALM_ZONE_CX_KEY] ?: 0.18f,
                centerYFrac = prefs[PALM_ZONE_CY_KEY] ?: 0.72f,
                widthMm = prefs[PALM_ZONE_W_KEY] ?: 72f,
                heightMm = prefs[PALM_ZONE_H_KEY] ?: 60f,
            ),
            palmRejectionEnabled = prefs[PALM_REJECTION_ENABLED_KEY] ?: true,
            restingHandModeEnabled = prefs[RESTING_HAND_ENABLED_KEY] ?: true,
            palmSizeThresholdMm = prefs[PALM_SIZE_THRESHOLD_KEY] ?: 24f,
            suspiciousSizeThresholdMm = prefs[SUSPICIOUS_SIZE_THRESHOLD_KEY] ?: 16f,
            movementPromoteThresholdMm = prefs[MOVEMENT_PROMOTE_KEY] ?: 3f,
            stationaryRestTimeMs = prefs[STATIONARY_REST_MS_KEY] ?: 350L,
            candidateEvaluationWindowMs = prefs[CANDIDATE_WINDOW_MS_KEY] ?: 250L,
            edgeMarginMm = prefs[EDGE_MARGIN_KEY] ?: 30f,
            clusterDistanceThresholdMm = prefs[CLUSTER_DIST_KEY] ?: 45f,
            clusterStationaryThresholdMs = prefs[CLUSTER_STATIONARY_MS_KEY] ?: 250L,
            palmGrowthCancelEnabled = prefs[PALM_GROWTH_CANCEL_KEY] ?: true,
            palmGrowthFactor = prefs[PALM_GROWTH_FACTOR_KEY] ?: 2.2f,
            debugOverlayEnabled = prefs[DEBUG_OVERLAY_KEY] ?: false,
        )
}