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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    private fun shapeRect(id: Long, left: Float, top: Float, right: Float, bottom: Float) =
        ShapeObject(
            id = id,
            kind = ShapeKind.RECT,
            x = left,
            y = top,
            points = listOf(Point(left, top), Point(right, bottom)),
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

    // --- Phase 7/8: selecting a shape pulls in its contained content ------------------

    private fun shapeWithInk(): NoteEditorState {
        val content = PageContent(
            strokes = listOf(
                stroke(10, 2f, 2f, 8f, 8f),          // fully inside the outer shape
                stroke(11, 9f, 9f, 20f, 20f),        // pokes outside -> not contained
            ),
            shapeObjects = listOf(
                shapeRect(1),                        // outer shape (0,0)-(10,10)
                shapeRect(2, 4f, 4f, 5f, 5f),        // nested shape, both corners inside
            ),
        )
        return NoteEditorState(content)
    }

    @Test
    fun selectingShapeIncludesFullyContainedContent() {
        val s = shapeWithInk()
        // Tap the outer shape's left edge, far from the nested shape and both strokes,
        // so only the outer shape is hit and containment decides the rest.
        s.selectAt(0.5f, 9.5f)
        assertEquals(setOf(1L, 2L, 10L), s.selectedIds.value)
    }

    @Test
    fun selectingStrokeDoesNotPullInShapes() {
        val s = shapeWithInk()
        s.selectAt(5f, 5f) // on the diagonal ink stroke -> the stroke is topmost
        assertEquals(setOf(10L), s.selectedIds.value)
    }

    @Test
    fun movingShapeMovesContainedContentTogether() {
        val s = shapeWithInk()
        s.selectAt(0.5f, 9.5f)
        s.beginMoveSelection(0f, 0f)
        s.moveSelectionTo(10f, 5f)
        s.endMoveSelection()

        val movedShape = s.content.value.shapeObjects.first { it.id == 1L }
        assertEquals(10f, movedShape.x, 0.001f)
        assertEquals(5f, movedShape.y, 0.001f)
        val movedStroke = s.content.value.strokes.first { it.id == 10L }
        assertTrue(movedStroke.pointsPacked.contentEquals(floatArrayOf(12f, 7f, 18f, 13f)))

        // The outside stroke is untouched.
        val outside = s.content.value.strokes.first { it.id == 11L }
        assertTrue(outside.pointsPacked.contentEquals(floatArrayOf(9f, 9f, 20f, 20f)))
    }

    @Test
    fun cornerResizeKeepsAspectRatio() {
        val s = shapeWithInk()
        s.selectAt(0.5f, 9.5f)
        s.beginResizeSelection(2) // bottom-right corner (proportional)
        s.resizeSelectionTo(20f, 10f)
        s.endResizeSelection()

        val shape = s.content.value.shapeObjects.first { it.id == 1L }
        val a = shape.points[0]
        val b = shape.points[1]
        val width = kotlin.math.abs(b.x - a.x)
        val height = kotlin.math.abs(b.y - a.y)
        assertEquals(width, height, 0.01f)

        // Contained content scales by the same factor about the same anchor.
        val stroke = s.content.value.strokes.first { it.id == 10L }
        val pts = stroke.pointsPacked
        val sx = (pts[2] - pts[0]) / (8f - 2f)
        val sy = (pts[3] - pts[1]) / (8f - 2f)
        assertEquals(sx, sy, 0.001f)
        assertEquals(width / 10f, sx, 0.01f)
    }

    @Test
    fun edgeResizeIsSingleAxis() {
        val s = shapeWithInk()
        s.selectAt(0.5f, 9.5f)
        s.beginResizeSelection(5) // right edge (horizontal only)
        s.resizeSelectionTo(22f, 5f)
        s.endResizeSelection()

        val shape = s.content.value.shapeObjects.first { it.id == 1L }
        val a = shape.points[0]
        val b = shape.points[1]
        val width = kotlin.math.abs(b.x - a.x)
        val height = kotlin.math.abs(b.y - a.y)
        assertEquals(10f, height, 0.001f) // y unchanged
        assertTrue(width > 10f)           // x grew

        // Contained stroke scales horizontally only: y offsets unchanged.
        val stroke = s.content.value.strokes.first { it.id == 10L }
        val pts = stroke.pointsPacked
        assertEquals(2f, pts[1], 0.001f)
        assertEquals(8f, pts[3], 0.001f)
        assertTrue(pts[2] > 8f)
    }

    @Test
    fun resizeSelectionUndoesAndRedoes() {
        val s = shapeWithInk()
        s.selectAt(0.5f, 9.5f)
        val original = s.content.value.shapeObjects.first { it.id == 1L }.points
        s.beginResizeSelection(2)
        s.resizeSelectionTo(20f, 20f)
        s.endResizeSelection()
        val resized = s.content.value.shapeObjects.first { it.id == 1L }.points
        assertTrue(resized[1].x > original[1].x)

        s.undo()
        val restored = s.content.value.shapeObjects.first { it.id == 1L }.points
        assertEquals(original[0].x, restored[0].x, 0.001f)
        assertEquals(original[1].x, restored[1].x, 0.001f)

        s.redo()
        val reapplied = s.content.value.shapeObjects.first { it.id == 1L }.points
        assertEquals(resized[1].x, reapplied[1].x, 0.001f)
    }
}