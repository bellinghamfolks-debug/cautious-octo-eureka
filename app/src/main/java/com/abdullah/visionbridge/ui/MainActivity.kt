package com.abdullah.visionbridge.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abdullah.visionbridge.capture.MediaProjectionService
import com.abdullah.visionbridge.ui.theme.VisionBridgeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val projectionManager by lazy { getSystemService(MediaProjectionManager::class.java) }
    private var startCaptureAfterNotificationPermission = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this,
                MediaProjectionService.startIntent(this, result.resultCode, data),
            )
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (startCaptureAfterNotificationPermission) {
            startCaptureAfterNotificationPermission = false
            launchProjectionConsent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state = viewModel.uiState.collectAsStateWithLifecycle().value
            VisionBridgeTheme {
                MainScreen(
                    state = state,
                    onSaveApiKey = viewModel::saveApiKey,
                    onDeleteApiKey = viewModel::deleteApiKey,
                    onModeChange = viewModel::setMode,
                    onModelChange = viewModel::setModel,
                    onForceCellularChange = viewModel::setForceCellular,
                    onSpeechChange = viewModel::setSpeechEnabled,
                    onLocalOcrChange = viewModel::setLocalOcrEnabled,
                    onStartCapture = ::requestCapture,
                    onStopCapture = {
                        startService(MediaProjectionService.stopIntent(this))
                    },
                    onMessageConsumed = viewModel::clearMessage,
                )
            }
        }
    }

    private fun requestCapture() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            startCaptureAfterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchProjectionConsent()
        }
    }

    private fun launchProjectionConsent() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
