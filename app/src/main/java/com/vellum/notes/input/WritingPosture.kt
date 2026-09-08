package com.vellum.notes.input

/**
 * Which hand the user writes with. Drives handedness-aware palm rejection:
 *
 * - The resting palm anchors on the palm-side screen edge, so that edge keeps
 *   the full [PalmRejectionSettings.edgeMarginMm] as resting evidence.
 * - The opposite (writing-side) edge gets a reduced margin: a contact resting
 *   there is far less likely to be the anchored palm and far more likely the
 *   start of a two-finger gesture or the writing hand crossing over, so it
 *   must not count as edge-resting as eagerly.
 * - Top/bottom edges are symmetric under every posture (the palm heel sits
 *   near the bottom edge regardless of handedness).
 * - [TWO_HANDED] (two thumbs / either hand) applies no bias: both vertical
 *   edges keep the full margin.
 */
enum class WritingPosture {
    RIGHT_HANDED,
    LEFT_HANDED,
    TWO_HANDED;

    /**
     * Palm side matching this posture, following the [PalmZoneSide] convention
     * (LEFT = typical right-handed). Null when unbiased: changing to
     * [TWO_HANDED] leaves the zone side untouched.
     */
    fun palmSide(): PalmZoneSide? = when (this) {
        RIGHT_HANDED -> PalmZoneSide.LEFT
        LEFT_HANDED -> PalmZoneSide.RIGHT
        TWO_HANDED -> null
    }

    /**
     * Horizontal edge margin for the given screen edge under this posture.
     *
     * @param leftEdge true for the left screen edge, false for the right.
     * @param fullMarginPx the configured full edge margin in px.
     */
    fun horizontalEdgeMarginPx(leftEdge: Boolean, fullMarginPx: Float): Float {
        val palmOnLeft = when (this) {
            // The writing palm anchors bottom-right for right-handed writers
            // (bottom-left for left-handed writers).
            RIGHT_HANDED -> false
            LEFT_HANDED -> true
            TWO_HANDED -> return fullMarginPx
        }
        return if (palmOnLeft == leftEdge) fullMarginPx
        else fullMarginPx * WRITING_SIDE_EDGE_FRACTION
    }

    companion object {
        /**
         * Fraction of the full edge margin kept on the writing-side vertical
         * edge. 0.35 keeps a slim band (a genuine edge press still counts)
         * while the 10–30mm band — where strokes legitimately start — no
         * longer reads as edge-resting evidence.
         */
        const val WRITING_SIDE_EDGE_FRACTION = 0.35f
    }
}

/**
 * Returns a copy with [posture] applied and the palm-zone side synced to it
 * (when the posture has a palm side). The zone side stays user-overridable
 * afterwards — this only sets the matching default on change.
 */
fun PalmRejectionSettings.withWritingPosture(posture: WritingPosture): PalmRejectionSettings =
    copy(
        writingPosture = posture,
        palmZone = posture.palmSide()?.let { palmZone.copy(side = it) } ?: palmZone,
    )
