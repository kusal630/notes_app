package com.vellum.notes

import android.app.Application
import com.vellum.notes.data.NotesRepository
import com.vellum.notes.data.SettingsRepository
import com.vellum.notes.data.SyncRepository
import com.vellum.notes.data.createRepository
import com.vellum.notes.data.settingsDataStore
import com.vellum.notes.data.syncDataStore
import com.vellum.notes.input.PalmRejectionEngine
import com.vellum.notes.input.InputCapabilities
import com.vellum.notes.input.PalmRejectionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Minimal application-scoped container for manual dependency injection.
 * Avoids heavy DI frameworks for the core graph while keeping systems testable.
 */
class AppContainer(private val application: Application) {

    private val dataStore = application.settingsDataStore()
    val settingsRepository = SettingsRepository(dataStore)
    val syncRepository = SyncRepository(application.syncDataStore())

    /** Latest persisted settings, cached for synchronous reads by the input engine. */
    @Volatile
    var currentSettings: PalmRejectionSettings = PalmRejectionSettings()
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        appScope.launch {
            settingsRepository.settingsFlow.collect { currentSettings = it }
        }
    }

    val inputCapabilities: InputCapabilities by lazy {
        InputCapabilities.detect(application)
    }

    val palmRejectionSettingsFlow: Flow<PalmRejectionSettings> = settingsRepository.settingsFlow

    val palmRejectionEngine: PalmRejectionEngine by lazy {
        PalmRejectionEngine(inputCapabilities) { currentSettings }
    }

    val notesRepository: NotesRepository by lazy {
        createRepository(application)
    }
}