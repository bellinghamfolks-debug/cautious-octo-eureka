package com.abdullah.visionbridge

import android.app.Application
import com.abdullah.visionbridge.di.AppContainer

class VisionBridgeApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
