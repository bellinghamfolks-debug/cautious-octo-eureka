package com.abdullah.visionbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.abdullah.visionbridge.domain.model.AnalysisMode

/**
 * The minimal operating surface: what the app is doing, what it should look for, and start or stop.
 *
 * Everything that is configured once and then left alone lives in [SettingsScreen]. A screen reader
 * user reaches the start control after three swipes instead of scrolling past a key field, a model
 * picker, a slider and six switches on every single launch.
 */
@Composable
fun MainScreen(
    state: MainUiState,
    onModeChange: (AnalysisMode) -> Unit,
    onOpenSettings: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onMessageConsumed: () -> Unit,
) {
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            onMessageConsumed()
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "رفيق الرؤية",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )

                StatusCard(state)

                SectionTitle("وضع التحليل")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.settings.mode == AnalysisMode.TEXT_READING,
                        onClick = { onModeChange(AnalysisMode.TEXT_READING) },
                        label = { Text("قراءة النص") },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .semantics { contentDescription = "تفعيل وضع قراءة النص العربي والإنجليزي" },
                    )
                    FilterChip(
                        selected = state.settings.mode == AnalysisMode.SCENE_DESCRIPTION,
                        onClick = { onModeChange(AnalysisMode.SCENE_DESCRIPTION) },
                        label = { Text("وصف المشهد") },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .semantics { contentDescription = "تفعيل وضع وصف المشهد والعوائق" },
                    )
                }

                if (state.capture.isRunning) {
                    Button(
                        onClick = onStopCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .semantics { contentDescription = "إيقاف التقاط الشاشة والتحليل" },
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Text(" إيقاف")
                    }
                } else {
                    // Deliberately enabled without a key: on-device Latin OCR still works offline.
                    Button(
                        onClick = onStartCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .semantics {
                                contentDescription = if (state.hasApiKey) {
                                    "بدء مشاركة الشاشة والتحليل"
                                } else {
                                    "بدء مشاركة الشاشة، بدون مفتاح Gemini سيعمل OCR المحلي للإنجليزية فقط"
                                }
                            },
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(" بدء التقاط الشاشة")
                    }
                }

                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics {
                            contentDescription =
                                "فتح الإعدادات: مفتاح Gemini والنموذج والنطق ودقة الالتقاط والتشخيص"
                        },
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Text(" الإعدادات")
                }

                Text(
                    text = "تنبيه سلامة: الوصف الآلي مساعد إضافي، وليس بديلاً عن العصا البيضاء أو مهارات التنقل أو التحقق البشري في البيئات الخطرة.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatusCard(state: MainUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = buildString {
                    append("حالة التطبيق: ${state.capture.status}. ")
                    if (!state.hasApiKey) append("لا يوجد مفتاح Gemini محفوظ. ")
                    state.capture.error?.let { append("الخطأ: $it. ") }
                    state.capture.lastResult?.text?.let { append("آخر نتيجة: $it") }
                }
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("الحالة", style = MaterialTheme.typography.titleLarge)
            Text(state.capture.status)
            if (state.capture.isProcessing) Text("يجري تحليل إطار جديد")
            if (!state.hasApiKey) Text("لا يوجد مفتاح Gemini محفوظ. افتح الإعدادات لحفظه.")
            state.capture.error?.let { Text("خطأ: $it", color = MaterialTheme.colorScheme.error) }
            state.capture.lastResult?.let {
                Text("آخر نتيجة", style = MaterialTheme.typography.titleMedium)
                Text(it.text)
                Text("المصدر: " + when (it.source.name) {
                    "LOCAL_OCR" -> "OCR محلي"
                    "LOCAL_VLM" -> "ذكاء محلي على الجهاز"
                    else -> "Gemini سحابي"
                })
            }
        }
    }
}
