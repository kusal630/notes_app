package com.premiumnotes.ui.editor

import android.content.Context
import android.graphics.Bitmap
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
import com.premiumnotes.model.Stroke
import com.premiumnotes.render.InkRenderer
import com.premiumnotes.render.PageBackgroundRenderer
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
 *  - Draw committed strokes from a cached bitmap layer (rebuilt only on change), and
 *    the active stroke live on top — no full-page redraw per touch event.
 *  - Handle two-finger pan/zoom without letting a resting palm trigger gestures.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onStrokeCommitted(stroke: Stroke)
        fun onEraseAt(x: Float, y: Float, radiusMm: Float)
        fun onEraseAlong(x1: Float, y1: Float, x2: Float, y2: Float, radiusMm: Float)
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
                if (!pureAppend) rebuildPending = true
                if (value.isNotEmpty()) {
                    strokeIdCounter = maxOf(strokeIdCounter, value.maxOf { it.id })
                }
            }
        }

    var background: PageBackground = PageBackground()

    var penStyle: PenStyle = PenStyle()
    var tool: Tool = Tool.PEN
    var eraserSizeMm: Float = 6f

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
    private var activePath = Path()
    private val activePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // --- committed layer cache ---
    private var committedLayer: Bitmap? = null
    private var layerScale = 0f
    private var layerOffsetX = 0f
    private var layerOffsetY = 0f
    private var strokesVersion = 0
    private var rebuildPending = true
    /** Number of strokes already baked into the cached layer. */
    private var renderedCount = 0
    /** [strokesVersion] at the time the cached layer was last written. */
    private var renderedVersion = -1

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        committedLayer = null
        rebuildPending = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val input = MotionEventParser.parse(event)
        val classified = engine.process(input)

        when {
            tool == Tool.ERASER -> handleEraser(input, classified)
            tool == Tool.PEN || tool == Tool.HIGHLIGHTER -> handleStroke(input, classified)
            tool == Tool.SELECT -> handleSelection(input, classified)
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
                            activePath.reset()
                        }
                    }
                }
            }

            com.premiumnotes.input.InputAction.CANCEL -> {
                strokeBuilder?.onCancel()
                strokeBuilder = null
                writingPointerId = -1
                activePath.reset()
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
                        if (builder.onMove(worldX, worldY, contact.contact.eventTimeNanos)) {
                            rebuildActivePath()
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

    // --- eraser ---

    private fun handleEraser(input: InputFrame, classified: ClassifiedFrame) {
        when (input.action) {
            com.premiumnotes.input.InputAction.DOWN,
            com.premiumnotes.input.InputAction.POINTER_DOWN,
            com.premiumnotes.input.InputAction.MOVE,
            -> {
                val writingId = classified.activeWritingPointerId ?: return
                val contact = classified.contactFor(writingId) ?: return
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
            -> lastEraserPoint = null
        }
    }

    private var lastEraserPoint: Point? = null

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
                rebuildPending = true
                invalidate()
            }
            else -> Unit
        }
    }

    // --- rendering ---

    private fun rebuildActivePath() {
        activePath.reset()
        val builder = strokeBuilder ?: return
        val pts = builder.livePoints
        if (pts.isEmpty()) return
        activePath.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) activePath.lineTo(pts[i].x, pts[i].y)

        activePaint.color = (penStyle.colorArgb and 0xFFFFFF).toInt() or
            ((penStyle.opacity.coerceIn(0f, 1f) * 255).toInt() shl 24)
        // Stroke width in world units (mm); the canvas transform converts it to pixels.
        activePaint.strokeWidth = penStyle.widthMm.coerceAtLeast(0.2f)
    }

    private fun rebuildCommittedLayer() {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bmp = committedLayer
        if (bmp == null || bmp.width != w || bmp.height != h) {
            committedLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            renderedCount = 0
        }
        val layer = committedLayer ?: return
        val c = Canvas(layer)
        c.save()
        c.translate(offsetX, offsetY)
        c.scale(scale, scale)

        // Fast path: viewport transform unchanged and exactly one ink stroke appended
        // since the layer was last written. Ink always draws above highlighters, so
        // appending ink directly is correct; anything else forces a full z-ordered pass.
        val transformUnchanged = scale == layerScale && offsetX == layerOffsetX && offsetY == layerOffsetY
        val singleAppend = !rebuildPending && transformUnchanged &&
            strokes.size == renderedCount + 1 && strokesVersion == renderedVersion + 1 &&
            strokes.last().style.type != com.premiumnotes.model.PenType.HIGHLIGHTER
        if (singleAppend) {
            renderer.drawStroke(c, strokes.last(), 1f)
        } else {
            layer.eraseColor(0x00000000)
            // Z-order: highlighters always render below ink so a later highlight never
            // obscures handwriting (matches paper behavior). Both passes keep their
            // relative chronological order.
            val highlighters = ArrayList<Stroke>()
            val ink = ArrayList<Stroke>()
            for (stroke in strokes) {
                if (stroke.style.type == com.premiumnotes.model.PenType.HIGHLIGHTER) highlighters += stroke
                else ink += stroke
            }
            for (stroke in highlighters) renderer.drawStroke(c, stroke, 1f)
            for (stroke in ink) renderer.drawStroke(c, stroke, 1f)
        }
        c.restore()

        renderedCount = strokes.size
        renderedVersion = this.strokesVersion
        layerScale = scale
        layerOffsetX = offsetX
        layerOffsetY = offsetY
        strokesVersion = this.strokesVersion
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!::capabilities.isInitialized) return
        val w = width.toFloat()
        val h = height.toFloat()

        // Background (world space).
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        PageBackgroundRenderer.drawBackground(
            canvas,
            background,
            pxPerMm = 1f,
            worldClip = RectF(
                -offsetX / scale, -offsetY / scale,
                (w - offsetX) / scale, (h - offsetY) / scale,
            ),
        )
        canvas.restore()

        // Committed layer: rebuilt only when strokes change, the view is resized, or the
        // transform drifts too far; otherwise the cached bitmap is re-projected so
        // pan/zoom never pay a full redraw per frame.
        val layer = committedLayer
        val ratio = if (layerScale > 0f) scale / layerScale else Float.MAX_VALUE
        val needsRebuild =
            layer == null ||
                layer!!.width != width || layer.height != height ||
                rebuildPending ||
                strokesVersion != this.strokesVersion ||
                kotlin.math.abs(offsetX - layerOffsetX) > w * 0.25f ||
                kotlin.math.abs(offsetY - layerOffsetY) > h * 0.25f ||
                ratio < 0.6f || ratio > 1.7f
        if (needsRebuild) {
            rebuildCommittedLayer()
            rebuildPending = false
        }
        committedLayer?.let { bmp ->
            val sf = scale / layerScale
            if (sf == 1f && offsetX == layerOffsetX && offsetY == layerOffsetY) {
                canvas.drawBitmap(bmp, 0f, 0f, null)
            } else {
                canvas.save()
                canvas.translate(offsetX - sf * layerOffsetX, offsetY - sf * layerOffsetY)
                canvas.scale(sf, sf)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                canvas.restore()
            }
        }

        // Selection bounds highlight.
        selectionBoundsMm?.let { bounds ->
            canvas.drawRect(worldRectToScreen(bounds), selectionPaint)
        }

        // Live lasso while dragging.
        if (selectionMode == SelectionMode.LASSO) {
            val lasso = worldRectToScreen(
                RectF(
                    kotlin.math.min(lassoStartWorld.x, lassoCurrentWorld.x),
                    kotlin.math.min(lassoStartWorld.y, lassoCurrentWorld.y),
                    kotlin.math.max(lassoStartWorld.x, lassoCurrentWorld.x),
                    kotlin.math.max(lassoStartWorld.y, lassoCurrentWorld.y),
                )
            )
            canvas.drawRect(lasso, lassoFillPaint)
            canvas.drawRect(lasso, lassoStrokePaint)
        }

        // Active stroke (world coordinates; rendered under the same viewport transform
        // as the committed layer so the live stroke tracks the pen exactly).
        val builder = strokeBuilder
        if (builder != null && builder.livePoints.size > 1) {
            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(scale, scale)
            canvas.drawPath(activePath, activePaint)
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        committedLayer?.recycle()
        committedLayer = null
    }
}