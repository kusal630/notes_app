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
import com.premiumnotes.input.PalmZone
import com.premiumnotes.input.PalmZoneRect
import com.premiumnotes.input.ToolKind
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
        fun onSelectionResizeStart(handleIndex: Int)
        fun onSelectionResizeTo(worldX: Float, worldY: Float)
        fun onSelectionResizeEnd()
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
        set(value) {
            if (field != value) {
                // Switching tools mid-stroke must commit the in-progress stroke rather
                // than silently dropping it.
                finalizeActiveStroke()
                field = value
            }
        }
    var eraserSizeMm: Float = 6f
    var shapeKind: ShapeKind = ShapeKind.RECT

    /**
     * When on (settings toggle, default off), a tight scribble over the page erases the
     * current gesture instead of writing. Never interferes with which touches are accepted
     * — it only re-routes the current gesture after palm rejection already approved it.
     */
    var autoEraseEnabled: Boolean = false

    private val writeEraseDetector = com.premiumnotes.input.WriteEraseDetector()

    /** While true, the current gesture is being treated as erase even though the user
     *  is on the pen tool (Feature 1 auto-detection fired mid-gesture). */
    private var gestureEraseOverride = false

    // --- palm rest zone (user-reserved region where the palm is always accepted) ---
    var palmZone: PalmZone = PalmZone()
        set(value) {
            if (field != value) {
                field = value
                if (!value.enabled) zoneDragging = false
                // Re-sync the engine immediately so the zone takes effect even before
                // the first touch event arrives.
                syncPalmZoneRect()
                invalidate()
            }
        }

    /** Called when the user drags the palm-zone grip so the position can be persisted. */
    var onPalmZoneChanged: ((PalmZone) -> Unit)? = null

    private var zoneDragging = false
    private var zoneDragPointerId = -1
    private var lastZoneRect: PalmZoneRect? = null

    // --- scroll bar (visible page scroller on the right edge) ---
    var scrollBarVisible: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    private var scrollDragging = false
    private var scrollDragPointerId = -1
    private val scrollBarWidthPx = 18f

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
    private enum class SelectionMode { NONE, LASSO, MOVE, RESIZE }
    private var selectionMode = SelectionMode.NONE
    private var resizeHandleIndex = -1
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
    private val selectionHandlePaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = true
    }
    private val selectionHandleOutlinePaint = Paint().apply {
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
        val points: FloatArray,
    )

    private data class CachedShape(
        val path: Path,
        val paint: Paint,
        val corner0: Point,
        val corner1: Point,
    )

    private var displayStrokes: List<CachedStroke> = emptyList()
    private var displayShapes: List<CachedShape> = emptyList()
    private val displayStrokeById = HashMap<Long, CachedStroke>()
    private val displayShapeById = HashMap<Long, CachedShape>()
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

        // The scroll bar and the palm-zone grip are direct-manipulation surfaces that
        // must never feed the palm rejection / writing pipeline.
        if (handleScrollBarTouch(input)) return true
        if (handleZoneGripTouch(input)) return true

        // Keep the engine's zone in sync with this frame before it classifies anything.
        syncPalmZoneRect()

        val classified = engine.process(input)

        // A new gesture always starts clean: clear any erase-override from a previous
        // gesture and reset the write/erase detector.
        if (input.action == com.premiumnotes.input.InputAction.DOWN ||
            input.action == com.premiumnotes.input.InputAction.POINTER_DOWN
        ) {
            gestureEraseOverride = false
        }

        // Two-finger gestures take priority over the active tool so the page can be
        // panned/zoomed while a pen is selected — otherwise the user gets stuck at the
        // bottom of a scrolled page with no way back up. This also covers the moment a
        // second contact lands while a stroke is in progress (handleNavigation finalizes it).
        if (classified.gesturePointerIds.size >= 2) {
            handleNavigation(input, classified)
            return true
        }

        when {
            gestureEraseOverride -> handleEraser(input, classified)
            tool == Tool.ERASER -> handleEraser(input, classified)
            tool == Tool.PEN || tool == Tool.HIGHLIGHTER -> handleStroke(input, classified)
            tool == Tool.SELECT -> handleSelection(input, classified)
            // A just-drawn shape is auto-selected; touching its handles or inside its
            // bounds moves/resizes it even while the SHAPES tool is still selected, so the
            // user can adjust the shape right after drawing it.
            tool == Tool.SHAPES && selectionTouchTarget(input, classified) -> handleSelection(input, classified)
            tool == Tool.SHAPES -> handleShapes(input, classified)
            else -> handleNavigation(input, classified)
        }
        if (input.action == com.premiumnotes.input.InputAction.UP ||
            input.action == com.premiumnotes.input.InputAction.POINTER_UP ||
            input.action == com.premiumnotes.input.InputAction.CANCEL
        ) {
            gestureEraseOverride = false
        }
        return true
    }

    // --- palm rest zone + scroll bar: geometry and direct manipulation ---

    /**
     * Recomputes the palm-zone rect and pushes it to the engine. Must be kept in sync
     * whenever the view is laid out, the zone settings change, or a frame is processed —
     * otherwise the reserved palm space is neither drawn nor active until a touch lands.
     */
    private fun syncPalmZoneRect() {
        if (!::capabilities.isInitialized || !::engine.isInitialized) return
        lastZoneRect = computePalmZoneRect()
        engine.setPalmZoneRect(lastZoneRect)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        syncPalmZoneRect()
        invalidate()
    }

    /**
     * Resolves the configured palm zone to screen pixels for the current frame.
     * - AUTO: purely automatic contact-size palm rejection — no reserved box is shown and
     *   no position-based rejection applies. A contact larger than a finger is the palm
     *   and does nothing; a writing pointer is never blocked by a phantom area. This is
     *   the simple, reliable behavior the user expects (a resting palm must never stop
     *   the pen from writing).
     * - MANUAL: fixed fractional position, drawn as a draggable box the user reserved.
     * Returns null when the zone is disabled.
     */
    private fun computePalmZoneRect(): PalmZoneRect? {
        if (!palmZone.enabled) return null
        if (palmZone.mode == com.premiumnotes.input.PalmZoneMode.AUTO) return null
        if (palmZone.mode != com.premiumnotes.input.PalmZoneMode.MANUAL) return null
        val w = width.toFloat()
        val h = height.toFloat()
        val zW = palmZone.widthMm * capabilities.pxPerMm
        val zH = palmZone.heightMm * capabilities.pxPerMm
        val cx = palmZone.centerXFrac * w
        val cy = palmZone.centerYFrac * h
        return PalmZoneRect(cx - zW / 2f, cy - zH / 2f, cx + zW / 2f, cy + zH / 2f)
    }

    /** The grip handle the user grabs to reposition the zone (its top-center). */
    private fun zoneGripCenter(): com.premiumnotes.model.Point? {
        val rect = lastZoneRect ?: return null
        return com.premiumnotes.model.Point(rect.centerX(), rect.topPx)
    }

    private fun handleZoneGripTouch(input: InputFrame): Boolean {
        when (input.action) {
            com.premiumnotes.input.InputAction.DOWN,
            com.premiumnotes.input.InputAction.POINTER_DOWN,
            -> {
                if (palmZone.enabled) {
                    val grip = zoneGripCenter()
                    val added = input.addedPointerId?.let { id ->
                        input.contacts.firstOrNull { it.pointerId == id }
                    }
                    // The grip is grabbed with a finger, never with the pen — a pen DOWN
                    // near the grip must start a stroke, not move the zone.
                    val addedKind = added?.let { InputCapabilities.toolKindFromRaw(it.toolTypeRaw) }
                    if (grip != null && added != null &&
                        (addedKind == ToolKind.FINGER || addedKind == ToolKind.UNKNOWN) &&
                        hypot(added.x - grip.x, added.y - grip.y) <= 36f
                    ) {
                        zoneDragging = true
                        zoneDragPointerId = added.pointerId
                        // Grabbing the grip converts the zone to a fixed manual position.
                        if (palmZone.mode != com.premiumnotes.input.PalmZoneMode.MANUAL) {
                            val rect = lastZoneRect ?: computePalmZoneRect()
                            if (rect != null) {
                                setPalmZonePos(rect.centerX() / width.toFloat(), rect.centerY() / height.toFloat())
                            }
                        }
                        return true
                    }
                }
            }
            com.premiumnotes.input.InputAction.MOVE -> {
                if (zoneDragging) {
                    val contact = input.contacts.firstOrNull { it.pointerId == zoneDragPointerId } ?: return true
                    val w = width.toFloat()
                    val h = height.toFloat()
                    if (w > 0f && h > 0f) {
                        setPalmZonePos(contact.x / w, contact.y / h)
                    }
                    return true
                }
            }
            com.premiumnotes.input.InputAction.UP,
            com.premiumnotes.input.InputAction.POINTER_UP,
            -> {
                if (zoneDragging && input.liftedPointerId == zoneDragPointerId) {
                    zoneDragging = false
                    zoneDragPointerId = -1
                    onPalmZoneChanged?.invoke(palmZone)
                    return true
                }
            }
            com.premiumnotes.input.InputAction.CANCEL -> {
                if (zoneDragging) {
                    zoneDragging = false
                    zoneDragPointerId = -1
                    return true
                }
            }
            else -> Unit
        }
        return false
    }

    /** Updates the zone center (fractions) and switches it to manual positioning. */
    private fun setPalmZonePos(cxFrac: Float, cyFrac: Float) {
        palmZone = palmZone.movedTo(cxFrac, cyFrac)
        lastZoneRect = computePalmZoneRect()
        engine.setPalmZoneRect(lastZoneRect)
        invalidate()
    }

    private fun handleScrollBarTouch(input: InputFrame): Boolean {
        if (!scrollBarVisible && !scrollDragging) return false
        when (input.action) {
            com.premiumnotes.input.InputAction.DOWN,
            com.premiumnotes.input.InputAction.POINTER_DOWN,
            -> {
                val added = input.addedPointerId?.let { id ->
                    input.contacts.firstOrNull { it.pointerId == id }
                } ?: return false
                if (added.x >= width.toFloat() - scrollBarWidthPx) {
                    scrollDragging = true
                    scrollDragPointerId = added.pointerId
                    scrollToDragY(added.y)
                    return true
                }
            }
            com.premiumnotes.input.InputAction.MOVE -> {
                if (scrollDragging) {
                    val contact = input.contacts.firstOrNull { it.pointerId == scrollDragPointerId }
                    if (contact != null) scrollToDragY(contact.y)
                    return true
                }
            }
            com.premiumnotes.input.InputAction.UP,
            com.premiumnotes.input.InputAction.POINTER_UP,
            -> {
                if (scrollDragging && input.liftedPointerId == scrollDragPointerId) {
                    scrollDragging = false
                    scrollDragPointerId = -1
                    return true
                }
            }
            com.premiumnotes.input.InputAction.CANCEL -> {
                if (scrollDragging) {
                    scrollDragging = false
                    scrollDragPointerId = -1
                    return true
                }
            }
            else -> Unit
        }
        return false
    }

    /** Maps a drag Y on the scroll bar to a viewport offset and scrolls the page. */
    private fun scrollToDragY(yPx: Float) {
        val extentMm = contentExtentMm()
        val h = height.toFloat()
        if (h <= 0f || scale <= 0f) return
        val worldTop = (yPx / h) * extentMm
        offsetY = (worldTop * scale).coerceIn(0f, (extentMm * scale - h).coerceAtLeast(0f))
        listener?.onViewportChanged(zoom, offsetX, offsetY)
        invalidate()
    }

    /** Bottom edge of all page content in world mm (used to size the scroll bar). */
    private fun contentExtentMm(): Float {
        var maxY = 0f
        for (stroke in strokes) {
            val pts = stroke.pointsPacked
            var i = 1
            while (i < pts.size) {
                if (pts[i] > maxY) maxY = pts[i]
                i += 2
            }
        }
        for (shape in shapes) {
            for (p in shape.points) {
                if (p.y > maxY) maxY = p.y
            }
        }
        // A short/empty page still gets a scrollable extent so the bar behaves predictably.
        return (maxY + 80f).coerceAtLeast(500f)
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
                            // Commit to the display list immediately (before the model
                            // round-trip) so the stroke never vanishes between layers.
                            commitStrokeGeometry(stroke)
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
                // Reconcile the engine-owned lock against this view's stroke state. The
                // engine may drop or reassign the writing lock mid-gesture (e.g. a resting
                // palm that was falsely locked while alone, then a genuinely small contact
                // arrived and claimed the lock — or a two-finger gesture reset the lock via
                // the navigation path). If the engine no longer writes with the pointer this
                // view is drawing with, finalize the stale stroke so a fresh one can start.
                // Without this the canvas stays stuck: a phantom stroke holds the builder
                // slot and every new stroke is silently swallowed.
                if (strokeBuilder != null && classified.activeWritingPointerId != writingPointerId) {
                    finalizeActiveStroke()
                }
                val writingId = classified.activeWritingPointerId ?: return
                val contact = classified.contactFor(writingId) ?: return
                when (input.action) {
                    com.premiumnotes.input.InputAction.DOWN,
                    com.premiumnotes.input.InputAction.POINTER_DOWN,
                    -> {
                        if (strokeBuilder == null) {
                            val worldX = screenToWorldX(contact.contact.x)
                            val worldY = screenToWorldY(contact.contact.y)
                            if (autoEraseEnabled) {
                                writeEraseDetector.reset(hitTestInk(worldX, worldY))
                                writeEraseDetector.addSample(worldX, worldY, contact.contact.eventTimeNanos)
                            }
                            val builder = StrokeBuilder(
                                style = penStyle,
                                id = nextStrokeId(),
                            )
                            builder.onDown(worldX, worldY)
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
                            val hx = screenToWorldX(h.x)
                            val hy = screenToWorldY(h.y)
                            if (autoEraseEnabled) {
                                writeEraseDetector.addSample(hx, hy, h.eventTimeNanos)
                            }
                            if (builder.onMove(hx, hy, h.eventTimeNanos)) {
                                changed = true
                            }
                        }
                        if (autoEraseEnabled) {
                            writeEraseDetector.addSample(worldX, worldY, contact.contact.eventTimeNanos)
                        }
                        if (builder.onMove(worldX, worldY, contact.contact.eventTimeNanos)) {
                            changed = true
                        }
                        if (changed) {
                            invalidate()
                        }
                        // Feature 1: a deliberate tight scribble flips THIS gesture to
                        // erase. The partial stroke is committed (not lost), the erase
                        // batch opens, and the eraser takes over for the rest of the
                        // gesture (its sticky contact logic starts clean here).
                        if (autoEraseEnabled &&
                            writeEraseDetector.intent() == com.premiumnotes.input.WriteEraseDetector.Intent.ERASE
                        ) {
                            finalizeActiveStroke()
                            gestureEraseOverride = true
                            lastEraserPoint = Point(worldX, worldY)
                            listener?.onEraseGestureBegin()
                            listener?.onEraseAt(worldX, worldY, eraserSizeMm / 2f)
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
    fun finalizeActiveStroke() {
        val builder = strokeBuilder ?: return
        strokeBuilder = null
        writingPointerId = -1
        val last = builder.livePoints.lastOrNull()
        if (last == null) {
            invalidate()
            return
        }
        val stroke = builder.onUp(last.x, last.y, 0L)
        if (stroke != null) {
            commitStrokeGeometry(stroke)
            listener?.onStrokeCommitted(stroke)
        }
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
                    val shape = ShapeObject(
                        id = nextStrokeId(),
                        kind = shapeKind,
                        points = listOf(start, current),
                        x = start.x,
                        y = start.y,
                        strokeWidthMm = penStyle.widthMm,
                        colorArgb = penStyle.colorArgb,
                    )
                    // Synchronous commit to the display list so the shape appears on the
                    // next frame instead of after the model round-trip.
                    commitShapeGeometry(shape)
                    listener?.onShapeCommitted(shape)
                }
                invalidate()
            }
        }
    }

    // --- selection ---

    /** Best pointer for selection: locked pen, else a gesture finger, else the first
     *  non-palm contact. Never falls back to a palm/rejected contact as the driver. */
    private fun primaryContact(classified: ClassifiedFrame): com.premiumnotes.input.ClassifiedContact? {
        val id = classified.activeWritingPointerId
            ?: classified.gesturePointerIds.firstOrNull()
            ?: classified.contacts.firstOrNull {
                it.classification == com.premiumnotes.input.ContactClassification.WRITING ||
                    it.classification == com.premiumnotes.input.ContactClassification.FINGER ||
                    it.classification == com.premiumnotes.input.ContactClassification.ERASER
            }?.contact?.pointerId
            ?: return null
        return classified.contactFor(id)
    }

    /** True when a selection is active and [input]'s touch targets it: a resize handle or
     *  anywhere inside its bounds. Used to route the touch to selection handling even when
     *  a non-select tool is active, so a just-drawn shape can be moved/resized directly. */
    private fun selectionTouchTarget(input: InputFrame, classified: ClassifiedFrame): Boolean {
        val bounds = selectionBoundsMm ?: return false
        val contact = primaryContact(classified) ?: return false
        val wx = screenToWorldX(contact.contact.x)
        val wy = screenToWorldY(contact.contact.y)
        if (hitTestSelectionHandle(bounds, wx, wy) >= 0) return true
        return bounds.contains(wx, wy)
    }

    private fun handleSelection(input: InputFrame, classified: ClassifiedFrame) {
        val contact = primaryContact(classified) ?: return
        val wx = screenToWorldX(contact.contact.x)
        val wy = screenToWorldY(contact.contact.y)

        when (input.action) {
            com.premiumnotes.input.InputAction.DOWN,
            com.premiumnotes.input.InputAction.POINTER_DOWN,
            -> {
                // Feature 3: grabbing a handle resizes (corner = proportional, edge =
                // single-axis); grabbing inside the selection moves it; otherwise lasso.
                val handle = selectionBoundsMm?.let { hitTestSelectionHandle(it, wx, wy) } ?: -1
                if (handle >= 0) {
                    selectionMode = SelectionMode.RESIZE
                    resizeHandleIndex = handle
                    listener?.onSelectionResizeStart(handle)
                } else {
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
            }

            com.premiumnotes.input.InputAction.MOVE -> {
                when (selectionMode) {
                    SelectionMode.MOVE -> listener?.onSelectionDragTo(wx, wy)
                    SelectionMode.RESIZE -> listener?.onSelectionResizeTo(wx, wy)
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
                    SelectionMode.RESIZE -> {
                        listener?.onSelectionResizeEnd()
                        resizeHandleIndex = -1
                    }
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
                resizeHandleIndex = -1
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

    /** The eight resize handles of a selection bounds: corners 0-3, edges 4-7 (world mm). */
    private fun selectionHandleWorldPositions(bounds: RectF): Array<Point> {
        val l = bounds.left; val t = bounds.top
        val r = bounds.right; val b = bounds.bottom
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        return arrayOf(
            Point(l, t), Point(r, t), Point(r, b), Point(l, b),
            Point(cx, t), Point(r, cy), Point(cx, b), Point(l, cy),
        )
    }

    /** Index of the resize handle within [touchRadiusPx] of the tap, or -1. */
    private fun hitTestSelectionHandle(bounds: RectF, wx: Float, wy: Float): Int {
        val radiusPx = resources.displayMetrics.density * 22f
        val hx = wx * scale + offsetX
        val hy = wy * scale + offsetY
        val handles = selectionHandleWorldPositions(bounds)
        for (i in handles.indices) {
            val dx = handles[i].x * scale + offsetX - hx
            val dy = handles[i].y * scale + offsetY - hy
            if (dx * dx + dy * dy <= radiusPx * radiusPx) return i
        }
        return -1
    }

    /** Draws the eight resize handles without allocating during draw. */
    private fun drawSelectionHandles(canvas: Canvas, bounds: RectF) {
        val radius = resources.displayMetrics.density * 5f
        val l = bounds.left * scale + offsetX
        val t = bounds.top * scale + offsetY
        val r = bounds.right * scale + offsetX
        val b = bounds.bottom * scale + offsetY
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        drawSelectionHandle(canvas, l, t, radius)
        drawSelectionHandle(canvas, r, t, radius)
        drawSelectionHandle(canvas, r, b, radius)
        drawSelectionHandle(canvas, l, b, radius)
        drawSelectionHandle(canvas, cx, t, radius)
        drawSelectionHandle(canvas, r, cy, radius)
        drawSelectionHandle(canvas, cx, b, radius)
        drawSelectionHandle(canvas, l, cy, radius)
    }

    private fun drawSelectionHandle(canvas: Canvas, x: Float, y: Float, radius: Float) {
        canvas.drawCircle(x, y, radius, selectionHandlePaint)
        canvas.drawCircle(x, y, radius, selectionHandleOutlinePaint)
    }

    /**
     * Whether [wx],[wy] (world mm) lands on already-drawn content: within ~2 mm of a
     * committed stroke or inside a shape's bounds. Used by Feature 1 to decide whether a
     * gesture started on existing ink (which slightly lowers the scribble threshold).
     */
    private fun hitTestInk(wx: Float, wy: Float): Boolean {
        val touchR = 2f
        for (item in displayStrokes) {
            val pts = item.points
            var i = 0
            while (i + 3 < pts.size) {
                if (pointSegmentDistance(wx, wy, pts[i], pts[i + 1], pts[i + 2], pts[i + 3]) <= touchR) return true
                i += 2
            }
            if (pts.size >= 2 && hypot(pts[pts.size - 2] - wx, pts[pts.size - 1] - wy) <= touchR) return true
        }
        for (item in displayShapes) {
            val p0 = item.corner0
            val p1 = item.corner1
            val left = kotlin.math.min(p0.x, p1.x)
            val right = kotlin.math.max(p0.x, p1.x)
            val top = kotlin.math.min(p0.y, p1.y)
            val bottom = kotlin.math.max(p0.y, p1.y)
            if (wx in left..right && wy in top..bottom) return true
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
        // Once a pointer is picked for this erase gesture, stick with it until it
        // actually lifts. A momentary reclassification (borderline contact flickering
        // between WRITING and PALM) must not drop frames from a scrub; only a missing
        // contact ends the sticky tracking. This mirrors the writing-lock stickiness.
        if (contact == null) {
            eraserPointerId = -1
            return null
        }
        if (eraserPointerId == -1) {
            if (contact.classification == com.premiumnotes.input.ContactClassification.PALM ||
                contact.classification == com.premiumnotes.input.ContactClassification.REJECTED
            ) {
                return null
            }
            eraserPointerId = id
        }
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
            points = stroke.pointsPacked,
        )
    }

    private fun appendStrokeGeometry(stroke: Stroke) {
        // Idempotent: a stroke may already be in the display list because the canvas
        // committed it synchronously on pen-up (commitStrokeGeometry) before the
        // state round-trip delivered the updated list. Appending twice would double-draw
        // translucent highlighters.
        if (displayStrokeById.containsKey(stroke.id)) return
        val item = buildStrokeGeometry(stroke)
        displayStrokes = displayStrokes + item
        displayStrokeById[stroke.id] = item
    }

    private fun rebuildStrokeGeometry() {
        val items = ArrayList<CachedStroke>(strokes.size)
        displayStrokeById.clear()
        for (stroke in strokes) {
            val item = buildStrokeGeometry(stroke)
            items += item
            displayStrokeById[stroke.id] = item
        }
        displayStrokes = items
    }

    /**
     * Synchronously adds a just-committed stroke to the cached display list so it is
     * visible on the very next draw pass. Without this there is a window between the
     * live-stroke layer being cleared and the committed list arriving through the
     * Compose state flow in which the stroke renders as invisible — the "ink disappears
     * for a moment and re-renders" glitch.
     */
    private fun commitStrokeGeometry(stroke: Stroke) {
        appendStrokeGeometry(stroke)
    }

    private fun buildShapeGeometry(shape: ShapeObject): CachedShape {
        val c0 = shape.points.getOrNull(0) ?: Point(shape.x, shape.y)
        val c1 = shape.points.getOrNull(1) ?: Point(shape.x, shape.y)
        return CachedShape(ShapeRenderer.buildPath(shape), ShapeRenderer.outlinePaint(shape), c0, c1)
    }

    private fun appendShapeGeometry(shape: ShapeObject) {
        if (displayShapeById.containsKey(shape.id)) return
        val item = buildShapeGeometry(shape)
        displayShapes = displayShapes + item
        displayShapeById[shape.id] = item
    }

    private fun rebuildShapeGeometry() {
        val items = ArrayList<CachedShape>(shapes.size)
        displayShapeById.clear()
        for (shape in shapes) {
            val item = buildShapeGeometry(shape)
            items += item
            displayShapeById[shape.id] = item
        }
        displayShapes = items
    }

    /** Synchronous variant of [appendShapeGeometry] used at shape commit time. */
    private fun commitShapeGeometry(shape: ShapeObject) {
        appendShapeGeometry(shape)
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
            // Use the style captured at stroke start: the live stroke must always match
            // what gets committed on pen-up, even if the toolbar changed mid-stroke.
            val live = Stroke(id = 0L, style = builder.style, pointsPacked = Stroke.pack(pts))
            renderer.drawStroke(canvas, live, 1f)
        }
        canvas.restore()

        // Screen-space selection overlays.
        selectionBoundsMm?.let { bounds ->
            canvas.drawRect(worldRectToScreen(bounds), selectionPaint)
            // Feature 3: eight resize handles — corners (proportional) and edge midpoints
            // (single-axis). Drawn in screen space so they stay grabbable at any zoom.
            drawSelectionHandles(canvas, bounds)
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

        // Palm rest zone: only drawn in MANUAL mode — a translucent reserved region the
        // user placed and can drag by its grip handle. AUTO mode is purely automatic
        // (contact-size based) and deliberately shows no box.
        syncPalmZoneRect()
        lastZoneRect?.let { zone ->
            val zonePaint = Paint().apply {
                style = Paint.Style.FILL
                color = 0x1A2E5BFF.toInt()
            }
            val zoneStroke = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = 0x662E5BFF.toInt()
            }
            canvas.drawRect(zone.leftPx, zone.topPx, zone.rightPx, zone.bottomPx, zonePaint)
            canvas.drawRect(zone.leftPx, zone.topPx, zone.rightPx, zone.bottomPx, zoneStroke)
            canvas.drawText(
                "PALM REST",
                zone.leftPx + 8f,
                zone.topPx + 22f,
                Paint().apply {
                    textSize = 14f
                    color = 0x882E5BFF.toInt()
                }
            )
            // Grip handle.
            val gx = zone.centerX()
            val gy = zone.topPx
            canvas.drawCircle(gx, gy, 18f, Paint().apply {
                style = Paint.Style.FILL
                color = 0xFF2E5BFF.toInt()
            })
            canvas.drawCircle(gx, gy, 6f, Paint().apply {
                style = Paint.Style.FILL
                color = 0xFFFFFFFF.toInt()
            })
        }

        // Scroll bar: a thin track on the right edge with a thumb sized to the viewport.
        if (scrollBarVisible) {
            val extentMm = contentExtentMm()
            val barLeft = w - scrollBarWidthPx
            canvas.drawRoundRect(
                barLeft, 0f, w, h, 4f, 4f,
                Paint().apply { color = 0x14333333.toInt() },
            )
            val viewHeightMm = h / scale
            val topWorld = offsetY / scale
            val thumbH = (viewHeightMm / extentMm * h).coerceIn(24f, h)
            val thumbY = (topWorld / extentMm * h).coerceIn(0f, h - thumbH)
            canvas.drawRoundRect(
                barLeft + 2f, thumbY, w - 2f, thumbY + thumbH, 6f, 6f,
                Paint().apply { color = 0x662E5BFF.toInt() },
            )
        }
    }
}