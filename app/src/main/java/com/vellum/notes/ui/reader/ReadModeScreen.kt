package com.vellum.notes.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vellum.notes.data.MediaLoader
import com.vellum.notes.data.NotesRepository
import com.vellum.notes.model.BookHighlight
import com.vellum.notes.model.PageSummary
import com.vellum.notes.pdf.PdfImporter
import com.vellum.notes.pdf.PdfReaderTheme
import kotlinx.coroutines.launch

/** Read-mode overlay tool. Null = plain reading (pager swipes work). */
private enum class ReadTool { HIGHLIGHT, ERASE }

/** Translucent highlight colors (alpha baked in). */
private val HIGHLIGHT_COLORS = listOf(
    0x66FFEB3BL to "Yellow",
    0x66F48FB1L to "Pink",
    0x6681C784L to "Green",
    0x6690CAF9L to "Blue",
)

/**
 * Read mode for books (PDF-backed notebooks): full-screen pages with themes,
 * a freehand highlighter whose strokes are saved per page, an eraser that
 * removes a highlight with a tap, and a jump-off to the highlights review.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadModeScreen(
    notebookId: Long,
    startPageId: Long?,
    repository: NotesRepository,
    onBack: () -> Unit,
    onOpenHighlights: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages by repository.pagesFor(notebookId).collectAsState(initial = emptyList())
    val highlights by repository.highlightsForNotebook(notebookId).collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("") }
    LaunchedEffect(notebookId) {
        title = repository.getNotebook(notebookId)?.title.orEmpty()
    }

    // Only rasterized PDF pages are readable books; ink-only pages are skipped.
    val bookPages = remember(pages) { pages.filter { it.pdfBackgroundPath.isNotBlank() } }
    val pagerState = rememberPagerState(pageCount = { bookPages.size })
    // Deep link from the highlights review: jump to the highlighted page once
    // the page list arrives.
    LaunchedEffect(bookPages, startPageId) {
        val idx = bookPages.indexOfFirst { it.id == startPageId }
        if (startPageId != null && idx >= 0 && pagerState.currentPage != idx) {
            pagerState.scrollToPage(idx)
        }
    }

    var tool by remember { mutableStateOf<ReadTool?>(null) }
    var colorArgb by remember { mutableStateOf(HIGHLIGHT_COLORS[0].first) }
    var theme by remember { mutableStateOf(PdfReaderTheme.ORIGINAL) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            title.ifBlank { "Reading" },
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (bookPages.isNotEmpty()) {
                            Text(
                                "Page ${pagerState.currentPage + 1} of ${bookPages.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { tool = if (tool == ReadTool.HIGHLIGHT) null else ReadTool.HIGHLIGHT },
                    ) {
                        Icon(
                            Icons.Filled.Highlight,
                            contentDescription = "Highlighter",
                            tint = if (tool == ReadTool.HIGHLIGHT) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { tool = if (tool == ReadTool.ERASE) null else ReadTool.ERASE },
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Erase highlight",
                            tint = if (tool == ReadTool.ERASE) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenHighlights) {
                        Icon(Icons.Filled.FormatListBulleted, contentDescription = "All highlights")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Reader theme + highlight color strip (only what the mode needs).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (t in PdfReaderTheme.entries) {
                    FilterChip(
                        selected = theme == t,
                        onClick = { theme = t },
                        label = {
                            Text(t.name.lowercase().replaceFirstChar { it.uppercase() })
                        },
                    )
                }
                if (tool == ReadTool.HIGHLIGHT) {
                    HIGHLIGHT_COLORS.forEach { (argb, name) ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(Color(argb))
                                .then(
                                    if (colorArgb == argb) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary, CircleShape,
                                    ) else Modifier
                                )
                                .then(
                                    Modifier.clickableNoRipple { colorArgb = argb },
                                ),
                        )
                    }
                }
            }

            if (bookPages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No readable pages — import a PDF to read it as a book.")
                }
                return@Column
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // While the highlighter/eraser is armed the overlay owns all
                    // touches; otherwise swipes turn pages.
                    userScrollEnabled = tool == null,
                    key = { bookPages[it].id },
                ) { index ->
                    val page = bookPages[index]
                    ReadPage(
                        page = page,
                        theme = theme,
                        highlights = highlights.filter { it.pageId == page.id },
                        tool = tool,
                        colorArgb = colorArgb,
                        onCommitHighlight = { points ->
                            scope.launch {
                                runCatching {
                                    repository.addHighlight(
                                        BookHighlight(
                                            notebookId = notebookId,
                                            pageId = page.id,
                                            points = points,
                                            colorArgb = colorArgb,
                                        )
                                    )
                                }
                            }
                        },
                        onEraseAt = { x, y ->
                            scope.launch {
                                runCatching {
                                    val hit = highlights
                                        .filter { it.pageId == page.id }
                                        .firstOrNull { it.hitTest(x, y) }
                                    if (hit != null) repository.deleteHighlight(hit.id)
                                }
                            }
                        },
                    )
                }

                // Page stepper (pager swipes also work when no tool is armed).
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 3.dp, shadowElevation = 2.dp) {
                        Row(
                            Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(
                                            (pagerState.currentPage - 1).coerceAtLeast(0)
                                        )
                                    }
                                },
                                enabled = pagerState.currentPage > 0,
                            ) {
                                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page")
                            }
                            Text(
                                "${pagerState.currentPage + 1} / ${bookPages.size}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(
                                            (pagerState.currentPage + 1).coerceAtMost(bookPages.size - 1)
                                        )
                                    }
                                },
                                enabled = pagerState.currentPage < bookPages.size - 1,
                            ) {
                                Icon(Icons.Filled.ChevronRight, contentDescription = "Next page")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = null,
        indication = null,
        onClick = onClick,
    )

/** Page-relative bbox hit test in normalized coordinates (generous 3% slop). */
private fun BookHighlight.hitTest(x: Float, y: Float): Boolean {
    if (points.size < 4) return false
    var l = Float.MAX_VALUE
    var t = Float.MAX_VALUE
    var r = -Float.MAX_VALUE
    var b = -Float.MAX_VALUE
    var i = 0
    while (i + 1 < points.size) {
        val px = points[i]
        val py = points[i + 1]
        if (px < l) l = px
        if (py < t) t = py
        if (px > r) r = px
        if (py > b) b = py
        i += 2
    }
    val slop = 0.03f
    return x in (l - slop)..(r + slop) && y in (t - slop)..(b + slop)
}

/**
 * One book page: raster image with theme tint plus the highlight overlay. The
 * overlay only intercepts touches while a tool is armed so page swipes work
 * while reading.
 */
@Composable
private fun ReadPage(
    page: PageSummary,
    theme: PdfReaderTheme,
    highlights: List<BookHighlight>,
    tool: ReadTool?,
    colorArgb: Long,
    onCommitHighlight: (List<Float>) -> Unit,
    onEraseAt: (x: Float, y: Float) -> Unit,
) {
    val context = LocalContext.current
    val file = remember(page.pdfBackgroundPath) {
        runCatching { PdfImporter.resolveFile(context, page.pdfBackgroundPath) }.getOrNull()
    }
    val base = remember(file?.absolutePath) {
        file?.let { MediaLoader.decodeSampled(it, maxDimPx = 2048, rgb565 = true) }
    }
    DisposableEffect(file?.absolutePath) {
        onDispose { base?.recycle() }
    }
    if (base == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Could not load page.")
        }
        return
    }
    val shown = remember(base, theme) {
        if (theme == PdfReaderTheme.ORIGINAL) base
        else PdfImporter.tintedCopy(base, theme)
    }
    DisposableEffect(shown) {
        onDispose { if (shown !== base) shown.recycle() }
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var livePts by remember(page.id, tool) { mutableStateOf(listOf<Offset>()) }
    // Displayed image rect inside the canvas (ContentScale.Fit letterboxing).
    fun displayedRect(): Rect {
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        if (w <= 0f || h <= 0f) return Rect.Zero
        val bmpAspect = base.width.toFloat() / base.height.toFloat()
        val viewAspect = w / h
        return if (bmpAspect > viewAspect) {
            val drawnH = w / bmpAspect
            Rect(0f, (h - drawnH) / 2f, w, (h + drawnH) / 2f)
        } else {
            val drawnW = h * bmpAspect
            Rect((w - drawnW) / 2f, 0f, (w + drawnW) / 2f, h)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            shown.asImageBitmap(), contentDescription = page.title,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
        )
        if (theme == PdfReaderTheme.NIGHT) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.08f)),
            )
        }
        val touchModifier = when (tool) {
            ReadTool.HIGHLIGHT -> Modifier.pointerInput(page.id, colorArgb) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (displayedRect().contains(offset)) livePts = listOf(offset)
                    },
                    onDrag = { change, _ ->
                        if (livePts.isNotEmpty()) livePts = livePts + change.position
                    },
                    onDragEnd = {
                        val pts = livePts
                        livePts = emptyList()
                        val rect = displayedRect()
                        if (!rect.isEmpty && pts.size >= 2) {
                            val relative = ArrayList<Float>(pts.size * 2)
                            for (p in pts) {
                                relative += (p.x - rect.left) / rect.width
                                relative += (p.y - rect.top) / rect.height
                            }
                            if (relative.size >= 4) onCommitHighlight(relative)
                        }
                    },
                    onDragCancel = { livePts = emptyList() },
                )
            }
            ReadTool.ERASE -> Modifier.pointerInput(page.id) {
                detectTapGestures { offset ->
                    val rect = displayedRect()
                    if (rect.isEmpty || !rect.contains(offset)) return@detectTapGestures
                    onEraseAt(
                        (offset.x - rect.left) / rect.width,
                        (offset.y - rect.top) / rect.height,
                    )
                }
            }
            null -> Modifier
        }
        Canvas(
            modifier = Modifier.fillMaxSize().then(touchModifier),
            onDraw = {
                val newSize = IntSize(size.width.toInt(), size.height.toInt())
                if (newSize != canvasSize) canvasSize = newSize
                val rect = displayedRect()
                if (rect.isEmpty) return@Canvas
                fun toPx(x: Float, y: Float) = Offset(
                    rect.left + x * rect.width,
                    rect.top + y * rect.height,
                )
                val widthPx = rect.width * 0.035f
                for (h in highlights) {
                    val pts = h.points
                    var i = 0
                    // Dots (single-point highlights) still render.
                    if (pts.size == 2) {
                        drawCircle(Color(h.colorArgb), radius = widthPx / 2f, center = toPx(pts[0], pts[1]))
                    }
                    while (i + 3 < pts.size) {
                        drawLine(
                            Color(h.colorArgb),
                            toPx(pts[i], pts[i + 1]),
                            toPx(pts[i + 2], pts[i + 3]),
                            strokeWidth = widthPx,
                            cap = StrokeCap.Round,
                        )
                        i += 2
                    }
                }
                var j = 0
                while (j + 3 < livePts.size) {
                    drawLine(
                        Color(colorArgb),
                        livePts[j],
                        livePts[j + 1],
                        strokeWidth = rect.width * 0.035f,
                        cap = StrokeCap.Round,
                    )
                    j += 1
                }
            },
        )
    }
}
