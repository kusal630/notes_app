package com.premiumnotes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.core.content.FileProvider
import com.premiumnotes.PremiumNotesApp
import com.premiumnotes.data.NotesRepository
import com.premiumnotes.data.SettingsRepository
import com.premiumnotes.editor.NoteEditorState
import com.premiumnotes.editor.Tool
import com.premiumnotes.export.PdfExporter
import com.premiumnotes.input.InputCapabilities
import com.premiumnotes.input.PalmRejectionEngine
import com.premiumnotes.input.PalmRejectionMode
import com.premiumnotes.input.PalmRejectionSettings
import com.premiumnotes.input.SmoothingMode
import com.premiumnotes.model.NoteType
import com.premiumnotes.model.PageBackground
import com.premiumnotes.model.PageSummary
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.PenType
import com.premiumnotes.model.ShapeKind
import com.premiumnotes.model.TranscriptSegment
import com.premiumnotes.speech.AudioCaptureService
import com.premiumnotes.speech.ModelDiscovery
import com.premiumnotes.speech.SpeechController
import com.premiumnotes.speech.SummaryGenerator
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow

private val PALETTE = listOf(
    0xFF000000, 0xFF424242, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFEC407A,
)

private val PEN_WIDTHS_MM = listOf(0.3f, 0.5f, 0.7f, 1f, 1.5f, 2f, 3f, 5f)

private val PEN_TYPES = listOf(
    PenType.BALLPOINT to "Ballpoint",
    PenType.MONOLINE to "Gel",
    PenType.FOUNTAIN to "Fountain",
    PenType.PENCIL to "Pencil",
    PenType.MARKER to "Marker",
    PenType.CALLIGRAPHY to "Calligraphy",
)

private val SHAPE_KINDS = listOf(
    ShapeKind.RECT to "Rectangle",
    ShapeKind.TRIANGLE to "Triangle",
    ShapeKind.CIRCLE to "Circle",
    ShapeKind.ELLIPSE to "Ellipse",
    ShapeKind.LINE to "Line",
    ShapeKind.ARROW to "Arrow",
    ShapeKind.STAR to "Star",
    ShapeKind.POLYGON to "Hexagon",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    notebookId: Long,
    repository: NotesRepository,
    capabilities: InputCapabilities,
    engine: PalmRejectionEngine,
    settingsFlow: Flow<PalmRejectionSettings>,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as PremiumNotesApp
    val uiContext = LocalContext.current
    val scope = rememberCoroutineScope()

    // Pages load asynchronously; null until the real list arrives so we never create a
    // duplicate page from the initial placeholder emission.
    var pages by remember { mutableStateOf<List<PageSummary>?>(null) }
    LaunchedEffect(notebookId) {
        repository.pagesFor(notebookId).collect { pages = it }
    }
    val pageList = pages.orEmpty()

    // Ensure at least one page exists before showing the editor. Keyed on the nullable
    // [pages] state: null -> first emission is a state change even when both lists are
    // structurally empty, so an empty new notebook always gets its first page.
    LaunchedEffect(pages) {
        if (pages != null && pages.orEmpty().isEmpty()) {
            repository.createPage(notebookId)
        }
    }

    // Selected page follows the page rail; falls back to the first page when the current
    // selection disappears (e.g. after deletion).
    var selectedPageId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pages) {
        val list = pages.orEmpty()
        if (selectedPageId == null || list.none { it.id == selectedPageId }) {
            selectedPageId = list.firstOrNull()?.id
        }
    }
    val pageId = selectedPageId

    if (pageId == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Preparing page…")
        }
        return
    }

    val factory = remember(pageId) {
        viewModelFactory {
            initializer { EditorViewModel(pageId, repository) }
        }
    }
    val vm: EditorViewModel = viewModel(key = "editor-$pageId", factory = factory)
    val editorState by vm.editor.collectAsState()

    if (editorState == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading page…")
        }
        return
    }

    val content by editorState!!.content.collectAsState()
    val tool by editorState!!.tool.collectAsState()
    val penStyle by editorState!!.penStyle.collectAsState()
    val eraserSize by editorState!!.eraserSizeMm.collectAsState()
    val shapeKind by editorState!!.shapeKind.collectAsState()
    val selectedIds by editorState!!.selectedIds.collectAsState()
    val settings by settingsFlow.collectAsState(initial = PalmRejectionSettings())

    // The page rail is a hideable overlay so the canvas stays full-screen for writing.
    var showRail by remember { mutableStateOf(false) }

    // A classroom note is a normal note plus the on-device audio/transcript sidebar.
    // Opening one shows the sidebar from the start (with a "record" hint) so the feature
    // is discoverable; a normal note only shows it once a recording is started.
    var isClassroom by remember { mutableStateOf(false) }
    LaunchedEffect(notebookId) {
        isClassroom = repository.getNotebook(notebookId)?.type == NoteType.CLASSROOM
    }

    // --- Classroom Notes (Feature 2): on-device recording + transcript sidebar. ---
    val transcript by SpeechController.segments.collectAsState()
    val partial by SpeechController.partial.collectAsState()
    val isRecording by SpeechController.isRecording.collectAsState()
    val recordingPageId by SpeechController.recordingPageId.collectAsState()
    val classroomAvailable = remember(uiContext) { ModelDiscovery.resolve(uiContext) != null }
    var classroomNotice by remember { mutableStateOf<String?>(null) }

    // While recording this page, mirror every recognized segment into the page content so
    // autosave persists it (undo does not apply to transcript updates).
    LaunchedEffect(transcript, pageId, recordingPageId, isRecording) {
        if (isRecording && recordingPageId == pageId) {
            vm.setTranscript(transcript)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            classroomNotice = null
            AudioCaptureService.start(uiContext, pageId)
        } else {
            classroomNotice = "Microphone permission denied — classroom recording is unavailable. " +
                "You can still write notes normally."
        }
    }

    val toggleClassroom: () -> Unit = {
        if (isRecording && recordingPageId == pageId) {
            AudioCaptureService.stop(uiContext)
            vm.setTranscript(SpeechController.segments.value)
        } else if (!classroomAvailable) {
            classroomNotice = "Speech model not installed. Run ./gradlew downloadVoskModel and " +
                "rebuild to enable Classroom Notes."
        } else if (uiContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            AudioCaptureService.start(uiContext, pageId)
        }
    }

    val pageBackground = remember(pageId) {
        pageList.firstOrNull { it.id == pageId }?.background ?: PageBackground()
    }

    Scaffold(
        topBar = {
            EditorTopBar(
                notebookId = notebookId,
                canUndo = editorState!!.canUndo,
                canRedo = editorState!!.canRedo,
                onUndo = { vm.undo() },
                onRedo = { vm.redo() },
                onBack = onBack,
                onToggleRail = { showRail = !showRail },
                onExportPdf = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            PdfExporter.export(uiContext, pageId, content, pageBackground)
                        }
                        if (file != null) {
                            val uri = FileProvider.getUriForFile(
                                uiContext,
                                "${uiContext.packageName}.fileprovider",
                                file,
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            uiContext.startActivity(Intent.createChooser(send, "Export PDF"))
                        } else {
                            Toast.makeText(uiContext, "PDF export failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                isRecording = isRecording && recordingPageId == pageId,
                classroomAvailable = classroomAvailable,
                onToggleClassroom = toggleClassroom,
            )
        },
        bottomBar = {
            EditorToolbar(
                tool = tool,
                penStyle = penStyle,
                eraserSizeMm = eraserSize,
                shapeKind = shapeKind,
                settings = settings,
                selectedCount = selectedIds.size,
                onTool = { t ->
                    vm.setTool(t)
                    if (t != Tool.SELECT) vm.clearSelection()
                    when (t) {
                        Tool.HIGHLIGHTER -> if (penStyle.type != PenType.HIGHLIGHTER) {
                            // Remember the user's ink pen so switching back restores it.
                            editorState!!.saveInkStyle()
                            vm.setPenStyle(penStyle.copy(type = PenType.HIGHLIGHTER, opacity = 0.4f, widthMm = 5f))
                        }
                        Tool.PEN -> editorState!!.restoreInkStyle()
                        else -> Unit
                    }
                },
                onShapeKind = { vm.setShapeKind(it) },
                onColor = { color ->
                    vm.setPenStyle(penStyle.copy(colorArgb = color))
                },
                onWidth = { w ->
                    vm.setPenStyle(penStyle.copy(widthMm = w))
                },
                onPenType = { type ->
                    vm.setPenStyle(
                        penStyle.copy(
                            type = type,
                            opacity = if (type == PenType.HIGHLIGHTER) 0.4f else 1f,
                        )
                    )
                },
                onEraserSize = { vm.setEraserSize(it) },
                onSelectAll = { vm.selectAll() },
                onDeleteSelection = { vm.deleteSelection() },
                onDuplicateSelection = { vm.duplicateSelection() },
                onSmoothingChange = { mode ->
                    scope.launch {
                        app.container.settingsRepository.updateSettings { this.smoothing = mode }
                    }
                },
                autoEraseEnabled = settings.autoEraseEnabled,
                onAutoEraseToggle = {
                    scope.launch {
                        app.container.settingsRepository.updateSettings {
                            autoEraseEnabled = !autoEraseEnabled
                        }
                    }
                },
            )
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Phones (narrow) get a slim page strip so the canvas keeps its width;
            // tablets/landscape get the full page rail.
            val compact = maxWidth < 600.dp

            Row(Modifier.fillMaxSize()) {
                // Canvas fills the whole screen so you can write edge to edge; the page
                // rail is a hideable overlay toggled from the top bar. Keying by pageId
                // recreates the view on page switch so the engine resets and any
                // in-progress stroke is finalized onto the page it was drawn on.
                Box(Modifier.weight(1f)) {
                    key(pageId) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                InkCanvasView(ctx).also { view ->
                                    view.capabilities = capabilities
                                    view.engine = engine
                                    view.listener = vm.canvasListener
                                    engine.reset()
                                }
                            },
                            update = { view ->
                                view.strokes = content.strokes
                                view.shapes = content.shapeObjects
                                view.penStyle = penStyle
                                view.tool = tool
                                view.eraserSizeMm = eraserSize
                                view.shapeKind = shapeKind
                                view.background = pageBackground
                                view.selectionBoundsMm = editorState!!.selectionBoundsMm
                                view.listener = vm.canvasListener
                                view.autoEraseEnabled = settings.autoEraseEnabled
                                // Palm rest zone + scroll bar.
                                view.palmZone = settings.palmZone
                                view.scrollBarVisible = true
                                view.onPalmZoneChanged = { zone ->
                                    scope.launch {
                                        app.container.settingsRepository.updateSettings { palmZone = zone }
                                    }
                                }
                            },
                            onRelease = { view -> view.finalizeActiveStroke() },
                        )
                    }
                    classroomNotice?.let { notice ->
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(12.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 4.dp,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(notice, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { classroomNotice = null }) { Text("Dismiss") }
                            }
                        }
                    }
                }

                // Classroom Notes transcript sidebar: a sibling of the canvas so it never
                // steals the pen's touches. Visible for classroom notes from the start
                // (live during recording, static when reopened), otherwise whenever a
                // recording session exists for this page.
                val showTranscript = isClassroom ||
                    transcript.isNotEmpty() || partial.isNotEmpty() || isRecording
                if (showTranscript) {
                    HorizontalDivider(
                        modifier = Modifier.width(1.dp).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    ClassroomSidebar(
                        segments = transcript,
                        partial = if (isRecording) partial else "",
                        isRecording = isRecording,
                        onStopRecording = { AudioCaptureService.stop(uiContext) },
                        summary = content.summary,
                        summaryEnabled = transcript.isNotEmpty(),
                        onGenerateSummary = {
                            vm.setSummary(SummaryGenerator.summarize(transcript))
                        },
                        modifier = Modifier.widthIn(min = 220.dp, max = 320.dp).fillMaxHeight(),
                    )
                }
            }

            if (showRail) {
                Row(Modifier.fillMaxHeight()) {
                    PageRail(
                        pages = pageList,
                        currentPageId = pageId,
                        compact = compact,
                        modifier = Modifier
                            .width(if (compact) 72.dp else 150.dp)
                            .fillMaxHeight(),
                        onSelectPage = { id -> selectedPageId = id },
                        onNewPage = { scope.launch { repository.createPage(notebookId) } },
                    )
                    HorizontalDivider(
                        modifier = Modifier.width(1.dp).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    notebookId: Long,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onToggleRail: () -> Unit,
    onExportPdf: () -> Unit,
    isRecording: Boolean,
    classroomAvailable: Boolean,
    onToggleClassroom: () -> Unit,
) {
    TopAppBar(
        title = {
            Text("Notebook · ${notebookId}", fontWeight = FontWeight.SemiBold)
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onToggleRail) {
                Icon(Icons.Filled.Menu, contentDescription = "Show or hide pages")
            }
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
            IconButton(onClick = onExportPdf) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = "Export PDF")
            }
            IconButton(
                onClick = onToggleClassroom,
                enabled = classroomAvailable || isRecording,
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Classroom Notes (record & transcribe)",
                    tint = if (isRecording) Color(0xFFE53935) else LocalContentColor.current,
                )
            }
        }
    )
}

/**
 * The Classroom Notes sidebar: a sibling of the canvas — never an overlay on top of it —
 * so pen input is never stolen. Organized as tabs so new features (notes, highlights,
 * export, …) slot in next to the transcript without redesigning the layout.
 *
 * - Transcript: live (or saved) recognized speech; auto-scrolls while recording.
 * - Summary: an on-device extractive summary of the transcript, generated on request.
 */
@Composable
private fun ClassroomSidebar(
    segments: List<TranscriptSegment>,
    partial: String,
    isRecording: Boolean,
    onStopRecording: () -> Unit,
    summary: String?,
    summaryEnabled: Boolean,
    onGenerateSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                text = if (isRecording) "Classroom · recording" else "Classroom notes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (isRecording) {
                // A red "live" dot keeps the "still recording" state unmistakable at a glance.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(Color(0xFFE53935), CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Microphone active — transcription is on-device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // Tabs: Transcript | Summary — extensible surface for future features.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SidebarTab(label = "Transcript", selected = tab == 0, onClick = { tab = 0 }, modifier = Modifier.weight(1f))
                SidebarTab(label = "Summary", selected = tab == 1, onClick = { tab = 1 }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))

            when (tab) {
                0 -> {
                    if (segments.isEmpty() && partial.isBlank()) {
                        Text(
                            if (isRecording) {
                                "No speech yet. Speak naturally — recognized words appear here."
                            } else {
                                "No transcript yet. Tap the mic in the top bar to record " +
                                    "and transcribe on-device."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(segments, key = { it.id }) { seg ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        seg.text,
                                        modifier = Modifier.padding(8.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            if (isRecording && partial.isNotBlank()) {
                                item(key = "partial") {
                                    Text(
                                        partial,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        LaunchedEffect(segments.size, partial) {
                            if (isRecording) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onStopRecording,
                        enabled = isRecording,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Stop recording")
                    }
                }
                1 -> {
                    if (summary != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            Text(
                                summary,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    } else if (segments.isEmpty()) {
                        Text(
                            "No transcript yet — record a lecture first, then generate a " +
                                "summary on-device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "A summary has not been generated yet. Tap below to condense the " +
                                "transcript on-device (no network).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onGenerateSummary,
                        enabled = summaryEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Summarize, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (summary != null) "Regenerate summary" else "Generate summary")
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PageRail(
    pages: List<PageSummary>,
    currentPageId: Long,
    compact: Boolean,
    onSelectPage: (Long) -> Unit,
    onNewPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            // A thin, draggable scroll bar over the page list: with many pages you can see
            // where you are and jump. Drawn in-house (not the foundation Scrollbar API,
            // which is absent from the resolved foundation 1.7.6 artifacts).
            val listState = rememberLazyListState()
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pages, key = { it.id }) { page ->
                        if (compact) {
                            val isCurrent = page.id == currentPageId
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .border(
                                        width = if (isCurrent) 2.dp else 1.dp,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(6.dp),
                                    )
                                    .clickable { onSelectPage(page.id) },
                            )
                        } else {
                            PageThumbnail(page, selected = page.id == currentPageId, onClick = { onSelectPage(page.id) })
                        }
                    }
                }
                PageRailScrollbar(
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
            if (compact) {
                IconButton(
                    onClick = onNewPage,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New page")
                }
            } else {
                Button(
                    onClick = onNewPage,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("New page")
                }
            }
        }
    }
}

@Composable
private fun PageThumbnail(page: PageSummary, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            page.title,
            modifier = Modifier.padding(6.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * A thin overlay scroll bar for the page rail's [LazyColumn]. Appears only when the list
 * overflows; you can drag anywhere on the track to jump. Knob position/size come from the
 * list's layout info (the rail's items are uniform, so item counts map cleanly to a ratio).
 * Drawn in-house: the foundation `Scrollbar` API is absent from the resolved 1.7.6 artifacts.
 */
@Composable
private fun PageRailScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val trackWidth = 4.dp

    val totalItems = listState.layoutInfo.totalItemsCount
    val visibleItems = listState.layoutInfo.visibleItemsInfo.size
    val firstIndex = listState.firstVisibleItemIndex
    val scrollable = totalItems > visibleItems

    // Uniform items -> the visible/total item ratio equals the visible/total height ratio.
    val knobRatio = remember(totalItems, visibleItems) {
        if (totalItems == 0) 1f else (visibleItems.toFloat() / totalItems).coerceIn(0.06f, 1f)
    }
    val scrollFraction = remember(totalItems, visibleItems, firstIndex) {
        if (totalItems <= visibleItems) 0f
        else (firstIndex.toFloat() / (totalItems - visibleItems)).coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .width(14.dp)
            .then(
                if (scrollable) {
                    Modifier.pointerInput(listState, totalItems, visibleItems, knobRatio) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                scope.launch {
                                    scrollListToFraction(listState, offset.y, size.height.toFloat(), knobRatio)
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                scope.launch {
                                    scrollListToFraction(listState, change.position.y, size.height.toFloat(), knobRatio)
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                }
            )
            .drawBehind {
                if (!scrollable) return@drawBehind
                val trackHeight = size.height
                val knobHeight = (trackHeight * knobRatio).coerceAtLeast(32f)
                val maxTop = trackHeight - knobHeight
                val top = (scrollFraction * maxTop).coerceIn(0f, maxTop)
                val halfWidth = trackWidth.toPx() / 2f
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(size.width - trackWidth.toPx(), top),
                    size = Size(trackWidth.toPx(), knobHeight),
                    cornerRadius = CornerRadius(halfWidth, halfWidth),
                )
            },
    )
}

private suspend fun scrollListToFraction(
    listState: LazyListState,
    dragY: Float,
    trackHeightPx: Float,
    knobRatio: Float,
) {
    val total = listState.layoutInfo.totalItemsCount
    val visible = listState.layoutInfo.visibleItemsInfo.size
    if (total == 0 || total <= visible) return
    val knobHeight = (trackHeightPx * knobRatio).coerceAtLeast(32f)
    val travelRange = (trackHeightPx - knobHeight).coerceAtLeast(1f)
    val fraction = ((dragY - knobHeight / 2f) / travelRange).coerceIn(0f, 1f)
    listState.scrollToItem((fraction * (total - visible)).toInt())
}

@Composable
private fun EditorToolbar(
    tool: Tool,
    penStyle: PenStyle,
    eraserSizeMm: Float,
    shapeKind: ShapeKind,
    settings: PalmRejectionSettings,
    selectedCount: Int,
    onTool: (Tool) -> Unit,
    onShapeKind: (ShapeKind) -> Unit,
    onColor: (Long) -> Unit,
    onWidth: (Float) -> Unit,
    onPenType: (PenType) -> Unit,
    onEraserSize: (Float) -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelection: () -> Unit,
    onDuplicateSelection: () -> Unit,
    onSmoothingChange: (SmoothingMode) -> Unit,
    autoEraseEnabled: Boolean,
    onAutoEraseToggle: () -> Unit,
) {
    Surface(tonalElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Primary tool row.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(
                    label = "Pen",
                    selected = tool == Tool.PEN,
                    onClick = { onTool(Tool.PEN) },
                    content = { Icon(Icons.Filled.BorderColor, contentDescription = "Pen") },
                )
                ToolButton(
                    label = "Highlighter",
                    selected = tool == Tool.HIGHLIGHTER,
                    onClick = { onTool(Tool.HIGHLIGHTER) },
                    content = { Icon(Icons.Filled.Highlight, contentDescription = "Highlighter") },
                )
                ToolButton(
                    label = "Eraser",
                    selected = tool == Tool.ERASER,
                    onClick = { onTool(Tool.ERASER) },
                    content = { Icon(Icons.Outlined.Circle, contentDescription = "Eraser") },
                )
                ToolButton(
                    label = "Select",
                    selected = tool == Tool.SELECT,
                    onClick = { onTool(Tool.SELECT) },
                    content = { Icon(Icons.Filled.SelectAll, contentDescription = "Select") },
                )
                ToolButton(
                    label = "Shapes",
                    selected = tool == Tool.SHAPES,
                    onClick = { onTool(Tool.SHAPES) },
                    content = { Icon(Icons.Filled.Category, contentDescription = "Shapes") },
                )

                Spacer(Modifier.width(8.dp))

                // Feature 1: automatic write/erase detection. Off by default; when off the
                // pen/eraser behave exactly as before. Manual tool selection always wins.
                ToolButton(
                    label = "Auto-erase",
                    selected = autoEraseEnabled,
                    onClick = onAutoEraseToggle,
                    content = { Icon(Icons.Filled.AutoFixHigh, contentDescription = "Auto-erase") },
                )

                Spacer(Modifier.width(8.dp))

                // Tools that are not implemented yet — explicitly disabled, never faked.
                DisabledToolButton(Icons.Filled.TextFields, "Text (in progress)")
                DisabledToolButton(Icons.Filled.Image, "Image (in progress)")
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Context row: colors + thickness (or eraser size).
            when (tool) {
                Tool.PEN, Tool.HIGHLIGHTER -> {
                    if (tool == Tool.PEN) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Pen", style = MaterialTheme.typography.labelMedium)
                            PEN_TYPES.forEach { (type, label) ->
                                val selected = penStyle.type == type
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(6.dp),
                                        )
                                        .clickable { onPenType(type) }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    ColorRow(penStyle = penStyle, onColor = onColor)
                    Spacer(Modifier.height(6.dp))
                    WidthRow(penStyle = penStyle, onWidth = onWidth)
                    if (tool == Tool.HIGHLIGHTER) {
                        Spacer(Modifier.height(6.dp))
                        Text("Highlighter: translucent, wide", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Smoothing", style = MaterialTheme.typography.labelMedium)
                        SmoothingMode.values().forEach { mode ->
                            val selected = settings.smoothing == mode
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    )
                                    .clickable { onSmoothingChange(mode) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(mode.name.substring(0, 1), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Tool.SHAPES -> {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Shape", style = MaterialTheme.typography.labelMedium)
                        SHAPE_KINDS.forEach { (kind, label) ->
                            val selected = shapeKind == kind
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(6.dp),
                                    )
                                    .clickable { onShapeKind(kind) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    ColorRow(penStyle = penStyle, onColor = onColor)
                    Spacer(Modifier.height(6.dp))
                    WidthRow(penStyle = penStyle, onWidth = onWidth)
                }
                Tool.ERASER -> {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Eraser size", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                        listOf(4f, 8f, 16f).forEach { s ->
                            val selected = kotlin.math.abs(eraserSizeMm - s) < 0.1f
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    )
                                    .clickable { onEraserSize(s) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .size((6 + s).dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                    }
                }
                Tool.SELECT -> {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (selectedCount > 0) "$selectedCount selected" else "Drag to select strokes",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (selectedCount > 0) {
                            TextButton(onClick = onDuplicateSelection) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Duplicate")
                            }
                            TextButton(onClick = onDeleteSelection) {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Delete")
                            }
                        } else {
                            TextButton(onClick = onSelectAll) { Text("Select all") }
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ColorRow(penStyle: PenStyle, onColor: (Long) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Color", style = MaterialTheme.typography.labelMedium)
        PALETTE.forEach { c ->
            val selected = penStyle.colorArgb == c
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        width = 2.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable { onColor(c) },
                contentAlignment = Alignment.Center,
            ) {}
        }
    }
}

@Composable
private fun WidthRow(penStyle: PenStyle, onWidth: (Float) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Width", style = MaterialTheme.typography.labelMedium)
        PEN_WIDTHS_MM.forEach { w ->
            val selected = kotlin.math.abs(penStyle.widthMm - w) < 0.01f
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .clickable { onWidth(w) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size((w * 3).dp)
                        .clip(CircleShape)
                        .background(penStyle.colorArgb.toColor())
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        content()
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DisabledToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
        Text("—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
    }
}

private fun Long.toColor(): Color = Color(this)