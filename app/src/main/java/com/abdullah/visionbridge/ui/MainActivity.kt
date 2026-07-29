package com.abdullah.visionbridge.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.abdullah.visionbridge.VisionBridgeApp
import com.abdullah.visionbridge.capture.MediaProjectionService
import com.abdullah.visionbridge.data.diagnostics.DiagnosticsHub
import com.abdullah.visionbridge.ui.theme.VisionBridgeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val projectionManager by lazy { getSystemService(MediaProjectionManager::class.java) }
    private val container by lazy { (application as VisionBridgeApp).container }
    private var startCaptureAfterNotificationPermission = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            DiagnosticsHub.stage("SCREEN_CAPTURE_PERMISSION_GRANTED")
            ContextCompat.startForegroundService(
                this,
                MediaProjectionService.startIntent(this, result.resultCode, data),
            )
        } else {
            DiagnosticsHub.stage("SCREEN_CAPTURE_PERMISSION_DENIED")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        DiagnosticsHub.stage("NOTIFICATION_PERMISSION_RESULT", mapOf("granted" to it))
        if (startCaptureAfterNotificationPermission) {
            startCaptureAfterNotificationPermission = false
            launchProjectionConsent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsHub.stage("MAIN_ACTIVITY_CREATED")
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
                        onStartCapture = ::requestCapture,
                        onStopCapture = {
                            DiagnosticsHub.stage("USER_STOP_CAPTURE")
                            startService(MediaProjectionService.stopIntent(this))
                        },
                        onMessageConsumed = viewModel::clearMessage,
                    )
                    ExtendedFloatingActionButton(
                        onClick = ::showDiagnosticPrivacyPrompt,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .semantics {
                                contentDescription = "مشاركة ملف التشخيص، مع اختيار تضمين الصور أو استبعادها"
                            },
                        text = { Text("مشاركة التشخيص") },
                    )
                }
            }
        }
    }

    private fun showDiagnosticPrivacyPrompt() {
        DiagnosticsHub.stage("DIAGNOSTIC_EXPORT_PROMPT_OPENED")
        AlertDialog.Builder(this)
            .setTitle("مشاركة ملف التشخيص")
            .setMessage(
                "هل تريد تضمين صور بث النظارة؟ الصور تساعد على تشخيص عدم قراءة العطر أو عدم وصف المشهد، لكنها قد تحتوي معلومات خاصة. مفتاح Gemini لا يُضمّن أبداً."
            )
            .setPositiveButton("تضمين الصور") { _, _ -> exportDiagnostics(includeImages = true) }
            .setNegativeButton("بدون صور") { _, _ -> exportDiagnostics(includeImages = false) }
            .setNeutralButton("إلغاء", null)
            .show()
    }

    private fun exportDiagnostics(includeImages: Boolean) {
        lifecycleScope.launch {
            runCatching {
                DiagnosticsHub.stage("DIAGNOSTIC_EXPORT_REQUESTED", mapOf("includeImages" to includeImages))
                container.diagnostics.export(includeImages)
            }.onSuccess { file ->
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "$packageName.diagnostics",
                    file,
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "VisionBridge diagnostic black box")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "ملف تشخيص VisionBridge. يتضمن الصور: ${if (includeImages) "نعم" else "لا"}."
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "مشاركة ملف التشخيص"))
            }.onFailure { error ->
                DiagnosticsHub.failure("DIAGNOSTIC_EXPORT", error)
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "تعذر إنشاء ملف التشخيص",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun requestCapture() {
        DiagnosticsHub.stage("USER_REQUEST_CAPTURE")
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
