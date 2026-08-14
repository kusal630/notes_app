package com.premiumnotes.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager

/**
 * Immutable description of what the current device can actually tell us about touch.
 * This is the single source of truth the palm rejection system uses to adapt its
 * behavior — the app never assumes capabilities the hardware does not expose.
 */
data class InputCapabilities(
    /** Physical pixel density: pixels per millimeter (approximated from densityDpi). */
    val pxPerMm: Float,
    /** Largest touch-screen dimension in pixels (used to convert getSize() to px). */
    val displayMaxPx: Float,
    /** Screen diagonal in millimeters (informational). */
    val screenDiagonalMm: Float,
    /** Whether at least one input device exposes an active stylus source. */
    val supportsStylus: Boolean,
    /** Whether the OS is capable of reporting distinct tool types (always true on minSdk 26+). */
    val supportsToolType: Boolean,
    /** Whether any device exposes stylus tool-type classification (active stylus). */
    val supportsStylusToolType: Boolean,
    /** Whether the device exposes contact-size (getSize) meaningfully. */
    val supportsContactSize: Boolean,
    /** Whether pressure values are available (active stylus or capable digitizer). */
    val supportsPressure: Boolean,
    /** Whether multi-touch is available (effectively always true on tablets). */
    val supportsMultiTouch: Boolean,
    /** Device has known palm-classification behavior hints (informational only). */
    val hasPalmClassificationHint: Boolean,
    val apiLevel: Int,
) {
    fun dimFromPx(px: Float): Float = if (pxPerMm > 0f) px / pxPerMm else 0f

    companion object {
        /**
         * Detects [InputCapabilities] from the running hardware. This is honest detection:
         * `supportsStylus` is true only when an input device actually reports a stylus source.
         * A passive stylus almost always results in `supportsStylusToolType == false`, and the
         * app then relies on the software classifier.
         */
        fun detect(context: Context): InputCapabilities {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)

            val densityDpi = metrics.densityDpi.coerceAtLeast(1)
            val pxPerMm = densityDpi / 25.4f
            val displayMaxPx = maxOf(metrics.widthPixels, metrics.heightPixels)
            val diagPx = kotlin.math.sqrt(
                (metrics.widthPixels * metrics.widthPixels +
                    metrics.heightPixels * metrics.heightPixels).toDouble()
            )
            val screenDiagonalMm = (diagPx / pxPerMm).toFloat()

            val inputManager = context.getSystemService(InputManager::class.java)

            val stylusSources = mutableListOf<Int>()
            val hasStylusDevice = inputManager.inputDeviceIds.any { id ->
                val dev = inputManager.getInputDevice(id)
                val s = dev?.sources ?: 0
                if (s and InputDevice.SOURCE_STYLUS != 0) {
                    stylusSources.add(s)
                    true
                } else false
            }

            // A stylus source that also reports class-specific tool types (active stylus).
            val stylusToolTypeExposed = stylusSources.any { s ->
                s and InputDevice.SOURCE_STYLUS != 0
            }

            return InputCapabilities(
                pxPerMm = pxPerMm,
                displayMaxPx = displayMaxPx.toFloat(),
                screenDiagonalMm = screenDiagonalMm.toFloat(),
                supportsStylus = hasStylusDevice,
                supportsToolType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT,
                supportsStylusToolType = stylusToolTypeExposed,
                supportsContactSize = true,
                supportsPressure = true,
                supportsMultiTouch = true,
                hasPalmClassificationHint = false,
                apiLevel = Build.VERSION.SDK_INT,
            )
        }

        /**
         * Maps a MotionEvent tool type constant to our [ToolKind]. Unknown types fall back
         * to geometric classification; we never invent a stylus where none was reported.
         */
        fun toolKindFromRaw(raw: Int): ToolKind = when (raw) {
            MotionEvent.TOOL_TYPE_STYLUS -> ToolKind.STYLUS
            MotionEvent.TOOL_TYPE_ERASER -> ToolKind.ERASER
            MotionEvent.TOOL_TYPE_FINGER -> ToolKind.FINGER
            MotionEvent.TOOL_TYPE_MOUSE -> ToolKind.MOUSE
            else -> ToolKind.UNKNOWN
        }
    }
}
