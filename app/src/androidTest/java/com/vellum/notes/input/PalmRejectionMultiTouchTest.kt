// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.input

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * TRUE multi-touch palm-rejection verification on the emulator.
 *
 * Injects genuine two-pointer MotionEvent streams through UiAutomation — exactly
 * what a passive capacitive stylus + resting palm produce on hardware without
 * native palm rejection: both pointers are TOOL_TYPE_FINGER, distinguished only by
 * contact size (pen ~6mm, palm ~28mm).
 *
 * The four required scenarios:
 *  1. hand rests first, then pen writes (simultaneous)
 *  2. pen writes first, then hand lands mid-stroke
 *  3. hand rests alone (nothing drawn, slight movement included)
 *  4. multiple strokes with the hand resting the whole time
 *
 * Pass criteria per test: ink pixels appear ONLY along the pen path (zone check on
 * screenshots); the palm zone stays clean.
 */
@RunWith(AndroidJUnit4::class)
class PalmRejectionMultiTouchTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    companion object {
        // Screen geometry (emulator: 2560x1600, 320dpi => 12.6 px/mm)
        const val PX_PER_MM = 12.6f
        const val PALM_MAJOR = 350  // ~28mm
        const val PEN_MAJOR = 75    // ~6mm

        // Palm rests bottom-right; pen writes left-center. Zones must not overlap.
        const val PALM_X = 2150f
        const val PALM_Y = 1400f
        const val PEN_X0 = 600f
        const val PEN_Y0 = 950f
        const val PEN_X1 = 1300f
        const val PEN_Y1 = 1250f

        const val PALM_ZONE_R = 160   // px radius checked around the palm
        const val NEW_INK_THRESHOLD = 60 // pixel luminance delta counted as ink
    }

    // ---------------------------------------------------------------- setup ---

    @org.junit.Before
    fun goHome() {
        device.pressBack()
        device.waitForIdle(500)
    }

    @org.junit.After
    fun leaveEditor() {
        device.pressBack()
        device.waitForIdle(500)
    }

    private fun launchApp() {
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, com.vellum.notes.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.waitForIdle(2000)
    }

    /**
     * Opens the handwriting editor. Creates one shared notebook on first use (empty
     * name → "Untitled"); every test measures NEW ink via its own before/after diff,
     * so reusing the same page across tests is safe.
     */
    private fun openEditor() {
        launchApp()
        device.wait(Until.hasObject(By.desc("New note")), 6000)
        if (device.findObject(By.text("Untitled")) == null) {
            device.findObject(By.desc("New note")).click()
            device.wait(Until.hasObject(By.text("Create")), 4000)
            device.findObject(By.text("Create")).click()
            device.waitForIdle(1500)
        }
        val card = device.wait(Until.findObject(By.text("Untitled")), 4000)
            ?: error("notebook card not found")
        card.click()
        check(device.wait(Until.hasObject(By.desc("Export PDF")), 6000) != null) { "editor did not open" }
        device.waitForIdle(800)
    }

    // ------------------------------------------------- multi-touch injection ---

    private fun pointer(id: Int, x: Float, y: Float, major: Int, pressure: Float): Pair<android.view.MotionEvent.PointerProperties, android.view.MotionEvent.PointerCoords> {
        val props = android.view.MotionEvent.PointerProperties().apply {
            this.id = id
            toolType = MotionEvent.TOOL_TYPE_FINGER // passive stylus = finger
        }
        val coords = android.view.MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            this.pressure = pressure
            this.size = 1f
            this.touchMajor = major.toFloat()
            this.touchMinor = major * 0.9f
            this.toolMajor = major.toFloat()
            this.toolMinor = major * 0.9f
        }
        return props to coords
    }

    private var injectedDownTime = 0L

    /** ACTION_POINTER_DOWN/UP with the changing pointer's INDEX encoded (mandatory). */
    private fun pointerAction(base: Int, index: Int): Int = base or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun inject(action: Int, pointers: List<Triple<Int, FloatArray, Int>>, eventTimeDeltaMs: Long) {
        val props = arrayOfNulls<android.view.MotionEvent.PointerProperties>(pointers.size)
        val coords = arrayOfNulls<android.view.MotionEvent.PointerCoords>(pointers.size)
        pointers.forEachIndexed { i, (id, xy, major) ->
            val (p, c) = pointer(id, xy[0], xy[1], major, 0.5f)
            props[i] = p; coords[i] = c
        }
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) injectedDownTime = now
        val event = MotionEvent.obtain(
            injectedDownTime, now + eventTimeDeltaMs, action,
            pointers.size, props, coords, 0, 0, 1f, 1f, 0, 0,
            MotionEvent.TOOL_TYPE_FINGER, 0,
        )
        val ok = instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
        check(ok) { "injection failed for action $action" }
    }

    /**
     * Runs a full scenario. [script] receives a helper that emits a frame for the
     * current pointer set; the test composes palm/pen frames explicitly.
     */
    private fun screenshot(name: String): Bitmap {
        val f = File(instrumentation.targetContext.externalCacheDir, "$name.png")
        device.takeScreenshot(f)
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    private fun countNewInk(before: Bitmap, after: Bitmap, zoneX: Float, zoneY: Float, radius: Int): Int {
        var count = 0
        val x0 = (zoneX - radius).toInt().coerceAtLeast(0)
        val x1 = (zoneX + radius).toInt().coerceAtMost(before.width - 1)
        val y0 = (zoneY - radius).toInt().coerceAtLeast(0)
        val y1 = (zoneY + radius).toInt().coerceAtMost(before.height - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val b = before.getPixel(x, y)
                val a = after.getPixel(x, y)
                val lb = 0.299f * ((b shr 16) and 0xFF) + 0.587f * ((b shr 8) and 0xFF) + 0.114f * (b and 0xFF)
                val la = 0.299f * ((a shr 16) and 0xFF) + 0.587f * ((a shr 8) and 0xFF) + 0.114f * (a and 0xFF)
                if (lb - la > NEW_INK_THRESHOLD) count++
            }
        }
        return count
    }

    private fun countInkAlongPath(before: Bitmap, after: Bitmap, steps: Int = 12): Int {
        var total = 0
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = PEN_X0 + (PEN_X1 - PEN_X0) * t
            val y = PEN_Y0 + (PEN_Y1 - PEN_Y0) * t
            total += countNewInk(before, after, x, y, 40)
        }
        return total
    }

    // ------------------------------------------------------------ the 4 tests ---

    /** TEST 0: single-pointer injection alone — validates the injection path. */
    @Test
    fun penWritesAlone_injectionPath() {
        openEditor()
        val before = screenshot("t0_before")
        inject(MotionEvent.ACTION_DOWN, listOf(Triple(1, floatArrayOf(PEN_X0, PEN_Y0), PEN_MAJOR)), 0)
        for (i in 1..12) {
            val t = i / 12f
            inject(MotionEvent.ACTION_MOVE, listOf(
                Triple(1, floatArrayOf(PEN_X0 + (PEN_X1 - PEN_X0) * t, PEN_Y0 + (PEN_Y1 - PEN_Y0) * t), PEN_MAJOR)), 24)
        }
        inject(MotionEvent.ACTION_UP, listOf(Triple(1, floatArrayOf(PEN_X1, PEN_Y1), PEN_MAJOR)), 0)
        Thread.sleep(1200)
        val after = screenshot("t0_after")
        val penInk = countInkAlongPath(before, after)
        assertTrue("single-pointer pen must write (ink=$penInk)", penInk > 30)
    }

    /** TEST 1: hand rests FIRST, pen writes simultaneously. */
    @Test
    fun handRestsFirst_penWritesSimultaneously() {
        openEditor()
        val before = screenshot("t1_before")

        // palm down first and STAYS
        inject(MotionEvent.ACTION_DOWN, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 0)
        repeat(6) { inject(MotionEvent.ACTION_MOVE, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 24) }

        // pen joins and writes a full stroke
        inject(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), listOf(
            Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR),
            Triple(1, floatArrayOf(PEN_X0, PEN_Y0), PEN_MAJOR)), 0)
        for (i in 1..12) {
            val t = i / 12f
            inject(MotionEvent.ACTION_MOVE, listOf(
                Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR),
                Triple(1, floatArrayOf(PEN_X0 + (PEN_X1 - PEN_X0) * t, PEN_Y0 + (PEN_Y1 - PEN_Y0) * t), PEN_MAJOR)), 24)
        }
        inject(pointerAction(MotionEvent.ACTION_POINTER_UP, 1), listOf(
            Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR),
            Triple(1, floatArrayOf(PEN_X1, PEN_Y1), PEN_MAJOR)), 0)
        repeat(3) { inject(MotionEvent.ACTION_MOVE, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 24) }
        inject(MotionEvent.ACTION_UP, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 0)
        Thread.sleep(1200)

        val after = screenshot("t1_after")
        val penInk = countInkAlongPath(before, after)
        val palmInk = countNewInk(before, after, PALM_X, PALM_Y, PALM_ZONE_R)
        assertTrue("pen must write with hand resting (ink=$penInk)", penInk > 30)
        assertTrue("palm must leave no marks (palmInk=$palmInk)", palmInk < 10)
    }

    /** TEST 2: pen writes first, hand lands MID-STROKE, pen keeps writing. */
    @Test
    fun penWritesFirst_handLandsMidStroke() {
        openEditor()
        val before = screenshot("t2_before")

        // pen starts writing
        inject(MotionEvent.ACTION_DOWN, listOf(Triple(1, floatArrayOf(PEN_X0, PEN_Y0), PEN_MAJOR)), 0)
        for (i in 1..6) {
            val t = i / 12f
            inject(MotionEvent.ACTION_MOVE, listOf(Triple(1, floatArrayOf(PEN_X0 + (PEN_X1 - PEN_X0) * t, PEN_Y0 + (PEN_Y1 - PEN_Y0) * t), PEN_MAJOR)), 24)
        }
        // hand lands mid-stroke and rests
        inject(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), listOf(
            Triple(1, floatArrayOf(PEN_X0 + (PEN_X1 - PEN_X0) * 0.5f, PEN_Y0 + (PEN_Y1 - PEN_Y0) * 0.5f), PEN_MAJOR),
            Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 0)
        for (i in 7..12) {
            val t = i / 12f
            inject(MotionEvent.ACTION_MOVE, listOf(
                Triple(1, floatArrayOf(PEN_X0 + (PEN_X1 - PEN_X0) * t, PEN_Y0 + (PEN_Y1 - PEN_Y0) * t), PEN_MAJOR),
                Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 24)
        }
        inject(pointerAction(MotionEvent.ACTION_POINTER_UP, 1), listOf(
            Triple(1, floatArrayOf(PEN_X1, PEN_Y1), PEN_MAJOR),
            Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 0)
        repeat(3) { inject(MotionEvent.ACTION_MOVE, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 24) }
        inject(MotionEvent.ACTION_UP, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 0)
        Thread.sleep(1200)

        val after = screenshot("t2_after")
        val penInk = countInkAlongPath(before, after)
        val palmInk = countNewInk(before, after, PALM_X, PALM_Y, PALM_ZONE_R)
        assertTrue("stroke must survive the palm landing (ink=$penInk)", penInk > 30)
        assertTrue("palm must leave no marks (palmInk=$palmInk)", palmInk < 10)
    }

    /** TEST 3: hand rests ALONE (incl. small drifts) — nothing may be drawn. */
    @Test
    fun handRestsAlone_drawsNothing() {
        openEditor()
        val before = screenshot("t3_before")

        inject(MotionEvent.ACTION_DOWN, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 0)
        repeat(10) { inject(MotionEvent.ACTION_MOVE, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 30) }
        // small natural hand shifts (a resting hand never sits perfectly still)
        inject(MotionEvent.ACTION_MOVE, listOf(Triple(0, floatArrayOf(PALM_X + 8, PALM_Y + 5), PALM_MAJOR)), 30)
        inject(MotionEvent.ACTION_MOVE, listOf(Triple(0, floatArrayOf(PALM_X - 6, PALM_Y + 3), PALM_MAJOR)), 30)
        repeat(8) { inject(MotionEvent.ACTION_MOVE, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 30) }
        inject(MotionEvent.ACTION_UP, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 0)
        Thread.sleep(1200)

        val after = screenshot("t3_after")
        val palmInk = countNewInk(before, after, PALM_X, PALM_Y, PALM_ZONE_R * 2)
        val penPathInk = countInkAlongPath(before, after)
        assertTrue("resting hand drew marks (palmInk=$palmInk, penPath=$penPathInk)", palmInk < 10 && penPathInk < 10)
    }

    /** TEST 4: several strokes with the hand resting the entire time. */
    @Test
    fun multiStrokesWithHandResting() {
        openEditor()
        val before = screenshot("t4_before")

        inject(MotionEvent.ACTION_DOWN, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 0)
        repeat(4) { inject(MotionEvent.ACTION_MOVE, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 24) }

        for (stroke in 0..2) {
            val yOff = stroke * 60f
            inject(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), listOf(
                Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR),
                Triple(1, floatArrayOf(PEN_X0, PEN_Y0 + yOff), PEN_MAJOR)), 0)
            for (i in 1..10) {
                val t = i / 10f
                inject(MotionEvent.ACTION_MOVE, listOf(
                    Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR),
                    Triple(1, floatArrayOf(PEN_X0 + (PEN_X1 - PEN_X0) * t, PEN_Y0 + yOff + (PEN_Y1 - PEN_Y0) * t * 0.4f), PEN_MAJOR)), 20)
            }
            inject(pointerAction(MotionEvent.ACTION_POINTER_UP, 1), listOf(
                Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR),
                Triple(1, floatArrayOf(PEN_X1, PEN_Y1 + yOff), PEN_MAJOR)), 0)
            repeat(3) { inject(MotionEvent.ACTION_MOVE, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 24) }
        }
        inject(MotionEvent.ACTION_UP, listOf(Triple(0, floatArrayOf(PALM_X, PALM_Y), PALM_MAJOR)), 0)
        Thread.sleep(1500)

        val after = screenshot("t4_after")
        val penInk = countInkAlongPath(before, after)
        val palmInk = countNewInk(before, after, PALM_X, PALM_Y, PALM_ZONE_R)
        assertTrue("all strokes must write with the hand resting (ink=$penInk)", penInk > 60)
        assertTrue("palm must leave no marks (palmInk=$palmInk)", palmInk < 10)
    }
}
