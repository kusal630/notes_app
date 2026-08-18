package com.premiumnotes.speech

import com.premiumnotes.model.TranscriptSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * App-scoped holder for Classroom Notes transcription state. The audio service writes
 * segments here on its background thread; the editor UI collects them and persists them
 * into the page's [com.premiumnotes.model.PageContent]. Keeping this outside the
 * ViewModel means the recording survives the editor being recreated (config change,
 * page navigation) without losing already-recognized text.
 */
object SpeechController {

    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val segments: StateFlow<List<TranscriptSegment>> = _segments.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** Page the current recording is attached to (cleared on stop). */
    private val _recordingPageId = MutableStateFlow<Long?>(null)
    val recordingPageId: StateFlow<Long?> = _recordingPageId.asStateFlow()

    private val nextSegmentId = AtomicLong(0)

    /** Starts a fresh recording session for [pageId] (replaces any previous transcript). */
    fun beginRecording(pageId: Long) {
        _segments.value = emptyList()
        _partial.value = ""
        _recordingPageId.value = pageId
        _isRecording.value = true
    }

    /** Appends a finalized speech segment (id allocated here). */
    fun addSegment(text: String, startMs: Long, endMs: Long) {
        if (text.isBlank()) return
        _segments.value = _segments.value + TranscriptSegment(
            id = nextSegmentId.incrementAndGet(),
            startMs = startMs,
            endMs = endMs,
            text = text.trim(),
        )
    }

    /** Updates the live (unfinalized) partial hypothesis. */
    fun setPartial(text: String) {
        _partial.value = text
    }

    /** Stops a session; keeps the accumulated transcript for the page it was attached to. */
    fun endRecording() {
        _isRecording.value = false
        _partial.value = ""
        _recordingPageId.value = null
    }

    /** Replaces the whole transcript (used when reopening a saved page). */
    fun setSegments(segments: List<TranscriptSegment>) {
        _segments.value = segments
        nextSegmentId.set(segments.maxOfOrNull { it.id } ?: 0L)
    }
}