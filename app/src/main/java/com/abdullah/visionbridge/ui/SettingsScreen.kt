package com.abdullah.visionbridge.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import kotlin.math.roundToInt

/**
 * Everything that is set once and then left alone: credentials, model, capture accuracy, speech
 * behaviour and diagnostics. Options that only apply to one analysis mode are shown only for that
 * mode, so the list a screen reader walks stays as short as the current task allows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: MainUiState,
    onSaveApiKey: (String) -> Unit,
    onDeleteApiKey: () -> Unit,
    onModelChange: (String) -> Unit,
    onForceCellularChange: (Boolean) -> Unit,
    onSpeechChange: (Boolean) -> Unit,
    onLocalOcrChange: (Boolean) -> Unit,
    onTrustGateChange: (Boolean) -> Unit,
    onCaptureProfileChange: (CaptureProfile) -> Unit,
    onInterruptSpeechChange: (Boolean) -> Unit,
    onSceneDescriptionStyleChange: (SceneDescriptionStyle) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onExportDiagnostics: () -> Unit,
    onBack: () -> Unit,
    onMessageConsumed: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
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
                    text = "الإعدادات",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics { contentDescription = "الرجوع إلى الشاشة الرئيسية" },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Text(" رجوع")
                }

                SectionTitle("مفتاح Gemini")
                Text(
                    text = if (state.hasApiKey) "المفتاح محفوظ ومشفّر داخل الجهاز." else "لا يوجد مفتاح محفوظ.",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("مفتاح API") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "حقل إدخال مفتاح Gemini، النص مخفي" },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSaveApiKey(apiKey)
                            apiKey = ""
                        },
                        enabled = apiKey.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) { Text("حفظ مشفّر") }
                    OutlinedButton(
                        onClick = onDeleteApiKey,
                        enabled = state.hasApiKey,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text(" حذف المفتاح")
                    }
                }

                SectionTitle("نموذج الذكاء الاصطناعي")
                ExposedDropdownMenuBox(
                    expanded = modelMenuExpanded,
                    onExpandedChange = { modelMenuExpanded = !modelMenuExpanded },
                ) {
                    OutlinedTextField(
                        value = state.settings.model,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("النموذج") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                            .semantics { contentDescription = "اختيار نموذج Gemini، الحالي ${state.settings.model}" },
                    )
                    ExposedDropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        AppSettings.SUPPORTED_MODELS.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    onModelChange(model)
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                if (state.settings.mode == AnalysisMode.TEXT_READING) {
                    SectionTitle("طريقة التقاط النص")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.settings.captureProfile == CaptureProfile.STABLE,
                            onClick = { onCaptureProfileChange(CaptureProfile.STABLE) },
                            label = { Text("ثابت ودقيق") },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .semantics {
                                    contentDescription = "التقاط ثابت ودقيق، ينتظر استقرار الصورة قبل القراءة"
                                },
                        )
                        FilterChip(
                            selected = state.settings.captureProfile == CaptureProfile.FAST_TEXT,
                            onClick = { onCaptureProfileChange(CaptureProfile.FAST_TEXT) },
                            label = { Text("نص متحرك سريع") },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .semantics {
                                    contentDescription = "التقاط سريع للنصوص المتحركة والترجمات والشريط الإخباري"
                                },
                        )
                    }
                    Text(
                        if (state.settings.captureProfile == CaptureProfile.FAST_TEXT) {
                            "الوضع السريع يلتقط التغير فوراً، ويرسل صورة أخف إلى Gemini ويحتفظ بأحدث لقطة فقط."
                        } else {
                            "الوضع الثابت يحافظ على أعلى دقة للنصوص الصغيرة والمستندات واللافتات."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AccessibleSwitchRow(
                        title = "بوابة الثقة قبل العرض والنطق",
                        description = if (state.settings.trustGateEnabled) {
                            "ترفض النص غير الواضح أو المستنتج، وتنطق تنبيهاً بدلاً من الصمت."
                        } else {
                            "موقفة. تبدأ القراءة أسرع، لكن قد تزيد احتمالية الخطأ أو التأليف."
                        },
                        checked = state.settings.trustGateEnabled,
                        onCheckedChange = onTrustGateChange,
                    )
                    AccessibleSwitchRow(
                        title = "OCR محلي سريع للإنجليزية",
                        description = "يقرأ النص اللاتيني على الجهاز فوراً، ويستعين بـGemini للعربية والنص المختلط.",
                        checked = state.settings.localOcrEnabled,
                        onCheckedChange = onLocalOcrChange,
                    )
                }

                if (state.settings.mode == AnalysisMode.SCENE_DESCRIPTION) {
                    SectionTitle("طول وصف المشهد")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.settings.sceneDescriptionStyle == SceneDescriptionStyle.COMPREHENSIVE,
                            onClick = { onSceneDescriptionStyleChange(SceneDescriptionStyle.COMPREHENSIVE) },
                            label = { Text("وصف شامل") },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .semantics { contentDescription = "وصف شامل يحافظ على التفاصيل الحالية" },
                        )
                        FilterChip(
                            selected = state.settings.sceneDescriptionStyle == SceneDescriptionStyle.BRIEF,
                            onClick = { onSceneDescriptionStyleChange(SceneDescriptionStyle.BRIEF) },
                            label = { Text("وصف موجز") },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .semantics { contentDescription = "وصف موجز سريع بحد أقصى ثمان وعشرين كلمة" },
                        )
                    }
                    Text(
                        if (state.settings.sceneDescriptionStyle == SceneDescriptionStyle.BRIEF) {
                            "الوصف الموجز يستخدم صورة أخف ودقة وسائط أقل لبدء الاستجابة بأقصى سرعة."
                        } else {
                            "الوصف الشامل يستخدم دقة متوسطة وتفاصيل أكثر، مع استمرار النطق المتدفق."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SectionTitle("النطق")
                AccessibleSwitchRow(
                    title = "النطق التلقائي",
                    description = "ينطق النتائج بنفس تسلسل المقاطع العربية والإنجليزية الظاهر في النص.",
                    checked = state.settings.speechEnabled,
                    onCheckedChange = onSpeechChange,
                )
                AccessibleSwitchRow(
                    title = "إيقاف النطق القديم عند تغيير النظرة",
                    description = "عند الانتقال الواضح إلى صورة أو نص آخر، يوقف الكلام السابق فوراً ويتجه إلى النتيجة الجديدة.",
                    checked = state.settings.interruptSpeechOnVisualChange,
                    onCheckedChange = onInterruptSpeechChange,
                )
                Text(
                    "سرعة النطق: ${(speechRateDraft * 100).roundToInt()} بالمئة",
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = speechRateDraft,
                    onValueChange = { speechRateDraft = it },
                    onValueChangeFinished = { onSpeechRateChange(speechRateDraft) },
                    valueRange = AppSettings.MIN_SPEECH_RATE..AppSettings.MAX_SPEECH_RATE,
                    steps = 11,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription =
                                "التحكم بسرعة النطق، الحالية ${(speechRateDraft * 100).roundToInt()} بالمئة"
                        },
                )
                Text("النطاق من 60 إلى 180 بالمئة. تُطبق السرعة على المقطع التالي مباشرة.")

                SectionTitle("الاتصال والتشخيص")
                AccessibleSwitchRow(
                    title = "إجبار طلبات Gemini على البيانات الخلوية",
                    description = "يستخدم شبكة الجوال لطلبات الذكاء الاصطناعي فقط، ولا يغيّر اتصال بقية الهاتف.",
                    checked = state.settings.forceCellular,
                    onCheckedChange = onForceCellularChange,
                )
                Text(
                    "التشخيص يسجل تلقائياً وبلا صور، ولا يحتاج تعليم لحظة المشكلة.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onExportDiagnostics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics {
                            contentDescription =
                                "مشاركة سجل التشخيص التلقائي الشامل، بلا صور، مع البصمات البصرية والتوقيتات والنصوص"
                        },
                ) {
                    Text("مشاركة التشخيص التلقائي")
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
