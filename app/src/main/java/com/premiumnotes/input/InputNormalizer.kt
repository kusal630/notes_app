package com.premiumnotes.input

/**
 * Converts raw [RawTouchContact] samples into [NormalizedContact] with real-world units.
 * Handles missing geometry: many devices do not report toolMajor/toolMinor, so a
 * fallback ellipse is derived from getSize() and the touch surface size. Fields the
 * device did not supply are marked unavailable so the classifier never over-trusts them.
 */
class InputNormalizer(private val capabilities: InputCapabilities) {

    fun normalize(raw: RawTouchContact): NormalizedContact {
        val hasGeometry = raw.toolMajorPx > 0f && raw.toolMinorPx > 0f
        val hasSize = raw.size > 0f

        val majorPx: Float
        val minorPx: Float
        when {
            hasGeometry -> {
                majorPx = raw.toolMajorPx
                minorPx = raw.toolMinorPx.coerceAtLeast(raw.toolMajorPx * 0.25f)
            }
            hasSize -> {
                // getSize() is relative to the touch surface; approximate its extent with
                // the display's largest dimension.
                val sizePx = raw.size * capabilities.displayMaxPx
                majorPx = sizePx
                minorPx = sizePx * 0.7f
            }
            else -> {
                majorPx = 0f
                minorPx = 0f
            }
        }

        return NormalizedContact(
            pointerId = raw.pointerId,
            x = raw.x,
            y = raw.y,
            pressure = raw.pressure.coerceIn(0f, 1f),
            size = raw.size.coerceIn(0f, 1f),
            toolMajorMm = capabilities.dimFromPx(majorPx),
            toolMinorMm = capabilities.dimFromPx(minorPx),
            orientation = raw.orientation,
            toolType = InputCapabilities.toolKindFromRaw(raw.toolTypeRaw),
            eventTimeNanos = raw.eventTimeNanos,
            downTimeNanos = raw.downTimeNanos,
            hasPressure = raw.pressure > 0f,
            hasGeometry = hasGeometry,
            hasSize = hasSize,
        )
    }
}
