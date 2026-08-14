package com.premiumnotes.input

import android.view.MotionEvent

/**
 * Android adapter: converts a [MotionEvent] into a pure [InputFrame]. This is the only
 * place in the input pipeline that reads platform event semantics (action masks,
 * pointer indices, historical/coalesced samples).
 */
object MotionEventParser {

    fun parse(event: MotionEvent): InputFrame {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> InputAction.DOWN
            MotionEvent.ACTION_MOVE -> InputAction.MOVE
            MotionEvent.ACTION_UP -> InputAction.UP
            MotionEvent.ACTION_POINTER_DOWN -> InputAction.POINTER_DOWN
            MotionEvent.ACTION_POINTER_UP -> InputAction.POINTER_UP
            MotionEvent.ACTION_CANCEL -> InputAction.CANCEL
            else -> return InputFrame(
                action = InputAction.MOVE,
                eventTimeNanos = event.eventTime * 1_000_000L,
                contacts = emptyList(),
            )
        }

        val addedPointerId: Int?
        val liftedPointerId: Int?
        when (action) {
            InputAction.POINTER_DOWN -> addedPointerId = event.getPointerId(event.actionIndex)
            else -> addedPointerId = null
        }
        when (action) {
            InputAction.POINTER_UP -> liftedPointerId = event.getPointerId(event.actionIndex)
            else -> liftedPointerId = null
        }

        val contacts = ArrayList<RawTouchContact>(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            contacts += sample(event, i, event.eventTime, historical = false)
        }

        val history = ArrayList<RawTouchContact>()
        if (action == InputAction.MOVE || action == InputAction.POINTER_DOWN) {
            val hCount = event.historySize
            for (h in 0 until hCount) {
                val hTime = event.getHistoricalEventTime(h)
                for (i in 0 until event.pointerCount) {
                    history += sample(event, i, hTime, h)
                }
            }
        }

        return InputFrame(
            action = action,
            eventTimeNanos = event.eventTime * 1_000_000L,
            contacts = contacts,
            history = history,
            addedPointerId = addedPointerId,
            liftedPointerId = liftedPointerId,
        )
    }

    private fun sample(
        event: MotionEvent,
        pointerIndex: Int,
        timeMs: Long,
        historyIndex: Int = -1,
        historical: Boolean = historyIndex >= 0,
    ): RawTouchContact {
        val x = if (historical) event.getHistoricalX(pointerIndex, historyIndex) else event.getX(pointerIndex)
        val y = if (historical) event.getHistoricalY(pointerIndex, historyIndex) else event.getY(pointerIndex)
        val pressure = if (historical) event.getHistoricalPressure(pointerIndex, historyIndex) else event.getPressure(pointerIndex)
        val size = if (historical) event.getHistoricalSize(pointerIndex, historyIndex) else event.getSize(pointerIndex)
        val major = if (historical) event.getHistoricalToolMajor(pointerIndex, historyIndex) else event.getToolMajor(pointerIndex)
        val minor = if (historical) event.getHistoricalToolMinor(pointerIndex, historyIndex) else event.getToolMinor(pointerIndex)
        val orientation = if (historical) event.getHistoricalOrientation(pointerIndex, historyIndex) else event.getOrientation(pointerIndex)

        return RawTouchContact(
            pointerId = event.getPointerId(pointerIndex),
            x = x,
            y = y,
            pressure = pressure,
            size = size,
            toolMajorPx = major,
            toolMinorPx = minor,
            orientation = orientation,
            toolTypeRaw = event.getToolType(pointerIndex),
            eventTimeNanos = timeMs * 1_000_000L,
            downTimeNanos = event.downTime * 1_000_000L,
            isHistorical = historical,
        )
    }
}