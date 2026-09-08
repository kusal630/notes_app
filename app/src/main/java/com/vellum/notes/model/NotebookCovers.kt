// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.model

/**
 * Built-in notebook cover library (wave 1, feature B).
 *
 * Covers are pure data (color stops + pattern id) rendered with Compose gradients /
 * Canvas patterns — no bitmap assets. Pure Kotlin so mapping is unit-testable.
 */
object NotebookCovers {
    enum class Pattern { SOLID, GRADIENT, DOTS, LINES, GRID }

    data class Cover(
        val id: String,
        val label: String,
        /** ARGB top/primary color. */
        val primaryArgb: Long,
        /** ARGB bottom/secondary color (== primary for [Pattern.SOLID]). */
        val secondaryArgb: Long,
        val pattern: Pattern = Pattern.GRADIENT,
    )

    val TEAL = Cover("TEAL", "Teal", 0xFF0E9D8E, 0xFF0B6E64, Pattern.GRADIENT)
    val NAVY = Cover("NAVY", "Midnight", 0xFF232F49, 0xFF101827, Pattern.GRADIENT)
    val EMBER = Cover("EMBER", "Ember", 0xFFE53935, 0xFF8E1B1B, Pattern.GRADIENT)
    val AMBER = Cover("AMBER", "Amber", 0xFFFB8C00, 0xFFB25E00, Pattern.DOTS)
    val FOREST = Cover("FOREST", "Forest", 0xFF43A047, 0xFF1B5E20, Pattern.LINES)
    val OCEAN = Cover("OCEAN", "Ocean", 0xFF1E88E5, 0xFF0D47A1, Pattern.GRID)
    val GRAPE = Cover("GRAPE", "Grape", 0xFF8E24AA, 0xFF4A148C, Pattern.DOTS)
    val STONE = Cover("STONE", "Stone", 0xFF757575, 0xFF757575, Pattern.SOLID)
    val AURUM = Cover("AURUM", "Aurum Gold", 0xFFE8B84B, 0xFF8A5E10, Pattern.GRADIENT)
    val NOIR = Cover("NOIR", "Noir Gold", 0xFF2A2418, 0xFF0E0C07, Pattern.LINES)

    val ALL: List<Cover> = listOf(TEAL, NAVY, EMBER, AMBER, FOREST, OCEAN, GRAPE, STONE, AURUM, NOIR)

    fun byId(id: String?): Cover =
        ALL.firstOrNull { it.id == id } ?: TEAL

    fun isKnownId(id: String?): Boolean = ALL.any { it.id == id }
}
