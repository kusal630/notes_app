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

    /**
     * Page the current transcript belongs to. Set when a recording starts and kept after
     * the recording stops (it is only replaced when a new recording begins) so the editor
     * keeps mirroring late-arriving final segments into the page after the service stops.
     * Null when no recording has ever been started (or a previously-loaded transcript has
     * been reattached via [setSegments]).
     */
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

    /**
     * Stops a session: flushes the live partial hypothesis into a final segment so the
     * last spoken phrase is never lost, clears the recording flag and keeps the transcript
     * attached to its page (so late-arriving final segments are still mirrored into the
     * page content until a new recording begins).
     *
     * [elapsedMs] is the time since the recording started (segments are timestamped
     * relative to the start); when omitted the flush falls back to the wall clock.
     */
    fun endRecording(elapsedMs: Long? = null) {
        flushPartial(elapsedMs)
        _isRecording.value = false
        _partial.value = ""
    }

    /** Flushes the current non-blank partial hypothesis as a finalized segment. */
    private fun flushPartial(elapsedMs: Long?) {
        val text = _partial.value.trim()
        if (text.isEmpty()) return
        val endMs = elapsedMs ?: System.currentTimeMillis()
        _segments.value = _segments.value + TranscriptSegment(
            id = nextSegmentId.incrementAndGet(),
            startMs = (_segments.value.lastOrNull()?.endMs ?: 0L).coerceAtLeast(0L),
            endMs = endMs,
            text = text,
        )
    }

    /** Replaces the whole transcript (used when reopening a saved page). */
    fun setSegments(segments: List<TranscriptSegment>) {
        _segments.value = segments
        nextSegmentId.set(segments.maxOfOrNull { it.id } ?: 0L)
    }
}