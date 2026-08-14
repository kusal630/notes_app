package com.premiumnotes.input

import kotlin.math.abs

/** Stored calibration measurements from the diagnostics screen. */
data class CalibrationData(
    val fingerMaxDimMm: Float? = null,
    val penMaxDimMm: Float? = null,
    val palmMaxDimMm: Float? = null,
)

/**
 * User-configurable palm rejection settings. In-memory for now; will be persisted via
 * DataStore in a later milestone. Values are in physical millimeters so behavior is
 * consistent across screen densities.
 *
 * The defaults are calibrated for a 10" tablet: a fingertip contact is roughly 8–12 mm,
 * a resting palm 25–60 mm, a stylus/fingernail pen 4–8 mm.
 */
class PalmRejectionSettings(
    var mode: PalmRejectionMode = PalmRejectionMode.WRITING,
    /** 0..1; higher = more aggressive palm rejection (smaller accepted contact). */
    var sensitivity: Float = 0.5f,
    /**
     * Baseline maximum dimension (mm) of a contact still accepted as writing.
     * Scaled by sensitivity: effective = baseline * (1.6f - sensitivity).
     */
    var writingMaxMm: Float = 9f,
    /** Baseline maximum dimension (mm) of a normal finger gesture contact. */
    var fingerMaxMm: Float = 14f,
    /** Above this dimension (mm) a contact is treated as a palm in relaxed mode. */
    var relaxedPalmMm: Float = 30f,
    /** Writing-lock hold-off after a pen lift before a new pointer can claim writing. */
    var writingHoldoffMs: Long = 120L,
    /** Distance (mm) within which a large contact near the writing pointer is accepted. */
    var palmProximityMm: Float = 8f,
    var calibration: CalibrationData = CalibrationData(),
    var smoothing: SmoothingMode = SmoothingMode.MEDIUM,
) {
    /** Applies user calibration and sensitivity to yield the effective writing cutoff. */
    fun effectiveWritingMaxMm(): Float {
        val base = calibration.penMaxDimMm ?: writingMaxMm
        return base * (1.6f - sensitivity.coerceIn(0f, 1f))
    }

    fun effectiveFingerMaxMm(): Float =
        (calibration.fingerMaxDimMm ?: fingerMaxMm) * (1.35f - 0.4f * sensitivity.coerceIn(0f, 1f))

    fun effectiveRelaxedPalmMm(): Float =
        calibration.palmMaxDimMm ?: relaxedPalmMm

    fun normalizedSensitivity(): Float = sensitivity.coerceIn(0f, 1f)

    companion object {
        fun clampMode(v: PalmRejectionMode): PalmRejectionMode =
            v

        fun distanceMm(ax: Float, ay: Float, bx: Float, by: Float): Float =
            abs(kotlin.math.hypot(ax - bx, ay - by))
    }
}