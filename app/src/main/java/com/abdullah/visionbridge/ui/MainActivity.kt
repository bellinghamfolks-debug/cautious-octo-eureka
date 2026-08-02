package com.abdullah.visionbridge.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abdullah.visionbridge.capture.MediaProjectionService
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.ui.theme.VisionBridgeTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val projectionManager by lazy { getSystemService(MediaProjectionManager::class.java) }
    private var startCaptureAfterNotificationPermission = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            DiagnosticHub.record("SCREEN_CAPTURE_PERMISSION_GRANTED")
            ContextCompat.startForegroundService(
                this@MainActivity,
                MediaProjectionService.startIntent(this@MainActivity, result.resultCode, data),
            )
        } else {
            DiagnosticHub.record("SCREEN_CAPTURE_PERMISSION_DENIED", mapOf("resultCode" to result.resultCode))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        DiagnosticHub.record("NOTIFICATION_PERMISSION_RESULT", mapOf("granted" to granted))
        if (startCaptureAfterNotificationPermission) {
            startCaptureAfterNotificationPermission = false
            launchProjectionConsent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticHub.record(
            "MAIN_ACTIVITY_CREATED",
            mapOf(
                "automaticDiagnostics" to true,
                "manualProblemMarkerRequired" to false,
                "storesImages" to false,
            ),
        )
        setContent {
            val state = viewModel.uiState.collectAsStateWithLifecycle().value
            var showSettings by rememberSaveable { mutableStateOf(false) }
            VisionBridgeTheme {
                if (showSettings) {
                    SettingsScreen(
                        state = state,
                        onSaveApiKey = viewModel::saveApiKey,
                        onDeleteApiKey = viewModel::deleteApiKey,
                        onModelChange = viewModel::setModel,
                        onForceCellularChange = viewModel::setForceCellular,
                        onSpeechChange = viewModel::setSpeechEnabled,
                        onTrustGateChange = viewModel::setTrustGateEnabled,
                        onCaptureProfileChange = viewModel::setCaptureProfile,
                        onInterruptSpeechChange = viewModel::setInterruptSpeechOnVisualChange,
                        onSceneDescriptionStyleChange = viewModel::setSceneDescriptionStyle,
                        onSpeechRateChange = viewModel::setSpeechRate,
                        onUseLocalOcrChange = viewModel::setUseLocalOcr,
                        onLocalReadingQualityChange = viewModel::setLocalReadingQuality,
                        onExportDiagnostics = {
                            viewModel.exportDiagnostics { file ->
                                this@MainActivity.shareDiagnosticFile(file)
                            }
                        },
                        onBack = { showSettings = false },
                        onMessageConsumed = viewModel::clearMessage,
                    )
                } else {
                    MainScreen(
                        state = state,
                        onModeChange = viewModel::setMode,
                        onOpenSettings = { showSettings = true },
                        onStartCapture = { this@MainActivity.requestCapture() },
                        onStopCapture = {
                            DiagnosticHub.record("USER_STOP_CAPTURE")
                            this@MainActivity.startService(
                                MediaProjectionService.stopIntent(this@MainActivity)
                            )
                        },
                        onMessageConsumed = viewModel::clearMessage,
                    )
                }
            }
        }
    }

    private fun shareDiagnosticFile(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                this@MainActivity,
                "$packageName.diagnostics",
                file,
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "VisionBridge automatic image-free diagnostics")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "ملف تشخيص VisionBridge من دون صور. يتضمن الخط الزمني، والبصمات البصرية غير القابلة لإعادة بناء الصورة، ونتائج OCR وGemini، وحالة النطق، والتوقيتات.",
                )
                clipData = ClipData.newRawUri("VisionBridge diagnostics", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            DiagnosticHub.record(
                "DIAGNOSTIC_SHARE_SHEET_OPENED",
                mapOf(
                    "fileName" to file.name,
                    "fileBytes" to file.length(),
                    "includesImages" to false,
                    "automaticContinuousRecording" to true,
                    "manualProblemMarkerRequired" to false,
                ),
            )
            this@MainActivity.startActivity(
                Intent.createChooser(share, "مشاركة ملف تشخيص VisionBridge")
            )
        }.onFailure { error ->
            DiagnosticHub.failure("DIAGNOSTIC_SHARE", error)
            Toast.makeText(
                this@MainActivity,
                error.message ?: "تعذرت مشاركة ملف التشخيص",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun requestCapture() {
        DiagnosticHub.record("USER_REQUEST_CAPTURE")
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            startCaptureAfterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchProjectionConsent()
        }
    }

    private fun launchProjectionConsent() {
        DiagnosticHub.record("SCREEN_CAPTURE_PERMISSION_PROMPT_OPENED")
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
