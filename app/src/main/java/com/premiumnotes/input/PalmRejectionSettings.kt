package com.premiumnotes.input

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
data class PalmRejectionSettings(
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
    /**
     * The user-reserved palm rest zone (see [PalmZone]). When enabled, contacts whose
     * center lands inside the zone are unconditionally treated as the resting palm.
     */
    var palmZone: PalmZone = PalmZone(),
    /**
     * Allows a bare finger to become the writing pointer while palm rejection stays
     * active: in WRITING/STRICT modes a finger-sized contact is accepted as writing,
     * and the writing lock still rejects any additional (palm) contact.
     */
    var enableFingerWriting: Boolean = true,
    /**
     * When on, finished handwriting strokes are automatically converted to typed text.
     * Recognition is not implemented yet, so this switch currently has no effect.
     */
    var autoConvertHandwritingToText: Boolean = false,
    /**
     * When on, a tight scribble gesture over the page automatically erases instead of
     * writing (see [com.premiumnotes.input.WriteEraseDetector]). Default off so manual
     * pen/eraser behavior is exactly as before; manual tool selection always overrides
     * the heuristic because it only affects the current gesture.
     */
    var autoEraseEnabled: Boolean = false,

    // --- Resting-hand / palm rejection knobs (Task 1) -------------------------------
    /**
     * Master switch for the entire palm/resting-hand rejection pipeline. When off, every
     * contact is treated as writable/finger input (no size-based rejection).
     */
    var palmRejectionEnabled: Boolean = true,
    /**
     * When on, the resting-hand tracker runs: stationary fingers, the side of the hand,
     * and multi-contact resting clusters are ignored while a moving writing pointer is
     * still accepted. This is the behavior that lets a user write with their hand resting
     * on the screen.
     */
    var restingHandModeEnabled: Boolean = true,
    /**
     * Smoothed contact size (mm) above which a drawing pointer is cancelled as a palm.
     * Only reached when the growth is sustained (smoothing + hysteresis), so a single
     * digitizer spike never kills an in-progress stroke.
     */
    var palmSizeThresholdMm: Float = 24f,
    /**
     * Contact size (mm) considered "suspicious" — large enough that combined with other
     * resting signals it hints at a palm even when below [palmSizeThresholdMm].
     */
    var suspiciousSizeThresholdMm: Float = 16f,
    /**
     * Distance (mm) a buffered [ContactClassification.CANDIDATE] must travel before it is
     * promoted to the writing pointer. Stroke-like motion promotes it; anything less is
     * jitter from a resting finger.
     */
    var movementPromoteThresholdMm: Float = 3f,
    /**
     * How long a contact may remain (nearly) stationary in a resting context before it is
     * classified [ContactClassification.RESTING] and stops drawing / driving gestures.
     */
    var stationaryRestTimeMs: Long = 350L,
    /**
     * Observation window for a buffered candidate: it is promoted on movement or demoted
     * to [ContactClassification.RESTING] after this long without movement.
     */
    var candidateEvaluationWindowMs: Long = 250L,
    /** Distance (mm) from a screen edge within which a contact is considered edge-adjacent. */
    var edgeMarginMm: Float = 30f,
    /** Max distance (mm) between two contacts for them to be part of the same resting cluster. */
    var clusterDistanceThresholdMm: Float = 45f,
    /**
     * How long (ms) cluster members must stay still before the cluster is treated as a
     * resting hand rather than an in-progress two-finger gesture.
     */
    var clusterStationaryThresholdMs: Long = 250L,
    /**
     * When on, the resting-hand tracker cancels a drawing pointer whose smoothed contact
     * size grows into palm territory (e.g. the user flattens their finger mid-stroke).
     */
    var palmGrowthCancelEnabled: Boolean = true,
    /** A drawing pointer is cancelled as a palm only when its smoothed size exceeds its
     *  initial size by at least this multiple (guards against a finger flattening briefly). */
    var palmGrowthFactor: Float = 2.2f,
    /**
     * Windowed velocity threshold (mm/s) for stroke-like motion. The sliding window is
     * [velocityWindowMs]. A contact must exceed this velocity to be considered a writer
     * (movement gate). Adaptive: raised if the resting hand produces high-velocity noise.
     */
    var minPromoteVelocityMmPerSec: Float = 120f,
    /**
     * Sliding window duration (ms) for velocity & continuity analysis. Must be 80–180 ms
     * per the palm rejection design. Default 120 ms.
     */
    var velocityWindowMs: Long = 120L,
    /**
     * Size threshold (mm) for growth cancellation of a locked writer. Defaults to
     * palmSizeThresholdMm * 1.15 (27.6 mm). Kept separate so it can be tuned independently.
     */
    var sizeGrowthCancelThresholdMm: Float = 27.6f,
    /**
     * When on, an isolated small contact (no resting hand nearby) draws immediately
     * without being buffered as a CANDIDATE. When off, even isolated contacts are
     * observed until they move like a stroke. Default on for backward compatibility.
     */
    var allowImmediateDrawWhenIsolated: Boolean = true,
    /**
     * When on, the canvas draws a live overlay of every contact with its classification
     * color, size circle and pointer id so resting-hand behavior can be verified on-device.
     */
    var debugOverlayEnabled: Boolean = false,
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
}