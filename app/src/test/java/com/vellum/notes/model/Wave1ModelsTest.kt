// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.model

import com.vellum.notes.pdf.PdfPagePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave-1 pure-logic tests: paper templates, notebook covers, PDF page planning.
 * All geometry is in millimetres; no Android dependencies.
 */
class Wave1ModelsTest {

    // ---------- PaperTemplates ----------

    @Test
    fun templateRegistry_hasFiveBuiltins_withUniqueIds() {
        assertEquals(5, PaperTemplates.ALL.size)
        assertEquals(PaperTemplates.ALL.size, PaperTemplates.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun byId_unknownOrNull_fallsBackToBlank() {
        assertEquals(PaperTemplates.BLANK, PaperTemplates.byId("NOPE"))
        assertEquals(PaperTemplates.BLANK, PaperTemplates.byId(null))
        assertEquals(PaperTemplates.RULED, PaperTemplates.byId("RULED"))
    }

    @Test
    fun isKnownId_matchesRegistry() {
        assertTrue(PaperTemplates.isKnownId("GRID"))
        assertFalse(PaperTemplates.isKnownId("grid")) // ids are case-sensitive canonical
        assertFalse(PaperTemplates.isKnownId(null))
    }

    @Test
    fun backgroundFor_lightVsDark_usesInkFriendlyColors() {
        val light = PaperTemplates.backgroundFor("RULED", darkTheme = false)
        val dark = PaperTemplates.backgroundFor("RULED", darkTheme = true)
        assertEquals(0xFFFFFFFF, light.colorArgb)
        assertEquals(0xFF141821, dark.colorArgb)
        assertTrue(dark.lineColorArgb != light.lineColorArgb)
        assertEquals(8f, light.lineSpacingMm)
    }

    @Test
    fun ruledLines_spacing8_onA4Height_yieldsExpectedCount() {
        val lines = PaperTemplates.ruledLines(8f, 0f, 297f)
        assertEquals(38, lines.size) // 0,8,...,296
        assertEquals(0f, lines.first())
        assertTrue(lines.last() < 297f)
    }

    @Test
    fun ruledLines_degenerateInputs_returnEmpty() {
        assertTrue(PaperTemplates.ruledLines(0f, 0f, 100f).isEmpty())
        assertTrue(PaperTemplates.ruledLines(8f, 100f, 100f).isEmpty())
        assertTrue(PaperTemplates.ruledLines(8f, 100f, 50f).isEmpty())
    }

    @Test
    fun ruledLines_respectsTopBound() {
        val lines = PaperTemplates.ruledLines(5f, 7f, 30f)
        assertTrue(lines.all { it >= 7f && it < 30f })
        assertEquals(10f, lines.first()) // first multiple of 5 >= 7
    }

    @Test
    fun gridLines_matchRuledGeometry() {
        assertEquals(
            PaperTemplates.ruledLines(5f, 0f, 50f),
            PaperTemplates.gridLines(5f, 0f, 50f),
        )
    }

    @Test
    fun dotPositions_tileFullRect_withExactCount() {
        val dots = PaperTemplates.dotPositions(5f, 0f, 0f, 10f, 10f)
        assertEquals(9, dots.size) // {0,5,10}^2
        assertTrue(dots.all { (x, y) -> x >= 0f && x <= 10f && y >= 0f && y <= 10f })
    }

    @Test
    fun dotPositions_degenerateInputs_returnEmpty() {
        assertTrue(PaperTemplates.dotPositions(0f, 0f, 0f, 10f, 10f).isEmpty())
        assertTrue(PaperTemplates.dotPositions(5f, 10f, 0f, 0f, 10f).isEmpty())
    }

    // ---------- NotebookCovers ----------

    @Test
    fun coverRegistry_hasEightCovers_withUniqueIds() {
        assertEquals(10, NotebookCovers.ALL.size)
        assertEquals(NotebookCovers.ALL.size, NotebookCovers.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun coverById_unknownOrNull_fallsBackToTeal() {
        assertEquals(NotebookCovers.TEAL, NotebookCovers.byId("MISSING"))
        assertEquals(NotebookCovers.TEAL, NotebookCovers.byId(null))
    }

    @Test
    fun solidCover_hasEqualPrimaryAndSecondary() {
        val stone = NotebookCovers.byId("STONE")
        assertEquals(NotebookCovers.Pattern.SOLID, stone.pattern)
        assertEquals(stone.primaryArgb, stone.secondaryArgb)
    }

    @Test
    fun everyCover_hasDistinctArgbPairs() {
        val pairs = NotebookCovers.ALL.map { it.primaryArgb to it.secondaryArgb }
        assertEquals(pairs.size, pairs.toSet().size)
    }

    // ---------- PdfPagePlan ----------

    @Test
    fun plan_preservesSourceOrder_andPageIndices() {
        val specs = PdfPagePlan.plan(4, notebookId = 7)
        assertEquals(4, specs.size)
        specs.forEachIndexed { i, s ->
            assertEquals(i, s.order)
            assertEquals(i, s.pdfPageIndex)
        }
    }

    @Test
    fun plan_fileNames_areUniqueAndDeterministic() {
        val a = PdfPagePlan.plan(3, notebookId = 7)
        val b = PdfPagePlan.plan(3, notebookId = 7)
        assertEquals(a.map { it.fileName }, b.map { it.fileName })
        assertEquals(3, a.map { it.fileName }.toSet().size)
    }

    @Test
    fun plan_zeroOrNegativePages_returnsEmpty() {
        assertTrue(PdfPagePlan.plan(0, notebookId = 1).isEmpty())
        assertTrue(PdfPagePlan.plan(-2, notebookId = 1).isEmpty())
    }

    @Test
    fun isPdfBacked_usesSentinelNegativeOne() {
        assertTrue(PdfPagePlan.isPdfBacked(0))
        assertTrue(PdfPagePlan.isPdfBacked(12))
        assertFalse(PdfPagePlan.isPdfBacked(-1))
    }
}
