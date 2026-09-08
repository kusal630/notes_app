package com.vellum.notes.editor

import com.vellum.notes.model.PageContent
import com.vellum.notes.model.PenStyle
import com.vellum.notes.model.Point
import com.vellum.notes.model.ShapeKind
import com.vellum.notes.model.ShapeObject
import com.vellum.notes.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingConvertTest {
    private fun stroke(id: Long, vararg pts: Float): Stroke {
        val points = pts.toList().chunked(2).map { Point(it[0], it[1]) }
        return Stroke(id = id, style = PenStyle(), pointsPacked = Stroke.pack(points))
    }

    @Test
    fun boundsCoverInkWithPadding() {
        val box = HandwritingConvert.selectionBoundsMm(
            listOf(stroke(1L, 10f, 10f, 20f, 20f)),
            emptyList(),
        )
        assertNotNull(box)
        assertTrue(box!!.left <= 10f && box.top <= 10f)
        assertTrue(box.right >= 20f && box.bottom >= 20f)
    }

    @Test
    fun emptySelectionBoundsNull() {
        assertNull(HandwritingConvert.selectionBoundsMm(emptyList(), emptyList()))
    }

    @Test
    fun buildTextObjectHasTappableMinimum() {
        val small = HandwritingConvert.BoundsMm(50f, 50f, 52f, 51f)
        val obj = HandwritingConvert.buildTextObject(small, 7L, "")
        assertEquals(7L, obj.id)
        assertTrue(obj.width >= 30f && obj.height >= 12f)
    }

    @Test
    fun convertCommandRoundTrips() {
        val s = stroke(1L, 10f, 10f, 20f, 20f)
        val sh = ShapeObject(
            id = 2L, kind = ShapeKind.RECT,
            points = listOf(Point(30f, 30f), Point(40f, 40f)),
            x = 30f, y = 30f,
        )
        val start = PageContent(strokes = listOf(s), shapeObjects = listOf(sh))
        val box = HandwritingConvert.buildTextObject(
            HandwritingConvert.selectionBoundsMm(listOf(s), listOf(sh))!!, 9L, ""
        )
        val cmd = ConvertToTextCommand(strokes = listOf(s), shapes = listOf(sh), text = box)
        val converted = cmd.apply(start)
        assertTrue(converted.strokes.isEmpty())
        assertTrue(converted.shapeObjects.isEmpty())
        assertEquals(1, converted.textObjects.size)

        val restored = cmd.invert().apply(converted)
        assertEquals(listOf(s), restored.strokes)
        assertEquals(listOf(sh), restored.shapeObjects)
        assertTrue(restored.textObjects.isEmpty())
    }

    @Test
    fun stateConvertSelectsNewBox() {
        val state = NoteEditorState(
            PageContent(strokes = listOf(stroke(1L, 10f, 10f, 60f, 30f)))
        )
        state.selectAt(30f, 20f)
        val newId = state.convertSelectionToText()
        assertTrue(newId != 0L)
        assertTrue(state.content.value.strokes.isEmpty())
        assertEquals(1, state.content.value.textObjects.size)
        state.undo()
        assertEquals(1, state.content.value.strokes.size)
        assertTrue(state.content.value.textObjects.isEmpty())
    }
}
