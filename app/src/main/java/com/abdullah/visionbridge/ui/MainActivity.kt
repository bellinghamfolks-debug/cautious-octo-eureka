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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
            VisionBridgeTheme {
                Box(Modifier.fillMaxSize()) {
                    MainScreen(
                        state = state,
                        onSaveApiKey = viewModel::saveApiKey,
                        onDeleteApiKey = viewModel::deleteApiKey,
                        onModeChange = viewModel::setMode,
                        onModelChange = viewModel::setModel,
                        onForceCellularChange = viewModel::setForceCellular,
                        onSpeechChange = viewModel::setSpeechEnabled,
                        onLocalOcrChange = viewModel::setLocalOcrEnabled,
                        onTrustGateChange = viewModel::setTrustGateEnabled,
                        onCaptureProfileChange = viewModel::setCaptureProfile,
                        onInterruptSpeechChange = viewModel::setInterruptSpeechOnVisualChange,
                        onSceneDescriptionStyleChange = viewModel::setSceneDescriptionStyle,
                        onSpeechRateChange = viewModel::setSpeechRate,
                        onStartCapture = { this@MainActivity.requestCapture() },
                        onStopCapture = {
                            DiagnosticHub.record("USER_STOP_CAPTURE")
                            this@MainActivity.startService(
                                MediaProjectionService.stopIntent(this@MainActivity)
                            )
                        },
                        onMessageConsumed = viewModel::clearMessage,
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            "التشخيص يسجل تلقائياً بلا صور",
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "نظام التشخيص التلقائي يعمل باستمرار ولا يحتاج تعليم لحظة المشكلة، ولا يحفظ صور الشاشة"
                            },
                        )
                        Button(
                            onClick = {
                                viewModel.exportDiagnostics { file ->
                                    this@MainActivity.shareDiagnosticFile(file)
                                }
                            },
                            modifier = Modifier
                                .height(56.dp)
                                .semantics {
                                    contentDescription =
                                        "مشاركة سجل التشخيص التلقائي الشامل، بلا صور، مع البصمات البصرية والتوقيتات والنصوص"
                                },
                        ) {
                            Text("مشاركة التشخيص التلقائي")
                        }
                    }
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
                    "حزمة تشخيص رفيق الرؤية التلقائية الشاملة. لا تحتوي صوراً، وتضم الخط الزمني الكامل والبصمات البصرية غير القابلة لإعادة البناء ونتائج OCR وGemini والنطق والتوقيتات والتحليل الآلي لكل لقطة.",
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
                Intent.createChooser(share, "مشاركة التشخيص التلقائي الشامل")
            )
        }.onFailure { error ->
            DiagnosticHub.failure("DIAGNOSTIC_SHARE", error)
            Toast.makeText(
                this@MainActivity,
                error.message ?: "تعذر مشاركة ملف التشخيص",
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
