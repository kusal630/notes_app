package com.premiumnotes.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.RectF
import com.premiumnotes.data.NotesRepository
import com.premiumnotes.editor.AddShapeCommand
import com.premiumnotes.editor.AddStrokeCommand
import com.premiumnotes.editor.NoteEditorState
import com.premiumnotes.editor.Tool
import com.premiumnotes.model.PageContent
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.ShapeKind
import com.premiumnotes.model.ShapeObject
import com.premiumnotes.model.Stroke
import com.premiumnotes.speech.SpeechController
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
                            repository.savePageContent(pageId, c)
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
    fun setTranscript(segments: List<com.premiumnotes.model.TranscriptSegment>) =
        _editor.value?.setTranscript(segments)
    fun undo() = _editor.value?.undo()
    fun redo() = _editor.value?.redo()

    fun selectAll() = _editor.value?.selectAll()
    fun deleteSelection() = _editor.value?.deleteSelection()
    fun duplicateSelection() = _editor.value?.duplicateSelection()
    fun clearSelection() = _editor.value?.clearSelection()

    override fun onCleared() {
        saveJob?.cancel()
        // Flush the latest content synchronously so work done just before navigating away
        // (back, page switch, process recreation) is never lost.
        val pending = _editor.value?.content?.value
        if (pending != null) {
            runBlocking { repository.savePageContent(pageId, pending) }
        }
    }
}