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
import com.abdullah.visionbridge.BuildConfig
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.LocalReadingQuality
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
    onTrustGateChange: (Boolean) -> Unit,
    onCaptureProfileChange: (CaptureProfile) -> Unit,
    onInterruptSpeechChange: (Boolean) -> Unit,
    onSceneDescriptionStyleChange: (SceneDescriptionStyle) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onUseLocalOcrChange: (Boolean) -> Unit,
    onLocalReadingQualityChange: (LocalReadingQuality) -> Unit,
    onCaptureFailureEvidenceChange: (Boolean) -> Unit,
    onDiscardEvidenceFrames: () -> Unit,
    onOpenAccessibilityShortcutSettings: () -> Unit,
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

                // Stated on the surface the user is already on. Two separate builds have now been
                // confused for each other in this project, and a blind user has no way to check an
                // APK's version from the outside.
                Text(
                    text = "الإصدار ${BuildConfig.VERSION_NAME}، Build ${BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "إصدار VisionBridge ${BuildConfig.VERSION_NAME}، Build ${BuildConfig.VERSION_CODE}"
                    },
                )

                SectionTitle("Gemini API Key")
                Text(
                    text = if (state.hasApiKey) "Gemini API Key محفوظ ومشفّر على هذا الجهاز." else "لا يوجد Gemini API Key محفوظ.",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Gemini API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "حقل إدخال Gemini API Key، والقيمة مخفية" },
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
                    ) { Text("حفظ المفتاح") }
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

                SectionTitle("نموذج Gemini")
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

                SectionTitle("OCR على الجهاز")
                AccessibleSwitchRow(
                    title = "استخدام PP-OCRv5 دون إنترنت",
                    description = if (state.settings.useLocalOcr) {
                        "تُقرأ النصوص العربية والإنجليزية على الجهاز، ولا تُرسل الصور إلى Gemini."
                    } else {
                        "تُقرأ النصوص عبر Gemini. فعّل هذا الخيار للقراءة على الجهاز دون إنترنت."
                    },
                    checked = state.settings.useLocalOcr,
                    onCheckedChange = onUseLocalOcrChange,
                )
                Text(
                    text = "وصف المشهد يحتاج Gemini واتصالًا بالإنترنت؛ PP-OCRv5 مخصص لقراءة النص فقط.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "ملفات PP-OCRv5 مضمّنة داخل التطبيق، ولا تحتاج إلى تنزيل أو إعداد إضافي.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )

                // Shown whether or not local reading is on. Hiding a setting behind another
                // setting means a screen-reader user walking the list never discovers it exists,
                // which is exactly how this one went missing.
                Text(
                    text = "جودة OCR على الجهاز",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (state.settings.useLocalOcr) {
                        "الوضع التلقائي يقيس حجم النص أمامك كل لقطة ويختار الدقة بنفسه. " +
                            "اختر مستوى ثابتاً فقط إن أردت التحكم بنفسك."
                    } else {
                        "يُطبّق هذا الإعداد عند تشغيل PP-OCRv5."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                LocalReadingQuality.entries.sortedBy { if (it.adaptive) 0 else 1 }.forEach { quality ->
                    val selected = state.settings.localReadingQuality == quality
                    val label = when (quality) {
                        LocalReadingQuality.AUTO -> "تلقائي"
                        LocalReadingQuality.FAST -> "سريع"
                        LocalReadingQuality.BALANCED -> "متوازن"
                        LocalReadingQuality.MAXIMUM -> "أعلى دقة"
                    }
                    val detail = when (quality) {
                        LocalReadingQuality.AUTO ->
                            "الموصى به. يقيس ارتفاع النص في كل لقطة ويحسب الدقة اللازمة له: " +
                                "سريع للقريب الكبير، وأعلى دقة للبعيد الصغير، من دون أن تختار."
                        LocalReadingQuality.FAST ->
                            "ثابت. أسرع استجابة للنص الكبير والمتحرك."
                        LocalReadingQuality.BALANCED ->
                            "ثابت. توازن بين السرعة والدقة."
                        LocalReadingQuality.MAXIMUM ->
                            "ثابت. للخط الصغير واللافتات البعيدة، لكنه أبطأ دائماً حتى مع النص الكبير."
                    }
                    OutlinedButton(
                        onClick = { onLocalReadingQualityChange(quality) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .semantics {
                                contentDescription = if (selected) {
                                    "$label، مختار حالياً. $detail"
                                } else {
                                    "$label. $detail"
                                }
                            },
                    ) { Text(if (selected) "\u2713 $label" else label) }
                }

                if (state.settings.mode == AnalysisMode.TEXT_READING) {
                    SectionTitle("أسلوب التقاط النص")
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
                                    contentDescription = "ثابت ودقيق. ينتظر ثبات الصورة قبل بدء القراءة"
                                },
                        )
                        FilterChip(
                            selected = state.settings.captureProfile == CaptureProfile.FAST_TEXT,
                            onClick = { onCaptureProfileChange(CaptureProfile.FAST_TEXT) },
                            label = { Text("سريع للنص المتحرك") },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .semantics {
                                    contentDescription = "سريع للنص المتحرك والترجمات والشريط الإخباري"
                                },
                        )
                    }
                    Text(
                        if (state.settings.captureProfile == CaptureProfile.FAST_TEXT) {
                            "يقرأ التغييرات بسرعة ويحتفظ بأحدث لقطة فقط. الأنسب للترجمات والشريط الإخباري."
                        } else {
                            "ينتظر ثبات الصورة قبل القراءة. الأنسب للمستندات واللافتات والنصوص الصغيرة."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AccessibleSwitchRow(
                        title = "التحقق من موثوقية OCR",
                        description = if (state.settings.trustGateEnabled) {
                            "يرفض النتائج غير الواضحة أو التي تتضمن تخمينًا، ويعرض تنبيهًا بدلًا منها."
                        } else {
                            "معطّل. قد تبدأ القراءة أسرع، لكن احتمال الخطأ أو التخمين يكون أعلى."
                        },
                        checked = state.settings.trustGateEnabled,
                        onCheckedChange = onTrustGateChange,
                    )
                }

                if (state.settings.mode == AnalysisMode.SCENE_DESCRIPTION) {
                    SectionTitle("تفصيل وصف المشهد")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.settings.sceneDescriptionStyle == SceneDescriptionStyle.COMPREHENSIVE,
                            onClick = { onSceneDescriptionStyleChange(SceneDescriptionStyle.COMPREHENSIVE) },
                            label = { Text("مفصل") },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .semantics { contentDescription = "وصف مفصل للمشهد والعناصر الظاهرة" },
                        )
                        FilterChip(
                            selected = state.settings.sceneDescriptionStyle == SceneDescriptionStyle.BRIEF,
                            onClick = { onSceneDescriptionStyleChange(SceneDescriptionStyle.BRIEF) },
                            label = { Text("موجز") },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .semantics { contentDescription = "وصف موجز وسريع للمشهد" },
                        )
                    }
                    Text(
                        if (state.settings.sceneDescriptionStyle == SceneDescriptionStyle.BRIEF) {
                            "يعطي وصفًا مباشرًا وقصيرًا بأسرع استجابة ممكنة."
                        } else {
                            "يذكر تفاصيل أكثر عن الاتجاهات والأشخاص والأشياء والنص المهم."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SectionTitle("النطق")
                AccessibleSwitchRow(
                    title = "نطق النتائج تلقائيًا",
                    description = "ينطق النص بالترتيب الظاهر، مع التبديل تلقائيًا بين العربية والإنجليزية.",
                    checked = state.settings.speechEnabled,
                    onCheckedChange = onSpeechChange,
                )
                AccessibleSwitchRow(
                    title = "إيقاف النطق عند تغيّر المحتوى",
                    description = "يوقف النتيجة الحالية عند الانتقال إلى محتوى مختلف، ثم ينطق أحدث نتيجة.",
                    checked = state.settings.interruptSpeechOnVisualChange,
                    onCheckedChange = onInterruptSpeechChange,
                )
                Text(
                    "سرعة النطق: ${(speechRateDraft * 100).roundToInt()}٪",
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
                                "التحكم بسرعة النطق. السرعة الحالية ${(speechRateDraft * 100).roundToInt()} بالمئة"
                        },
                )
                Text("يمكن ضبط السرعة من 60٪ إلى 180٪، ويُطبّق التغيير على النطق التالي.")

                SectionTitle("الاتصال والتشخيص")
                AccessibleSwitchRow(
                    title = "استخدام بيانات الجوال لطلبات Gemini",
                    description = "يوجّه طلبات Gemini عبر بيانات الجوال فقط، من دون تغيير اتصال بقية التطبيقات.",
                    checked = state.settings.forceCellular,
                    onCheckedChange = onForceCellularChange,
                )
                Text(
                    "يسجل VisionBridge بيانات التشخيص تلقائيًا من دون صور، بما يشمل التوقيتات ونتائج OCR وGemini وحالة النطق.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                // Off by default, and worded so the cost is plain before it is switched on. A frame
                // is whatever the user was looking at, and that is theirs to decide about each time.
                AccessibleSwitchRow(
                    title = "حفظ صورة الشاشة عند فشل القراءة",
                    description = if (state.settings.captureFailureEvidence) {
                        "مُفعّل. تُحفظ صورة الشاشة الكاملة في لحظات الفشل فقط، بحد أقصى ٤٠ صورة، " +
                            "وتُرسل داخل ملف التشخيص. أطفئه بعد إعادة إنتاج المشكلة."
                    } else {
                        "مطفأ. لا تُحفظ لقطات جديدة. فعّله فقط أثناء إعادة إنتاج مشكلة قراءة، " +
                            "لأن الصورة وحدها تفرّق بين نص لم يُكتشف ونص اكتُشف ثم رُمي. " +
                            "اللقطات التي حُفظت سابقاً تبقى حتى تحذفها من الزر أدناه، " +
                            "وعددها مذكور في اسم ملف التشخيص."
                    },
                    checked = state.settings.captureFailureEvidence,
                    onCheckedChange = onCaptureFailureEvidenceChange,
                )
                if (state.settings.captureFailureEvidence) {
                    Text(
                        text = "تنبيه: ملف التشخيص القادم سيحتوي صوراً لما كنت تنظر إليه عند الفشل. " +
                            "اسم الملف سيذكر عدد الصور.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }

                // The switch above is unreachable at the only moment it matters: reaching it means
                // leaving the shared view, which ends the capture that was failing. These two rows
                // are how it is reached without leaving.
                val notificationActionLabel = if (state.settings.captureFailureEvidence) {
                    "إيقاف حفظ اللقطات"
                } else {
                    "تشغيل حفظ اللقطات"
                }
                Text(
                    text = "تشغيله وإيقافه أثناء الاستخدام: زر إمكانية الوصول العائم، أو زر " +
                        "«$notificationActionLabel» داخل إشعار VisionBridge. كلاهما ينطق الوضع " +
                        "الجديد بعد الضغط، فلا حاجة لمغادرة تطبيق eSight.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onOpenAccessibilityShortcutSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics {
                            contentDescription =
                                "فتح إعدادات إمكانية الوصول لإسناد زر VisionBridge إلى الزر العائم. " +
                                    "الخدمة لا تقرأ محتوى الشاشة، ووظيفتها الوحيدة استقبال الضغطة."
                        },
                ) {
                    Text("إسناد الزر العائم لحفظ اللقطات")
                }

                OutlinedButton(
                    onClick = onDiscardEvidenceFrames,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics {
                            contentDescription =
                                "حذف كل لقطات الفشل المحفوظة الآن، قبل مشاركة ملف التشخيص"
                        },
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("  حذف اللقطات المحفوظة")
                }

                Button(
                    onClick = onExportDiagnostics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics {
                            contentDescription = if (state.settings.captureFailureEvidence) {
                                "مشاركة ملف التشخيص، ويحتوي صور لحظات الفشل والتوقيتات ونتائج OCR وGemini وحالة النطق"
                            } else {
                                "مشاركة ملف التشخيص من دون صور، ويشمل التوقيتات ونتائج OCR وGemini وحالة النطق"
                            }
                        },
                ) {
                    Text("مشاركة ملف التشخيص")
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
