package com.abdullah.visionbridge

import android.app.Application
import com.abdullah.visionbridge.data.diagnostics.DiagnosticsHub
import com.abdullah.visionbridge.di.AppContainer

class VisionBridgeApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        DiagnosticsHub.initialize(container.diagnostics)
    }
}
