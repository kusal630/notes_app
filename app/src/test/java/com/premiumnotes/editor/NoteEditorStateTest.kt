package com.premiumnotes.editor

import android.graphics.RectF
import com.premiumnotes.model.PageContent
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.PenType
import com.premiumnotes.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorStateTest {

    private fun stroke(id: Long, x1: Float, y1: Float, x2: Float, y2: Float) =
        Stroke(id = id, style = PenStyle(), pointsPacked = floatArrayOf(x1, y1, x2, y2))

    private fun state(vararg strokes: Stroke) =
        NoteEditorState(PageContent(strokes = strokes.toList()))

    @Test
    fun selectInRectFindsIntersectingStrokes() {
        val s = state(stroke(1, 0f, 0f, 5f, 5f), stroke(2, 100f, 100f, 110f, 110f))
        s.selectInRect(RectF(-1f, -1f, 6f, 6f))
        assertEquals(setOf(1L), s.selectedIds.value)
    }

    @Test
    fun deleteSelectionRemovesAndUndoes() {
        val s = state(stroke(1, 0f, 0f, 5f, 5f), stroke(2, 100f, 100f, 110f, 110f))
        s.selectAll()
        s.deleteSelection()
        assertEquals(0, s.content.value.strokes.size)
        assertEquals(emptySet<Long>(), s.selectedIds.value)
        s.undo()
        assertEquals(2, s.content.value.strokes.size)
    }

    @Test
    fun duplicateSelectionAddsIndependentCopies() {
        val s = state(stroke(1, 0f, 0f, 5f, 5f))
        s.selectAll()
        s.duplicateSelection()
        assertEquals(2, s.content.value.strokes.size)
        assertEquals(1, s.selectedIds.value.size)
        val copy = s.content.value.strokes.first { it.id != 1L }
        assertTrue(copy.pointsPacked.contentEquals(floatArrayOf(0f, 0f, 5f, 5f)))
    }

    @Test
    fun duplicateSelectionNeverReusesCommittedStrokeIds() {
        val s = state()
        // Simulates strokes committed by the canvas (ids allocated outside the state).
        s.apply(AddStrokeCommand(stroke(1, 0f, 0f, 1f, 1f)))
        s.apply(AddStrokeCommand(stroke(2, 0f, 0f, 1f, 1f)))
        s.selectAll()
        s.duplicateSelection()
        val ids = s.content.value.strokes.map { it.id }
        assertEquals(4, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun moveSelectionCoalescesIntoSingleUndoStep() {        val s = state(stroke(1, 0f, 0f, 5f, 5f))
        s.selectAll()
        s.beginMoveSelection(0f, 0f)
        s.moveSelectionTo(10f, 10f)
        s.moveSelectionTo(20f, 20f)
        s.moveSelectionTo(30f, 30f)
        s.endMoveSelection()
        val moved = s.content.value.strokes.single()
        assertEquals(30f, moved.pointsPacked[0])
        assertEquals(30f, moved.pointsPacked[1])

        // A single undo restores the original geometry.
        s.undo()
        val restored = s.content.value.strokes.single()
        assertEquals(0f, restored.pointsPacked[0])
        assertEquals(0f, restored.pointsPacked[1])

        // Redo reapplies the whole drag.
        s.redo()
        val redone = s.content.value.strokes.single()
        assertEquals(30f, redone.pointsPacked[0])
        assertEquals(30f, redone.pointsPacked[1])
    }

    @Test
    fun eraseGestureCoalescesIntoSingleUndoStep() {
        val s = state(stroke(1, 0f, 0f, 5f, 5f), stroke(2, 100f, 100f, 110f, 110f))
        s.eraseGestureBegin()
        s.eraseAt(0.5f, 0.5f, 1f)
        s.eraseAlong(100f, 95f, 100f, 105f, 1f)
        s.eraseGestureEnd()
        assertEquals(0, s.content.value.strokes.size)

        // One undo restores everything erased during the gesture.
        s.undo()
        assertEquals(2, s.content.value.strokes.size)
    }

    @Test
    fun eraseWithoutGestureStillUndoable() {
        val s = state(stroke(1, 0f, 0f, 5f, 5f))
        s.eraseAt(0.5f, 0.5f, 1f)
        assertEquals(0, s.content.value.strokes.size)
        s.undo()
        assertEquals(1, s.content.value.strokes.size)
    }

    @Test
    fun inkStyleSavedAndRestoredAcrossHighlighter() {
        val s = state()
        s.setPenStyle(PenStyle(type = PenType.FOUNTAIN, widthMm = 1.5f, colorArgb = 0xFFE53935))
        s.saveInkStyle()
        assertEquals(PenType.FOUNTAIN, s.savedInkStyle.value?.type)

        s.setPenStyle(PenStyle(type = PenType.HIGHLIGHTER, opacity = 0.4f, widthMm = 5f))
        s.restoreInkStyle()
        assertEquals(PenType.FOUNTAIN, s.penStyle.value.type)
        assertEquals(1.5f, s.penStyle.value.widthMm)
        assertEquals(0xFFE53935, s.penStyle.value.colorArgb)
        assertNull(s.savedInkStyle.value)
    }
}