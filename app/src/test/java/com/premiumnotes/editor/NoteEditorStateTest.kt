package com.premiumnotes.editor

import android.graphics.RectF
import com.premiumnotes.model.PageContent
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.PenType
import com.premiumnotes.model.Point
import com.premiumnotes.model.ShapeKind
import com.premiumnotes.model.ShapeObject
import com.premiumnotes.model.Stroke
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorStateTest {

    private fun stroke(id: Long, x1: Float, y1: Float, x2: Float, y2: Float) =
        Stroke(id = id, style = PenStyle(), pointsPacked = floatArrayOf(x1, y1, x2, y2))

    private fun state(vararg strokes: Stroke) =
        NoteEditorState(PageContent(strokes = strokes.toList()))

    private fun shapeRect(id: Long) =
        ShapeObject(
            id = id,
            kind = ShapeKind.RECT,
            x = 0f,
            y = 0f,
            points = listOf(Point(0f, 0f), Point(10f, 10f)),
            strokeWidthMm = 1.5f,
            colorArgb = 0xFF000000,
        )

    private fun highlighter(id: Long, x1: Float, y1: Float, x2: Float, y2: Float) =
        Stroke(
            id = id,
            style = PenStyle(type = PenType.HIGHLIGHTER, opacity = 0.4f, widthMm = 5f),
            pointsPacked = floatArrayOf(x1, y1, x2, y2),
        )

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

    // --- Phase 4: shape transform + erase-gesture redo regression --------------------

    @Test
    fun moveSelectedShapeCoalescesAndUndoes() {
        val s = NoteEditorState(PageContent(shapeObjects = listOf(shapeRect(1))))
        s.selectAll()
        s.beginMoveSelection(0f, 0f)
        s.moveSelectionTo(10f, 5f)
        s.moveSelectionTo(20f, 5f)
        s.endMoveSelection()
        val moved = s.content.value.shapeObjects.single()
        assertEquals(20f, moved.x)
        assertEquals(5f, moved.y)

        s.undo()
        val restored = s.content.value.shapeObjects.single()
        assertEquals(0f, restored.x)
        assertEquals(0f, restored.y)
    }

    @Test
    fun eraseGestureRedoReappliesRemoval() {
        val s = state(stroke(1, 0f, 0f, 5f, 5f), stroke(2, 100f, 100f, 110f, 110f))
        s.eraseGestureBegin()
        s.eraseAt(0.5f, 0.5f, 1f)
        s.eraseAlong(100f, 95f, 100f, 105f, 1f)
        s.eraseGestureEnd()
        assertEquals(0, s.content.value.strokes.size)

        s.undo()
        assertEquals(2, s.content.value.strokes.size)

        s.redo()
        assertEquals(0, s.content.value.strokes.size)
    }

    @Test
    fun duplicateSelectedShapeGetsFreshId() {
        val s = NoteEditorState(PageContent(shapeObjects = listOf(shapeRect(1))))
        s.selectAll()
        s.duplicateSelection()
        val ids = s.content.value.shapeObjects.map { it.id }
        assertEquals(2, s.content.value.shapeObjects.size)
        assertEquals(2, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun addShapeIsUndoableAndRedoable() {
        val s = state()
        s.addShape(
            ShapeObject(
                id = 7,
                kind = ShapeKind.RECT,
                x = 0f,
                y = 0f,
                points = listOf(Point(0f, 0f), Point(10f, 10f)),
                strokeWidthMm = 1.5f,
                colorArgb = 0xFFE53935,
            )
        )
        assertEquals(1, s.content.value.shapeObjects.size)
        assertEquals(ShapeKind.RECT, s.content.value.shapeObjects.single().kind)

        s.undo()
        assertEquals(0, s.content.value.shapeObjects.size)

        s.redo()
        assertEquals(1, s.content.value.shapeObjects.size)
        assertEquals(7L, s.content.value.shapeObjects.single().id)
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

    // --- Phase 3: unified eraser hit-testing ---------------------------------------

    @Test
    fun eraseInkByTapInMiddleOfLongSegment() {
        // Two sparse endpoints; the eraser taps the middle of the segment, not a sample.
        val s = state(stroke(1, 0f, 0f, 100f, 0f))
        s.eraseAt(50f, 0f, 3f)
        assertEquals(0, s.content.value.strokes.size)
        s.undo()
        assertEquals(1, s.content.value.strokes.size)
    }

    @Test
    fun eraseShapeByTappingItsOutline() {
        val s = NoteEditorState(PageContent(shapeObjects = listOf(shapeRect(1))))
        s.eraseAt(0.5f, 5f, 2f)
        assertEquals(0, s.content.value.shapeObjects.size)
        s.undo()
        assertEquals(1, s.content.value.shapeObjects.size)
    }

    @Test
    fun eraseShapeByTappingInsideFilledRegion() {
        val s = NoteEditorState(PageContent(shapeObjects = listOf(shapeRect(1))))
        s.eraseAt(5f, 5f, 2f)
        assertEquals(0, s.content.value.shapeObjects.size)
        s.undo()
        assertEquals(1, s.content.value.shapeObjects.size)
        s.redo()
        assertEquals(0, s.content.value.shapeObjects.size)
    }

    @Test
    fun diagonalLineShapeNotErasedFarFromStroke() {
        val line = ShapeObject(
            id = 1, kind = ShapeKind.LINE, x = 0f, y = 0f,
            points = listOf(Point(0f, 0f), Point(100f, 100f)),
            strokeWidthMm = 1.5f, colorArgb = 0xFF000000,
        )
        val s = NoteEditorState(PageContent(shapeObjects = listOf(line)))
        // 56mm off the line but inside its bounding box — the old bbox test erased it.
        s.eraseAt(90f, 10f, 2f)
        assertEquals(1, s.content.value.shapeObjects.size)
        // On the line it does erase.
        s.eraseAt(50f, 50f, 2f)
        assertEquals(0, s.content.value.shapeObjects.size)
    }

    @Test
    fun eraseHighlighterRemovesOnlyTheHighlighter() {
        val hl = highlighter(1, 0f, 0f, 50f, 0f)
        val ink = stroke(2, 10f, 10f, 60f, 10f)
        val s = NoteEditorState(PageContent(strokes = listOf(hl, ink)))
        s.eraseAt(30f, 0f, 3f)
        assertEquals(1, s.content.value.strokes.size)
        assertEquals(2L, s.content.value.strokes.single().id)
        s.undo()
        assertEquals(2, s.content.value.strokes.size)
    }

    @Test
    fun eraseInkThenShapeWhenOverlapping() {
        val ink = stroke(1, 5f, 5f, 50f, 5f)
        val rect = shapeRect(2)
        val s = NoteEditorState(PageContent(strokes = listOf(ink), shapeObjects = listOf(rect)))
        // The tap overlaps BOTH the ink line and the rect interior; ink is topmost so it
        // goes first, one tier per action.
        s.eraseAt(5f, 5f, 3f)
        assertEquals(0, s.content.value.strokes.size)
        assertEquals(1, s.content.value.shapeObjects.size)
        // Second tap reaches the shape underneath.
        s.eraseAt(5f, 5f, 3f)
        assertEquals(0, s.content.value.shapeObjects.size)
        // Two separate erase actions, so undo twice restores both.
        s.undo()
        assertEquals(1, s.content.value.shapeObjects.size)
        s.undo()
        assertEquals(1, s.content.value.strokes.size)
        assertEquals(1, s.content.value.shapeObjects.size)
    }

    @Test
    fun erasedObjectsAreGoneAfterJsonRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun roundTrip(c: PageContent): PageContent = json.decodeFromString(json.encodeToString(c))

        val hl = highlighter(1, 0f, 0f, 50f, 0f)
        val rect = shapeRect(2)
        val s = NoteEditorState(PageContent(strokes = listOf(hl), shapeObjects = listOf(rect)))

        // Erase the highlighter (only thing hit at this point), then the shape.
        s.eraseAt(30f, 0f, 3f)
        assertEquals(0, s.content.value.strokes.size)
        assertEquals(1, s.content.value.shapeObjects.size)
        s.eraseAt(5f, 5f, 3f)
        assertEquals(0, s.content.value.shapeObjects.size)

        // What the app would persist to Room and reload is the erased content.
        val reloaded = roundTrip(s.content.value)
        assertEquals(0, reloaded.strokes.size)
        assertEquals(0, reloaded.shapeObjects.size)
    }

    @Test
    fun tapSelectionMatchesTopmostDrawOrder() {
        // Shapes are drawn ABOVE highlighters in onDraw; tapping the overlap must select
        // the shape, not the highlighter underneath.
        val hl = highlighter(1, 0f, 0f, 20f, 0f)
        val rect = shapeRect(2)
        val s = NoteEditorState(PageContent(strokes = listOf(hl), shapeObjects = listOf(rect)))
        s.selectAt(5f, 5f)
        assertEquals(setOf(2L), s.selectedIds.value)
    }
}