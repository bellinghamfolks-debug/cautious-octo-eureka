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
import com.abdullah.visionbridge.domain.model.AnalysisSource

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
                    text = "VisionBridge",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )

                StatusCard(state)

                SectionTitle("وضع التشغيل")
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
                            .semantics { contentDescription = "اختيار قراءة النص العربي والإنجليزي" },
                    )
                    FilterChip(
                        selected = state.settings.mode == AnalysisMode.SCENE_DESCRIPTION,
                        onClick = { onModeChange(AnalysisMode.SCENE_DESCRIPTION) },
                        label = { Text("وصف المشهد") },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .semantics { contentDescription = "اختيار وصف المشهد والعوائق الظاهرة" },
                    )
                }

                if (state.capture.isRunning) {
                    Button(
                        onClick = onStopCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .semantics { contentDescription = "إيقاف مشاركة الشاشة والتحليل" },
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Text(" إيقاف")
                    }
                } else {
                    // Deliberately enabled without a key: the on-device reader works offline, so a
                    // user who has installed it should not be blocked by a missing cloud key.
                    Button(
                        onClick = onStartCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .semantics {
                                contentDescription = when {
                                    state.hasApiKey -> "بدء مشاركة الشاشة وتشغيل VisionBridge"
                                    state.settings.useLocalOcr ->
                                        "بدء مشاركة الشاشة. قراءة النص ستعمل عبر PP-OCRv5 على الجهاز"
                                    else ->
                                        "بدء مشاركة الشاشة. يلزم Gemini API Key أو تفعيل PP-OCRv5 من الإعدادات"
                                }
                            },
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(" بدء مشاركة الشاشة")
                    }
                }

                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics {
                            contentDescription =
                                "فتح الإعدادات: Gemini API Key، نموذج Gemini، OCR على الجهاز، النطق، والتشخيص"
                        },
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Text(" الإعدادات")
                }

                Text(
                    text = "تنبيه للسلامة: وصف المشهد أداة مساعدة فقط. لا تعتمد عليه بدل العصا البيضاء أو مهارات التوجيه والتنقل، ولا تستخدمه لاتخاذ قرار يتعلق بالسلامة في موقف خطِر.",
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
                    append("حالة VisionBridge: ${state.capture.status}. ")
                    append(engineSummary(state))
                    append(". ")
                    if (!state.hasApiKey && !state.settings.useLocalOcr) {
                        append("لا يوجد Gemini API Key محفوظ، وPP-OCRv5 غير مفعّل. ")
                    }
                    state.capture.error?.let { append("الخطأ: $it. ") }
                    state.capture.lastResult?.text?.let { append("آخر نتيجة: $it") }
                }
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("الحالة", style = MaterialTheme.typography.titleLarge)
            Text(state.capture.status)
            Text(engineSummary(state))
            if (state.capture.isProcessing) Text("جارٍ تحليل أحدث لقطة")
            if (!state.hasApiKey && !state.settings.useLocalOcr) {
                Text("يلزم Gemini API Key أو تفعيل PP-OCRv5 من الإعدادات.")
            }
            state.capture.error?.let { Text("خطأ: $it", color = MaterialTheme.colorScheme.error) }
            state.capture.lastResult?.let {
                Text("آخر نتيجة", style = MaterialTheme.typography.titleMedium)
                Text(it.text)
                Text("المصدر: " + when (it.source) {
                    AnalysisSource.LOCAL_OCR -> "PP-OCRv5 على الجهاز"
                    AnalysisSource.GEMINI -> "Gemini"
                })
            }
        }
    }
}

/**
 * One line naming the engine each mode will actually use.
 *
 * Where screen content goes is not a detail a blind user should have to infer from a settings
 * screen they are not currently on, so it is stated on the main surface and read out with the
 * status.
 */
private fun engineSummary(state: MainUiState): String =
    if (state.settings.useLocalOcr) {
        "قراءة النص: PP-OCRv5 على الجهاز. وصف المشهد: Gemini عبر الإنترنت"
    } else {
        "قراءة النص ووصف المشهد: Gemini عبر الإنترنت"
    }
