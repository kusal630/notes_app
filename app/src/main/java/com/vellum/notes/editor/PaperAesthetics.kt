package com.vellum.notes.editor

import com.vellum.notes.model.PageBackground
import com.vellum.notes.model.PageBackgroundType

/**
 * Nebo-style paper aesthetics: pure color/geometry constants for a warm paper
 * feel plus builders for the default blank page. No Android dependency so the
 * values are unit-testable off-device.
 *
 * The warm tint is subtle on purpose: dark enough to read as paper next to a
 * pure-white UI, light enough that black/blue ink keeps full contrast.
 */
object PaperAesthetics {
    /** Warm paper tint for the default blank page (Nebo paper feel). */
    const val WARM_PAPER_ARGB: Long = 0xFFFAF6EE

    /** Subtle warm ruling that stays readable without shouting over ink. */
    const val WARM_LINE_ARGB: Long = 0xFFD9CFB8

    /** Cool-white paper for users who prefer a stark page. */
    const val COOL_PAPER_ARGB: Long = 0xFFFFFFFF

    /** Subtle cool ruling for the stark page. */
    const val COOL_LINE_ARGB: Long = 0xFFCBD5E4

    /**
     * Ink colors tuned for paper: a soft black, deep notebook navy, warm sepia,
     * plus the classic blue/red/green. Shown in the pen long-press switcher.
     */
    val PAPER_INKS: List<Long> = listOf(
        0xFF1A1A1A, // soft black (easier on paper than pure black)
        0xFF1F2A44, // notebook navy
        0xFF5B4636, // sepia
        0xFF1E88E5, // classic blue
        0xFFE53935, // classic red
        0xFF2E7D32, // classic green
    )

    /** A smaller quick-switch subset for the pen long-press popup. */
    val QUICK_INKS: List<Long> = listOf(
        0xFF1A1A1A, 0xFF1F2A44, 0xFF1E88E5, 0xFFE53935, 0xFF2E7D32, 0xFF000000,
    )

    /**
     * Refined default blank page. Warm tint by default; the ruling color is
     * kept (used if the user switches to ruled without a stored background).
     */
    fun blankBackground(warm: Boolean = true): PageBackground =
        if (warm) {
            PageBackground(
                type = PageBackgroundType.BLANK,
                colorArgb = WARM_PAPER_ARGB,
                lineColorArgb = WARM_LINE_ARGB,
            )
        } else {
            PageBackground(
                type = PageBackgroundType.BLANK,
                colorArgb = COOL_PAPER_ARGB,
                lineColorArgb = COOL_LINE_ARGB,
            )
        }

    /** Subtle ruled page matching the paper tint (8 mm Nebo-like ruling). */
    fun subtleRuledBackground(warm: Boolean = true): PageBackground =
        blankBackground(warm).copy(
            type = PageBackgroundType.RULED,
            lineSpacingMm = 8f,
        )

    /** True when [background] is the factory default (no user customization). */
    fun isFactoryDefault(background: PageBackground): Boolean =
        background == PageBackground()
}
