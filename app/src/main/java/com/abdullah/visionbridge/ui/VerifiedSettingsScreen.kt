package com.abdullah.visionbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.abdullah.visionbridge.BuildConfig
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.LocalReadingQuality
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.model.ViewportMode
import kotlin.math.roundToInt

/**
 * 3.7 settings surface. Only controls with a verified runtime effect are exposed here.
 * Legacy model selection, legacy cloud trust gate and forced-cellular controls were intentionally
 * removed because the Live-only pipeline did not obey them.
 */
@Composable
fun VerifiedSettingsScreen(
    state: MainUiState,
    onSaveApiKey: (String) -> Unit,
    onDeleteApiKey: () -> Unit,
    onSpeechChange: (Boolean) -> Unit,
    onCaptureProfileChange: (CaptureProfile) -> Unit,
    onInterruptSpeechChange: (Boolean) -> Unit,
    onSceneDescriptionStyleChange: (SceneDescriptionStyle) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onUseLocalOcrChange: (Boolean) -> Unit,
    onDescribeAlongsideTextChange: (Boolean) -> Unit,
    onLocalReadingQualityChange: (LocalReadingQuality) -> Unit,
    onViewportModeChange: (ViewportMode) -> Unit,
    onCaptureFailureEvidenceChange: (Boolean) -> Unit,
    onDiscardEvidenceFrames: () -> Unit,
    onOpenAccessibilityShortcutSettings: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onBack: () -> Unit,
    onMessageConsumed: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var speechRateDraft by remember(state.settings.speechRate) {
        mutableFloatStateOf(state.settings.speechRate)
    }
    val snackbarHost = remember { SnackbarHostState() }

    BackHandler(onBack = onBack)
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
                    "الإعدادات الموثقة",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Text(" رجوع")
                }
                Text(
                    "الإصدار ${BuildConfig.VERSION_NAME}، Build ${BuildConfig.VERSION_CODE}",
                    modifier = Modifier.semantics {
                        contentDescription = "VisionBridge ${BuildConfig.VERSION_NAME}، Build ${BuildConfig.VERSION_CODE}"
                    },
                )

                SectionTitle("المحرك الفعلي")
                Text(
                    if (state.settings.useLocalOcr && state.settings.mode == AnalysisMode.TEXT_READING) {
                        "قراءة النص الحالية: PP-OCRv5 على الجهاز."
                    } else {
                        "المسار السحابي: ${AppSettings.LIVE_MODEL_LABEL} عبر WebSocket Live فقط، بلا Legacy fallback."
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    "حارس الدقة وعدم التخمين في Live مفعّل دائمًا، لذلك لا يوجد زر موثوقية شكلي ولا اختيار نموذج لا يستخدمه البث.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                SectionTitle("Gemini API Key")
                Text(if (state.hasApiKey) "المفتاح محفوظ ومشفّر على الجهاز." else "لا يوجد مفتاح محفوظ.")
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Gemini API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "حقل Gemini API Key، القيمة مخفية"
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSaveApiKey(apiKey); apiKey = "" },
                        enabled = apiKey.isNotBlank(),
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("حفظ") }
                    OutlinedButton(
                        onClick = onDeleteApiKey,
                        enabled = state.hasApiKey,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text(" حذف")
                    }
                }

                SectionTitle("قص نافذة النظارة")
                Text(
                    "مرجع eSight الحقيقي: في لقطة 1356×610 نافذة الرؤية هي تقريبًا x 68 إلى 1034، و y 76 إلى 533. النسب تُطبّق على دقة الشاشة الحالية.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ViewportMode.entries.forEach { mode ->
                    val selected = state.settings.viewportMode == mode
                    val title = when (mode) {
                        ViewportMode.AUTO -> "تلقائي"
                        ViewportMode.ESIGHT_FIXED -> "eSight ثابت"
                        ViewportMode.ESIGHT_TEXT_SAFE -> "eSight نص آمن، موصى به"
                    }
                    val detail = when (mode) {
                        ViewportMode.AUTO -> "يكتشف الحواف السوداء تلقائيًا. استخدمه لمصادر غير eSight."
                        ViewportMode.ESIGHT_FIXED -> "يستخدم نافذة eSight المعايرة في قراءة النص ووصف المشهد."
                        ViewportMode.ESIGHT_TEXT_SAFE -> "في قراءة النص يستخدم نافذة eSight الثابتة ولا يسمح لظلام المحتوى أو البطاقة أن يغيّر القص. وصف المشهد يبقى تلقائيًا."
                    }
                    OutlinedButton(
                        onClick = { onViewportModeChange(mode) },
                        modifier = Modifier.fillMaxWidth().height(64.dp).semantics {
                            contentDescription = "$title. $detail. ${if (selected) "مختار" else "غير مختار"}"
                        },
                    ) { Text(if (selected) "✓ $title" else title) }
                }

                if (state.settings.mode == AnalysisMode.TEXT_READING) {
                    SectionTitle("مصدر قراءة النص")
                    AccessibleSwitchRow(
                        title = "استخدام PP-OCRv5 على الجهاز",
                        description = if (state.settings.useLocalOcr) {
                            "الصور لا تُرسل إلى Gemini للقراءة. هذا اختيار صريح وليس مسارًا احتياطيًا."
                        } else {
                            "القراءة عبر Gemini Live فقط. إذا فشل Live لا ينتقل التطبيق إلى Legacy."
                        },
                        checked = state.settings.useLocalOcr,
                        onCheckedChange = onUseLocalOcrChange,
                    )

                    if (!state.settings.useLocalOcr) {
                        AccessibleSwitchRow(
                            title = "جملة وصفية بعد النص",
                            description = if (state.settings.describeAlongsideText) {
                                "Live يقرأ النص أولًا، ثم قد يضيف جملة واحدة فقط إذا كان الوصف مرئيًا ومؤكدًا."
                            } else {
                                "الناتج نص فقط. لا يُطلب أي وصف للمشهد أو الإضاءة."
                            },
                            checked = state.settings.describeAlongsideText,
                            onCheckedChange = onDescribeAlongsideTextChange,
                        )
                    } else {
                        SectionTitle("جودة PP-OCR")
                        LocalReadingQuality.entries.sortedBy { if (it.adaptive) 0 else 1 }.forEach { quality ->
                            val selected = state.settings.localReadingQuality == quality
                            val label = when (quality) {
                                LocalReadingQuality.AUTO -> "تلقائي"
                                LocalReadingQuality.FAST -> "سريع"
                                LocalReadingQuality.BALANCED -> "متوازن"
                                LocalReadingQuality.MAXIMUM -> "أعلى دقة"
                            }
                            OutlinedButton(
                                onClick = { onLocalReadingQualityChange(quality) },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) { Text(if (selected) "✓ $label" else label) }
                        }
                        AccessibleSwitchRow(
                            title = "إيقاف النطق عند تغيّر الهدف",
                            description = "يعمل في مسار PP-OCR المحلي فقط، حيث يتوفر تتبع الهدف البصري الكامل.",
                            checked = state.settings.interruptSpeechOnVisualChange,
                            onCheckedChange = onInterruptSpeechChange,
                        )
                    }

                    SectionTitle("أسلوب التقاط النص")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.settings.captureProfile == CaptureProfile.STABLE,
                            onClick = { onCaptureProfileChange(CaptureProfile.STABLE) },
                            label = { Text("ثابت ودقيق") },
                            modifier = Modifier.weight(1f).height(56.dp),
                        )
                        FilterChip(
                            selected = state.settings.captureProfile == CaptureProfile.FAST_TEXT,
                            onClick = { onCaptureProfileChange(CaptureProfile.FAST_TEXT) },
                            label = { Text("سريع") },
                            modifier = Modifier.weight(1f).height(56.dp),
                        )
                    }
                    Text(
                        if (state.settings.captureProfile == CaptureProfile.STABLE) {
                            "للملصقات والمستندات والنص الصغير."
                        } else {
                            "للترجمة والشريط المتحرك، مع الاحتفاظ بأحدث لقطة."
                        },
                    )
                } else {
                    SectionTitle("تفصيل وصف المشهد")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.settings.sceneDescriptionStyle == SceneDescriptionStyle.COMPREHENSIVE,
                            onClick = { onSceneDescriptionStyleChange(SceneDescriptionStyle.COMPREHENSIVE) },
                            label = { Text("مفصل") },
                            modifier = Modifier.weight(1f).height(56.dp),
                        )
                        FilterChip(
                            selected = state.settings.sceneDescriptionStyle == SceneDescriptionStyle.BRIEF,
                            onClick = { onSceneDescriptionStyleChange(SceneDescriptionStyle.BRIEF) },
                            label = { Text("موجز") },
                            modifier = Modifier.weight(1f).height(56.dp),
                        )
                    }
                }

                SectionTitle("النطق")
                AccessibleSwitchRow(
                    title = "نطق النتائج تلقائيًا",
                    description = "ينطق نتائج Live أو PP-OCR بالناطق المحلي الأنثوي المفضّل عند توفره.",
                    checked = state.settings.speechEnabled,
                    onCheckedChange = onSpeechChange,
                )
                Text("سرعة النطق: ${(speechRateDraft * 100).roundToInt()}٪")
                Slider(
                    value = speechRateDraft,
                    onValueChange = { speechRateDraft = it },
                    onValueChangeFinished = { onSpeechRateChange(speechRateDraft) },
                    valueRange = AppSettings.MIN_SPEECH_RATE..AppSettings.MAX_SPEECH_RATE,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "سرعة النطق ${(speechRateDraft * 100).roundToInt()} بالمئة"
                    },
                )

                SectionTitle("التشخيص")
                AccessibleSwitchRow(
                    title = "حفظ صورة الشاشة عند فشل القراءة",
                    description = if (state.settings.captureFailureEvidence) {
                        "مفعّل مؤقتًا. قد يحتوي ملف التشخيص صورًا لما كنت تنظر إليه."
                    } else {
                        "مطفأ. لا تُحفظ لقطات جديدة."
                    },
                    checked = state.settings.captureFailureEvidence,
                    onCheckedChange = onCaptureFailureEvidenceChange,
                )
                OutlinedButton(
                    onClick = onOpenAccessibilityShortcutSettings,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("إسناد الزر العائم لحفظ اللقطات") }
                OutlinedButton(
                    onClick = onDiscardEvidenceFrames,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text(" حذف اللقطات المحفوظة")
                }
                Button(
                    onClick = onExportDiagnostics,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("مشاركة ملف التشخيص") }
            }
        }
    }
}
