package com.abdullah.visionbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onSaveApiKey: (String) -> Unit,
    onDeleteApiKey: () -> Unit,
    onModeChange: (AnalysisMode) -> Unit,
    onModelChange: (String) -> Unit,
    onForceCellularChange: (Boolean) -> Unit,
    onSpeechChange: (Boolean) -> Unit,
    onLocalOcrChange: (Boolean) -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onMessageConsumed: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
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
                Text(
                    text = "يحلل ما يظهر في بث النظارة على شاشة الهاتف. اختر نافذة تطبيق النظارة عند طلب مشاركة الشاشة.",
                    style = MaterialTheme.typography.bodyLarge,
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

                SectionTitle("الاتصال والصوت")
                AccessibleSwitchRow(
                    title = "إجبار طلبات Gemini على البيانات الخلوية",
                    description = "يستخدم شبكة الجوال لطلبات الذكاء الاصطناعي فقط، ولا يغيّر اتصال بقية الهاتف.",
                    checked = state.settings.forceCellular,
                    onCheckedChange = onForceCellularChange,
                )
                AccessibleSwitchRow(
                    title = "النطق التلقائي",
                    description = "ينطق النتائج ويبدّل بين العربية والإنجليزية حسب مقاطع النص.",
                    checked = state.settings.speechEnabled,
                    onCheckedChange = onSpeechChange,
                )
                AccessibleSwitchRow(
                    title = "OCR محلي سريع للإنجليزية",
                    description = "يقرأ النص اللاتيني على الجهاز فوراً، ويستعين بـGemini للعربية والنص المختلط.",
                    checked = state.settings.localOcrEnabled,
                    onCheckedChange = onLocalOcrChange,
                )

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
                    Button(
                        onClick = onStartCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .semantics { contentDescription = "بدء مشاركة الشاشة والتحليل" },
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(" بدء التقاط الشاشة")
                    }
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
                    state.capture.error?.let { append("الخطأ: $it. ") }
                    state.capture.lastResult?.text?.let { append("آخر نتيجة: $it") }
                }
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("الحالة", style = MaterialTheme.typography.titleLarge)
            Text(state.capture.status)
            if (state.capture.isProcessing) Text("يجري تحليل إطار جديد")
            state.capture.error?.let { Text("خطأ: $it", color = MaterialTheme.colorScheme.error) }
            state.capture.lastResult?.let {
                Text("آخر نتيجة", style = MaterialTheme.typography.titleMedium)
                Text(it.text)
                Text("المصدر: ${if (it.source.name == "LOCAL_OCR") "OCR محلي" else "Gemini"}")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun AccessibleSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 72.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $description. ${if (checked) "مفعّل" else "غير مفعّل"}"
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
