package com.premiumnotes.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.RectF
import com.premiumnotes.data.NotesRepository
import com.premiumnotes.editor.AddStrokeCommand
import com.premiumnotes.editor.NoteEditorState
import com.premiumnotes.editor.Tool
import com.premiumnotes.model.PageContent
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.Stroke
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

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

        override fun onEraseAt(x: Float, y: Float, radiusMm: Float) {
            _editor.value?.eraseAt(x, y, radiusMm)
        }

        override fun onEraseAlong(x1: Float, y1: Float, x2: Float, y2: Float, radiusMm: Float) {
            _editor.value?.eraseAlong(x1, y1, x2, y2, radiusMm)
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
    }

    fun setTool(tool: Tool) = _editor.value?.setTool(tool)
    fun setPenStyle(style: PenStyle) = _editor.value?.setPenStyle(style)
    fun setEraserSize(sizeMm: Float) = _editor.value?.setEraserSize(sizeMm)
    fun undo() = _editor.value?.undo()
    fun redo() = _editor.value?.redo()

    fun selectAll() = _editor.value?.selectAll()
    fun deleteSelection() = _editor.value?.deleteSelection()
    fun duplicateSelection() = _editor.value?.duplicateSelection()
    fun clearSelection() = _editor.value?.clearSelection()

    override fun onCleared() {
        saveJob?.cancel()
    }
}