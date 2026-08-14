package com.premiumnotes

import android.app.Application
import com.premiumnotes.data.NotesRepository
import com.premiumnotes.data.SettingsRepository
import com.premiumnotes.data.createRepository
import com.premiumnotes.data.settingsDataStore
import com.premiumnotes.input.PalmRejectionEngine
import com.premiumnotes.input.InputCapabilities
import com.premiumnotes.input.PalmRejectionSettings
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