package com.premiumnotes.input

/**
 * The palm rest zone: a user-reserved region of the screen where they can rest their
 * palm. Instead of (only) classifying contacts by size, any contact whose center lands
 * inside the zone is unconditionally treated as a palm — it can never write, never start
 * a two-finger gesture, and never drop the writing lock. This gives the palm a reliable
 * home and makes palm rejection predictable on any hardware.
 *
 * The zone is stored in a screen-relative way so it adapts to rotation and window size:
 *  - Size is in physical millimeters (a palm is a fixed physical size).
 *  - A manual position is stored as fractions (0..1) of the screen width/height.
 *  - In AUTO mode the zone follows the active writing pointer and anchors to a bottom
 *    corner when idle, so the resting place tracks where the user actually writes.
 */
enum class PalmZoneMode {
    /** No zone; purely automatic classification. */
    OFF,

    /** The zone follows the active writing pointer and anchors to a corner when idle. */
    AUTO,

    /** The zone stays where the user dragged it. */
    MANUAL,
}

/** Which side of the writing pointer the palm rests on (AUTO mode). */
enum class PalmZoneSide {
    /** Palm to the LEFT of the pen — typical for right-handed writers. */
    LEFT,

    /** Palm to the RIGHT of the pen — typical for left-handed writers. */
    RIGHT,
}

/**
 * A rectangular palm zone. Coordinates are fractions of the screen (0..1); the size is
 * physical millimeters so the reserved area matches a real palm at any density.
 */
data class PalmZone(
    val mode: PalmZoneMode = PalmZoneMode.OFF,
    val side: PalmZoneSide = PalmZoneSide.LEFT,
    /** Manual position, center of the zone as a fraction of screen width (0..1). */
    val centerXFrac: Float = 0.18f,
    /** Manual position, center of the zone as a fraction of screen height (0..1). */
    val centerYFrac: Float = 0.72f,
    /** Zone width in physical mm (measured palm width + margin). */
    val widthMm: Float = 72f,
    /** Zone height in physical mm (measured palm length + margin). */
    val heightMm: Float = 60f,
) {
    val enabled: Boolean get() = mode != PalmZoneMode.OFF

    /** Returns a copy with the manual center moved (fractions kept in 0..1). */
    fun movedTo(cx: Float, cy: Float): PalmZone =
        copy(centerXFrac = cx.coerceIn(0f, 1f), centerYFrac = cy.coerceIn(0f, 1f), mode = PalmZoneMode.MANUAL)

    companion object {
        /** A zone sized around a measured palm with comfortable padding. */
        fun fromPalm(palmWidthMm: Float, palmHeightMm: Float, side: PalmZoneSide): PalmZone =
            PalmZone(
                mode = PalmZoneMode.AUTO,
                side = side,
                widthMm = palmWidthMm * 1.8f,
                heightMm = palmHeightMm * 1.4f,
            )
    }
}

/**
 * A palm zone resolved to screen pixels for the current frame. Pure Kotlin so the engine
 * stays unit-testable on the JVM. The view computes this from [PalmZone] + its own size.
 */
data class PalmZoneRect(
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float,
) {
    fun contains(xPx: Float, yPx: Float): Boolean =
        xPx in leftPx..rightPx && yPx in topPx..bottomPx

    fun centerX(): Float = (leftPx + rightPx) / 2f
    fun centerY(): Float = (topPx + bottomPx) / 2f
    fun widthPx(): Float = rightPx - leftPx
    fun heightPx(): Float = bottomPx - topPx
}