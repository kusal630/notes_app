package com.premiumnotes.input

import android.view.MotionEvent

/**
 * Builders for synthetic input streams used to simulate pen, finger, palm and
 * multi-touch contacts. Pure Kotlin — reused across the input test suites.
 */
object TestTouchFactory {

    const val TOOL_FINGER = MotionEvent.TOOL_TYPE_FINGER.toLong().toInt()
    const val TOOL_STYLUS = MotionEvent.TOOL_TYPE_STYLUS.toLong().toInt()
    const val TOOL_UNKNOWN = MotionEvent.TOOL_TYPE_UNKNOWN.toLong().toInt()
    const val TOOL_ERASER = MotionEvent.TOOL_TYPE_ERASER.toLong().toInt()

    fun contact(
        pointerId: Int,
        x: Float,
        y: Float,
        timeMs: Long,
        downTimeMs: Long = timeMs,
        majorPx: Float = 40f,
        minorPx: Float = 36f,
        pressure: Float = 0.5f,
        size: Float = 0.03f,
        toolType: Int = TOOL_FINGER,
    ) = RawTouchContact(
        pointerId = pointerId,
        x = x,
        y = y,
        pressure = pressure,
        size = size,
        toolMajorPx = majorPx,
        toolMinorPx = minorPx,
        orientation = 0f,
        toolTypeRaw = toolType,
        eventTimeNanos = timeMs * 1_000_000L,
        downTimeNanos = downTimeMs * 1_000_000L,
    )

    fun frame(
        action: InputAction,
        timeMs: Long,
        contacts: List<RawTouchContact>,
        history: List<RawTouchContact> = emptyList(),
        added: Int? = null,
        lifted: Int? = null,
    ) = InputFrame(
        action = action,
        eventTimeNanos = timeMs * 1_000_000L,
        contacts = contacts,
        history = history,
        addedPointerId = added,
        liftedPointerId = lifted,
    )

    /** A small pen-like contact (≈2.5mm major at ~10px/mm). */
    fun pen(
        pointerId: Int = 0,
        x: Float = 100f,
        y: Float = 100f,
        timeMs: Long = 0L,
        majorPx: Float = 26f,
        minorPx: Float = 24f,
        toolType: Int = TOOL_FINGER,
    ) = contact(pointerId, x, y, timeMs, majorPx = majorPx, minorPx = minorPx, pressure = 0.6f, size = 0.02f, toolType = toolType)

    /** A fingertip contact (≈11mm, typical human fingertip). */
    fun fingertip(
        pointerId: Int = 1,
        x: Float = 200f,
        y: Float = 200f,
        timeMs: Long = 0L,
    ) = contact(pointerId, x, y, timeMs, majorPx = 110f, minorPx = 100f, pressure = 0.7f, size = 0.05f)

    /** A large palm contact (≈30mm). */
    fun palm(
        pointerId: Int = 2,
        x: Float = 500f,
        y: Float = 700f,
        timeMs: Long = 0L,
        toolType: Int = TOOL_FINGER,
    ) = contact(pointerId, x, y, timeMs, majorPx = 300f, minorPx = 240f, pressure = 1.0f, size = 0.28f, toolType = toolType)
}

/** Capabilities for a typical 10" tablet: ~10 px/mm, no stylus hardware. */
fun testCapabilities(
    pxPerMm: Float = 10f,
    supportsStylusToolType: Boolean = false,
    displayMaxPx: Float = 2000f,
) = InputCapabilities(
    pxPerMm = pxPerMm,
    displayMaxPx = displayMaxPx,
    screenDiagonalMm = 254f,
    supportsStylus = supportsStylusToolType,
    supportsToolType = true,
    supportsStylusToolType = supportsStylusToolType,
    supportsContactSize = true,
    supportsPressure = true,
    supportsMultiTouch = true,
    hasPalmClassificationHint = false,
    apiLevel = 35,
)

fun testSettings(
    mode: PalmRejectionMode = PalmRejectionMode.BALANCED,
    sensitivity: Float = 0.5f,
) = PalmRejectionSettings(mode = mode, sensitivity = sensitivity, writingHoldoffMs = 0L)