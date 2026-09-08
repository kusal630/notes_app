// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.model

/**
 * Built-in paper template registry (wave 1, feature A).
 *
 * The five Noteshelf-class templates map onto the existing [PageBackgroundType]
 * values so [com.vellum.notes.render.PageBackgroundRenderer] keeps rendering them
 * as vector-tiled, resolution-independent Canvas drawing. This registry is pure
 * Kotlin (no Android dependency) so template geometry is unit-testable off-device.
 */
object PaperTemplates {
    data class Template(
        val id: String,
        val label: String,
        val type: PageBackgroundType,
        val lineSpacingMm: Float = 8f,
        val gridSizeMm: Float = 5f,
        val dotSpacingMm: Float = 5f,
        val hasMarginLine: Boolean = false,
    )

    val BLANK = Template("BLANK", "Blank", PageBackgroundType.BLANK)
    val RULED = Template("RULED", "Ruled", PageBackgroundType.RULED, lineSpacingMm = 8f, hasMarginLine = true)
    val GRID = Template("GRID", "Grid", PageBackgroundType.GRID, gridSizeMm = 5f)
    val DOTTED = Template("DOTTED", "Dotted", PageBackgroundType.DOTTED, dotSpacingMm = 5f)
    val GRAPH = Template("GRAPH", "Graph", PageBackgroundType.GRAPH, gridSizeMm = 5f)

    val ALL: List<Template> = listOf(BLANK, RULED, GRID, DOTTED, GRAPH)

    fun byId(id: String?): Template =
        ALL.firstOrNull { it.id == id } ?: BLANK

    fun isKnownId(id: String?): Boolean = ALL.any { it.id == id }

    /**
     * Default [PageBackground] for a template. [darkTheme] switches to ink-friendly
     * dark-paper colors (dark page, soft lines) so templates stay readable at night.
     */
    fun backgroundFor(id: String?, darkTheme: Boolean = false): PageBackground {
        val t = byId(id)
        return if (!darkTheme) {
            // Nebo-style warm paper: subtle tint reads as paper next to a pure-white
            // UI while black/blue ink keeps full contrast.
            PageBackground(
                type = t.type,
                colorArgb = 0xFFFAF6EE,
                lineColorArgb = 0xFFC9BFA8,
                lineSpacingMm = t.lineSpacingMm,
                gridSizeMm = t.gridSizeMm,
                dotSpacingMm = t.dotSpacingMm,
            )
        } else {
            PageBackground(
                type = t.type,
                colorArgb = 0xFF141821,
                lineColorArgb = 0xFF3A4A5E,
                lineSpacingMm = t.lineSpacingMm,
                gridSizeMm = t.gridSizeMm,
                dotSpacingMm = t.dotSpacingMm,
            )
        }
    }

    /** Y positions (mm) of ruled lines visible in [topMm, bottomMm]. Pure geometry for tests. */
    fun ruledLines(spacingMm: Float, topMm: Float, bottomMm: Float): List<Float> {
        if (spacingMm <= 0f || bottomMm <= topMm) return emptyList()
        val out = ArrayList<Float>()
        var y = kotlin.math.floor(topMm / spacingMm) * spacingMm
        while (y < bottomMm) {
            if (y >= topMm) out += y.toFloat()
            y += spacingMm
        }
        return out
    }

    /** Grid line positions on one axis for [topMm, bottomMm]. */
    fun gridLines(sizeMm: Float, topMm: Float, bottomMm: Float): List<Float> =
        ruledLines(sizeMm, topMm, bottomMm)

    /** Dot grid positions for a rect, returned as (x, y) pairs in mm. */
    fun dotPositions(spacingMm: Float, left: Float, top: Float, right: Float, bottom: Float): List<Pair<Float, Float>> {
        if (spacingMm <= 0f || right <= left || bottom <= top) return emptyList()
        val out = ArrayList<Pair<Float, Float>>()
        var x = kotlin.math.floor(left / spacingMm) * spacingMm
        while (x <= right) {
            var y = kotlin.math.floor(top / spacingMm) * spacingMm
            while (y <= bottom) {
                if (x >= left && y >= top) out += x.toFloat() to y.toFloat()
                y += spacingMm
            }
            x += spacingMm
        }
        return out
    }
}
