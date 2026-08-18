package com.premiumnotes.editor

import com.premiumnotes.model.PageContent
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.PenType
import com.premiumnotes.model.Point
import com.premiumnotes.model.ShapeKind
import com.premiumnotes.model.ShapeObject
import com.premiumnotes.model.Stroke
import android.graphics.RectF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Active editor tool. */
enum class Tool {
    PEN, HIGHLIGHTER, ERASER, SELECT, SHAPES, TEXT, IMAGE
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

    private val _shapeKind = MutableStateFlow(ShapeKind.RECT)
    val shapeKind: StateFlow<ShapeKind> = _shapeKind.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    // Live-drag bookkeeping so a whole move/resize coalesces into one undo step. The
    // "originals" capture the geometry at gesture start; every live update re-derives the
    // transform from those originals, so undo restores the exact pre-gesture content.
    private var selectionAnchor: Point? = null
    private var selectionOriginalsStrokes: List<Stroke>? = null
    private var selectionOriginalsShapes: List<ShapeObject>? = null
    private var resizeHandleIndex = -1
    private var resizeOriginalsStrokes: List<Stroke>? = null
    private var resizeOriginalsShapes: List<ShapeObject>? = null

    // Erase-gesture bookkeeping: objects removed during one press-to-lift coalesce into
    // a single undo entry so undoing an erase restores everything at once.
    private var eraseBatch: EraseBatch? = null

    /** Objects removed during one erase gesture, later collapsed into one undo entry. */
    private class EraseBatch(
        val strokes: MutableList<Stroke> = ArrayList(),
        val shapes: MutableList<ShapeObject> = ArrayList(),
        val textObjects: MutableList<com.premiumnotes.model.TextObject> = ArrayList(),
        val imageObjects: MutableList<com.premiumnotes.model.ImageObject> = ArrayList(),
    ) {
        val isEmpty: Boolean
            get() = strokes.isEmpty() && shapes.isEmpty() && textObjects.isEmpty() && imageObjects.isEmpty()

        fun clear() {
            strokes.clear(); shapes.clear(); textObjects.clear(); imageObjects.clear()
        }
    }

    /** Ink pen style saved while the user switches to the highlighter (or back). */
    private val _savedInkStyle = MutableStateFlow<PenStyle?>(null)
    val savedInkStyle: StateFlow<PenStyle?> = _savedInkStyle.asStateFlow()

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

    fun setShapeKind(kind: ShapeKind) {
        _shapeKind.value = kind
    }

    /** Adds a placed geometric shape (undoable). */
    fun addShape(shape: ShapeObject) {
        apply(AddShapeCommand(shape))
    }

    /**
     * Replaces the Classroom Notes transcript (live during recording, static on reopen).
     * Not an undoable drawing action — it flows straight into [PageContent] so autosave
     * persists it with the page.
     */
    fun setTranscript(transcript: List<com.premiumnotes.model.TranscriptSegment>) {
        _content.value = _content.value.copy(transcript = transcript)
        ids.accumulateAndGet(transcript.maxOfOrNull { it.id } ?: 0L) { c, m -> maxOf(c, m) }
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

    /** Bounding box (world mm) of the current selection (strokes + shapes), or null. */
    val selectionBoundsMm: RectF?
        get() {
            val ids = _selectedIds.value
            if (ids.isEmpty()) return null
            val rect = RectF()
            var set = false
            for (s in _content.value.strokes) {
                if (s.id !in ids) continue
                val pts = s.pointsPacked
                var i = 0
                while (i + 1 < pts.size) {
                    if (!set) {
                        rect.set(pts[i], pts[i + 1], pts[i], pts[i + 1])
                        set = true
                    } else {
                        rect.union(pts[i], pts[i + 1])
                    }
                    i += 2
                }
            }
            for (sh in _content.value.shapeObjects) {
                if (sh.id !in ids) continue
                val a = sh.points.getOrNull(0) ?: continue
                val b = sh.points.getOrNull(1) ?: continue
                val left = kotlin.math.min(a.x, b.x)
                val top = kotlin.math.min(a.y, b.y)
                val right = kotlin.math.max(a.x, b.x)
                val bottom = kotlin.math.max(a.y, b.y)
                if (!set) {
                    rect.set(left, top, right, bottom)
                    set = true
                } else {
                    rect.union(left, top)
                    rect.union(right, bottom)
                }
            }
            if (!set) return null
            val pad = 2f
            return RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
        }

    /** Selects a single object at [x],[y] (shapes and ink strokes, topmost first). */
    fun selectAt(x: Float, y: Float) {
        val shapes = _content.value.shapeObjects.filter { shapeIntersects(it, x, y, 4f) }
        val highlighters = _content.value.strokes.filter {
            it.style.type == PenType.HIGHLIGHTER && strokeIntersects(it, x, y, 3f)
        }
        val ink = _content.value.strokes.filter {
            it.style.type != PenType.HIGHLIGHTER && strokeIntersects(it, x, y, 3f)
        }
        // Topmost-first z-order on the canvas (matches onDraw):
        // highlighters < shapes < ink, so ink wins, then shapes, then highlighters.
        val topId = when {
            ink.isNotEmpty() -> ink.last().id
            shapes.isNotEmpty() -> shapes.last().id
            highlighters.isNotEmpty() -> highlighters.last().id
            else -> null
        }
        if (topId == null) {
            _selectedIds.value = emptySet()
            return
        }
        val topShape = _content.value.shapeObjects.find { it.id == topId }
        if (topShape == null) {
            _selectedIds.value = setOf(topId)
            return
        }
        // Selecting a shape pulls in everything fully inside it (Feature 3): ink strokes
        // whose points all fall inside its bounds, and shapes whose corners both do.
        // Contained content then follows the shape on move/resize because it is part of
        // the selection.
        val ids = HashSet<Long>()
        ids += topId
        val region = shapeRegion(topShape)
        for (s in _content.value.strokes) if (strokeFullyInside(s, region)) ids += s.id
        for (sh in _content.value.shapeObjects) if (sh.id != topId && shapeFullyInside(sh, region)) ids += sh.id
        _selectedIds.value = ids
    }

    /** Inflated world-space bounds of a shape (a touch on the outline counts as inside). */
    private fun shapeRegion(shape: ShapeObject): RectF {
        val a = shape.points.getOrNull(0) ?: return RectF()
        val b = shape.points.getOrNull(1)
            ?: return RectF(a.x - 1f, a.y - 1f, a.x + 1f, a.y + 1f)
        val pad = 1f
        return RectF(
            kotlin.math.min(a.x, b.x) - pad,
            kotlin.math.min(a.y, b.y) - pad,
            kotlin.math.max(a.x, b.x) + pad,
            kotlin.math.max(a.y, b.y) + pad,
        )
    }

    private fun strokeFullyInside(s: Stroke, region: RectF): Boolean {
        val pts = s.pointsPacked
        var i = 0
        while (i + 1 < pts.size) {
            if (!insideRegion(pts[i], pts[i + 1], region)) return false
            i += 2
        }
        return true
    }

    private fun shapeFullyInside(sh: ShapeObject, region: RectF): Boolean {
        val a = sh.points.getOrNull(0) ?: return false
        val b = sh.points.getOrNull(1) ?: return insideRegion(a.x, a.y, region)
        return insideRegion(a.x, a.y, region) && insideRegion(b.x, b.y, region)
    }

    private fun insideRegion(x: Float, y: Float, region: RectF): Boolean =
        x >= region.left && x <= region.right && y >= region.top && y <= region.bottom

    /** Selects objects (strokes + shapes) whose bounding box intersects [rect] (world mm). */
    fun selectInRect(rect: RectF) {
        val ids = HashSet<Long>()
        for (s in _content.value.strokes) if (strokeIntersectsRect(s, rect)) ids += s.id
        for (sh in _content.value.shapeObjects) if (shapeIntersectsRect(sh, rect)) ids += sh.id
        _selectedIds.value = ids
    }

    fun selectAll() {
        val ids = _content.value.strokes.mapTo(HashSet()) { it.id }
        _content.value.shapeObjects.forEach { ids += it.id }
        _selectedIds.value = ids
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        selectionAnchor = null
        selectionOriginalsStrokes = null
        selectionOriginalsShapes = null
        resizeHandleIndex = -1
        resizeOriginalsStrokes = null
        resizeOriginalsShapes = null
    }

    fun deleteSelection() {
        val strokes = _content.value.strokes.filter { it.id in _selectedIds.value }
        val shapes = _content.value.shapeObjects.filter { it.id in _selectedIds.value }
        if (strokes.isEmpty() && shapes.isEmpty()) return
        apply(RemoveObjectsCommand(strokes = strokes, shapes = shapes))
        clearSelection()
    }

    fun duplicateSelection() {
        val strokes = _content.value.strokes.filter { it.id in _selectedIds.value }
        val shapes = _content.value.shapeObjects.filter { it.id in _selectedIds.value }
        if (strokes.isEmpty() && shapes.isEmpty()) return
        val strokeCopies = strokes.map {
            it.copy(id = nextId(), pointsPacked = it.pointsPacked.copyOf())
        }
        val shapeCopies = shapes.map {
            it.copy(id = nextId(), points = it.points.map { p -> p })
        }
        apply(AddObjectsCommand(strokes = strokeCopies, shapes = shapeCopies))
        val copyIds = HashSet<Long>()
        strokeCopies.forEach { copyIds += it.id }
        shapeCopies.forEach { copyIds += it.id }
        _selectedIds.value = copyIds
    }

    fun beginMoveSelection(anchorWorldX: Float, anchorWorldY: Float) {
        selectionAnchor = Point(anchorWorldX, anchorWorldY)
        selectionOriginalsStrokes = _content.value.strokes.filter { it.id in _selectedIds.value }
        selectionOriginalsShapes = _content.value.shapeObjects.filter { it.id in _selectedIds.value }
    }

    fun moveSelectionTo(worldX: Float, worldY: Float) {
        val originalsStrokes = selectionOriginalsStrokes ?: return
        val originalsShapes = selectionOriginalsShapes ?: return
        val anchor = selectionAnchor ?: return
        if (originalsStrokes.isEmpty() && originalsShapes.isEmpty()) return
        val dx = worldX - anchor.x
        val dy = worldY - anchor.y
        val movedStrokes = originalsStrokes.map { translateStroke(it, dx, dy) }
        val movedShapes = originalsShapes.map { translateShape(it, dx, dy) }
        applyCoalescing(
            TransformSelectionCommand(originalsStrokes, movedStrokes, originalsShapes, movedShapes)
        )
    }

    fun endMoveSelection() {
        selectionAnchor = null
        selectionOriginalsStrokes = null
        selectionOriginalsShapes = null
    }

    // --- shape resize -------------------------------------------------------

    /**
     * Starts resizing the selection from one of eight handles: corners (0-3, proportional)
     * or edge midpoints (4-7, single-axis).
     */
    fun beginResizeSelection(handleIndex: Int) {
        if (selectionBoundsMm == null) return
        resizeHandleIndex = handleIndex
        resizeOriginalsStrokes = _content.value.strokes.filter { it.id in _selectedIds.value }
        resizeOriginalsShapes = _content.value.shapeObjects.filter { it.id in _selectedIds.value }
    }

    /**
     * Live-resizes the selection from a corner (proportional, aspect locked) or edge
     * (single axis) handle. The opposite corner/edge stays anchored. Keeps geometry
     * non-degenerate (min 1 mm per axis) and coalesces the whole drag into one undo step.
     */
    fun resizeSelectionTo(worldX: Float, worldY: Float) {
        val originalsStrokes = resizeOriginalsStrokes ?: return
        val originalsShapes = resizeOriginalsShapes ?: return
        if (originalsStrokes.isEmpty() && originalsShapes.isEmpty()) return
        val bounds = selectionBoundsMm ?: return
        // The anchor is the corner/edge opposite the dragged handle; the old handle
        // position is the handle of the ORIGINAL bounds (scale relative to gesture start).
        val anchorX: Float
        val anchorY: Float
        val draggedOldX: Float
        val draggedOldY: Float
        when (resizeHandleIndex) {
            0 -> { // top-left corner (proportional)
                anchorX = bounds.right; anchorY = bounds.bottom
                draggedOldX = bounds.left; draggedOldY = bounds.top
            }
            1 -> { // top-right corner (proportional)
                anchorX = bounds.left; anchorY = bounds.bottom
                draggedOldX = bounds.right; draggedOldY = bounds.top
            }
            2 -> { // bottom-right corner (proportional)
                anchorX = bounds.left; anchorY = bounds.top
                draggedOldX = bounds.right; draggedOldY = bounds.bottom
            }
            3 -> { // bottom-left corner (proportional)
                anchorX = bounds.right; anchorY = bounds.top
                draggedOldX = bounds.left; draggedOldY = bounds.bottom
            }
            4 -> { // top edge (vertical only)
                anchorX = (bounds.left + bounds.right) / 2f; anchorY = bounds.bottom
                draggedOldX = anchorX; draggedOldY = bounds.top
            }
            5 -> { // right edge (horizontal only)
                anchorX = bounds.left; anchorY = (bounds.top + bounds.bottom) / 2f
                draggedOldX = bounds.right; draggedOldY = anchorY
            }
            6 -> { // bottom edge (vertical only)
                anchorX = (bounds.left + bounds.right) / 2f; anchorY = bounds.top
                draggedOldX = anchorX; draggedOldY = bounds.bottom
            }
            else -> { // left edge (horizontal only)
                anchorX = bounds.right; anchorY = (bounds.top + bounds.bottom) / 2f
                draggedOldX = bounds.left; draggedOldY = anchorY
            }
        }
        val denomX = draggedOldX - anchorX
        val denomY = draggedOldY - anchorY
        val anchor = Point(anchorX, anchorY)

        var sx: Float
        var sy: Float
        if (resizeHandleIndex < 4) {
            // Corner: proportional. Diagonal-distance ratio is the intuitive feel —
            // content tracks the finger while keeping its aspect ratio.
            val handleDist = kotlin.math.hypot(draggedOldX - anchorX, draggedOldY - anchorY)
            val fingerDist = kotlin.math.hypot(worldX - anchorX, worldY - anchorY)
            var scale = if (handleDist == 0f) 1f else fingerDist / handleDist
            val minScale = maxOf(
                if (denomX == 0f) 0f else 1f / kotlin.math.abs(denomX),
                if (denomY == 0f) 0f else 1f / kotlin.math.abs(denomY),
            )
            scale = scale.coerceAtLeast(minScale)
            sx = scale
            sy = scale
        } else {
            // Edge: single axis. Reuse the sign-aware clamp so the handle never crosses
            // the anchor and geometry stays at least 1 mm per axis.
            val minSx = if (denomX == 0f) 1f else 1f / denomX
            val minSy = if (denomY == 0f) 1f else 1f / denomY
            val sxRaw = if (denomX == 0f) 1f else (worldX - anchorX) / denomX
            val syRaw = if (denomY == 0f) 1f else (worldY - anchorY) / denomY
            sx = if (denomX > 0) sxRaw.coerceAtLeast(minSx) else sxRaw.coerceAtMost(minSx)
            sy = if (denomY > 0) syRaw.coerceAtLeast(minSy) else syRaw.coerceAtMost(minSy)
            if (resizeHandleIndex == 4 || resizeHandleIndex == 6) sx = 1f else sy = 1f
        }

        val resizedStrokes = originalsStrokes.map { scaleStroke(it, anchor, sx, sy) }
        val resizedShapes = originalsShapes.map { scaleShape(it, anchor, sx, sy) }
        applyCoalescing(
            TransformSelectionCommand(originalsStrokes, resizedStrokes, originalsShapes, resizedShapes)
        )
    }

    fun endResizeSelection() {
        resizeHandleIndex = -1
        resizeOriginalsStrokes = null
        resizeOriginalsShapes = null
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

    private fun translateShape(s: ShapeObject, dx: Float, dy: Float): ShapeObject =
        s.copy(
            x = s.x + dx,
            y = s.y + dy,
            points = s.points.map { Point(it.x + dx, it.y + dy) },
        )

    private fun scaleStroke(s: Stroke, anchor: Point, sx: Float, sy: Float): Stroke {
        val pts = s.pointsPacked
        val out = FloatArray(pts.size)
        var i = 0
        while (i + 1 < pts.size) {
            out[i] = anchor.x + (pts[i] - anchor.x) * sx
            out[i + 1] = anchor.y + (pts[i + 1] - anchor.y) * sy
            i += 2
        }
        return s.copy(pointsPacked = out)
    }

    private fun scaleShape(s: ShapeObject, anchor: Point, sx: Float, sy: Float): ShapeObject =
        s.copy(
            x = anchor.x + (s.x - anchor.x) * sx,
            y = anchor.y + (s.y - anchor.y) * sy,
            points = s.points.map { Point(anchor.x + (it.x - anchor.x) * sx, anchor.y + (it.y - anchor.y) * sy) },
        )

    fun undo() {
        val c = undoRedo.undoCommand() ?: return
        _content.value = c.invert().apply(_content.value)
    }

    fun redo() {
        val c = undoRedo.redoCommand() ?: return
        _content.value = c.apply(_content.value)
    }

    /** Objects hit by one erase action, split by erase priority tier. */
    private class EraseHit(
        val highlighters: List<Stroke> = emptyList(),
        val textObjects: List<com.premiumnotes.model.TextObject> = emptyList(),
        val shapes: List<ShapeObject> = emptyList(),
        val ink: List<Stroke> = emptyList(),
        val images: List<com.premiumnotes.model.ImageObject> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = highlighters.isEmpty() && textObjects.isEmpty() &&
                shapes.isEmpty() && ink.isEmpty() && images.isEmpty()

        val count: Int
            get() = highlighters.size + textObjects.size + shapes.size + ink.size + images.size
    }

    /**
     * Erases objects touched at a point. One tier per action, topmost-first: ink, then
     * shapes, then highlighters, then text, then images — matching the canvas draw order
     * (highlighters < shapes < ink). Tapping overlapping ink + shape removes the ink
     * first; a second tap reaches the shape underneath.
     */
    fun eraseAt(x: Float, y: Float, radiusMm: Float): Int {
        val hit = collectErasePoint(x, y, radiusMm)
        return applyEraseHit(hit)
    }

    /** Scrub eraser: erases objects touched along a swept segment. */
    fun eraseAlong(fromX: Float, fromY: Float, toX: Float, toY: Float, radiusMm: Float): Int {
        val hit = collectEraseSegment(fromX, fromY, toX, toY, radiusMm)
        return applyEraseHit(hit)
    }

    private fun applyEraseHit(hit: EraseHit): Int {
        if (hit.isEmpty) return 0
        if (eraseBatch != null) {
            // Accumulate during the gesture; the whole gesture becomes one undo step.
            eraseBatch!!.strokes += hit.highlighters + hit.ink
            eraseBatch!!.shapes += hit.shapes
            eraseBatch!!.textObjects += hit.textObjects
            eraseBatch!!.imageObjects += hit.images
            removeObjectsFromContent(hit)
        } else {
            apply(
                RemoveObjectsCommand(
                    strokes = hit.highlighters + hit.ink,
                    shapes = hit.shapes,
                    textObjects = hit.textObjects,
                    imageObjects = hit.images,
                )
            )
        }
        return hit.count
    }

    private fun removeObjectsFromContent(hit: EraseHit) {
        val strokeIds = (hit.highlighters + hit.ink).mapTo(HashSet()) { it.id }
        val shapeIds = hit.shapes.mapTo(HashSet()) { it.id }
        val textIds = hit.textObjects.mapTo(HashSet()) { it.id }
        val imageIds = hit.images.mapTo(HashSet()) { it.id }
        _content.value = _content.value.copy(
            strokes = _content.value.strokes.filterNot { it.id in strokeIds },
            shapeObjects = _content.value.shapeObjects.filterNot { it.id in shapeIds },
            textObjects = _content.value.textObjects.filterNot { it.id in textIds },
            imageObjects = _content.value.imageObjects.filterNot { it.id in imageIds },
        )
    }

    /**
     * Opens an erase gesture. Objects erased until [eraseGestureEnd] are batched into one
     * undo step; without this every MOVE during an erase would push its own undo entry.
     */
    fun eraseGestureBegin() {
        if (eraseBatch == null) eraseBatch = EraseBatch()
    }

    /** Closes the erase gesture and records the batch as a single undoable command. */
    fun eraseGestureEnd() {
        val batch = eraseBatch ?: return
        eraseBatch = null
        if (batch.isEmpty) return
        val distinctStrokes = batch.strokes.distinctBy { it.id }
        val distinctShapes = batch.shapes.distinctBy { it.id }
        val distinctText = batch.textObjects.distinctBy { it.id }
        val distinctImages = batch.imageObjects.distinctBy { it.id }
        undoRedo.push(
            RemoveObjectsCommand(
                strokes = distinctStrokes,
                shapes = distinctShapes,
                textObjects = distinctText,
                imageObjects = distinctImages,
            )
        )
    }

    private fun collectErasePoint(x: Float, y: Float, r: Float): EraseHit {
        val highlighters = ArrayList<Stroke>()
        val ink = ArrayList<Stroke>()
        for (s in _content.value.strokes) {
            if (!strokeIntersects(s, x, y, r)) continue
            if (s.style.type == PenType.HIGHLIGHTER) highlighters += s else ink += s
        }
        val text = _content.value.textObjects.filter { textContains(it, x, y, r) }
        val shapes = _content.value.shapeObjects.filter { shapeIntersects(it, x, y, r) }
        val images = _content.value.imageObjects.filter { imageContains(it, x, y, r) }
        // Topmost-first: ink, shapes, highlighters, text, images.
        return when {
            ink.isNotEmpty() -> EraseHit(ink = ink)
            shapes.isNotEmpty() -> EraseHit(shapes = shapes)
            highlighters.isNotEmpty() -> EraseHit(highlighters = highlighters)
            text.isNotEmpty() -> EraseHit(textObjects = text)
            images.isNotEmpty() -> EraseHit(images = images)
            else -> EraseHit()
        }
    }

    private fun collectEraseSegment(ax: Float, ay: Float, bx: Float, by: Float, r: Float): EraseHit {
        val highlighters = ArrayList<Stroke>()
        val ink = ArrayList<Stroke>()
        for (s in _content.value.strokes) {
            if (!strokeIntersectsSegment(s, ax, ay, bx, by, r)) continue
            if (s.style.type == PenType.HIGHLIGHTER) highlighters += s else ink += s
        }
        val text = _content.value.textObjects.filter { textIntersectsSegment(it, ax, ay, bx, by, r) }
        val shapes = _content.value.shapeObjects.filter { shapeIntersectsSegment(it, ax, ay, bx, by, r) }
        val images = _content.value.imageObjects.filter { imageIntersectsSegment(it, ax, ay, bx, by, r) }
        // Topmost-first: ink, shapes, highlighters, text, images.
        return when {
            ink.isNotEmpty() -> EraseHit(ink = ink)
            shapes.isNotEmpty() -> EraseHit(shapes = shapes)
            highlighters.isNotEmpty() -> EraseHit(highlighters = highlighters)
            text.isNotEmpty() -> EraseHit(textObjects = text)
            images.isNotEmpty() -> EraseHit(images = images)
            else -> EraseHit()
        }
    }

    private fun textContains(t: com.premiumnotes.model.TextObject, x: Float, y: Float, r: Float): Boolean {
        val inflate = r + 1f
        return x >= t.x - inflate && x <= t.x + t.width + inflate &&
            y >= t.y - inflate && y <= t.y + t.height + inflate
    }

    private fun textIntersectsSegment(
        t: com.premiumnotes.model.TextObject, ax: Float, ay: Float, bx: Float, by: Float, r: Float,
    ): Boolean {
        val inflate = r + 1f
        val left = t.x - inflate
        val right = t.x + t.width + inflate
        val top = t.y - inflate
        val bottom = t.y + t.height + inflate
        return segmentIntersectsRect(ax, ay, bx, by, left, top, right, bottom)
    }

    private fun imageContains(
        im: com.premiumnotes.model.ImageObject, x: Float, y: Float, r: Float,
    ): Boolean {
        val inflate = r + 1f
        return x >= im.x - inflate && x <= im.x + im.width + inflate &&
            y >= im.y - inflate && y <= im.y + im.height + inflate
    }

    private fun imageIntersectsSegment(
        im: com.premiumnotes.model.ImageObject, ax: Float, ay: Float, bx: Float, by: Float, r: Float,
    ): Boolean {
        val inflate = r + 1f
        return segmentIntersectsRect(ax, ay, bx, by, im.x - inflate, im.y - inflate, im.x + im.width + inflate, im.y + im.height + inflate)
    }

    private fun segmentIntersectsRect(
        ax: Float, ay: Float, bx: Float, by: Float,
        left: Float, top: Float, right: Float, bottom: Float,
    ): Boolean {
        // Cheap test: the segment's bounding box overlaps the rect OR an endpoint is inside.
        if (ax <= right && bx >= left && ay <= bottom && by >= top) {
            // Conservative: segment bbox overlap is enough for erasing; the eraser is fuzzy.
            return true
        }
        return false
    }

    // --- shape geometry hit-testing ------------------------------------------------
    //
    // Shapes are hit-tested against their actual outline geometry (the same vertices
    // ShapeRenderer.buildPath uses) plus their interior for closed kinds, so the eraser
    // and tap-selection behave precisely: a LINE/ARROW is only erased near its strokes,
    // while a filled rectangle/star/etc. is also erased when tapped inside. The old
    // bounding-box test erased a diagonal line when tapping anywhere in its huge box.

    /** Returns the outline of [s] as an edge list (world mm), mirroring ShapeRenderer. */
    private fun shapeOutlineSegments(s: ShapeObject): List<Pair<Point, Point>> {
        val a = s.points.getOrNull(0) ?: return emptyList()
        val b = s.points.getOrNull(1) ?: return emptyList()
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val w = right - left
        val h = bottom - top
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f

        return when (s.kind) {
            ShapeKind.LINE -> listOf(a to b)
            ShapeKind.ARROW -> arrowOutline(a, b, w, h)
            ShapeKind.RECT -> polyline(
                listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom)),
                closed = true,
            )
            ShapeKind.ROUNDED_RECT -> roundedRectOutline(left, top, right, bottom)
            ShapeKind.CIRCLE -> ellipseOutline(cx, cy, min(w, h) / 2f, min(w, h) / 2f)
            ShapeKind.ELLIPSE -> ellipseOutline(cx, cy, w / 2f, h / 2f)
            ShapeKind.TRIANGLE -> polyline(
                listOf(Point(cx, top), Point(left, bottom), Point(right, bottom)),
                closed = true,
            )
            ShapeKind.POLYGON -> regularPolygonOutline(cx, cy, min(w, h) / 2f, 6)
            ShapeKind.STAR -> starOutline(cx, cy, min(w, h) / 2f, 5)
        }
    }

    private fun shapeIsClosed(kind: ShapeKind): Boolean = when (kind) {
        ShapeKind.LINE, ShapeKind.ARROW -> false
        else -> true
    }

    private fun ellipseOutline(cx: Float, cy: Float, rx: Float, ry: Float, n: Int = 32): List<Pair<Point, Point>> {
        val pts = ArrayList<Point>(n)
        for (i in 0 until n) {
            val t = 2.0 * PI * i / n
            pts += Point((cx + rx * cos(t)).toFloat(), (cy + ry * sin(t)).toFloat())
        }
        return polyline(pts, closed = true)
    }

    private fun roundedRectOutline(left: Float, top: Float, right: Float, bottom: Float): List<Pair<Point, Point>> {
        val radius = min(right - left, bottom - top) * 0.2f
        val pts = ArrayList<Point>()
        val steps = 4
        fun corner(cx: Float, cy: Float, startAngle: Double) {
            for (i in 0 until steps) {
                val t = startAngle + PI / 2.0 * i / steps
                pts += Point((cx + radius * cos(t)).toFloat(), (cy + radius * sin(t)).toFloat())
            }
        }
        corner(left + radius, top + radius, PI)
        pts += Point(right - radius, top)
        corner(right - radius, top + radius, PI * 1.5)
        pts += Point(right, bottom - radius)
        corner(right - radius, bottom - radius, 0.0)
        pts += Point(left + radius, bottom)
        corner(left + radius, bottom - radius, PI * 0.5)
        pts += Point(left, top + radius)
        return polyline(pts, closed = true)
    }

    private fun regularPolygonOutline(cx: Float, cy: Float, r: Float, n: Int): List<Pair<Point, Point>> {
        val pts = ArrayList<Point>(n)
        for (i in 0 until n) {
            val a = -PI / 2.0 + 2.0 * PI * i / n
            pts += Point((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
        }
        return polyline(pts, closed = true)
    }

    private fun starOutline(cx: Float, cy: Float, outer: Float, points: Int): List<Pair<Point, Point>> {
        val inner = outer * 0.4f
        val pts = ArrayList<Point>(points * 2)
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outer else inner
            val a = -PI / 2.0 + PI * i / points
            pts += Point((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
        }
        return polyline(pts, closed = true)
    }

    private fun arrowOutline(a: Point, b: Point, w: Float, h: Float): List<Pair<Point, Point>> {
        val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(0.1f)
        val ux = (b.x - a.x) / len
        val uy = (b.y - a.y) / len
        val nx = -uy
        val ny = ux
        val head = (max(w, h) * 0.25f).coerceAtLeast(2f)
        val h1 = Point(b.x - ux * head + nx * head * 0.5f, b.y - uy * head + ny * head * 0.5f)
        val h2 = Point(b.x - ux * head - nx * head * 0.5f, b.y - uy * head - ny * head * 0.5f)
        return listOf(a to b, b to h1, b to h2)
    }

    private fun polyline(points: List<Point>, closed: Boolean): List<Pair<Point, Point>> {
        val segs = ArrayList<Pair<Point, Point>>(points.size)
        for (i in 0 until points.size - 1) segs += points[i] to points[i + 1]
        if (closed && points.size > 1) segs += points.last() to points.first()
        return segs
    }

    /** Ray-casting point-in-polygon over an edge list. */
    private fun pointInPolygon(x: Float, y: Float, edges: List<Pair<Point, Point>>): Boolean {
        var inside = false
        for ((p, q) in edges) {
            val crosses = (p.y > y) != (q.y > y) &&
                x < (q.x - p.x) * (y - p.y) / (q.y - p.y) + p.x
            if (crosses) inside = !inside
        }
        return inside
    }

    /** True when the circle at [x],[y] radius [r] touches the shape's outline or interior. */
    private fun shapeIntersects(s: ShapeObject, x: Float, y: Float, r: Float): Boolean {
        val edges = shapeOutlineSegments(s)
        if (edges.isEmpty()) return false
        for ((p, q) in edges) {
            if (pointSegmentDistance(x, y, p.x, p.y, q.x, q.y) <= r) return true
        }
        if (shapeIsClosed(s.kind) && pointInPolygon(x, y, edges)) return true
        return false
    }

    /** True when the swept eraser segment (inflated by [r]) touches the shape. */
    private fun shapeIntersectsSegment(
        s: ShapeObject, ax: Float, ay: Float, bx: Float, by: Float, r: Float,
    ): Boolean {
        val edges = shapeOutlineSegments(s)
        if (edges.isEmpty()) return false
        for ((p, q) in edges) {
            if (segmentDistance(ax, ay, bx, by, p.x, p.y, q.x, q.y) <= r) return true
        }
        if (shapeIsClosed(s.kind)) {
            // The sweep started or ended inside the shape.
            if (pointInPolygon(ax, ay, edges) || pointInPolygon(bx, by, edges)) return true
        }
        return false
    }

    private fun shapeIntersectsRect(s: ShapeObject, rect: RectF): Boolean {
        val a = s.points.getOrNull(0) ?: return false
        val b = s.points.getOrNull(1) ?: return false
        val left = kotlin.math.min(a.x, b.x)
        val right = kotlin.math.max(a.x, b.x)
        val top = kotlin.math.min(a.y, b.y)
        val bottom = kotlin.math.max(a.y, b.y)
        return left <= rect.right && right >= rect.left && top <= rect.bottom && bottom >= rect.top
    }

    /** Remembers the current pen style so the user can return to it after highlighting. */
    fun saveInkStyle() {
        if (_penStyle.value.type != PenType.HIGHLIGHTER) {
            _savedInkStyle.value = _penStyle.value
        }
    }

    /** Restores the ink pen style saved by [saveInkStyle], if any. */
    fun restoreInkStyle() {
        val ink = _savedInkStyle.value
        if (ink != null) {
            _penStyle.value = ink
            _savedInkStyle.value = null
        } else if (_penStyle.value.type == PenType.HIGHLIGHTER) {
            _penStyle.value = _penStyle.value.copy(type = PenType.BALLPOINT, opacity = 1f)
        }
    }

    private fun strokeIntersects(stroke: Stroke, x: Float, y: Float, r: Float): Boolean {
        val pts = stroke.pointsPacked
        var i = 0
        // Distance to every segment, not just the sampled points, so a tap in the
        // middle of a long segment between sparse samples still erases the stroke.
        while (i + 3 < pts.size) {
            if (pointSegmentDistance(x, y, pts[i], pts[i + 1], pts[i + 2], pts[i + 3]) <= r) return true
            i += 2
        }
        // Single-point stroke / the final pen-up tip.
        if (pts.size >= 2) {
            val last = pts.size - 2
            if (hypot(pts[last] - x, pts[last + 1] - y) <= r) return true
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
        return hypot(px - cx, py - cy)
    }

    /** Minimum distance between two segments, or 0 when they intersect. */
    private fun segmentDistance(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Float {
        if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return minOf(
            pointSegmentDistance(ax, ay, cx, cy, dx, dy),
            pointSegmentDistance(bx, by, cx, cy, dx, dy),
            pointSegmentDistance(cx, cy, ax, ay, bx, by),
            pointSegmentDistance(dx, dy, ax, ay, bx, by),
        )
    }

    private fun orientation(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Int {
        val v = (qy - py) * (rx - qx) - (qx - px) * (ry - qy)
        return when {
            v > 1e-9f -> 1
            v < -1e-9f -> -1
            else -> 0
        }
    }

    private fun onSegment(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Boolean =
        rx in min(px, qx)..max(px, qx) && ry in min(py, qy)..max(py, qy)

    private fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Boolean {
        val o1 = orientation(ax, ay, bx, by, cx, cy)
        val o2 = orientation(ax, ay, bx, by, dx, dy)
        val o3 = orientation(cx, cy, dx, dy, ax, ay)
        val o4 = orientation(cx, cy, dx, dy, bx, by)
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(ax, ay, bx, by, cx, cy)) return true
        if (o2 == 0 && onSegment(ax, ay, bx, by, dx, dy)) return true
        if (o3 == 0 && onSegment(cx, cy, dx, dy, ax, ay)) return true
        if (o4 == 0 && onSegment(cx, cy, dx, dy, bx, by)) return true
        return false
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
    for (seg in transcript) if (seg.id > max) max = seg.id
    return max
}