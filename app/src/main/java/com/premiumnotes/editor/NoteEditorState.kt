package com.premiumnotes.editor

import com.premiumnotes.model.PageContent
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.PenType
import com.premiumnotes.model.Point
import com.premiumnotes.model.Stroke
import android.graphics.RectF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Active editor tool. */
enum class Tool {
    PEN, HIGHLIGHTER, ERASER, SELECT, TEXT, IMAGE
}

/**
 * In-memory editor state: document content, active tool, pen style and the undo/redo
 * stacks. All mutations flow through [apply] so undo/redo stay consistent. This class is
 * pure Kotlin and unit-testable.
 */
class NoteEditorState(
    initialContent: PageContent,
    private val ids: AtomicLong = AtomicLong(initialContent.maxObjectId()),
) {
    private val _content = MutableStateFlow(initialContent)
    val content: StateFlow<PageContent> = _content.asStateFlow()

    private val undoRedo = UndoRedoStack()

    private val _tool = MutableStateFlow(Tool.PEN)
    val tool: StateFlow<Tool> = _tool.asStateFlow()

    private val _penStyle = MutableStateFlow(PenStyle())
    val penStyle: StateFlow<PenStyle> = _penStyle.asStateFlow()

    private val _eraserSizeMm = MutableStateFlow(6f)
    val eraserSizeMm: StateFlow<Float> = _eraserSizeMm.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    // Live-drag bookkeeping so a whole move coalesces into one undo step.
    private var selectionAnchor: Point? = null
    private var selectionOriginals: List<Stroke>? = null

    val canUndo: Boolean get() = undoRedo.canUndo
    val canRedo: Boolean get() = undoRedo.canRedo

    fun setTool(tool: Tool) {
        _tool.value = tool
    }

    fun setPenStyle(style: PenStyle) {
        _penStyle.value = style
    }

    fun setEraserSize(sizeMm: Float) {
        _eraserSizeMm.value = sizeMm
    }

    fun nextId(): Long = ids.incrementAndGet()

    /** Applies a command, records it for undo, clears redo. */
    fun apply(command: EditorCommand) {
        val next = command.apply(_content.value)
        _content.value = next
        ids.accumulateAndGet(next.maxObjectId()) { current, max -> maxOf(current, max) }
        undoRedo.push(command)
    }

    /** Applies a command, coalescing with the top undo entry when possible. */
    fun applyCoalescing(command: EditorCommand) {
        val next = command.apply(_content.value)
        _content.value = next
        ids.accumulateAndGet(next.maxObjectId()) { current, max -> maxOf(current, max) }
        undoRedo.coalescePush(command)
    }

    // --- selection ----------------------------------------------------------

    /** Bounding box (world mm) of the current selection, or null. */
    val selectionBoundsMm: RectF?
        get() {
            val ids = _selectedIds.value
            if (ids.isEmpty()) return null
            val selected = _content.value.strokes.filter { it.id in ids }
            if (selected.isEmpty()) return null
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (s in selected) {
                val pts = s.pointsPacked
                var i = 0
                while (i + 1 < pts.size) {
                    if (pts[i] < minX) minX = pts[i]
                    if (pts[i] > maxX) maxX = pts[i]
                    if (pts[i + 1] < minY) minY = pts[i + 1]
                    if (pts[i + 1] > maxY) maxY = pts[i + 1]
                    i += 2
                }
            }
            if (minX > maxX) return null
            val pad = 2f
            return RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        }

    /** Selects strokes whose bounding box intersects [rect] (world mm). */
    fun selectInRect(rect: RectF) {
        _selectedIds.value = _content.value.strokes
            .filter { strokeIntersectsRect(it, rect) }
            .map { it.id }
            .toSet()
    }

    fun selectAll() {
        _selectedIds.value = _content.value.strokes.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        selectionAnchor = null
        selectionOriginals = null
    }

    fun deleteSelection() {
        val selected = _content.value.strokes.filter { it.id in _selectedIds.value }
        if (selected.isEmpty()) return
        apply(RemoveStrokesCommand(selected))
        clearSelection()
    }

    fun duplicateSelection() {
        val selected = _content.value.strokes.filter { it.id in _selectedIds.value }
        if (selected.isEmpty()) return
        val copies = selected.map { it.copy(id = nextId(), pointsPacked = it.pointsPacked.copyOf()) }
        apply(AddStrokesCommand(copies))
        _selectedIds.value = copies.map { it.id }.toSet()
    }

    fun beginMoveSelection(anchorWorldX: Float, anchorWorldY: Float) {
        selectionAnchor = Point(anchorWorldX, anchorWorldY)
        selectionOriginals = _content.value.strokes.filter { it.id in _selectedIds.value }
    }

    fun moveSelectionTo(worldX: Float, worldY: Float) {
        val originals = selectionOriginals ?: return
        val anchor = selectionAnchor ?: return
        if (originals.isEmpty()) return
        val dx = worldX - anchor.x
        val dy = worldY - anchor.y
        applyCoalescing(TransformStrokesCommand(originals, originals.map { translateStroke(it, dx, dy) }))
    }

    fun endMoveSelection() {
        selectionAnchor = null
        selectionOriginals = null
    }

    private fun translateStroke(s: Stroke, dx: Float, dy: Float): Stroke {
        val pts = s.pointsPacked
        if (pts.isEmpty()) return s
        val out = FloatArray(pts.size)
        var i = 0
        while (i + 1 < pts.size) {
            out[i] = pts[i] + dx
            out[i + 1] = pts[i + 1] + dy
            i += 2
        }
        return s.copy(pointsPacked = out)
    }

    fun undo() {
        val c = undoRedo.undoCommand() ?: return
        _content.value = c.invert().apply(_content.value)
    }

    fun redo() {
        val c = undoRedo.redoCommand() ?: return
        _content.value = c.apply(_content.value)
    }

    /** Removes strokes intersecting a screen-space circle given in world mm. */
    fun eraseAt(x: Float, y: Float, radiusMm: Float): Int {
        val hit = _content.value.strokes.filter { strokeIntersects(it, x, y, radiusMm) }
        if (hit.isEmpty()) return 0
        apply(RemoveStrokesCommand(hit))
        return hit.size
    }

    /** Erases whole strokes whose path crosses the swept line (scrub eraser). */
    fun eraseAlong(fromX: Float, fromY: Float, toX: Float, toY: Float, radiusMm: Float): Int {
        val hit = _content.value.strokes.filter { strokeIntersectsSegment(it, fromX, fromY, toX, toY, radiusMm) }
        if (hit.isEmpty()) return 0
        apply(RemoveStrokesCommand(hit))
        return hit.size
    }

    private fun strokeIntersects(stroke: Stroke, x: Float, y: Float, r: Float): Boolean {
        val pts = stroke.pointsPacked
        var i = 0
        while (i + 1 < pts.size) {
            val dx = pts[i] - x
            val dy = pts[i + 1] - y
            if (dx * dx + dy * dy <= r * r) return true
            i += 2
        }
        return false
    }

    private fun strokeIntersectsSegment(stroke: Stroke, ax: Float, ay: Float, bx: Float, by: Float, r: Float): Boolean {
        val pts = stroke.pointsPacked
        var i = 0
        while (i + 1 < pts.size) {
            if (pointSegmentDistance(pts[i], pts[i + 1], ax, ay, bx, by) <= r) return true
            i += 2
        }
        return false
    }

    private fun pointSegmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay
        val len2 = abx * abx + aby * aby
        val t = if (len2 == 0f) 0f else ((apx * abx + apy * aby) / len2).coerceIn(0f, 1f)
        val cx = ax + t * abx
        val cy = ay + t * aby
        return kotlin.math.hypot(px - cx, py - cy)
    }

    private fun strokeIntersectsRect(s: Stroke, rect: RectF): Boolean {
        val pts = s.pointsPacked
        if (pts.isEmpty()) return false
        var minX = pts[0]; var maxX = pts[0]
        var minY = pts[1]; var maxY = pts[1]
        var i = 2
        while (i + 1 < pts.size) {
            if (pts[i] < minX) minX = pts[i]
            if (pts[i] > maxX) maxX = pts[i]
            if (pts[i + 1] < minY) minY = pts[i + 1]
            if (pts[i + 1] > maxY) maxY = pts[i + 1]
            i += 2
        }
        return minX <= rect.right && maxX >= rect.left &&
            minY <= rect.bottom && maxY >= rect.top
    }
}

/** Highest object id already present in a page, used to seed the id counter. */
private fun PageContent.maxObjectId(): Long {
    var max = 0L
    for (s in strokes) if (s.id > max) max = s.id
    for (t in textObjects) if (t.id > max) max = t.id
    for (i in imageObjects) if (i.id > max) max = i.id
    for (sh in shapeObjects) if (sh.id > max) max = sh.id
    return max
}