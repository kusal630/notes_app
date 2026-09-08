package com.vellum.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.core.content.FileProvider
import com.vellum.notes.VellumApp
import com.vellum.notes.data.NotesRepository
import com.vellum.notes.data.SettingsRepository
import com.vellum.notes.editor.NoteEditorState
import com.vellum.notes.editor.Tool
import com.vellum.notes.export.PdfExporter
import java.io.File
import androidx.activity.result.PickVisualMediaRequest
import android.graphics.BitmapFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.vellum.notes.data.ImageStore
import com.vellum.notes.data.MediaLoader
import com.vellum.notes.model.PaperTemplates
import com.vellum.notes.pdf.PdfImporter
import com.vellum.notes.input.InputCapabilities
import com.vellum.notes.input.PalmRejectionEngine
import com.vellum.notes.input.PalmRejectionMode
import com.vellum.notes.input.PalmRejectionSettings
import com.vellum.notes.input.SmoothingMode
import com.vellum.notes.model.NoteType
import com.vellum.notes.model.PageBackground
import com.vellum.notes.model.PageSummary
import com.vellum.notes.model.PenStyle
import com.vellum.notes.model.PenType
import com.vellum.notes.model.ShapeKind
import com.vellum.notes.model.TranscriptSegment
import com.vellum.notes.speech.AudioCaptureService
import com.vellum.notes.speech.ModelDiscovery
import com.vellum.notes.speech.SpeechController
import com.vellum.notes.speech.SummaryGenerator
import com.vellum.notes.ui.theme.VellumAccent
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow

private val PALETTE = com.vellum.notes.editor.PaperAesthetics.PAPER_INKS + listOf(
    0xFF000000, 0xFF424242, 0xFFFFFFFF, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF00ACC1, 0xFF8E24AA, 0xFFEC407A,
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
    val app = LocalContext.current.applicationContext as VellumApp
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

    // Page rail overlay + version history dialog state.
    var historyPageId by remember { mutableStateOf<Long?>(null) }
    // The page rail is a hideable overlay so the canvas stays full-screen for writing.
    var showRail by remember { mutableStateOf(false) }

    // The transcript sidebar is user-closable/openable: it starts open for classroom
    // notes (and whenever a recording/transcript exists) but the user can hide it to
    // reclaim the canvas and reopen it from the top bar. rememberSaveable keeps the
    // open/closed choice across configuration changes (rotation).
    var showTranscriptSidebar by rememberSaveable { mutableStateOf(true) }

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
    var summaryGenerating by remember { mutableStateOf(false) }

    // Mirror every recognized segment into the page content while this page owns the
    // transcript — while recording AND after stop (recordingPageId survives the stop) —
    // so autosave persists it, including the final segments the service flushes when the
    // capture thread ends. Undo does not apply to transcript updates.
    LaunchedEffect(transcript, pageId, recordingPageId) {
        if (recordingPageId == pageId) {
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
        run {
            if (!isClassroom) {
                classroomNotice = "Transcription is only available in classroom notebooks."
                return@run
            }
            if (isRecording && recordingPageId == pageId) {
                AudioCaptureService.stop(uiContext)
                // The service flushes the final partial into segments before clearing the
                // recording flag; the mirror LaunchedEffect persists the final transcript.
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
    }

    // ---- Wave-1 UI state: templates, text boxes, images ----
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var editingTextId by remember { mutableStateOf<Long?>(null) }

    val currentSummary = pageList.firstOrNull { it.id == pageId }
    val pageBackground = remember(pageId, currentSummary?.templateId, currentSummary?.background) {
        PaperTemplates.backgroundFor(currentSummary?.templateId).let { themed ->
            if (currentSummary?.background != PageBackground()) currentSummary?.background ?: themed else themed
        }
    }

    // Rasterized PDF page underlay for PDF-backed pages.
    val pdfPageBitmap = remember(pageId, currentSummary?.pdfBackgroundPath) {
        val path = currentSummary?.pdfBackgroundPath.orEmpty()
        if (path.isBlank()) null
        else runCatching {
            PdfImporter.resolveFile(uiContext, path)?.let { MediaLoader.decodeSampled(it, rgb565 = true) }
        }.getOrNull()
    }

    // Decoded image bitmaps for canvas rendering, keyed by fileRef.
    val imageBitmapCache = remember(content.imageObjects.map { it.fileRef }.joinToString("|")) {
        content.imageObjects.associate { im ->
            im.fileRef to runCatching {
                ImageStore.resolveFile(uiContext, im.fileRef)?.let { MediaLoader.decodeSampled(it) }
            }.getOrNull()
        }.filterValues { it != null } as Map<String, android.graphics.Bitmap>
    }

    // Photo picker for image insertion.
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileRef = ImageStore.importUri(uiContext, uri)
        if (fileRef == null) {
            android.widget.Toast.makeText(uiContext, "Could not import image", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val file = ImageStore.resolveFile(uiContext, fileRef)
        // Place at a sensible default: 60mm wide, centered in the current viewport.
        // Probe dimensions without a full decode (OOM-safe).
        val size = file?.let { MediaLoader.probeSize(it) }
        val aspect = if (size != null && size.first > 0) size.second.toFloat() / size.first else 0.75f
        val wMm = 60f
        val hMm = (wMm * aspect).coerceAtMost(160f)
        // Default placement: upper-center of the A4-width world (210mm wide).
        vm.addImage(
            com.vellum.notes.model.ImageObject(
                id = 0L,
                x = 105f - wMm / 2f,
                y = 80f - hMm / 2f,
                width = wMm,
                height = hMm,
                fileRef = fileRef,
            ),
        )
    }

    // ---- Page version history ----
    historyPageId?.let { hid ->
        VersionsDialog(
            pageId = hid,
            repository = repository,
            onDismiss = { historyPageId = null },
            onRestoredCurrentPage = {
                // The restored page is open: reload it into the canvas now.
                if (hid == pageId) vm.refreshContent()
            },
        )
    }

    // ---- Page template picker ----
    if (showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text("Page template") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PaperTemplates.ALL.forEach { t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setPageTemplate(t.id)
                                    showTemplateDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.GridOn,
                                contentDescription = null,
                                tint = if (currentSummary?.templateId == t.id) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(t.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTemplateDialog = false }) { Text("Done") }
            },
        )
    }

    // ---- Insert text box ----
    if (showTextDialog) {
        var textValue by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Insert text") },
            text = {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("Text") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (textValue.isNotBlank()) {
                        val wMm = 90f
                        val fontSize = 8f
                        vm.addText(
                            com.vellum.notes.model.TextObject(
                                id = 0L,
                                x = 105f - wMm / 2f,
                                y = 60f,
                                width = wMm,
                                height = fontSize * 1.35f * (textValue.lines().size + 2),
                                text = textValue,
                            ),
                        )
                    }
                    showTextDialog = false
                }) { Text("Insert") }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ---- Edit selected text box ----
    val editingText = editingTextId?.let { id -> content.textObjects.firstOrNull { it.id == id } }
    if (editingText != null) {
        var editValue by remember(editingText.id) { mutableStateOf(editingText.text) }
        AlertDialog(
            onDismissRequest = { editingTextId = null },
            title = { Text("Edit text") },
            text = {
                OutlinedTextField(
                    value = editValue,
                    onValueChange = { editValue = it },
                    label = { Text("Text") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editValue.isNotBlank()) {
                        vm.updateText(editingText.copy(text = editValue))
                    }
                    editingTextId = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingTextId = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold { padding ->
        // Export-to-PDF action shared by the floating action pill.
        val onExportPdf: () -> Unit = {
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
        }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Escape closes the transcript sidebar just like the close button.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        if (showTranscriptSidebar) {
                            showTranscriptSidebar = false
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
        ) {
            // Phones (narrow) get a slim page strip so the canvas keeps its width;
            // tablets/landscape get the full page rail.
            val compact = maxWidth < 600.dp

            // Classroom Notes transcript sidebar: a sibling of the canvas so it never
            // steals the pen's touches. Visible ONLY for classroom notes.
            val showTranscript = showTranscriptSidebar && isClassroom

            // Hardware/system back closes the transcript sidebar before leaving the note.
            BackHandler(enabled = showTranscript) {
                showTranscriptSidebar = false
            }

            Column(Modifier.fillMaxSize()) {
                // Floating pills up top (never under the palm at the bottom):
                // navigation, tools and page actions hover over the canvas.
                CanvasTopBar(
                    tool = tool,
                    canUndo = editorState!!.canUndo,
                    canRedo = editorState!!.canRedo,
                    onUndo = { vm.undo() },
                    onRedo = { vm.redo() },
                    onBack = onBack,
                    onToggleRail = { showRail = !showRail },
                    onExportPdf = onExportPdf,
                    isRecording = isRecording && recordingPageId == pageId,
                    onToggleClassroom = toggleClassroom,
                    transcriptAvailable = isClassroom,
                    onToggleTranscriptSidebar = { showTranscriptSidebar = !showTranscriptSidebar },
                    classroomEnabled = isClassroom,
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
                    onInsertText = { showTextDialog = true },
                    onInsertImage = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onPickTemplate = { showTemplateDialog = true },
                    canEditText = selectedIds.size == 1 &&
                        content.textObjects.any { it.id in selectedIds },
                    onEditText = {
                        selectedIds.firstOrNull()?.let { editingTextId = it }
                    },
                    canSmooth = content.strokes.any { it.id in selectedIds },
                    onSmoothSelection = { vm.smoothSelection() },
                    canConvert = content.strokes.any { it.id in selectedIds } ||
                        content.shapeObjects.any { it.id in selectedIds },
                    onConvertSelection = {
                        val newId = vm.convertSelectionToText()
                        if (newId != 0L) editingTextId = newId
                    },
                )
                Row(Modifier.weight(1f).fillMaxWidth()) {
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
                                view.images = content.imageObjects
                                view.texts = content.textObjects
                                view.imageBitmaps = imageBitmapCache
                                view.pdfBackground = pdfPageBitmap
                                view.selectionBoundsMm = editorState!!.selectionBoundsMm
                                view.listener = vm.canvasListener
                                view.autoEraseEnabled = settings.autoEraseEnabled
                                view.scribbleSensitivity = settings.scribbleSensitivity
                                view.debugOverlayEnabled = settings.debugOverlayEnabled
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
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            shape = RoundedCornerShape(20.dp),
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
                    // Left color rail: quick pen colors + widths hovering over the
                    // canvas edge, clear of the writing hand.
                    var paletteOpen by remember { mutableStateOf(false) }
                    ColorRail(
                        penStyle = penStyle,
                        onColor = { color ->
                            vm.setPenStyle(penStyle.copy(colorArgb = color))
                        },
                        onWidth = { w ->
                            vm.setPenStyle(penStyle.copy(widthMm = w))
                        },
                        onOpenPalette = { paletteOpen = !paletteOpen },
                        paletteOpen = paletteOpen,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    )
                    if (paletteOpen) {
                        Surface(
                            modifier = Modifier.align(Alignment.CenterStart)
                                .padding(start = 72.dp, end = 12.dp),
                            shape = RoundedCornerShape(20.dp),
                            tonalElevation = 3.dp,
                            shadowElevation = 2.dp,
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Colors", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(6.dp))
                                ColorRow(penStyle = penStyle, onColor = { color ->
                                    vm.setPenStyle(penStyle.copy(colorArgb = color))
                                })
                            }
                        }
                    }
                }

                // Classroom Notes transcript sidebar: a sibling of the canvas so it never
                // steals the pen's touches. Visible for classroom notes from the start
                // (live during recording, static when reopened), otherwise whenever a
                // recording session exists for this page.
                if (showTranscript) {
                    HorizontalDivider(
                        modifier = Modifier.width(1.dp).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    ClassroomSidebar(
                        segments = transcript,
                        partial = if (isRecording) partial else "",
                        isRecording = isRecording,
                        onToggleRecording = toggleClassroom,
                        onClose = { showTranscriptSidebar = false },
                        summary = content.summary,
                        summaryEnabled = transcript.isNotEmpty(),
                        summaryGenerating = summaryGenerating,
                        onGenerateSummary = {
                            run {
                                if (!isClassroom || summaryGenerating) return@run
                                scope.launch {
                                    summaryGenerating = true
                                    try {
                                        val result = withContext(Dispatchers.Default) {
                                            SummaryGenerator.summarize(transcript)
                                        }
                                        vm.setSummary(result)
                                    } catch (e: Exception) {
                                        classroomNotice = "Summary generation failed: ${e.message}"
                                    } finally {
                                        summaryGenerating = false
                                    }
                                }
                            }
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
                        onDuplicatePage = { id ->
                            scope.launch {
                                val newId = repository.duplicatePage(id)
                                selectedPageId = newId
                            }
                        },
                        onDeletePage = { id ->
                            scope.launch {
                                repository.deletePage(id)
                                if (id == selectedPageId) selectedPageId = null
                            }
                        },
                        onHistoryPage = { id -> historyPageId = id },
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
    onToggleRecording: () -> Unit,
    onClose: () -> Unit,
    summary: String?,
    summaryEnabled: Boolean,
    summaryGenerating: Boolean = false,
    onGenerateSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isRecording) "Classroom · recording" else "Classroom notes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Hide transcript sidebar",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (isRecording) {
                // A red "live" dot keeps the "still recording" state unmistakable at a glance.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
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
                            shape = RoundedCornerShape(20.dp),
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
                        onClick = onToggleRecording,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (isRecording) "Stop recording" else "Start recording")
                    }
                }
                1 -> {
                    if (summary != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(20.dp),
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
                        enabled = summaryEnabled && !summaryGenerating,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Summarize, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (summaryGenerating) "Generating…" else if (summary != null) "Regenerate summary" else "Generate summary")
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
            .padding(vertical = 14.dp),
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
private fun VersionsDialog(
    pageId: Long,
    repository: NotesRepository,
    onDismiss: () -> Unit,
    onRestoredCurrentPage: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val versions by repository.versionsForPage(pageId).collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page history") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Snapshots are saved when pages close and on demand. " +
                        "Restoring replaces the page (the replaced state is snapshotted first).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { repository.saveVersion(pageId) } }) {
                    Text("Snapshot now")
                }
                Spacer(Modifier.height(8.dp))
                if (versions.isEmpty()) {
                    Text("No snapshots yet.")
                }
                versions.forEach { v ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(
                            versionDateFormat.format(Date(v.createdAt)),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = {
                            scope.launch {
                                repository.restoreVersion(v.id)
                                onRestoredCurrentPage()
                            }
                        }) { Text("Restore") }
                        TextButton(onClick = {
                            scope.launch { repository.deleteVersion(v.id) }
                        }) { Text("Delete") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

private val versionDateFormat = SimpleDateFormat("MM/dd/yy, h:mm a", Locale.US)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PageRail(
    pages: List<PageSummary>,
    currentPageId: Long,
    compact: Boolean,
    onSelectPage: (Long) -> Unit,
    onNewPage: () -> Unit,
    onDuplicatePage: (Long) -> Unit = {},
    onDeletePage: (Long) -> Unit = {},
    onHistoryPage: (Long) -> Unit = {},
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
                        var menuOpen by remember(page.id) { mutableStateOf(false) }
                        Box {
                            if (compact) {
                                val isCurrent = page.id == currentPageId
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
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
                                        .combinedClickable(
                                            onClick = { onSelectPage(page.id) },
                                            onLongClick = { menuOpen = true },
                                        ),
                                )
                            } else {
                                PageThumbnail(
                                    page,
                                    selected = page.id == currentPageId,
                                    onClick = { onSelectPage(page.id) },
                                    onLongClick = { menuOpen = true },
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    onClick = { menuOpen = false; onDuplicatePage(page.id) },
                                )
                                DropdownMenuItem(
                                    text = { Text("History") },
                                    onClick = { menuOpen = false; onHistoryPage(page.id) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = { menuOpen = false; onDeletePage(page.id) },
                                )
                            }
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
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("New page")
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PageThumbnail(page: PageSummary, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
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
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            page.title,
            modifier = Modifier.padding(6.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
private fun CanvasTopBar(
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
    onInsertText: () -> Unit = {},
    onInsertImage: () -> Unit = {},
    onPickTemplate: () -> Unit = {},
    canEditText: Boolean = false,
    onEditText: () -> Unit = {},
    canSmooth: Boolean = false,
    onSmoothSelection: () -> Unit = {},
    canConvert: Boolean = false,
    onConvertSelection: () -> Unit = {},
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onBack: () -> Unit = {},
    onToggleRail: () -> Unit = {},
    onExportPdf: () -> Unit = {},
    isRecording: Boolean = false,
    onToggleClassroom: () -> Unit = {},
    transcriptAvailable: Boolean = false,
    onToggleTranscriptSidebar: () -> Unit = {},
    classroomEnabled: Boolean = false,
) {
    // Tapping the active pen/highlighter/eraser/shapes tool toggles its settings panel.
    var pickerOpen by remember { mutableStateOf(true) }
    fun stripClick(t: Tool) {
        if (t == tool && (t == Tool.PEN || t == Tool.HIGHLIGHTER || t == Tool.ERASER || t == Tool.SHAPES)) {
            pickerOpen = !pickerOpen
        } else {
            onTool(t)
            pickerOpen = true
        }
    }
    val showPicker = pickerOpen && (tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.ERASER || tool == Tool.SHAPES)
    Column(Modifier.fillMaxWidth()) {
        // Floating pills hovering over the canvas: navigation, tools, actions.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(onClick = onUndo, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = onRedo, enabled = canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
            ) {
                // Primary tool strip.
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                ToolButton(
                    label = "Pen",
                    selected = tool == Tool.PEN,
                    onClick = { stripClick(Tool.PEN) },
                    content = { Icon(Icons.Filled.BorderColor, contentDescription = "Pen") },
                )
                ToolButton(
                    label = "Highlighter",
                    selected = tool == Tool.HIGHLIGHTER,
                    onClick = { stripClick(Tool.HIGHLIGHTER) },
                    content = { Icon(Icons.Filled.Highlight, contentDescription = "Highlighter") },
                )
                ToolButton(
                    label = "Eraser",
                    selected = tool == Tool.ERASER,
                    onClick = { stripClick(Tool.ERASER) },
                    content = { Icon(Icons.Outlined.Circle, contentDescription = "Eraser") },
                )
                ToolButton(
                    label = "Select",
                    selected = tool == Tool.SELECT,
                    onClick = { stripClick(Tool.SELECT) },
                    content = { Icon(Icons.Filled.SelectAll, contentDescription = "Select") },
                )
                ToolButton(
                    label = "Shapes",
                    selected = tool == Tool.SHAPES,
                    onClick = { stripClick(Tool.SHAPES) },
                    content = { Icon(Icons.Filled.Category, contentDescription = "Shapes") },
                )
                ToolButton(
                    label = "Text",
                    selected = tool == Tool.TEXT,
                    onClick = onInsertText,
                    content = { Icon(Icons.Filled.TextFields, contentDescription = "Text box") },
                )
                ToolButton(
                    label = "Image",
                    selected = false,
                    onClick = onInsertImage,
                    content = { Icon(Icons.Filled.Image, contentDescription = "Insert image") },
                )
                ToolButton(
                    label = "Template",
                    selected = false,
                    onClick = onPickTemplate,
                    content = { Icon(Icons.Filled.GridOn, contentDescription = "Page template") },
                )

                // Feature 1: automatic write/erase detection. Off by default; when off the
                // pen/eraser behave exactly as before. Manual tool selection always wins.
                ToolButton(
                    label = "Auto-erase",
                    selected = autoEraseEnabled,
                    onClick = onAutoEraseToggle,
                    content = { Icon(Icons.Filled.AutoFixHigh, contentDescription = "Auto-erase") },
                )
                }
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onToggleRail) {
                        Icon(Icons.Filled.Menu, contentDescription = "Show or hide pages")
                    }
                    IconButton(onClick = onExportPdf) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = "Export PDF")
                    }
                    IconButton(
                        onClick = onToggleClassroom,
                        enabled = classroomEnabled,
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Classroom Notes (record & transcribe)",
                            tint = if (isRecording) MaterialTheme.colorScheme.error
                            else LocalContentColor.current,
                        )
                    }
                    IconButton(
                        onClick = onToggleTranscriptSidebar,
                        enabled = transcriptAvailable,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Article,
                            contentDescription = "Show or hide transcript",
                        )
                    }
                }
            }
        }

        // Context panel: settings for the active tool, or selection actions.
        if (showPicker || tool == Tool.SELECT) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
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
                                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(14.dp),
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
                                    .size(48.dp)
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
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(14.dp),
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
                                    .size(48.dp)
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
                            if (canEditText) {
                                TextButton(onClick = onEditText) {
                                    Icon(Icons.Filled.Edit, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Edit text")
                                }
                            }
                            if (canSmooth) {
                                TextButton(onClick = onSmoothSelection) {
                                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Smooth")
                                }
                            }
                            if (canConvert) {
                                TextButton(onClick = onConvertSelection) {
                                    Icon(Icons.Filled.Title, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Convert")
                                }
                            }
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
                    .size(48.dp)
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
                    .size(48.dp)
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
    // Nebo-style compact strip button: icon-only 48dp target, pill highlight +
    // accent underline for the active tool. Label stays as content description.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
        // Active tool indicator: 2dp accent underline (4dp spacing grid).
        Box(
            modifier = Modifier
                .size(width = 24.dp, height = 2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) VellumAccent else Color.Transparent)
        )
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

/** Quick pen colors + widths on a floating rail at the canvas edge. */
private val RAIL_COLORS = listOf(0xFF000000L, 0xFF1565C0L, 0xFFD32F2F)

private val RAIL_WIDTHS_MM = listOf(0.5f, 2.0f)

private val RAINBOW_BRUSH = Brush.sweepGradient(
    listOf(
        Color.Red, Color(0xFFFF9800), Color(0xFFFDD835), Color(0xFF43A047),
        Color(0xFF00ACC1), Color(0xFF3F51B5), Color(0xFF8E24AA), Color.Red,
    )
)

@Composable
private fun ColorRail(
    penStyle: PenStyle,
    onColor: (Long) -> Unit,
    onWidth: (Float) -> Unit,
    onOpenPalette: () -> Unit,
    paletteOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RAIL_COLORS.forEach { color ->
                RailDot(
                    selected = penStyle.colorArgb == color,
                    onClick = { onColor(color) },
                ) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(color)))
                }
            }
            // Full palette opener.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(RAINBOW_BRUSH)
                    .border(
                        width = if (paletteOpen) 3.dp else 1.dp,
                        color = if (paletteOpen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .clickable(onClick = onOpenPalette),
            )
            HorizontalDivider(Modifier.width(24.dp))
            RAIL_WIDTHS_MM.forEach { w ->
                RailDot(
                    selected = kotlin.math.abs(penStyle.widthMm - w) < 0.2f,
                    onClick = { onWidth(w) },
                ) {
                    Box(
                        Modifier.size((10 + w * 6).toInt().coerceAtMost(26).dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface)
                    )
                }
            }
        }
    }
}

@Composable
private fun RailDot(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}