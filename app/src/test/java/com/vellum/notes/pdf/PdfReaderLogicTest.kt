// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the PDF reader: themes, thumbnails, crop rects. No Android deps. */
class PdfReaderLogicTest {

    // ---------- Themes ----------

    @Test
    fun tint_original_hasNoOverlay() {
        assertEquals(0L, PdfReaderTint.overlayArgb(PdfReaderTheme.ORIGINAL))
        assertFalse(PdfReaderTint.hasOverlay(PdfReaderTheme.ORIGINAL))
    }

    @Test
    fun tint_sepiaAndNight_haveOverlays() {
        assertTrue(PdfReaderTint.hasOverlay(PdfReaderTheme.SEPIA))
        assertTrue(PdfReaderTint.hasOverlay(PdfReaderTheme.NIGHT))
        // Night veil is more opaque than the sepia wash.
        val sepiaAlpha = ((PdfReaderTint.overlayArgb(PdfReaderTheme.SEPIA) ushr 24) and 0xFF).toInt()
        val nightAlpha = ((PdfReaderTint.overlayArgb(PdfReaderTheme.NIGHT) ushr 24) and 0xFF).toInt()
        assertTrue(nightAlpha > sepiaAlpha)
    }

    // ---------- Thumbnails (pure helpers) ----------

    @Test
    fun thumbName_appendsThumbSuffix() {
        assertEquals("abc-p0-thumb.png", PdfImporter.thumbNameFor("abc-p0.png"))
        assertEquals("abc-p0-thumb.png", PdfImporter.thumbNameFor("/x/pdf-pages/abc-p0.png"))
    }

    @Test
    fun sampleSize_capsLongestSide_powerOfTwo() {
        assertEquals(1, PdfImporter.sampleSizeFor(200, 100))
        assertEquals(2, PdfImporter.sampleSizeFor(512, 300))
        assertEquals(8, PdfImporter.sampleSizeFor(2048, 1500))
        assertEquals(1, PdfImporter.sampleSizeFor(0, 0))
        assertEquals(1, PdfImporter.sampleSizeFor(-5, 100))
        // Exact fit needs no sampling.
        assertEquals(1, PdfImporter.sampleSizeFor(256, 200))
        // Just over -> 2.
        assertEquals(2, PdfImporter.sampleSizeFor(257, 200))
    }

    @Test
    fun thumbMax_is256() {
        assertEquals(256, PdfImporter.THUMB_MAX_PX)
    }

    // ---------- Crop rects ----------

    @Test
    fun cropRect_normalized_clampsToUnit() {
        val r = PdfCropRect(-0.5f, -1f, 2f, 5f).normalized()
        assertEquals(PdfCropRect(0f, 0f, 1f, 1f), r)
    }

    @Test
    fun cropRect_degenerate_isInvalid() {
        assertFalse(PdfCropRect(0.5f, 0.5f, 0.5f, 0.5f).isValid())
        assertFalse(PdfCropRect(0.7f, 0.2f, 0.3f, 0.8f).isValid())
        assertTrue(PdfCropRect(0.1f, 0.1f, 0.9f, 0.9f).isValid())
    }

    @Test
    fun cropRect_toPixels_scalesToPage() {
        val px = PdfCropRect(0.25f, 0.5f, 0.75f, 1f).toPixels(1000, 2000)
        assertEquals(250, px[0])
        assertEquals(1000, px[1])
        assertEquals(750, px[2])
        assertEquals(2000, px[3])
    }

    @Test
    fun cropRect_json_roundTrips() {
        val r = PdfCropRect(0.1f, 0.2f, 0.8f, 0.9f)
        val parsed = PdfCropRect.parse(r.toJson())
        assertNotNull(parsed)
        assertEquals(r.left, parsed!!.left, 1e-6f)
        assertEquals(r.top, parsed.top, 1e-6f)
        assertEquals(r.right, parsed.right, 1e-6f)
        assertEquals(r.bottom, parsed.bottom, 1e-6f)
    }

    @Test
    fun cropRect_parse_rejectsGarbage() {
        assertNull(PdfCropRect.parse("not json"))
        assertNull(PdfCropRect.parse("{}"))
        assertNull(PdfCropRect.parse("{\"l\":0.5,\"t\":0.5,\"r\":0.5,\"b\":0.5}")) // degenerate
    }

    @Test
    fun cropSidecarName_appendsCropJson() {
        assertEquals("abc-p0.crop.json", PdfImporter.cropSidecarNameFor("abc-p0.png"))
    }

    // ---------- Content detection ----------

    @Test
    fun detect_blankPage_returnsNull() {
        assertNull(PdfCrop.detect(100, 100, { _, _ -> false }))
        assertNull(PdfCrop.detect(0, 0, { _, _ -> true }))
    }

    @Test
    fun detect_singleBlock_returnsTightBoundsWithPadding() {
        // Content block x in [40,60), y in [20,30) on a 100x100 page.
        val r = PdfCrop.detect(100, 100, { x, y -> x in 40..59 && y in 20..29 }, stride = 1)
        assertNotNull(r)
        assertTrue(r!!.left < 0.4f && r.right > 0.6f)
        assertTrue(r.top < 0.2f && r.bottom > 0.3f)
        assertTrue(r.isValid())
    }

    @Test
    fun detect_fullPage_returnsNearFullRect() {
        val r = PdfCrop.detect(50, 50, { _, _ -> true }, stride = 2)
        assertNotNull(r)
        assertEquals(0f, r!!.left, 1e-6f)
        assertEquals(0f, r.top, 1e-6f)
        assertEquals(1f, r.right, 1e-6f)
        assertEquals(1f, r.bottom, 1e-6f)
    }
}
