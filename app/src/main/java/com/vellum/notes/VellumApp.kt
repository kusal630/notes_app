package com.vellum.notes

import android.app.Application

class VellumApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Hardened background sync: periodic folder verification with
        // exponential-backoff retries. Best effort — never crash startup.
        runCatching { container.syncRepository.schedulePeriodicSync(this) }
    }
}
