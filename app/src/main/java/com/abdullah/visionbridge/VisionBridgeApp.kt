package com.abdullah.visionbridge

import android.app.Application
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.di.AppContainer

class VisionBridgeApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        DiagnosticHub.initialize(container.diagnostics)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            container.diagnostics.recordFatalBlocking(error)
            previous?.uncaughtException(thread, error)
        }
    }
}
