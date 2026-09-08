package com.vellum.notes.input

/**
 * Preset levels for palm rejection sensitivity (see
 * [PalmRejectionSettings.sensitivity]). The slider still allows fine-tuning
 * between presets; [fromValue] reports the nearest preset for display.
 */
enum class SensitivityLevel(val value: Float) {
    /** Permissive: larger contacts still write (weakest palm rejection). */
    LOW(0.2f),

    /** Balanced default. */
    MEDIUM(0.5f),

    /** Aggressive: only clearly small contacts write. */
    HIGH(0.8f);

    companion object {
        /** Nearest preset for a raw sensitivity value (ties round up). */
        fun fromValue(v: Float): SensitivityLevel =
            entries.minByOrNull { kotlin.math.abs(it.value - v) } ?: MEDIUM
    }
}
