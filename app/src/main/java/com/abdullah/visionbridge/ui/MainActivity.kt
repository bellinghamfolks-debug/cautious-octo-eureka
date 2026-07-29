package com.abdullah.visionbridge.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
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
        DiagnosticHub.record("MAIN_ACTIVITY_CREATED")
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
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Button(
                            onClick = { this@MainActivity.showProblemMarkerDialog() },
                            modifier = Modifier
                                .height(56.dp)
                                .semantics {
                                    contentDescription = "تعليم لحظة حدوث مشكلة في القراءة أو وصف المشهد داخل ملف التشخيص"
                                },
                        ) {
                            Text("حدثت مشكلة الآن")
                        }
                        Button(
                            onClick = {
                                viewModel.exportDiagnostics { file ->
                                    this@MainActivity.shareDiagnosticFile(file)
                                }
                            },
                            modifier = Modifier
                                .height(56.dp)
                                .semantics {
                                    contentDescription = "مشاركة حزمة تشخيص ذكية صغيرة تركز على آخر مشكلة وصورها القريبة"
                                },
                        ) {
                            Text("مشاركة التشخيص")
                        }
                    }
                }
            }
        }
    }

    private fun showProblemMarkerDialog() {
        DiagnosticHub.record("PROBLEM_MARKER_DIALOG_OPENED")
        val note = EditText(this@MainActivity).apply {
            hint = "مثال: صورت عطراً ولم يقرأه، أو لم يصف المشهد"
            minLines = 2
            contentDescription = "ملاحظة اختيارية تصف المشكلة التي حدثت الآن"
        }
        AlertDialog.Builder(this@MainActivity)
            .setTitle("تعليم لحظة المشكلة")
            .setMessage("اكتب ملاحظة اختيارية، ثم اضغط حفظ. سيُربط التوقيت بأقرب الصور والنصوص والأحداث.")
            .setView(note)
            .setPositiveButton("حفظ العلامة") { _, _ ->
                val value = note.text?.toString().orEmpty()
                DiagnosticHub.record("USER_CONFIRMED_PROBLEM_MARKER", mapOf("note" to value))
                viewModel.markDiagnosticProblem(value)
            }
            .setNegativeButton("إلغاء", null)
            .show()
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
                putExtra(Intent.EXTRA_SUBJECT, "VisionBridge smart diagnostic bundle")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "حزمة تشخيص رفيق الرؤية الذكية. تركز على آخر مشكلة، وتضم الأحداث والصور الأقرب إليها ضمن ملف واحد محدود الحجم.",
                )
                clipData = ClipData.newRawUri("VisionBridge diagnostics", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            DiagnosticHub.record(
                "DIAGNOSTIC_SHARE_SHEET_OPENED",
                mapOf(
                    "fileName" to file.name,
                    "fileBytes" to file.length(),
                    "includesImages" to true,
                    "smartFocusedExport" to true,
                ),
            )
            this@MainActivity.startActivity(Intent.createChooser(share, "مشاركة حزمة التشخيص الذكية"))
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
