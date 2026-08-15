package com.premiumnotes.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.premiumnotes.editor.StrokeBuilder
import com.premiumnotes.editor.Tool
import com.premiumnotes.input.ClassifiedFrame
import com.premiumnotes.input.InputCapabilities
import com.premiumnotes.input.InputFrame
import com.premiumnotes.input.MotionEventParser
import com.premiumnotes.input.PalmRejectionEngine
import com.premiumnotes.model.PageBackground
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.Point
import com.premiumnotes.model.ShapeKind
import com.premiumnotes.model.ShapeObject
import com.premiumnotes.model.Stroke
import com.premiumnotes.render.InkRenderer
import com.premiumnotes.render.PageBackgroundRenderer
import com.premiumnotes.render.ShapeRenderer
import kotlin.math.hypot

/**
 * The low-latency handwriting canvas. This is a custom [View] (not Compose) so it can
 * access the full [MotionEvent] stream — including toolMajor/toolMinor, size,
 * orientation and coalesced history — which Compose's pointer API does not expose and
 * which the palm rejection system depends on.
 *
 * Responsibilities:
 *  - Route every MotionEvent through the palm rejection pipeline.
 *  - Drive the active stroke from the locked writing pointer (smoothed, dead-zoned).
 *  - Draw committed content (strokes + shapes) from a cached display list. The cached
 *    list only holds geometry (paths/paints), rebuilt when content changes, so the
 *    canvas renders every stroke/shape every frame under the viewport transform — the
 *    whole page is always present like paper, no bitmap layer to go stale while
 *    scrolling.
 *  - Render the in-progress stroke through the same renderer as committed strokes so
 *    the live stroke looks exactly like the final one (no thickness jump on commit).
 *  - Handle two-finger pan/zoom without letting a resting palm trigger gestures.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onStrokeCommitted(stroke: Stroke)
        fun onShapeCommitted(shape: ShapeObject)
        fun onEraseGestureBegin()
        fun onEraseAt(x: Float, y: Float, radiusMm: Float)
        fun onEraseAlong(x1: Float, y1: Float, x2: Float, y2: Float, radiusMm: Float)
        fun onEraseGestureEnd()
        fun onViewportChanged(zoom: Float, offsetX: Float, offsetY: Float)
        fun onSelectInRect(rect: RectF)
        fun onSelectionDragStart(worldX: Float, worldY: Float)
        fun onSelectionDragTo(worldX: Float, worldY: Float)
        fun onSelectionDragEnd()
    }

    lateinit var capabilities: InputCapabilities
    lateinit var engine: PalmRejectionEngine
    var listener: Listener? = null

    // --- document / tool state (set by the UI) ---
    var strokes: List<Stroke> = emptyList()
        set(value) {
            if (field !== value) {
                val pureAppend = value.size == field.size + 1 && value.dropLast(1) == field
                field = value
                strokesVersion++
                if (pureAppend) appendStrokeGeometry(value.last()) else rebuildStrokeGeometry()
                if (value.isNotEmpty()) {
                    strokeIdCounter = maxOf(strokeIdCounter, value.maxOf { it.id })
                }
                invalidate()
            }
        }

    var shapes: List<ShapeObject> = emptyList()
        set(value) {
            if (field !== value) {
                val pureAppend = value.size == field.size + 1 && value.dropLast(1) == field
                field = value
                shapesVersion++
                if (pureAppend) appendShapeGeometry(value.last()) else rebuildShapeGeometry()
                invalidate()
            }
        }

    var background: PageBackground = PageBackground()

    var penStyle: PenStyle = PenStyle()
    var tool: Tool = Tool.PEN
    var eraserSizeMm: Float = 6f
    var shapeKind: ShapeKind = ShapeKind.RECT

    /** World-space bounding box of the current selection, set by the UI. */
    var selectionBoundsMm: RectF? = null

    // --- viewport (screen px = world mm * scale + offset) ---
    var zoom: Float = 1f
        private set
    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    private val renderer = InkRenderer()

    // --- selection state ---
    private enum class SelectionMode { NONE, LASSO, MOVE }
    private var selectionMode = SelectionMode.NONE
    private var lassoStartWorld = Point(0f, 0f)
    private var lassoCurrentWorld = Point(0f, 0f)
    private var dragAnchorWorld = Point(0f, 0f)

    // Reused scratch objects so the steady-state draw path allocates nothing per frame.
    private val worldClipRect = RectF()
    private val lassoScreenRect = RectF()

    private val selectionPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF2E5BFF.toInt()
        isAntiAlias = true
    }
    private val lassoFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x222E5BFF.toInt()
    }
    private val lassoStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF2E5BFF.toInt()
        isAntiAlias = true
    }

    // --- active stroke ---
    private var strokeBuilder: StrokeBuilder? = null

    // --- committed content display list ---
    // Only geometry is cached (paths + paints); onDraw draws every committed stroke and
    // shape under the viewport transform, so nothing goes stale while the page scrolls.
    private data class CachedStroke(
        val type: com.premiumnotes.model.PenType,
        val path: Path,
        val paint: Paint,
        val pencil: Boolean,
        val grainAlpha: Int,
        val grainDx: Float,
        val grainDy: Float,
    )

    private data class CachedShape(val path: Path, val paint: Paint)

    private var displayStrokes: List<CachedStroke> = emptyList()
    private var displayShapes: List<CachedShape> = emptyList()
    private var strokesVersion = 0
    private var shapesVersion = 0

    // --- gesture state ---
    private data class GestureStart(
        val centroidX: Float, val centroidY: Float,
        val dist: Float, val zoom: Float,
        val offsetX: Float, val offsetY: Float,
    )

    private var gesture: GestureStart? = null

    private val scale: Float get() = capabilities.pxPerMm * zoom

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun screenToWorldX(sx: Float) = (sx - offsetX) / scale
    fun screenToWorldY(sy: Float) = (sy - offsetY) / scale

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val input = MotionEventParser.parse(event)
        val classified = engine.process(input)

        // Two-finger gestures take priority over the active tool so the page can be
        // panned/zoomed while a pen is selected — otherwise the user gets stuck at the
        // bottom of a scrolled page with no way back up. This also covers the moment a
        // second contact lands while a stroke is in progress (handleNavigation finalizes it).
        if (classified.gesturePointerIds.size >= 2) {
            handleNavigation(input, classified)
            return true
        }

        when {
            tool == Tool.ERASER -> handleEraser(input, classified)
            tool == Tool.PEN || tool == Tool.HIGHLIGHTER -> handleStroke(input, classified)
            tool == Tool.SELECT -> handleSelection(input, classified)
            tool == Tool.SHAPES -> handleShapes(input, classified)
            else -> handleNavigation(input, classified)
        }
        return true
    }

    // --- writing ---

    private var writingPointerId: Int = -1

    private fun handleStroke(input: InputFrame, classified: ClassifiedFrame) {
        when (input.action) {
            com.premiumnotes.input.InputAction.UP,
            com.premiumnotes.input.InputAction.POINTER_UP,
            -> {
                if (input.liftedPointerId == writingPointerId) {
                    val builder = strokeBuilder
                    strokeBuilder = null
                    writingPointerId = -1
                    builder?.let { b ->
                        val contact = classified.contactFor(input.liftedPointerId)
                        val endX: Float
                        val endY: Float
                        val endT: Long
                        if (contact != null) {
                            endX = screenToWorldX(contact.contact.x)
                            endY = screenToWorldY(contact.contact.y)
                            endT = contact.contact.eventTimeNanos
                        } else {
                            val last = b.livePoints.lastOrNull()
                            if (last == null) return@let
                            endX = last.x
                            endY = last.y
                            endT = 0L
                        }
                        val stroke = b.onUp(endX, endY, endT)
                        if (stroke != null) {
                            listener?.onStrokeCommitted(stroke)
                        }
                        invalidate()
                    }
                }
            }

            com.premiumnotes.input.InputAction.CANCEL -> {
                strokeBuilder?.onCancel()
                strokeBuilder = null
                writingPointerId = -1
                invalidate()
            }

            else -> {
                val writingId = classified.activeWritingPointerId ?: return
                val contact = classified.contactFor(writingId) ?: return
                when (input.action) {
                    com.premiumnotes.input.InputAction.DOWN,
                    com.premiumnotes.input.InputAction.POINTER_DOWN,
                    -> {
                        if (strokeBuilder == null) {
                            val builder = StrokeBuilder(
                                style = penStyle,
                                id = nextStrokeId(),
                            )
                            builder.onDown(screenToWorldX(contact.contact.x), screenToWorldY(contact.contact.y))
                            strokeBuilder = builder
                            writingPointerId = writingId
                        }
                    }

                    com.premiumnotes.input.InputAction.MOVE -> {
                        val builder = strokeBuilder ?: return
                        // Auto-scroll (page scrolling): keep the pen away from the top and
                        // bottom viewport edges so writing flows like a real notebook instead
                        // of forcing the user to create a new page. The world point for THIS
                        // event is computed before scrolling, so strokes stay continuous.
                        val h = height.toFloat()
                        val margin = 150f
                        val penY = contact.contact.y
                        val shiftY = when {
                            penY > h - margin -> penY - (h - margin)
                            penY < margin -> penY - margin
                            else -> 0f
                        }
                        if (shiftY != 0f) {
                            offsetY -= shiftY
                            listener?.onViewportChanged(zoom, offsetX, offsetY)
                        }
                        val worldX = screenToWorldX(contact.contact.x)
                        val worldY = screenToWorldY(contact.contact.y)
                        // Coalesced history samples (older first) carry the pointer motion
                        // the OS batched into this event; feeding them to the smoother keeps
                        // fast strokes continuous instead of dropping points.
                        var changed = false
                        for (h in input.history) {
                            if (h.pointerId != writingId) continue
                            if (builder.onMove(screenToWorldX(h.x), screenToWorldY(h.y), h.eventTimeNanos)) {
                                changed = true
                            }
                        }
                        if (builder.onMove(worldX, worldY, contact.contact.eventTimeNanos)) {
                            changed = true
                        }
                        if (changed) {
                            invalidate()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private var strokeIdCounter = 0L
    private fun nextStrokeId(): Long = ++strokeIdCounter

    /**
     * Commits any stroke currently being drawn, e.g. when a two-finger gesture starts.
     * Without this the in-progress stroke would be silently dropped by the canvas lock
     * being released for the gesture.
     */
    private fun finalizeActiveStroke() {
        val builder = strokeBuilder ?: return
        strokeBuilder = null
        writingPointerId = -1
        val last = builder.livePoints.lastOrNull()
        if (last == null) {
            invalidate()
            return
        }
        val stroke = builder.onUp(last.x, last.y, 0L)
        if (stroke != null) listener?.onStrokeCommitted(stroke)
        invalidate()
    }

    // --- shapes ---

    private var shapeStartWorld: Point? = null
    private var shapeCurrentWorld: Point? = null

    private fun handleShapes(input: InputFrame, classified: ClassifiedFrame) {
        val contact = primaryContact(classified) ?: return
        when (input.action) {
            com.premiumnotes.input.InputAction.DOWN,
            com.premiumnotes.input.InputAction.POINTER_DOWN,
            -> {
                shapeStartWorld = Point(screenToWorldX(contact.contact.x), screenToWorldY(contact.contact.y))
                shapeCurrentWorld = shapeStartWorld
                invalidate()
            }

            com.premiumnotes.input.InputAction.MOVE -> {
                if (shapeStartWorld != null) {
                    shapeCurrentWorld = Point(screenToWorldX(contact.contact.x), screenToWorldY(contact.contact.y))
                    invalidate()
                }
            }

            com.premiumnotes.input.InputAction.UP,
            com.premiumnotes.input.InputAction.POINTER_UP,
            com.premiumnotes.input.InputAction.CANCEL,
            -> {
                val start = shapeStartWorld ?: run { shapeCurrentWorld = null; return }
                val current = shapeCurrentWorld ?: start
                shapeStartWorld = null
                shapeCurrentWorld = null
                val size = hypot(current.x - start.x, current.y - start.y)
                if (size >= 2f) {
                    listener?.onShapeCommitted(
                        ShapeObject(
                            id = nextStrokeId(),
                            kind = shapeKind,
                            points = listOf(start, current),
                            x = start.x,
                            y = start.y,
                            strokeWidthMm = penStyle.widthMm,
                            colorArgb = penStyle.colorArgb,
                        )
                    )
                }
                invalidate()
            }
        }
    }

    // --- selection ---

    /** Best pointer for selection: locked pen, else a gesture finger, else the first contact. */
    private fun primaryContact(classified: ClassifiedFrame): com.premiumnotes.input.ClassifiedContact? {
        val id = classified.activeWritingPointerId
            ?: classified.gesturePointerIds.firstOrNull()
            ?: classified.contacts.firstOrNull()?.contact?.pointerId
        return id?.let { classified.contactFor(it) }
    }

    private fun handleSelection(input: InputFrame, classified: ClassifiedFrame) {
        val contact = primaryContact(classified) ?: return
        val wx = screenToWorldX(contact.contact.x)
        val wy = screenToWorldY(contact.contact.y)

        when (input.action) {
            com.premiumnotes.input.InputAction.DOWN,
            com.premiumnotes.input.InputAction.POINTER_DOWN,
            -> {
                val inside = selectionBoundsMm?.contains(wx, wy) == true
                if (inside) {
                    selectionMode = SelectionMode.MOVE
                    dragAnchorWorld = Point(wx, wy)
                    listener?.onSelectionDragStart(wx, wy)
                } else {
                    selectionMode = SelectionMode.LASSO
                    lassoStartWorld = Point(wx, wy)
                    lassoCurrentWorld = Point(wx, wy)
                    invalidate()
                }
            }

            com.premiumnotes.input.InputAction.MOVE -> {
                when (selectionMode) {
                    SelectionMode.MOVE -> listener?.onSelectionDragTo(wx, wy)
                    SelectionMode.LASSO -> {
                        lassoCurrentWorld = Point(wx, wy)
                        invalidate()
                    }
                    SelectionMode.NONE -> Unit
                }
            }

            com.premiumnotes.input.InputAction.UP,
            com.premiumnotes.input.InputAction.POINTER_UP,
            -> {
                when (selectionMode) {
                    SelectionMode.MOVE -> listener?.onSelectionDragEnd()
                    SelectionMode.LASSO -> {
                        val left = kotlin.math.min(lassoStartWorld.x, lassoCurrentWorld.x)
                        val top = kotlin.math.min(lassoStartWorld.y, lassoCurrentWorld.y)
                        val right = kotlin.math.max(lassoStartWorld.x, lassoCurrentWorld.x)
                        val bottom = kotlin.math.max(lassoStartWorld.y, lassoCurrentWorld.y)
                        listener?.onSelectInRect(RectF(left, top, right, bottom))
                    }
                    SelectionMode.NONE -> Unit
                }
                selectionMode = SelectionMode.NONE
                invalidate()
            }

            com.premiumnotes.input.InputAction.CANCEL -> {
                selectionMode = SelectionMode.NONE
                invalidate()
            }
        }
    }

    private fun worldRectToScreen(rect: RectF): RectF =
        RectF(
            rect.left * scale + offsetX,
            rect.top * scale + offsetY,
            rect.right * scale + offsetX,
            rect.bottom * scale + offsetY,
        )

    private var lastEraserPoint: Point? = null

    /** Pointer driving the eraser for the current gesture, tracked so classification
     *  flicker (WRITING vs FINGER) doesn't drop or swap the eraser mid-stroke. */
    private var eraserPointerId: Int = -1

    private fun eraserContact(classified: ClassifiedFrame): com.premiumnotes.input.ClassifiedContact? {
        var id = eraserPointerId
        if (id == -1) {
            id = classified.activeWritingPointerId
                ?: classified.contacts.firstOrNull {
                    it.classification == com.premiumnotes.input.ContactClassification.WRITING ||
                        it.classification == com.premiumnotes.input.ContactClassification.ERASER ||
                        it.classification == com.premiumnotes.input.ContactClassification.FINGER
                }?.contact?.pointerId
                ?: -1
        }
        if (id == -1) return null
        val contact = classified.contactFor(id)
        if (contact == null ||
            contact.classification == com.premiumnotes.input.ContactClassification.PALM ||
            contact.classification == com.premiumnotes.input.ContactClassification.REJECTED
        ) {
            eraserPointerId = -1
            return null
        }
        eraserPointerId = id
        return contact
    }

    private fun handleEraser(input: InputFrame, classified: ClassifiedFrame) {
        when (input.action) {
            com.premiumnotes.input.InputAction.DOWN,
            com.premiumnotes.input.InputAction.POINTER_DOWN,
            -> {
                listener?.onEraseGestureBegin()
                val contact = eraserContact(classified) ?: return
                val worldX = screenToWorldX(contact.contact.x)
                val worldY = screenToWorldY(contact.contact.y)
                val radius = eraserSizeMm / 2f
                listener?.onEraseAt(worldX, worldY, radius)
                lastEraserPoint = Point(worldX, worldY)
            }
            com.premiumnotes.input.InputAction.MOVE -> {
                val contact = eraserContact(classified) ?: return
                val worldX = screenToWorldX(contact.contact.x)
                val worldY = screenToWorldY(contact.contact.y)
                val radius = eraserSizeMm / 2f
                val prev = lastEraserPoint
                if (prev == null) {
                    listener?.onEraseAt(worldX, worldY, radius)
                } else {
                    listener?.onEraseAlong(prev.x, prev.y, worldX, worldY, radius)
                }
                lastEraserPoint = Point(worldX, worldY)
            }
            com.premiumnotes.input.InputAction.UP,
            com.premiumnotes.input.InputAction.POINTER_UP,
            com.premiumnotes.input.InputAction.CANCEL,
            -> {
                lastEraserPoint = null
                eraserPointerId = -1
                listener?.onEraseGestureEnd()
            }
        }
    }

    // --- navigation / gestures ---

    private fun handleNavigation(input: InputFrame, classified: ClassifiedFrame) {
        val gestures = classified.gesturePointerIds
        if (gestures.size < 2) {
            gesture = null
            return
        }

        val p1 = classified.contactFor(gestures[0]) ?: return
        val p2 = classified.contactFor(gestures[1]) ?: return
        val cx = (p1.contact.x + p2.contact.x) / 2f
        val cy = (p1.contact.y + p2.contact.y) / 2f
        val dist = hypot(p1.contact.x - p2.contact.x, p1.contact.y - p2.contact.y).coerceAtLeast(1f)

        when (input.action) {
            com.premiumnotes.input.InputAction.DOWN,
            com.premiumnotes.input.InputAction.POINTER_DOWN,
            -> {
                // A second contact joined while drawing: commit the stroke so the gesture
                // doesn't lose it, then begin panning/zooming.
                finalizeActiveStroke()
                gesture = GestureStart(cx, cy, dist, zoom, offsetX, offsetY)
            }
            com.premiumnotes.input.InputAction.MOVE -> {
                val start = gesture ?: return
                // Anchor the world point under the gesture centroid to the new centroid.
                val anchorWorldX = (start.centroidX - start.offsetX) / (capabilities.pxPerMm * start.zoom)
                val anchorWorldY = (start.centroidY - start.offsetY) / (capabilities.pxPerMm * start.zoom)
                val newZoom = (start.zoom * dist / start.dist).coerceIn(0.3f, 8f)
                zoom = newZoom
                offsetX = cx - anchorWorldX * scale
                offsetY = cy - anchorWorldY * scale
                listener?.onViewportChanged(zoom, offsetX, offsetY)
                // The cached committed layer is transformed in onDraw (no full rebuild per
                // frame); it is re-rendered once the transform leaves the crisp deadband.
                invalidate()
            }
            com.premiumnotes.input.InputAction.UP,
            com.premiumnotes.input.InputAction.POINTER_UP,
            -> {
                gesture = null
                invalidate()
            }
            else -> Unit
        }
    }

    // --- rendering ---

    /** Builds cached render geometry for one committed stroke (world units). */
    private fun buildStrokeGeometry(stroke: Stroke): CachedStroke {
        val rp = renderer.buildRenderPath(stroke)
        val paint = if (rp.fill) {
            Paint(renderer.paintFor(stroke.style)).apply { style = Paint.Style.FILL }
        } else {
            renderer.paintFor(stroke.style).apply {
                strokeWidth = stroke.style.widthMm.coerceAtLeast(0.2f)
            }
        }
        val pencil = stroke.style.type == com.premiumnotes.model.PenType.PENCIL
        val grainAlpha = if (pencil) (paint.alpha * 0.5f).toInt() else 0
        val seed = (stroke.id * 7919L).toInt()
        val grainDx = 0.06f + (seed and 0x1F) * 0.002f
        return CachedStroke(
            type = stroke.style.type,
            path = rp.path,
            paint = paint,
            pencil = pencil,
            grainAlpha = grainAlpha,
            grainDx = grainDx,
            grainDy = grainDx * 0.5f,
        )
    }

    private fun appendStrokeGeometry(stroke: Stroke) {
        displayStrokes = displayStrokes + buildStrokeGeometry(stroke)
    }

    private fun rebuildStrokeGeometry() {
        val items = ArrayList<CachedStroke>(strokes.size)
        for (stroke in strokes) items += buildStrokeGeometry(stroke)
        displayStrokes = items
    }

    private fun buildShapeGeometry(shape: ShapeObject): CachedShape =
        CachedShape(ShapeRenderer.buildPath(shape), ShapeRenderer.outlinePaint(shape))

    private fun appendShapeGeometry(shape: ShapeObject) {
        displayShapes = displayShapes + buildShapeGeometry(shape)
    }

    private fun rebuildShapeGeometry() {
        val items = ArrayList<CachedShape>(shapes.size)
        for (shape in shapes) items += buildShapeGeometry(shape)
        displayShapes = items
    }

    private fun drawCommittedStroke(canvas: Canvas, item: CachedStroke) {
        if (item.pencil) {
            val grain = Paint(item.paint).apply { alpha = item.grainAlpha }
            canvas.drawPath(item.path, grain)
            canvas.save()
            canvas.translate(item.grainDx, item.grainDy)
            canvas.drawPath(item.path, grain)
            canvas.restore()
        }
        canvas.drawPath(item.path, item.paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!::capabilities.isInitialized) return
        val w = width.toFloat()
        val h = height.toFloat()

        // World space: background + committed content + live strokes.
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        PageBackgroundRenderer.drawBackground(
            canvas,
            background,
            pxPerMm = 1f,
            worldClip = worldClipRect.also {
                it.set(
                    -offsetX / scale, -offsetY / scale,
                    (w - offsetX) / scale, (h - offsetY) / scale,
                )
            },
        )

        // Committed content is drawn every frame from the cached display list, so the
        // whole page is always present at its world position — no bitmap layer to go
        // stale while scrolling (strokes never "reload" or pop in).
        // Z-order: highlighters < shapes < ink (matches paper behavior).
        for (item in displayStrokes) {
            if (item.type == com.premiumnotes.model.PenType.HIGHLIGHTER) drawCommittedStroke(canvas, item)
        }
        for (item in displayShapes) canvas.drawPath(item.path, item.paint)
        for (item in displayStrokes) {
            if (item.type != com.premiumnotes.model.PenType.HIGHLIGHTER) drawCommittedStroke(canvas, item)
        }

        // Live shape preview while dragging.
        val shapeStart = shapeStartWorld
        val shapeCurrent = shapeCurrentWorld
        if (shapeStart != null && shapeCurrent != null) {
            val preview = ShapeObject(
                id = -1L,
                kind = shapeKind,
                points = listOf(shapeStart, shapeCurrent),
                x = shapeStart.x,
                y = shapeStart.y,
                strokeWidthMm = penStyle.widthMm,
                colorArgb = penStyle.colorArgb,
            )
            canvas.drawPath(ShapeRenderer.buildPath(preview), ShapeRenderer.outlinePaint(preview))
        }

        // Active stroke: rendered through the same renderer as committed strokes so the
        // live stroke matches the final one exactly (width, fountain/calligraphy profile,
        // pencil grain) — no thickness or appearance change on commit.
        val builder = strokeBuilder
        if (builder != null && builder.livePoints.size > 1) {
            val pts = builder.livePoints
            val live = Stroke(id = 0L, style = penStyle, pointsPacked = Stroke.pack(pts))
            renderer.drawStroke(canvas, live, 1f)
        }
        canvas.restore()

        // Screen-space selection overlays.
        selectionBoundsMm?.let { bounds ->
            canvas.drawRect(worldRectToScreen(bounds), selectionPaint)
        }
        if (selectionMode == SelectionMode.LASSO) {
            val lasso = lassoScreenRect.also {
                val lx = kotlin.math.min(lassoStartWorld.x, lassoCurrentWorld.x)
                val ly = kotlin.math.min(lassoStartWorld.y, lassoCurrentWorld.y)
                val rx = kotlin.math.max(lassoStartWorld.x, lassoCurrentWorld.x)
                val ry = kotlin.math.max(lassoStartWorld.y, lassoCurrentWorld.y)
                it.set(lx * scale + offsetX, ly * scale + offsetY, rx * scale + offsetX, ry * scale + offsetY)
            }
            canvas.drawRect(lasso, lassoFillPaint)
            canvas.drawRect(lasso, lassoStrokePaint)
        }
    }
}