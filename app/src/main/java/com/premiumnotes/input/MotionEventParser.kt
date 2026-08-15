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
            InputAction.DOWN -> addedPointerId = event.getPointerId(0)
            InputAction.POINTER_DOWN -> addedPointerId = event.getPointerId(event.actionIndex)
            else -> addedPointerId = null
        }
        when (action) {
            InputAction.UP -> liftedPointerId = event.getPointerId(0)
            InputAction.POINTER_UP -> liftedPointerId = event.getPointerId(event.actionIndex)
            else -> liftedPointerId = null
        }

        val contacts = ArrayList<RawTouchContact>(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            contacts += sample(event, i, event.eventTime)
        }

        // Coalesced/historical samples: the OS batches several pointer positions into a
        // single MOVE. Exposing them lets the stroke builder keep fast strokes smooth
        // instead of losing intermediate points. Historical frames are ordered oldest
        // first and always precede the current [contacts].
        val history = ArrayList<RawTouchContact>()
        if (action == InputAction.MOVE && event.historySize > 0) {
            for (h in 0 until event.historySize) {
                val hTime = event.getHistoricalEventTime(h)
                for (i in 0 until event.pointerCount) {
                    history += historicalSample(event, i, h, hTime)
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
    ): RawTouchContact {
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val pressure = event.getPressure(pointerIndex)
        val size = event.getSize(pointerIndex)
        val major = event.getToolMajor(pointerIndex)
        val minor = event.getToolMinor(pointerIndex)
        val orientation = event.getOrientation(pointerIndex)

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
        )
    }

    private fun historicalSample(
        event: MotionEvent,
        pointerIndex: Int,
        historyIndex: Int,
        timeMs: Long,
    ): RawTouchContact = RawTouchContact(
        pointerId = event.getPointerId(pointerIndex),
        x = event.getHistoricalX(pointerIndex, historyIndex),
        y = event.getHistoricalY(pointerIndex, historyIndex),
        pressure = event.getHistoricalPressure(pointerIndex, historyIndex),
        size = event.getHistoricalSize(pointerIndex, historyIndex),
        toolMajorPx = event.getHistoricalToolMajor(pointerIndex, historyIndex),
        toolMinorPx = event.getHistoricalToolMinor(pointerIndex, historyIndex),
        orientation = event.getHistoricalOrientation(pointerIndex, historyIndex),
        toolTypeRaw = event.getToolType(pointerIndex),
        eventTimeNanos = timeMs * 1_000_000L,
        downTimeNanos = event.downTime * 1_000_000L,
    )
}