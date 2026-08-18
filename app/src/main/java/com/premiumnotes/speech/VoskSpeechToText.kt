package com.premiumnotes.speech

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Thin wrapper around the Vosk offline recognizer for Classroom Notes. All recognition
 * happens on-device with no network access of any kind (FOSS Apache-2.0 toolkit; the
 * model is bundled at build time or installed manually).
 */
class VoskSpeechToText(context: Context) {

    /** One recognition step's outcome. */
    data class Result(
        /** Live partial hypothesis; display it, don't persist it. */
        val partial: String,
        /** Finalized sentence/segment, if the recognizer committed one now. */
        val final: String?,
    )

    private val model: Model
    private val recognizer: Recognizer

    init {
        val path = ModelDiscovery.resolve(context)
            ?: error("Vosk model not found — resolve() must be checked before creating the recognizer")
        // The Android Vosk Model only accepts a real filesystem path; ModelDiscovery
        // hands back app-private storage (extracting the bundled asset on first use).
        model = Model(path)
        recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
    }

    /** Feeds one chunk of PCM16 mono audio and returns partial + any finalized text. */
    @Synchronized
    fun accept(shortBuf: ShortArray, len: Int): Result {
        val hasFinal = recognizer.acceptWaveForm(shortBuf, len)
        val partial = runCatching { JSONObject(recognizer.getPartialResult()) }
            .getOrNull()?.optString("partial") ?: ""
        if (!hasFinal) return Result(partial, null)
        val text = runCatching { JSONObject(recognizer.getResult()) }
            .getOrNull()?.optString("text") ?: ""
        return Result(partial, text)
    }

    @Synchronized
    fun reset() = recognizer.reset()

    @Synchronized
    fun close() {
        recognizer.close()
        model.close()
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}