package com.premiumnotes.input

/**
 * Pure input models. No Android dependencies in this file so the entire
 * classification pipeline is unit-testable on the JVM.
 */

/** User-selectable palm rejection profile. */
enum class PalmRejectionMode {
    /** Rejects nearly all large contacts. Best for handwriting. */
    STRICT,

    /** Allows normal finger interaction while rejecting obvious palm contacts. */
    BALANCED,

    /** Very permissive; only huge smears are rejected. For browsing. */
    RELAXED,

    /** Locks onto the writing contact and rejects every large secondary contact. */
    WRITING,
}

/** A per-contact decision from the palm rejection engine. */
enum class ContactClassification {
    /** Small contact (or real stylus) — accepted as stroke input. */
    WRITING,

    /** Normal finger contact — allowed for UI taps and two-finger gestures. */
    FINGER,

    /** Large contact judged to be a resting palm — ignored for strokes and gestures. */
    PALM,

    /** Hardware-reported eraser tool type. */
    ERASER,

    /** Contact filtered out entirely by the active mode. */
    REJECTED,

    /**
     * A small contact currently being observed by the resting-hand tracker. It landed in
     * a resting-hand context (resting fingers already down, or several contacts at once)
     * so it is not drawn yet: it is promoted to [WRITING] once it moves like a stroke, or
     * demoted to [RESTING] if it stays still for the evaluation window.
     */
    CANDIDATE,

    /**
     * A stationary small contact that is part of a resting hand (a resting finger, the
     * side of a hand near the screen edge, or a member of a stationary cluster). It never
     * draws and never drives gestures.
     */
    RESTING,
}

/** Hardware tool type as reported by the OS (mapped from MotionEvent). */
enum class ToolKind { STYLUS, FINGER, ERASER, MOUSE, UNKNOWN }

/** High-level semantic of a motion event, parsed from the raw action. */
enum class InputAction {
    DOWN, MOVE, UP, POINTER_DOWN, POINTER_UP, CANCEL
}

/** A raw per-pointer sample exactly as reported by MotionEvent. */
data class RawTouchContact(
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val size: Float,
    val toolMajorPx: Float,
    val toolMinorPx: Float,
    val orientation: Float,
    val toolTypeRaw: Int,
    val eventTimeNanos: Long,
    val downTimeNanos: Long,
)

/**
 * One parsed event. [contacts] contains the current position of every pointer that is
 * currently down; [history] carries coalesced samples from the same event so high
 * refresh-rate hardware does not leave gaps in a stroke.
 */
data class InputFrame(
    val action: InputAction,
    val eventTimeNanos: Long,
    val contacts: List<RawTouchContact>,
    val history: List<RawTouchContact> = emptyList(),
    /** For DOWN/POINTER_DOWN: the pointer that just went down. */
    val addedPointerId: Int? = null,
    /** For UP/POINTER_UP: the pointer that just lifted. */
    val liftedPointerId: Int? = null,
) {
    val pointerCount: Int get() = contacts.size
}

/** A normalized contact in world coordinates with real units where available. */
data class NormalizedContact(
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val size: Float,
    val toolMajorMm: Float,
    val toolMinorMm: Float,
    val orientation: Float,
    val toolType: ToolKind,
    val eventTimeNanos: Long,
    val downTimeNanos: Long,
    val hasPressure: Boolean = false,
    val hasGeometry: Boolean = false,
    val hasSize: Boolean = false,
) {
    /** Maximum contact ellipse dimension in millimeters (0 when unknown). */
    val maxDimMm: Float get() = maxOf(toolMajorMm, toolMinorMm)

    /** Approximate contact area in mm² (0 when unknown). */
    val areaMm2: Float
        get() {
            if (!hasGeometry) return 0f
            return Math.PI.toFloat() * (toolMajorMm / 2f) * (toolMinorMm / 2f)
        }

    fun ageMs(nowNanos: Long): Long = (nowNanos - downTimeNanos) / 1_000_000L
}

/** Why a contact was classified a certain way (for diagnostics). */
enum class ClassificationReason {
    HARDWARE_STYLUS,
    HARDWARE_ERASER,
    SMALL_CONTACT,
    MEDIUM_CONTACT,
    LARGE_CONTACT,
    LOCKED_WRITING_POINTER,
    SECONDARY_WHILE_WRITING,
    NO_GEOMETRY_INFO,
    FINGER_WRITING,
    MODE_STRICT_REJECT,
    /** Contact fell inside the user-reserved palm rest zone. */
    IN_PALM_ZONE,
    /** Resting-hand tracker: contact stayed still in a resting context. */
    RESTING_STATIONARY,
    /** Resting-hand tracker: contact is part of a stationary resting cluster. */
    RESTING_CLUSTER,
    /** Resting-hand tracker: contact rests near a screen edge. */
    RESTING_EDGE,
    /** Resting-hand tracker: contact is held for observation before drawing. */
    CANDIDATE_BUFFER,
    /** Resting-hand tracker: a buffered contact moved like a stroke and became the writer. */
    PROMOTED_TO_WRITING,
    /** Resting-hand tracker: a drawing pointer's smoothed contact grew palm-sized. */
    PALM_GROWTH_CANCELLED,
    /** Contact moved but velocity was below the stroke threshold (kept as RESTING). */
    VELOCITY_GATE,
    /** Contact moved slowly in a coherent cluster (hand-shift drift). */
    HAND_SHIFT_DRIFT,
}

data class ClassificationResult(
    val classification: ContactClassification,
    val confidence: Float,
    val reason: ClassificationReason,
    val effectiveThresholdMm: Float,
)

data class ClassifiedContact(
    val contact: NormalizedContact,
    val classification: ContactClassification,
    val confidence: Float,
    val reason: ClassificationReason,
    val effectiveThresholdMm: Float,
    val speedMmPerSec: Float,
    val durationMs: Long,
    /** Screen position where this pointer first went down (for seeding a promoted stroke). */
    val downX: Float? = null,
    val downY: Float? = null,
    /** Windowed velocity (mm/s) over the configured velocity window. */
    val windowedVelocityMmPerSec: Float = 0f,
    /** Total path length travelled since down (mm). */
    val pathLengthMm: Float = 0f,
    /** Write-score (0..1): how much this contact resembles a deliberate stroke. */
    val writeScore: Float = 0f,
    /** Rest-score (0..1): how much this contact resembles a resting hand. */
    val restScore: Float = 0f,
)

/**
 * A per-pointer dynamic state the engine tracks between frames.
 *
 * The resting-hand tracker extends this with motion and contact-size history so it can
 * distinguish a stationary resting finger from the moving writing finger using nothing
 * more than motion, timing, cluster membership and edge proximity — no absolute size
 * assumptions that vary across digitizers.
 */
internal data class PointerMotionState(
    val downTimeNanos: Long,
    val startX: Float,
    val startY: Float,
    var lastX: Float,
    var lastY: Float,
    var lastTimeNanos: Long,
    var speedMmPerSec: Float = 0f,
    var rawSampleCount: Int = 0,
    /** Total distance travelled since this pointer went down, in screen pixels. */
    var totalDistPx: Float = 0f,
    /** Distance travelled between the two most recent samples (screen px). */
    var lastFrameDistPx: Float = 0f,
    /** Time of the last sample that moved more than the jitter dead-zone (nanos). */
    var lastMoveTimeNanos: Long = downTimeNanos,
    /** Contact size (maxDimMm) reported when this pointer first went down. */
    var initialContactSizeMm: Float = 0f,
    /** Exponential-moving-average of the contact size (mm); resists single-frame spikes. */
    var smoothedContactSizeMm: Float = 0f,
    /** Most recent raw contact size (mm). */
    var lastContactSizeMm: Float = 0f,
    /** Resting-hand classification from the previous frame (keeps CANDIDATE/RESTING sticky). */
    var restingClassification: ContactClassification = ContactClassification.WRITING,
    /** True on the first frame a pointer is seen (used by the candidate-buffering rule). */
    var isNew: Boolean = true,

    // --- Velocity window & scoring (Task: velocity-aware resting-hand rejection) ---
    /** Recent samples for sliding-window velocity & continuity analysis. */
    val recentSamples: ArrayDeque<MotionSample> = ArrayDeque(),
    /** Windowed velocity (mm/s) over the configured velocity window. */
    var windowedVelocityMmPerSec: Float = 0f,
    /** Number of moving samples within the window (continuity). */
    var movingSampleCount: Int = 0,
    /** Write-score (0..1): how much this contact resembles a deliberate stroke. */
    var writeScore: Float = 0f,
    /** Rest-score (0..1): how much this contact resembles a resting hand. */
    var restScore: Float = 0f,
)

/** One sample in the velocity/continuity window. */
data class MotionSample(
    val timeNanos: Long,
    val x: Float,
    val y: Float,
)

/** Bounding box of a resting cluster (screen px). */
data class ClusterBounds(
    val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
)

/**
 * Result of running the palm rejection engine over one [InputFrame].
 * [gesturePointerIds] are the (at most two) contacts permitted to pan/zoom.
 */
data class ClassifiedFrame(
    val frame: InputFrame,
    val contacts: List<ClassifiedContact>,
    val activeWritingPointerId: Int?,
    val gesturePointerIds: List<Int>,
    /** Bounding boxes of resting clusters (≥2 stationary close contacts), for debug overlay. */
    val clusterBounds: List<ClusterBounds> = emptyList(),
) {
    fun contactFor(pointerId: Int): ClassifiedContact? =
        contacts.firstOrNull { it.contact.pointerId == pointerId }
}
