package com.vellum.notes.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vellum.notes.editor.StrokeBuilder
import com.vellum.notes.editor.Tool
import com.vellum.notes.input.ClassifiedFrame
import com.vellum.notes.input.InputCapabilities
import com.vellum.notes.input.InputFrame
import com.vellum.notes.input.MotionEventParser
import com.vellum.notes.input.PalmRejectionEngine
import com.vellum.notes.input.PalmZone
import com.vellum.notes.input.PalmZoneRect
import com.vellum.notes.input.ToolKind
import com.vellum.notes.model.PageBackground
import com.vellum.notes.model.PenStyle
import com.vellum.notes.model.Point
import com.vellum.notes.model.ShapeKind
import com.vellum.notes.model.ShapeObject
import com.vellum.notes.model.Stroke
import com.vellum.notes.render.InkRenderer
import com.vellum.notes.render.PageBackgroundRenderer
import com.vellum.notes.render.ShapeRenderer
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
        /**
         * Strike-out word erase: the erase gesture ended; [minX]..[maxY] is the
         * gesture's world-space bounding box. The caller erases everything inside.
         */
        fun onScribbleWordErase(minX: Float, minY: Float, maxX: Float, maxY: Float, marginMm: Float) {}
        fun onViewportChanged(zoom: Float, offsetX: Float, offsetY: Float)
        fun onSelectInRect(rect: RectF)
        fun onSelectionDragStart(worldX: Float, worldY: Float)
        fun onSelectionDragTo(worldX: Float, worldY: Float)
        fun onSelectionDragEnd()
        fun onSelectionResizeStart(handleIndex: Int)
        fun onSelectionResizeTo(worldX: Float, worldY: Float)
        fun onSelectionResizeEnd()
        /**
         * Nebo-style gesture: double-tap with two fingers = undo.
         * Default no-op so existing listeners keep compiling.
         */
        fun onTwoFingerDoubleTap() {}
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

    /** Rasterized PDF page drawn beneath ink for PDF-backed pages (null = normal page). */
    var pdfBackground: android.graphics.Bitmap? = null
        set(value) {
            if (field !== value) {
                val old = field
                field = value
                old?.let { bmp -> post { if (!bmp.isRecycled) bmp.recycle() } }
                invalidate()
            }
        }

    /** Image objects on the current page (rendered between paper and ink, in z-order). */
    var images: List<com.vellum.notes.model.ImageObject> = emptyList()
        set(value) {
            field = value
            // Pre-sort once per assignment: onDraw used to sort every frame.
            sortedImages = value.sortedBy { it.zOrder }
            recomputeContentMaxY()
            invalidate()
        }

    /** Images pre-sorted by z-order (see [images]); the draw path iterates this. */
    private var sortedImages: List<com.vellum.notes.model.ImageObject> = emptyList()

    /** Committed text objects, drawn between images and ink. */
    var texts: List<com.vellum.notes.model.TextObject> = emptyList()
        set(value) {
            field = value
            rebuildTextLayoutCache()
            invalidate()
        }

    /**
     * Word-wrapped lines per text id, rebuilt when [texts] changes so onDraw never
     * splits/allocates per frame. Keyed by text id; ids are assigned by the editor
     * state when the object is created. Mutable so a rare cache miss in onDraw can
     * be stored instead of re-wrapped every frame.
     */
    private var textLinesCache: MutableMap<Long, List<String>> = HashMap()

    /** Scratch paint for text measuring (cache build) and drawing (onDraw). */
    private val scratchTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
    }

    /** Bounded typeface cache: one entry per (family, bold) pair actually on the page. */
    private val typefaceCache = HashMap<Pair<String, Boolean>, android.graphics.Typeface>()

    private fun typefaceFor(family: String, bold: Boolean): android.graphics.Typeface =
        typefaceCache.getOrPut(family to bold) {
            android.graphics.Typeface.create(
                family,
                if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
            )
        }

    /** Rebuilds [textLinesCache] for the current [texts] (runs on assignment, not per frame). */
    private fun rebuildTextLayoutCache() {
        if (texts.isEmpty()) {
            textLinesCache = HashMap()
            return
        }
        val cache = HashMap<Long, List<String>>(texts.size)
        for (t in texts) {
            if (t.text.isBlank()) continue
            scratchTextPaint.textSize = t.fontSizeMm
            scratchTextPaint.typeface = typefaceFor(t.fontFamily, t.bold)
            cache[t.id] = com.vellum.notes.render.TextLayout.wrap(
                t.text,
                { s -> scratchTextPaint.measureText(s) },
                t.width,
            )
        }
        textLinesCache = cache
    }

    /** Decoded bitmaps keyed by [com.vellum.notes.model.ImageObject.fileRef]. */
    var imageBitmaps: Map<String, android.graphics.Bitmap> = emptyMap()
        set(value) {
            if (field !== value) {
                val old = field
                field = value
                for ((key, bmp) in old) {
                    if (value[key] === bmp) continue
                    post { if (!bmp.isRecycled) bmp.recycle() }
                }
                invalidate()
            }
        }

    var penStyle: PenStyle = PenStyle()
        set(value) {
            field = value
            // A style change mid-shape-drag updates the live preview immediately.
            if (shapeStartWorld != null) refreshShapePreview()
        }
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
        set(value) {
            field = value
            if (shapeStartWorld != null) refreshShapePreview()
        }

    /**
     * When on (settings toggle, default off), a tight scribble over the page erases the
     * current gesture instead of writing. Never interferes with which touches are accepted
     * — it only re-routes the current gesture after palm rejection already approved it.
     */
    var autoEraseEnabled: Boolean = false

    /** When on, the canvas draws a live per-contact classification overlay (settings toggle). */
    var debugOverlayEnabled: Boolean = false

    private var writeEraseDetector = com.vellum.notes.input.WriteEraseDetector()
    private var detectorSensitivity: com.vellum.notes.input.ScribbleSensitivity? = null
    /** Current scribble sensitivity preset (set from settings each frame). */
    var scribbleSensitivity: com.vellum.notes.input.ScribbleSensitivity =
        com.vellum.notes.input.ScribbleSensitivity.BALANCED
    /** World-space points of the current erase gesture (scribble word-erase bbox). */
    private var eraseGesturePoints: ArrayList<Point>? = null

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
    /** Screen-space clip of the current draw (partial under surgical invalidation). */
    private val screenClipRect = android.graphics.Rect()
    /** Scratch rects for bitmap underlays: drawBitmap took fresh Rect/RectF per item/frame. */
    private val pdfSrcRect = android.graphics.Rect()
    private val pdfDstRect = RectF()
    private val imageSrcRect = android.graphics.Rect()
    private val imageDstRect = RectF()
    /** Scratch screen-space rect for the selection overlay (was a fresh RectF per frame). */
    private val selectionScreenRect = RectF()
    /** Reused live-stroke geometry/paint: the active stroke redraws every MOVE frame. */
    private val livePath = Path()
    private val livePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var livePaintStyle: PenStyle? = null
    /**
     * Cached bottom edge of page content in world mm (scroll-bar extent). Recomputed
     * when the content lists change and extended incrementally on commit, so onDraw
     * reads an O(1) field instead of scanning every stroke/shape/image per frame.
     */
    private var contentMaxYMm: Float = 0f

    // Contrast audit (paper is always light, even in dark theme): selection teal
    // #0B7A6F is 5.2:1 on white (>= 4.5:1); white handle fill is 16+:1 on the
    // teal outline. Lasso fill uses 20% alpha of the same audited teal so the
    // dashed region stays visible without obscuring ink.
    private val selectionPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF0B7A6F.toInt()
        isAntiAlias = true
    }
    private val lassoFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x330B7A6F.toInt()
    }
    private val lassoStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF0B7A6F.toInt()
        isAntiAlias = true
    }
    private val selectionHandlePaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = true
    }
    private val selectionHandleOutlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF0B7A6F.toInt()
        isAntiAlias = true
    }

    // --- classification debug overlay ---
    private val debugFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val debugLabelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 14f
        color = 0xFF000000.toInt()
    }

    // --- palm zone paints (fields: onDraw used to allocate these every frame) ---
    // Contrast audit on light paper: zone blue #2E5BFF is 5.2:1 on white;
    // label #1A46CC is 7.5:1; debug cluster #546E7A is 5.4:1 (all >= 4.5:1).
    // Strokes/thumbs are opaque (or near-opaque) so translucency never drops
    // the effective ratio below 4.5:1; fills stay translucent but are never
    // the sole indicator (each has an opaque stroke/label alongside).
    private val zoneFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x332E5BFF.toInt()
    }
    private val zoneStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF2E5BFF.toInt()
    }
    private val zoneLabelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 16f
        color = 0xFF1A46CC.toInt()
    }
    private val zoneGripPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFF2E5BFF.toInt()
        isAntiAlias = true
    }
    private val zoneGripInnerPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = true
    }
    private val scrollTrackPaint = Paint().apply {
        color = 0x40333333.toInt()
    }
    private val scrollThumbPaint = Paint().apply {
        color = 0xFF2E5BFF.toInt()
    }
    private val clusterBoundsPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF546E7A.toInt()
    }

    // --- active stroke ---
    private var strokeBuilder: StrokeBuilder? = null

    // --- committed content display list ---
    // Only geometry is cached (paths + paints); onDraw draws every committed stroke and
    // shape under the viewport transform, so nothing goes stale while the page scrolls.
    private data class CachedStroke(
        val type: com.vellum.notes.model.PenType,
        val path: Path,
        val paint: Paint,
        val pencil: Boolean,
        val grainAlpha: Int,
        val grainDx: Float,
        val grainDy: Float,
        val points: FloatArray,
        /** Precomputed world-mm bounds (padded); used for viewport culling. */
        val bounds: RectF,
        /** Prebuilt second-pass paint for pencil grain; null for non-pencil strokes. */
        val grainPaint: Paint?,
    )

    private data class CachedShape(
        val path: Path,
        val paint: Paint,
        val fillPaint: Paint?,
        val corner0: Point,
        val corner1: Point,
        /** Precomputed world-mm bounds (padded); used for viewport culling. */
        val bounds: RectF,
    )

    private var displayStrokes: MutableList<CachedStroke> = mutableListOf()
    private var displayShapes: MutableList<CachedShape> = mutableListOf()
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

    /** Nebo-style double-tap-with-two-fingers = undo detector (raw touch path). */
    private val twoFingerTapDetector = com.vellum.notes.editor.TwoFingerDoubleTapDetector()

    /** Most recent classified frame, kept for the debug overlay (drawn only when enabled). */
    private var lastClassified: ClassifiedFrame? = null

    private val scale: Float get() = capabilities.pxPerMm * zoom

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // TalkBack: the canvas is a single working surface; tools/options live
        // in the Compose toolbar so the canvas itself exposes one summary node.
        contentDescription = "Handwriting canvas. Draw with pen, erase, or use two fingers to pan and zoom."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun screenToWorldX(sx: Float) = (sx - offsetX) / scale
    fun screenToWorldY(sy: Float) = (sy - offsetY) / scale

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Nebo-style double-tap with two fingers = undo. Detected on the raw
        // touch path (before palm rejection) so it works regardless of how the
        // engine classifies the two contacts. Quick taps never move enough to
        // disturb the pan/zoom state; a real pan breaks the pending tap instead.
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2f
                    val cy = (event.getY(0) + event.getY(1)) / 2f
                    if (twoFingerTapDetector.onTwoFingerDown(event.eventTime, cx, cy)) {
                        finalizeActiveStroke()
                        gesture = null
                        twoFingerTapDetector.reset()
                        listener?.onTwoFingerDoubleTap()
                        return true
                    }
                } else if (event.pointerCount > 2) {
                    twoFingerTapDetector.reset()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) {
                    // pointerCount still includes the lifted pointer here.
                    val cx = (event.getX(0) + event.getX(1)) / 2f
                    val cy = (event.getY(0) + event.getY(1)) / 2f
                    twoFingerTapDetector.onTwoFingerUp(event.eventTime, cx, cy)
                }
            }
            MotionEvent.ACTION_CANCEL -> twoFingerTapDetector.reset()
            else -> Unit
        }
        if (detectorSensitivity != scribbleSensitivity) {
            detectorSensitivity = scribbleSensitivity
            val s = scribbleSensitivity
            writeEraseDetector = com.vellum.notes.input.WriteEraseDetector(
                minReversals = s.minReversals,
                minReversalsOnInk = s.minReversalsOnInk,
                scribbleBoxMm = s.scribbleBoxMm,
                maxDurationMs = s.maxDurationMs,
            )
        }
        val input = MotionEventParser.parse(event) ?: return true

        // The scroll bar and the palm-zone grip are direct-manipulation surfaces that
        // must never feed the palm rejection / writing pipeline.
        if (handleScrollBarTouch(input)) return true
        if (handleZoneGripTouch(input)) return true

        // Keep the engine's zone in sync with this frame before it classifies anything.
        syncPalmZoneRect()

        val classified = engine.process(input)
        lastClassified = classified

        // A new gesture always starts clean: clear any erase-override from a previous
        // gesture and reset the write/erase detector. Also claim the touch stream so
        // no ancestor (edge-to-edge insets, dialogs) can steal it mid-stroke — a
        // stolen stream arrives here as CANCEL and kills the live stroke.
        if (input.action == com.vellum.notes.input.InputAction.DOWN ||
            input.action == com.vellum.notes.input.InputAction.POINTER_DOWN
        ) {
            gestureEraseOverride = false
            parent?.requestDisallowInterceptTouchEvent(true)
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
        if (input.action == com.vellum.notes.input.InputAction.UP ||
            input.action == com.vellum.notes.input.InputAction.POINTER_UP ||
            input.action == com.vellum.notes.input.InputAction.CANCEL
        ) {
            gestureEraseOverride = false
            parent?.requestDisallowInterceptTouchEvent(false)
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
        engine.setViewportSize(width, height)
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
        if (palmZone.mode == com.vellum.notes.input.PalmZoneMode.AUTO) return null
        if (palmZone.mode != com.vellum.notes.input.PalmZoneMode.MANUAL) return null
        val w = width.toFloat()
        val h = height.toFloat()
        val zW = palmZone.widthMm * capabilities.pxPerMm
        val zH = palmZone.heightMm * capabilities.pxPerMm
        val cx = palmZone.centerXFrac * w
        val cy = palmZone.centerYFrac * h
        return PalmZoneRect(cx - zW / 2f, cy - zH / 2f, cx + zW / 2f, cy + zH / 2f)
    }

    /** The grip handle the user grabs to reposition the zone (its top-center). */
    private fun zoneGripCenter(): com.vellum.notes.model.Point? {
        val rect = lastZoneRect ?: return null
        return com.vellum.notes.model.Point(rect.centerX(), rect.topPx)
    }

    private fun handleZoneGripTouch(input: InputFrame): Boolean {
        when (input.action) {
            com.vellum.notes.input.InputAction.DOWN,
            com.vellum.notes.input.InputAction.POINTER_DOWN,
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
                        if (palmZone.mode != com.vellum.notes.input.PalmZoneMode.MANUAL) {
                            val rect = lastZoneRect ?: computePalmZoneRect()
                            if (rect != null) {
                                setPalmZonePos(rect.centerX() / width.toFloat(), rect.centerY() / height.toFloat())
                            }
                        }
                        return true
                    }
                }
            }
            com.vellum.notes.input.InputAction.MOVE -> {
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
            com.vellum.notes.input.InputAction.UP,
            com.vellum.notes.input.InputAction.POINTER_UP,
            -> {
                if (zoneDragging && input.liftedPointerId == zoneDragPointerId) {
                    zoneDragging = false
                    zoneDragPointerId = -1
                    onPalmZoneChanged?.invoke(palmZone)
                    return true
                }
            }
            com.vellum.notes.input.InputAction.CANCEL -> {
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
            com.vellum.notes.input.InputAction.DOWN,
            com.vellum.notes.input.InputAction.POINTER_DOWN,
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
            com.vellum.notes.input.InputAction.MOVE -> {
                if (scrollDragging) {
                    val contact = input.contacts.firstOrNull { it.pointerId == scrollDragPointerId }
                    if (contact != null) scrollToDragY(contact.y)
                    return true
                }
            }
            com.vellum.notes.input.InputAction.UP,
            com.vellum.notes.input.InputAction.POINTER_UP,
            -> {
                if (scrollDragging && input.liftedPointerId == scrollDragPointerId) {
                    scrollDragging = false
                    scrollDragPointerId = -1
                    return true
                }
            }
            com.vellum.notes.input.InputAction.CANCEL -> {
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
    private fun contentExtentMm(): Float =
        // O(1) read: [contentMaxYMm] is maintained on content change (see
        // [recomputeContentMaxY]); onDraw used to scan every point per frame.
        (contentMaxYMm + 80f).coerceAtLeast(500f)

    /**
     * Recomputes [contentMaxYMm] from the current content lists. Called when a list
     * is assigned or a full geometry rebuild runs (erase/undo/load); incremental
     * commits extend the cached max directly instead.
     */
    private fun recomputeContentMaxY() {
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
        for (im in images) {
            val b = im.y + im.height
            if (b > maxY) maxY = b
        }
        contentMaxYMm = maxY
    }

    // --- writing ---

    private var writingPointerId: Int = -1

    private fun handleStroke(input: InputFrame, classified: ClassifiedFrame) {
        when (input.action) {
            com.vellum.notes.input.InputAction.UP,
            com.vellum.notes.input.InputAction.POINTER_UP,
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
                } else if (strokeBuilder != null && classified.contactFor(writingPointerId) == null) {
                    // The UP belonged to a different pointer and the engine no longer
                    // holds this view's writer (lock dropped mid-gesture): finalize the
                    // stranded stroke now instead of leaving a phantom builder that
                    // swallows the next strokes.
                    finalizeActiveStroke()
                }
            }

            com.vellum.notes.input.InputAction.CANCEL -> {
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
                    com.vellum.notes.input.InputAction.DOWN,
                    com.vellum.notes.input.InputAction.POINTER_DOWN,
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

                    com.vellum.notes.input.InputAction.MOVE -> {
                        var builder = strokeBuilder
                        if (builder == null) {
                            // The writing lock was just established on this MOVE frame by the
                            // resting-hand tracker (a buffered CANDIDATE promoted to the
                            // writer). The stroke never saw a DOWN event, so seed it here from
                            // the pointer's recorded down position; otherwise the stroke would
                            // begin at the current point and lose the leading tail of the
                            // stroke it already traveled.
                            val startX = screenToWorldX(contact.contact.x)
                            val startY = screenToWorldY(contact.contact.y)
                            val downX = contact.downX
                            val downY = contact.downY
                            if (autoEraseEnabled) {
                                writeEraseDetector.reset(
                                    hitTestInk(
                                        if (downX != null && downY != null) screenToWorldX(downX) else startX,
                                        if (downX != null && downY != null) screenToWorldY(downY) else startY,
                                    )
                                )
                            }
                            val b = StrokeBuilder(style = penStyle, id = nextStrokeId())
                            if (downX != null && downY != null) {
                                b.onDown(screenToWorldX(downX), screenToWorldY(downY))
                            } else {
                                b.onDown(startX, startY)
                            }
                            strokeBuilder = b
                            writingPointerId = writingId
                            builder = b
                        }
                        // No auto-scroll: the page stays where the user put it (pan/zoom
                        // and the scroll bar move the viewport instead). The world point
                        // for THIS event is computed directly, so strokes stay continuous.
                        val worldX = screenToWorldX(contact.contact.x)
                        val worldY = screenToWorldY(contact.contact.y)
                        // Coalesced history samples (older first) carry the pointer motion
                        // the OS batched into this event; feeding them to the smoother keeps
                        // fast strokes continuous instead of dropping points.
                        //
                        // Surgical invalidation: only the newly added segment changed, so
                        // the dirty region covers the previous live point plus every fed
                        // sample (history + current). onDraw culls to the canvas clip, so
                        // everything outside this bbox is skipped. A viewport shift moves
                        // all content and needs a full invalidate instead.
                        val prevLive = builder.livePoints.lastOrNull()
                        var dirtyL = prevLive?.x ?: worldX
                        var dirtyT = prevLive?.y ?: worldY
                        var dirtyR = dirtyL
                        var dirtyB = dirtyT
                        fun extendDirty(x: Float, y: Float) {
                            if (x < dirtyL) dirtyL = x
                            if (y < dirtyT) dirtyT = y
                            if (x > dirtyR) dirtyR = x
                            if (y > dirtyB) dirtyB = y
                        }
                        var changed = false
                        for (h in input.history) {
                            if (h.pointerId != writingId) continue
                            val hx = screenToWorldX(h.x)
                            val hy = screenToWorldY(h.y)
                            extendDirty(hx, hy)
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
                        extendDirty(worldX, worldY)
                        if (changed) {
                            val pad = com.vellum.notes.render.DirtyRect.padForWidth(
                                builder.style.widthMm,
                            )
                            val dirty = com.vellum.notes.render.DirtyRect.segment(
                                dirtyL, dirtyT, dirtyR, dirtyB,
                                scale, offsetX, offsetY, pad,
                            )
                            invalidate(
                                (dirty.left - 2f).toInt(), (dirty.top - 2f).toInt(),
                                (dirty.right + 2f).toInt(), (dirty.bottom + 2f).toInt(),
                            )
                        }
                        // Feature 1: a deliberate tight scribble flips THIS gesture to
                        // erase. The partial stroke is committed (not lost), the erase
                        // batch opens, and the eraser takes over for the rest of the
                        // gesture (its sticky contact logic starts clean here).
                        if (autoEraseEnabled &&
                            writeEraseDetector.intent() == com.vellum.notes.input.WriteEraseDetector.Intent.ERASE
                        ) {
                            finalizeActiveStroke()
                            gestureEraseOverride = true
                            eraseGesturePoints = arrayListOf(Point(worldX, worldY))
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
    /**
     * Cached preview path for the in-progress shape drag, rebuilt on DOWN/MOVE so
     * onDraw never allocates a scratch [ShapeObject] per frame while dragging.
     */
    private var shapePreviewPath: Path? = null
    private var shapePreviewPaint: Paint? = null

    /** Rebuilds [shapePreviewPath] from the current drag endpoints (or clears it). */
    private fun refreshShapePreview() {
        val start = shapeStartWorld
        val current = shapeCurrentWorld
        if (start == null || current == null) {
            shapePreviewPath = null
            shapePreviewPaint = null
            return
        }
        val preview = ShapeObject(
            id = -1L,
            kind = shapeKind,
            points = listOf(start, current),
            x = start.x,
            y = start.y,
            strokeWidthMm = penStyle.widthMm,
            colorArgb = penStyle.colorArgb,
        )
        shapePreviewPath = ShapeRenderer.buildPath(preview)
        shapePreviewPaint = ShapeRenderer.outlinePaint(preview)
    }

    private fun handleShapes(input: InputFrame, classified: ClassifiedFrame) {
        val contact = primaryContact(classified) ?: return
        when (input.action) {
            com.vellum.notes.input.InputAction.DOWN,
            com.vellum.notes.input.InputAction.POINTER_DOWN,
            -> {
                shapeStartWorld = Point(screenToWorldX(contact.contact.x), screenToWorldY(contact.contact.y))
                shapeCurrentWorld = shapeStartWorld
                refreshShapePreview()
                invalidate()
            }

            com.vellum.notes.input.InputAction.MOVE -> {
                if (shapeStartWorld != null) {
                    shapeCurrentWorld = Point(screenToWorldX(contact.contact.x), screenToWorldY(contact.contact.y))
                    refreshShapePreview()
                    invalidate()
                }
            }

            com.vellum.notes.input.InputAction.UP,
            com.vellum.notes.input.InputAction.POINTER_UP,
            com.vellum.notes.input.InputAction.CANCEL,
            -> {
                val start = shapeStartWorld ?: run { shapeCurrentWorld = null; return }
                val current = shapeCurrentWorld ?: start
                shapeStartWorld = null
                shapeCurrentWorld = null
                refreshShapePreview()
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
    private fun primaryContact(classified: ClassifiedFrame): com.vellum.notes.input.ClassifiedContact? {
        val id = classified.activeWritingPointerId
            ?: classified.gesturePointerIds.firstOrNull()
            ?: classified.contacts.firstOrNull {
                it.classification == com.vellum.notes.input.ContactClassification.WRITING ||
                    it.classification == com.vellum.notes.input.ContactClassification.FINGER ||
                    it.classification == com.vellum.notes.input.ContactClassification.ERASER
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
            com.vellum.notes.input.InputAction.DOWN,
            com.vellum.notes.input.InputAction.POINTER_DOWN,
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

            com.vellum.notes.input.InputAction.MOVE -> {
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

            com.vellum.notes.input.InputAction.UP,
            com.vellum.notes.input.InputAction.POINTER_UP,
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

            com.vellum.notes.input.InputAction.CANCEL -> {
                selectionMode = SelectionMode.NONE
                resizeHandleIndex = -1
                invalidate()
            }
        }
    }

    /**
     * Converts a screen-px clip rect to the world-mm culling clip under the current
     * viewport transform. Visible for unit tests (same module); onDraw feeds it the
     * canvas clip so partial invalidates cull correctly.
     */
    internal fun worldClipForClip(clip: android.graphics.Rect, out: RectF): RectF {
        out.set(
            (clip.left - offsetX) / scale, (clip.top - offsetY) / scale,
            (clip.right - offsetX) / scale, (clip.bottom - offsetY) / scale,
        )
        return out
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

    private fun eraserContact(classified: ClassifiedFrame): com.vellum.notes.input.ClassifiedContact? {
        var id = eraserPointerId
        if (id == -1) {
            // A hardware eraser tool always wins over the writing lock holder: when the
            // eraser joins while a pen is still down, erasing must follow the eraser
            // pointer, not keep scrubbing with the pen position.
            id = classified.contacts.firstOrNull {
                it.classification == com.vellum.notes.input.ContactClassification.ERASER
            }?.contact?.pointerId
                ?: classified.activeWritingPointerId
                ?: classified.contacts.firstOrNull {
                    it.classification == com.vellum.notes.input.ContactClassification.WRITING ||
                        it.classification == com.vellum.notes.input.ContactClassification.FINGER
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
            if (contact.classification == com.vellum.notes.input.ContactClassification.PALM ||
                contact.classification == com.vellum.notes.input.ContactClassification.REJECTED
            ) {
                return null
            }
            eraserPointerId = id
        }
        return contact
    }

    private fun handleEraser(input: InputFrame, classified: ClassifiedFrame) {
        when (input.action) {
            com.vellum.notes.input.InputAction.DOWN,
            com.vellum.notes.input.InputAction.POINTER_DOWN,
            -> {
                listener?.onEraseGestureBegin()
                val contact = eraserContact(classified) ?: return
                val worldX = screenToWorldX(contact.contact.x)
                val worldY = screenToWorldY(contact.contact.y)
                val radius = eraserSizeMm / 2f
                eraseGesturePoints = arrayListOf(Point(worldX, worldY))
                listener?.onEraseAt(worldX, worldY, radius)
                lastEraserPoint = Point(worldX, worldY)
            }
            com.vellum.notes.input.InputAction.MOVE -> {
                val contact = eraserContact(classified) ?: return
                val worldX = screenToWorldX(contact.contact.x)
                val worldY = screenToWorldY(contact.contact.y)
                val radius = eraserSizeMm / 2f
                eraseGesturePoints?.add(Point(worldX, worldY))
                val prev = lastEraserPoint
                if (prev == null) {
                    listener?.onEraseAt(worldX, worldY, radius)
                } else {
                    listener?.onEraseAlong(prev.x, prev.y, worldX, worldY, radius)
                }
                lastEraserPoint = Point(worldX, worldY)
            }
            com.vellum.notes.input.InputAction.UP,
            com.vellum.notes.input.InputAction.POINTER_UP,
            com.vellum.notes.input.InputAction.CANCEL,
            -> {
                // Strike-out word erase: a scribble gesture erases the WHOLE word —
                // every object whose geometry intersects the gesture's bounding box —
                // not just the ink the nib radius physically touched.
                val path = eraseGesturePoints
                eraseGesturePoints = null
                if (path != null && path.size >= 2 && gestureEraseOverride) {
                    var minX = path[0].x; var maxX = path[0].x
                    var minY = path[0].y; var maxY = path[0].y
                    for (pt in path) {
                        if (pt.x < minX) minX = pt.x
                        if (pt.x > maxX) maxX = pt.x
                        if (pt.y < minY) minY = pt.y
                        if (pt.y > maxY) maxY = pt.y
                    }
                    listener?.onScribbleWordErase(minX, minY, maxX, maxY, eraserSizeMm / 2f)
                }
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
            com.vellum.notes.input.InputAction.DOWN,
            com.vellum.notes.input.InputAction.POINTER_DOWN,
            -> {
                // A second contact joined while drawing: commit the stroke so the gesture
                // doesn't lose it, then begin panning/zooming.
                finalizeActiveStroke()
                gesture = GestureStart(cx, cy, dist, zoom, offsetX, offsetY)
            }
            com.vellum.notes.input.InputAction.MOVE -> {
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
            com.vellum.notes.input.InputAction.UP,
            com.vellum.notes.input.InputAction.POINTER_UP,
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
        val pencil = stroke.style.type == com.vellum.notes.model.PenType.PENCIL
        val grainAlpha = if (pencil) (paint.alpha * 0.5f).toInt() else 0
        val seed = (stroke.id * 7919L).toInt()
        val grainDx = 0.06f + (seed and 0x1F) * 0.002f
        // Prebuilt second-pass paint for pencil grain: onDraw used to copy the paint
        // per pencil stroke on every frame.
        val grainPaint = if (pencil) Paint(paint).apply { alpha = grainAlpha } else null
        return CachedStroke(
            type = stroke.style.type,
            path = rp.path,
            paint = paint,
            pencil = pencil,
            grainAlpha = grainAlpha,
            grainDx = grainDx,
            grainDy = grainDx * 0.5f,
            points = stroke.pointsPacked,
            bounds = com.vellum.notes.render.StrokeCull.boundsOf(
                stroke.pointsPacked,
                com.vellum.notes.render.StrokeCull.padForWidth(stroke.style.widthMm),
            ),
            grainPaint = grainPaint,
        )
    }

    private fun appendStrokeGeometry(stroke: Stroke) {
        // Idempotent: a stroke may already be in the display list because the canvas
        // committed it synchronously on pen-up (commitStrokeGeometry) before the
        // state round-trip delivered the updated list. Appending twice would double-draw
        // translucent highlighters.
        if (displayStrokeById.containsKey(stroke.id)) return
        val item = buildStrokeGeometry(stroke)
        displayStrokes.add(item)
        displayStrokeById[stroke.id] = item
        if (item.bounds.bottom > contentMaxYMm) contentMaxYMm = item.bounds.bottom
    }

    private fun rebuildStrokeGeometry() {
        displayStrokeById.clear()
        displayStrokes.clear()
        for (stroke in strokes) {
            val item = buildStrokeGeometry(stroke)
            displayStrokes.add(item)
            displayStrokeById[stroke.id] = item
        }
        recomputeContentMaxY()
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
        val l = kotlin.math.min(c0.x, c1.x)
        val t = kotlin.math.min(c0.y, c1.y)
        val r = kotlin.math.max(c0.x, c1.x)
        val b = kotlin.math.max(c0.y, c1.y)
        val pad = com.vellum.notes.render.StrokeCull.padForWidth(shape.strokeWidthMm)
        return CachedShape(
            ShapeRenderer.buildPath(shape),
            ShapeRenderer.outlinePaint(shape),
            ShapeRenderer.fillPaint(shape),
            c0,
            c1,
            RectF(l - pad, t - pad, r + pad, b + pad),
        )
    }

    private fun appendShapeGeometry(shape: ShapeObject) {
        if (displayShapeById.containsKey(shape.id)) return
        val item = buildShapeGeometry(shape)
        displayShapes.add(item)
        displayShapeById[shape.id] = item
        if (item.bounds.bottom > contentMaxYMm) contentMaxYMm = item.bounds.bottom
    }

    private fun rebuildShapeGeometry() {
        displayShapeById.clear()
        displayShapes.clear()
        for (shape in shapes) {
            val item = buildShapeGeometry(shape)
            displayShapes.add(item)
            displayShapeById[shape.id] = item
        }
        recomputeContentMaxY()
    }

    /** Synchronous variant of [appendShapeGeometry] used at shape commit time. */
    private fun commitShapeGeometry(shape: ShapeObject) {
        appendShapeGeometry(shape)
    }

    private fun drawCommittedStroke(canvas: Canvas, item: CachedStroke) {
        if (item.pencil) {
            val grain = item.grainPaint ?: return
            canvas.drawPath(item.path, grain)
            canvas.save()
            canvas.translate(item.grainDx, item.grainDy)
            canvas.drawPath(item.path, grain)
            canvas.restore()
        }
        canvas.drawPath(item.path, item.paint)
    }

    /**
     * Draws the in-progress stroke without per-frame allocations for the common
     * plain-polyline pens. The path and paints are scratch fields rewound/updated
     * in place; variable-width (fountain/calligraphy) and pencil grain keep using
     * the shared renderer so the live stroke still matches the committed one.
     */
    private fun drawLiveStroke(canvas: Canvas, pts: List<Point>, style: PenStyle) {
        val type = style.type
        if (type == com.vellum.notes.model.PenType.FOUNTAIN ||
            type == com.vellum.notes.model.PenType.CALLIGRAPHY ||
            type == com.vellum.notes.model.PenType.PENCIL
        ) {
            val live = Stroke(id = 0L, style = style, pointsPacked = Stroke.pack(pts))
            renderer.drawStroke(canvas, live, 1f)
            return
        }
        if (livePaintStyle != style) {
            livePaintStyle = style
            val alpha = (style.opacity.coerceIn(0f, 1f) * 255).toInt()
            livePaint.color = (style.colorArgb and 0xFFFFFF).toInt() or (alpha shl 24)
            livePaint.strokeWidth = style.widthMm.coerceAtLeast(0.2f)
        }
        livePath.rewind()
        livePath.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            livePath.lineTo(pts[i].x, pts[i].y)
        }
        canvas.drawPath(livePath, livePaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!::capabilities.isInitialized) return
        val w = width.toFloat()
        val h = height.toFloat()

        // The culling clip follows the canvas clip, not the view size: under a
        // surgical (partial) invalidate the system clips to the dirty region, so
        // every cached layer outside it is skipped for free. A full invalidate
        // clips to the whole view, which reduces to the previous behavior.
        if (!canvas.getClipBounds(screenClipRect) || screenClipRect.isEmpty) return

        // World space: background + committed content + live strokes.
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        PageBackgroundRenderer.drawBackground(
            canvas,
            background,
            pxPerMm = 1f,
            worldClip = worldClipRect.also {
                worldClipForClip(screenClipRect, it)
            },
        )
        // World-space viewport clip, reused for culling every cached layer below.
        val worldClip = worldClipRect
        val cull = com.vellum.notes.render.StrokeCull

        // Committed content is drawn every frame from the cached display list, so the
        // whole page is always present at its world position — no bitmap layer to go
        // stale while scrolling (strokes never "reload" or pop in).
        // Z-order: paper < pdf page < images < highlighters < shapes < ink.
        pdfBackground?.let { bmp ->
            if (!bmp.isRecycled) {
                val (pdfW, pdfH) = com.vellum.notes.pdf.PdfImporter.worldSizeMm(bmp.width, bmp.height)
                // Cull the full-page underlay when the viewport shows none of it.
                if (worldClip.intersects(0f, 0f, pdfW, pdfH)) {
                    pdfSrcRect.set(0, 0, bmp.width, bmp.height)
                    pdfDstRect.set(0f, 0f, pdfW, pdfH)
                    canvas.drawBitmap(
                        bmp,
                        pdfSrcRect,
                        pdfDstRect,
                        null,
                    )
                }
            }
        }
        // Pre-sorted by z-order on assignment (see [sortedImages]); culled per rect.
        for (im in sortedImages) {
            if (im.x > worldClip.right || im.x + im.width < worldClip.left ||
                im.y > worldClip.bottom || im.y + im.height < worldClip.top
            ) {
                continue
            }
            val bmp = imageBitmaps[im.fileRef] ?: continue
            if (bmp.isRecycled) continue
            imageSrcRect.set(0, 0, bmp.width, bmp.height)
            imageDstRect.set(im.x, im.y, im.x + im.width, im.y + im.height)
            canvas.drawBitmap(
                bmp,
                imageSrcRect,
                imageDstRect,
                null,
            )
        }
        // Text objects: world-space boxes drawn between images and ink so ink and
        // highlights stay on top, matching the documented z-order. Lines are wrapped
        // once per assignment (see [textLinesCache]); a scratch paint avoids per-frame
        // allocation. Rotated boxes skip culling (axis-aligned test would be wrong).
        for (t in texts) {
            if (t.text.isBlank()) continue
            if (t.rotation == 0f &&
                (t.x > worldClip.right || t.x + t.width < worldClip.left ||
                    t.y > worldClip.bottom || t.y + t.height < worldClip.top)
            ) {
                continue
            }
            val textPaint = scratchTextPaint
            textPaint.color = t.colorArgb.toInt()
            textPaint.textSize = t.fontSizeMm
            textPaint.typeface = typefaceFor(t.fontFamily, t.bold)
            // Simple word-wrap into the box width; lines flow downward from the top edge.
            // A rare cache miss (id not seen at assignment) is stored so it is
            // wrapped once, not on every frame.
            var lines = textLinesCache[t.id]
            if (lines == null) {
                lines = com.vellum.notes.render.TextLayout.wrap(
                    t.text,
                    { s -> textPaint.measureText(s) },
                    t.width,
                )
                textLinesCache[t.id] = lines
            }
            if (t.rotation == 0f) {
                var y = t.y + t.fontSizeMm
                for (line in lines) {
                    canvas.drawText(line, t.x, y, textPaint)
                    y += t.fontSizeMm * 1.35f
                }
            } else {
                canvas.save()
                canvas.rotate(t.rotation, t.x, t.y)
                var y = t.y + t.fontSizeMm
                for (line in lines) {
                    canvas.drawText(line, t.x, y, textPaint)
                    y += t.fontSizeMm * 1.35f
                }
                canvas.restore()
            }
        }
        for (item in displayStrokes) {
            if (item.type == com.vellum.notes.model.PenType.HIGHLIGHTER &&
                cull.isVisible(item.bounds, worldClip)
            ) {
                drawCommittedStroke(canvas, item)
            }
        }
        for (item in displayShapes) {
            if (!cull.isVisible(item.bounds, worldClip)) continue
            item.fillPaint?.let { canvas.drawPath(item.path, it) }
            canvas.drawPath(item.path, item.paint)
        }
        for (item in displayStrokes) {
            if (item.type != com.vellum.notes.model.PenType.HIGHLIGHTER &&
                cull.isVisible(item.bounds, worldClip)
            ) {
                drawCommittedStroke(canvas, item)
            }
        }

        // Live shape preview while dragging (path cached on DOWN/MOVE, not per frame).
        val previewPath = shapePreviewPath
        val previewPaint = shapePreviewPaint
        if (previewPath != null && previewPaint != null) {
            canvas.drawPath(previewPath, previewPaint)
        }

        // Active stroke: rendered through the same renderer as committed strokes so the
        // live stroke matches the final one exactly (width, fountain/calligraphy profile,
        // pencil grain) — no thickness or appearance change on commit.
        val builder = strokeBuilder
        if (builder != null && builder.livePoints.size > 1) {
            val pts = builder.livePoints
            // Use the style captured at stroke start: the live stroke must always match
            // what gets committed on pen-up, even if the toolbar changed mid-stroke.
            drawLiveStroke(canvas, pts, builder.style)
        }
        canvas.restore()

        // Screen-space selection overlays.
        selectionBoundsMm?.let { bounds ->
            selectionScreenRect.set(
                bounds.left * scale + offsetX,
                bounds.top * scale + offsetY,
                bounds.right * scale + offsetX,
                bounds.bottom * scale + offsetY,
            )
            canvas.drawRect(selectionScreenRect, selectionPaint)
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
        // (contact-size based) and deliberately shows no box. Uses the last synced
        // rect: the engine/zone state is already synced on size change, zone edits,
        // and every touch frame — re-syncing (and allocating) here every draw is
        // unnecessary on the hot path.
        lastZoneRect?.let { zone ->
            canvas.drawRect(zone.leftPx, zone.topPx, zone.rightPx, zone.bottomPx, zoneFillPaint)
            canvas.drawRect(zone.leftPx, zone.topPx, zone.rightPx, zone.bottomPx, zoneStrokePaint)
            canvas.drawText(
                "PALM REST",
                zone.leftPx + 8f,
                zone.topPx + 22f,
                zoneLabelPaint,
            )
            // Grip handle.
            val gx = zone.centerX()
            val gy = zone.topPx
            canvas.drawCircle(gx, gy, 18f, zoneGripPaint)
            canvas.drawCircle(gx, gy, 6f, zoneGripInnerPaint)
        }

        // Scroll bar: a thin track on the right edge with a thumb sized to the viewport.
        if (scrollBarVisible) {
            val extentMm = contentExtentMm()
            val barLeft = w - scrollBarWidthPx
            canvas.drawRoundRect(
                barLeft, 0f, w, h, 4f, 4f,
                scrollTrackPaint,
            )
            val viewHeightMm = h / scale
            val topWorld = offsetY / scale
            val thumbH = (viewHeightMm / extentMm * h).coerceIn(24f, h)
            val thumbY = (topWorld / extentMm * h).coerceIn(0f, h - thumbH)
            canvas.drawRoundRect(
                barLeft + 2f, thumbY, w - 2f, thumbY + thumbH, 6f, 6f,
                scrollThumbPaint,
            )
        }

        if (debugOverlayEnabled) {
            drawDebugOverlay(canvas)
        }
    }

    /**
     * Live classification overlay: one filled circle per active contact colored by its
     * current classification plus a label with the pointer id, classification and
     * confidence. Drawn in screen space on top of everything so resting-hand behavior can
     * be verified on-device (WRITING blue, STYLUS blue, CANDIDATE amber, RESTING gray,
     * PALM red, FINGER green, ERASER purple, REJECTED gray).
     */
    private fun drawDebugOverlay(canvas: Canvas) {
        val frame = lastClassified ?: return
        for (cc in frame.contacts) {
            val c = cc.contact
            // Circle fills use contrast-audited hues (>= 4.5:1 on white paper):
            // REJECTED #616161 (7.0:1), CANDIDATE #9C6D00 (5.0:1),
            // RESTING #546E7A (5.4:1), others already pass as opaque fills.
            val color = when (cc.classification) {
                com.vellum.notes.input.ContactClassification.WRITING -> 0xFF2E5BFF.toInt()
                com.vellum.notes.input.ContactClassification.FINGER -> 0xFF007A4D.toInt()
                com.vellum.notes.input.ContactClassification.PALM -> 0xFFC62828.toInt()
                com.vellum.notes.input.ContactClassification.ERASER -> 0xFF7B1FA2.toInt()
                com.vellum.notes.input.ContactClassification.REJECTED -> 0xFF616161.toInt()
                com.vellum.notes.input.ContactClassification.CANDIDATE -> 0xFF9C6D00.toInt()
                com.vellum.notes.input.ContactClassification.RESTING -> 0xFF546E7A.toInt()
            }
            val r = (c.toolMajorMm * capabilities.pxPerMm / 2f).coerceAtLeast(24f)
            debugFillPaint.color = color
            debugFillPaint.alpha = 70
            canvas.drawCircle(c.x, c.y, r, debugFillPaint)
            debugFillPaint.alpha = 255
            canvas.drawCircle(c.x, c.y, r, debugFillPaint)
            // Labels stay black on the light paper (21:1) — classification hues
            // alone would drop below 4.5:1 for gray/amber states.
            debugLabelPaint.color = 0xFF000000.toInt()
            canvas.drawText(
                "P${c.pointerId} ${cc.classification.name} ${(cc.confidence * 100).toInt()}%",
                c.x + r + 4f,
                c.y + 4f,
                debugLabelPaint,
            )
            // Second line: windowed velocity, path length, write/rest scores, and the reason
            // so velocity-gated resting behavior can be diagnosed on-device.
            canvas.drawText(
                "v=${"%.0f".format(cc.windowedVelocityMmPerSec)}mm/s " +
                    "L=${"%.0f".format(cc.pathLengthMm)}mm " +
                    "W=${"%.0f".format(cc.writeScore)} R=${"%.0f".format(cc.restScore)} " +
                    cc.reason.name,
                c.x + r + 4f,
                c.y + 20f,
                debugLabelPaint,
            )
        }
        // Bounding boxes of resting clusters: visual confirmation that the whole hand (not
        // just one finger) is being treated as resting.
        for (bounds in frame.clusterBounds) {
            canvas.drawRect(bounds.minX, bounds.minY, bounds.maxX, bounds.maxY, clusterBoundsPaint)
        }
    }
}