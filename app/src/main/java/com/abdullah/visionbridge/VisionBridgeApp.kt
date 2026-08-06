package com.abdullah.visionbridge

import android.app.Application
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VisionBridgeApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        DiagnosticHub.initialize(container.diagnostics)

        // Applied here rather than only inside the capture service, because the accessibility
        // shortcut can be pressed while nothing is capturing, and because a stored "on" has to
        // survive the process being restarted between the moment it was switched on and the moment
        // the failure happens.
        scope.launch {
            container.settingsRepository.settings
                .map { it.captureFailureEvidence }
                .distinctUntilChanged()
                .collect { enabled -> DiagnosticHub.setEvidenceCapture(enabled) }
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DiagnosticHub.recordFatalBlocking(error)
            previous?.uncaughtException(thread, error)
        }
    }
}
