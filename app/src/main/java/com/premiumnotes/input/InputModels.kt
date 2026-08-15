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
)

/** Per-pointer dynamic state the engine tracks between frames. */
internal data class PointerMotionState(
    val downTimeNanos: Long,
    var lastX: Float,
    var lastY: Float,
    var lastTimeNanos: Long,
    var speedMmPerSec: Float = 0f,
    var rawSampleCount: Int = 0,
)

/**
 * Result of running the palm rejection engine over one [InputFrame].
 * [gesturePointerIds] are the (at most two) contacts permitted to drive pan/zoom.
 */
data class ClassifiedFrame(
    val frame: InputFrame,
    val contacts: List<ClassifiedContact>,
    val activeWritingPointerId: Int?,
    val gesturePointerIds: List<Int>,
) {
    fun contactFor(pointerId: Int): ClassifiedContact? =
        contacts.firstOrNull { it.contact.pointerId == pointerId }
}
