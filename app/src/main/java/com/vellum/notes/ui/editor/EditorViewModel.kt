package com.vellum.notes.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.RectF
import com.vellum.notes.data.NotesRepository
import com.vellum.notes.editor.AddShapeCommand
import com.vellum.notes.editor.AddStrokeCommand
import com.vellum.notes.editor.NoteEditorState
import com.vellum.notes.editor.Tool
import com.vellum.notes.model.PageContent
import com.vellum.notes.model.PenStyle
import com.vellum.notes.model.ShapeKind
import com.vellum.notes.model.ShapeObject
import com.vellum.notes.model.Stroke
import com.vellum.notes.speech.SpeechController
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock

/**
 * Editor ViewModel: loads a page's content into a [NoteEditorState], exposes it to the
 * UI, forwards canvas events into the command stack, and autosaves incrementally on a
 * background dispatcher (debounced, never on the main thread).
 */
@OptIn(FlowPreview::class)
class EditorViewModel(
    private val pageId: Long,
    private val repository: NotesRepository,
) : ViewModel() {

    private val _editor = MutableStateFlow<NoteEditorState?>(null)
    val editor: StateFlow<NoteEditorState?> = _editor.asStateFlow()

    private var saveJob: Job? = null
    /** Serializes Room writes so rapid edits can never interleave or starve each other. */
    private val saveMutex = kotlinx.coroutines.sync.Mutex()

    init {
        viewModelScope.launch {
            val content = repository.loadPageContent(pageId) ?: PageContent()
            val state = NoteEditorState(content)
            _editor.value = state
            // Reopening a classroom note shows its saved transcript in the sidebar (static).
            SpeechController.setSegments(content.transcript)

            // Incremental autosave with debounce — no per-point DB writes.
            viewModelScope.launch {
                state.content
                    .debounce(700)
                    .collectLatest { c ->
                        saveJob?.cancel()
                        saveJob = viewModelScope.launch {
                            saveMutex.withLock {
                                runCatching { repository.savePageContent(pageId, c) }
                            }
                        }
                    }
            }
        }
    }

    val canvasListener = object : InkCanvasView.Listener {
        override fun onStrokeCommitted(stroke: Stroke) {
            _editor.value?.apply(AddStrokeCommand(stroke))
        }

        override fun onShapeCommitted(shape: ShapeObject) {
            _editor.value?.addShape(shape)
            // Select the shape right after drawing so its move/resize handles appear
            // immediately — the user can adjust it without switching to the select tool.
            val a = shape.points.getOrNull(0) ?: return
            val b = shape.points.getOrNull(1) ?: return
            _editor.value?.selectAt((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        }

        override fun onEraseGestureBegin() {
            _editor.value?.eraseGestureBegin()
        }

        override fun onEraseAt(x: Float, y: Float, radiusMm: Float) {
            _editor.value?.eraseAt(x, y, radiusMm)
        }

        override fun onEraseAlong(x1: Float, y1: Float, x2: Float, y2: Float, radiusMm: Float) {
            _editor.value?.eraseAlong(x1, y1, x2, y2, radiusMm)
        }

        override fun onScribbleWordErase(minX: Float, minY: Float, maxX: Float, maxY: Float, marginMm: Float) {
            _editor.value?.eraseInRect(minX, minY, maxX, maxY, marginMm)        }

        override fun onEraseGestureEnd() {
            _editor.value?.eraseGestureEnd()
        }

        override fun onViewportChanged(zoom: Float, offsetX: Float, offsetY: Float) {
            // Viewport is persisted with the page in a later milestone.
        }

        override fun onSelectInRect(rect: RectF) {
            _editor.value?.selectInRect(rect)
        }

        override fun onSelectionDragStart(worldX: Float, worldY: Float) {
            _editor.value?.beginMoveSelection(worldX, worldY)
        }

        override fun onSelectionDragTo(worldX: Float, worldY: Float) {
            _editor.value?.moveSelectionTo(worldX, worldY)
        }

        override fun onSelectionDragEnd() {
            _editor.value?.endMoveSelection()
        }

        override fun onSelectionResizeStart(handleIndex: Int) {
            _editor.value?.beginResizeSelection(handleIndex)
        }

        override fun onSelectionResizeTo(worldX: Float, worldY: Float) {
            _editor.value?.resizeSelectionTo(worldX, worldY)
        }

        override fun onSelectionResizeEnd() {
            _editor.value?.endResizeSelection()
        }
    }

    fun setTool(tool: Tool) = _editor.value?.setTool(tool)
    fun setPenStyle(style: PenStyle) = _editor.value?.setPenStyle(style)
    fun setEraserSize(sizeMm: Float) = _editor.value?.setEraserSize(sizeMm)
    fun setShapeKind(kind: ShapeKind) = _editor.value?.setShapeKind(kind)
    fun addImage(image: com.vellum.notes.model.ImageObject) {
        val editor = _editor.value ?: return
        editor.addImage(image)
        // Select the new image so move/resize handles appear immediately.
        val id = editor.content.value.imageObjects.maxByOrNull { it.id }?.id
        if (id != null) editor.selectAt(
            editor.content.value.imageObjects.first { it.id == id }.let { it.x + it.width / 2f },
            editor.content.value.imageObjects.first { it.id == id }.let { it.y + it.height / 2f },
        )
    }

    fun addText(text: com.vellum.notes.model.TextObject) {
        val editor = _editor.value ?: return
        editor.addText(text)
        // Select the new text so move/resize + Edit action are immediately available.
        val obj = editor.content.value.textObjects.maxByOrNull { it.id }
        if (obj != null) editor.selectAt(obj.x + obj.width / 2f, obj.y + obj.height / 2f)
    }
    fun updateText(updated: com.vellum.notes.model.TextObject) = _editor.value?.updateText(updated)

    /** Sets this page's paper template and persists it. */
    fun setPageTemplate(templateId: String) {
        viewModelScope.launch { repository.setPageTemplate(pageId, templateId) }
    }
    fun setTranscript(segments: List<com.vellum.notes.model.TranscriptSegment>) =
        _editor.value?.setTranscript(segments)
    fun setSummary(summary: String?) = _editor.value?.setSummary(summary)
    fun undo() = _editor.value?.undo()
    fun redo() = _editor.value?.redo()

    fun selectAll() = _editor.value?.selectAll()
    fun deleteSelection() = _editor.value?.deleteSelection()
    fun duplicateSelection() = _editor.value?.duplicateSelection()
    fun smoothSelection() = _editor.value?.smoothSelection()
    fun clearSelection() = _editor.value?.clearSelection()

    override fun onCleared() {
        saveJob?.cancel()
        // Flush the latest content synchronously so work done just before navigating away
        // (back, page switch, process recreation) is never lost. IO dispatcher: never blocks
        // the main thread's Looper, avoiding the ANR the old main-thread flush risked.
        val pending = _editor.value?.content?.value
        if (pending != null) {
            runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { repository.savePageContent(pageId, pending) }
            }
        }
    }
}