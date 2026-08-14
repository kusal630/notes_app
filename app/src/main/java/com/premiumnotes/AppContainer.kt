package com.premiumnotes

import android.app.Application
import com.premiumnotes.data.NotesRepository
import com.premiumnotes.data.createRepository
import com.premiumnotes.input.PalmRejectionSettings
import com.premiumnotes.input.PalmRejectionEngine
import com.premiumnotes.input.InputCapabilities

/**
 * Minimal application-scoped container for manual dependency injection.
 * Avoids heavy DI frameworks for the core graph while keeping systems testable.
 */
class AppContainer(private val application: Application) {

    val inputCapabilities: InputCapabilities by lazy {
        InputCapabilities.detect(application)
    }

    val palmRejectionSettings: PalmRejectionSettings by lazy {
        PalmRejectionSettings()
    }

    val palmRejectionEngine: PalmRejectionEngine by lazy {
        PalmRejectionEngine(inputCapabilities, palmRejectionSettings)
    }

    val notesRepository: NotesRepository by lazy {
        createRepository(application)
    }
}