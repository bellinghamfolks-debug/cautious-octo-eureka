#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/abdullah/visionbridge/capture/MediaProjectionService.kt"
SETTINGS = ROOT / "app/src/main/java/com/abdullah/visionbridge/ui/SettingsScreen.kt"
HUB = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/diagnostics/DiagnosticHub.kt"
STORE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/diagnostics/EvidenceStore.kt"
RECORDER = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/diagnostics/DiagnosticRecorder.kt"
SHORTCUT = ROOT / "app/src/main/java/com/abdullah/visionbridge/accessibility/EvidenceShortcut.kt"

DENSE_MARKER = "DENSE_DIAGNOSTIC_EVIDENCE_V381"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"3.8.1 current-live patch failed: {label} anchor not found")
    return text.replace(old, new, 1)


def patch_current_live() -> None:
    service = SERVICE.read_text()
    if "SMART_TARGET_SCENE_OBSERVER_RATE_V381" not in service:
        service = replace_once(
            service,
            "            cloudLive && settings.mode == AnalysisMode.SCENE_DESCRIPTION -> 1_000L\n",
            "            // SMART_TARGET_SCENE_OBSERVER_RATE_V381: sample locally for target switching at\n"
            "            // ~4 Hz. GeminiLiveSession still enforces its own 1 FPS transport limit, so this\n"
            "            // improves interruption reaction time without increasing model traffic.\n"
            "            cloudLive && settings.mode == AnalysisMode.SCENE_DESCRIPTION -> 260L\n",
            "scene observer capture rate",
        )
        SERVICE.write_text(service)

    settings = SETTINGS.read_text()
    if "CURRENT_LIVE_MODEL_ONLY_V381" not in settings:
        model_pattern = re.compile(
            r'''\n                SectionTitle\("نموذج Gemini"\)\n                ExposedDropdownMenuBox\(.*?\n                \}\n\n                SectionTitle\("القراءة مع الوصف"\)''',
            re.DOTALL,
        )
        model_replacement = '''
                // CURRENT_LIVE_MODEL_ONLY_V381
                SectionTitle("Gemini Live")
                Text(
                    text = "Gemini 3.1 Flash Live هو النموذج السحابي الحالي الوحيد. لا توجد نماذج قديمة أو اختيار نموذج في هذا الإصدار.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "النموذج السحابي الحالي الوحيد: Gemini 3.1 Flash Live. لا توجد نماذج قديمة."
                    },
                )

                SectionTitle("القراءة مع الوصف")'''
        settings, count = model_pattern.subn(model_replacement, settings, count=1)
        if count != 1:
            raise SystemExit(f"3.8.1 current-live patch failed: model chooser removal count={count}")
        settings = settings.replace(
            '    var modelMenuExpanded by remember { mutableStateOf(false) }\n',
            '',
            1,
        )
        SETTINGS.write_text(settings)


def patch_dense_hub() -> None:
    text = HUB.read_text()
    if DENSE_MARKER in text:
        return
    text = replace_once(
        text,
        "import java.util.concurrent.atomic.AtomicReference\n",
        "import java.util.concurrent.atomic.AtomicLong\nimport java.util.concurrent.atomic.AtomicReference\n",
        "dense AtomicLong import",
    )
    text = replace_once(
        text,
        "object DiagnosticHub {\n    private const val FATAL_FLUSH_TIMEOUT_MS = 2_000L\n",
        f"// {DENSE_MARKER}\nobject DiagnosticHub {{\n"
        "    private const val FATAL_FLUSH_TIMEOUT_MS = 2_000L\n"
        "    private const val DENSE_SELECTED_EVIDENCE_INTERVAL_MS = 240L\n",
        "dense hub marker",
    )
    text = replace_once(
        text,
        "    private val latestTrace = AtomicReference<DiagnosticTrace?>(null)\n",
        "    private val latestTrace = AtomicReference<DiagnosticTrace?>(null)\n"
        "    private val lastDenseEvidenceAtElapsedMs = AtomicLong(0L)\n",
        "dense frame clock",
    )
    text = replace_once(
        text,
        '''                "storesImages" to false,
                "visualEvidence" to "aggregate_fingerprint",
''',
        '''                "storesImagesByDefault" to false,
                "optInDenseImageEvidence" to true,
                "visualEvidence" to "aggregate_fingerprint_plus_opt_in_dense_frames",
''',
        "dense init metadata",
    )
    text = replace_once(
        text,
        '''        record(
            "EVIDENCE_CAPTURE_SETTING",
            mapOf("enabled" to enabled, "framesHeld" to store.frameCount()),
        )
''',
        '''        record(
            "EVIDENCE_CAPTURE_SETTING",
            mapOf(
                "enabled" to enabled,
                "framesHeld" to store.frameCount(),
                "captureMode" to "dense_selected_input_plus_quality_failures",
                "selectedInputIntervalMs" to DENSE_SELECTED_EVIDENCE_INTERVAL_MS,
            ),
        )
''',
        "dense setting metadata",
    )
    text = replace_once(
        text,
        '''        val name = store.capture(bitmap, frameId, reason) ?: return
        record(
            "EVIDENCE_FRAME_CAPTURED",
            fields + mapOf("frameId" to frameId, "reason" to reason, "file" to name),
        )
''',
        '''        val started = SystemClock.elapsedRealtimeNanos()
        val name = store.capture(bitmap, frameId, reason) ?: return
        val completed = SystemClock.elapsedRealtimeNanos()
        record(
            "EVIDENCE_FRAME_CAPTURED",
            fields + mapOf(
                "frameId" to frameId,
                "reason" to reason,
                "file" to name,
                "evidenceWriteMs" to (completed - started) / 1_000_000.0,
            ),
        )
''',
        "evidence write timing",
    )
    text = replace_once(
        text,
        '''        recordVisualFingerprint(
            bitmap = bitmap,
            frameId = frameId,
            role = "selected_input",
            reason = stage,
            eventType = "FRAME_VISUAL_FINGERPRINT",
            metadata = metadata,
        )
    }

    /** Records a representative rejected frame as metrics only, never as a preview image. */
''',
        '''        recordVisualFingerprint(
            bitmap = bitmap,
            frameId = frameId,
            role = "selected_input",
            reason = stage,
            eventType = "FRAME_VISUAL_FINGERPRINT",
            metadata = metadata,
        )
        captureDenseSelectedInputEvidence(bitmap, frameId, stage, metadata)
    }

    private fun captureDenseSelectedInputEvidence(
        bitmap: Bitmap,
        frameId: String,
        stage: String,
        metadata: Map<String, Any?>,
    ) {
        val store = evidence ?: return
        if (!store.enabled) return
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastDenseEvidenceAtElapsedMs.get()
            if (previous > 0L && now - previous < DENSE_SELECTED_EVIDENCE_INTERVAL_MS) return
            if (lastDenseEvidenceAtElapsedMs.compareAndSet(previous, now)) break
        }
        evidence(
            bitmap = bitmap,
            frameId = frameId,
            reason = "selected_input_dense",
            fields = metadata + mapOf(
                "evidenceRole" to "selected_input",
                "sourceStage" to stage,
                "selectedInputIntervalMs" to DENSE_SELECTED_EVIDENCE_INTERVAL_MS,
            ),
        )
    }

    /** Records a representative rejected frame as metrics only, never as a preview image. */
''',
        "dense selected input capture",
    )
    text = replace_once(
        text,
        '''                VisualFingerprintAnalyzer.reset()
                target.startSession(command.settings)
''',
        '''                VisualFingerprintAnalyzer.reset()
                lastDenseEvidenceAtElapsedMs.set(0L)
                target.startSession(command.settings)
''',
        "dense session reset",
    )
    HUB.write_text(text)


def patch_dense_store() -> None:
    text = STORE.read_text()
    if DENSE_MARKER in text:
        return
    text = replace_once(
        text,
        "class EvidenceStore(val directory: File) {\n",
        f"// {DENSE_MARKER}\nclass EvidenceStore(val directory: File) {{\n",
        "dense store marker",
    )
    text = replace_once(
        text,
        '''        "evidenceByteLimit" to MAX_TOTAL_BYTES,
    )
''',
        '''        "evidenceByteLimit" to MAX_TOTAL_BYTES,
        "evidenceCaptureMode" to "dense_selected_input_plus_quality_failures",
        "evidenceLongEdgeLimitPx" to MAX_EDGE,
        "evidenceJpegQuality" to JPEG_QUALITY,
    )
''',
        "dense store manifest",
    )
    text = replace_once(
        text,
        '''        const val MAX_FRAMES = 40
        const val MAX_TOTAL_BYTES = 24L * 1024 * 1024
        const val MAX_EDGE = 1600
        const val JPEG_QUALITY = 78
''',
        '''        const val MAX_FRAMES = 160
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_EDGE = 1920
        const val JPEG_QUALITY = 88
''',
        "dense store limits",
    )
    STORE.write_text(text)


def patch_dense_recorder() -> None:
    text = RECORDER.read_text()
    if DENSE_MARKER in text:
        return
    text = replace_once(
        text,
        "/** Continuous, automatic, image-free diagnostic flight recorder. */\nclass DiagnosticRecorder(context: Context) {\n",
        f"/** Continuous diagnostic flight recorder; images remain opt-in only. */\n"
        f"// {DENSE_MARKER}\nclass DiagnosticRecorder(context: Context) {{\n",
        "dense recorder marker",
    )
    text = replace_once(
        text,
        '''                    "includesImages" to false,
                    "manualMarkerRequired" to false,
''',
        '''                    "includesImages" to (evidenceStore.frameCount() > 0),
                    "imageCount" to evidenceStore.frameCount(),
                    "manualMarkerRequired" to false,
''',
        "export image count",
    )
    text = replace_once(
        text,
        '                        put("includesImages", false)\n',
        '                        put("includesImages", evidenceStore.frameCount() > 0)\n',
        "manifest includesImages",
    )
    text = replace_once(
        text,
        '''                            "Contains recognized text, model output, app settings and timing data. It contains no screen images and excludes API keys and authorization headers.",
''',
        '''                            if (evidenceStore.frameCount() > 0) {
                                "Contains recognized text, model output, app settings, timing data and opt-in screen evidence frames. Excludes API keys and authorization headers."
                            } else {
                                "Contains recognized text, model output, app settings and timing data. It contains no screen images and excludes API keys and authorization headers."
                            },
''',
        "dynamic privacy warning",
    )
    text = replace_once(
        text,
        "                imageCount = 0,\n",
        "                imageCount = evidenceStore.frameCount(),\n",
        "storage image count",
    )
    text = replace_once(
        text,
        '''            put("containsImages", false)
            put("sessionCount", sessionSummaries.length())
''',
        '''            put("containsImages", evidenceStore.frameCount() > 0)
            put("evidenceFrameCount", evidenceStore.frameCount())
            put("sessionCount", sessionSummaries.length())
''',
        "summary image truth",
    )
    text = replace_once(
        text,
        '''                    "- الحزمة تحتوي $evidenceFrames صورة شاشة، حفظتها بنفسك بتفعيل خيار " +
                        "«حفظ صورة الشاشة عند فشل القراءة». مجلد evidence/.",
''',
        '''                    "- الحزمة تحتوي $evidenceFrames لقطة تشخيص بصرية، حفظتها بنفسك بتفعيل خيار " +
                        "«حفظ لقطات التشخيص السريع». تشمل مدخلات التحليل المتتابعة ولقطات فشل الجودة. مجلد evidence/.",
''',
        "README dense image summary",
    )
    text = replace_once(
        text,
        '''                    "- توجد $evidenceFrames صورة شاشة في مجلد evidence/، كل واحدة باسم سبب الفشل " +
                        "الذي حفظها. إيقاف الحفظ (من زر إمكانية الوصول العائم، أو من إشعار " +
                        "VisionBridge، أو من الإعدادات) يوقف حفظ لقطات جديدة ويُبقي المحفوظ؛ " +
                        "«حذف اللقطات المحفوظة» في الإعدادات يمسحها فتعود الحزم بلا صور.",
''',
        '''                    "- توجد $evidenceFrames لقطة تشخيص في مجلد evidence/. اللقطات المسماة " +
                        "selected_input_dense هي المدخلات الفعلية المتتابعة التي دخلت التحليل، " +
                        "وتوجد أيضاً لقطات لأعطال جودة الصورة عند وقوعها. إيقاف الحفظ من إشعار " +
                        "VisionBridge أو من الإعدادات يوقف حفظ لقطات جديدة ويُبقي المحفوظ؛ " +
                        "«حذف اللقطات المحفوظة» في الإعدادات يمسحها فتعود الحزم بلا صور.",
''',
        "README dense privacy",
    )
    RECORDER.write_text(text)


def patch_dense_settings() -> None:
    text = SETTINGS.read_text()
    if DENSE_MARKER in text:
        return
    text = replace_once(
        text,
        '''                Text(
                    "يسجل VisionBridge بيانات التشخيص تلقائيًا من دون صور، بما يشمل التوقيتات ونتائج OCR وGemini وحالة النطق.",
                    style = MaterialTheme.typography.bodyMedium,
                )
''',
        f'''                // {DENSE_MARKER}
                Text(
                    "يسجل VisionBridge التشخيص تلقائيًا من دون صور افتراضيًا. عند تشغيل حفظ اللقطات، " +
                        "يضيف سلسلة صور متتابعة من المدخل الفعلي للتحليل لتتبع فقد الأسطر والقص والترتيب.",
                    style = MaterialTheme.typography.bodyMedium,
                )
''',
        "dense settings intro",
    )
    text = replace_once(
        text,
        '''                    title = "حفظ صورة الشاشة عند فشل القراءة",
                    description = if (state.settings.captureFailureEvidence) {
                        "مُفعّل. تُحفظ صورة الشاشة الكاملة في لحظات الفشل فقط، بحد أقصى ٤٠ صورة، " +
                            "وتُرسل داخل ملف التشخيص. أطفئه بعد إعادة إنتاج المشكلة."
                    } else {
                        "مطفأ. لا تُحفظ لقطات جديدة. فعّله فقط أثناء إعادة إنتاج مشكلة قراءة، " +
                            "لأن الصورة وحدها تفرّق بين نص لم يُكتشف ونص اكتُشف ثم رُمي. " +
                            "اللقطات التي حُفظت سابقاً تبقى حتى تحذفها من الزر أدناه، " +
                            "وعددها مذكور في اسم ملف التشخيص."
                    },
''',
        '''                    title = "حفظ لقطات التشخيص السريع",
                    description = if (state.settings.captureFailureEvidence) {
                        "مُفعّل. تُحفظ المدخلات الفعلية التي تدخل Gemini أو PP-OCR بصورة متتابعة، " +
                            "نحو ٣ إلى ٤ لقطات في الثانية بحسب وضع الالتقاط، مع لقطات فشل الجودة أيضاً. " +
                            "الحد الأقصى ١٦٠ لقطة أو ٦٤ ميجابايت. أطفئه بعد إعادة إنتاج المشكلة."
                    } else {
                        "مطفأ. لا تُحفظ صور جديدة. فعّله فقط أثناء إعادة إنتاج مشكلة القراءة أو الوصف؛ " +
                            "سيحفظ تسلسلاً سريعاً يوضح ما رآه المحلل قبل الخطأ وأثناءه وبعده. " +
                            "اللقطات المحفوظة سابقاً تبقى حتى تحذفها من الزر أدناه."
                    },
''',
        "dense settings switch",
    )
    text = replace_once(
        text,
        '''                        text = "تنبيه: ملف التشخيص القادم سيحتوي صوراً لما كنت تنظر إليه عند الفشل. " +
                            "اسم الملف سيذكر عدد الصور.",
''',
        '''                        text = "تنبيه: ملف التشخيص القادم سيحتوي سلسلة صور لما كان يدخل التحليل أثناء " +
                            "فترة الاختبار. اسم الملف سيذكر عدد اللقطات.",
''',
        "dense warning",
    )
    text = text.replace(
        '"حذف كل لقطات الفشل المحفوظة الآن، قبل مشاركة ملف التشخيص"',
        '"حذف كل لقطات التشخيص المحفوظة الآن، قبل مشاركة ملف التشخيص"',
        1,
    )
    text = text.replace(
        '"مشاركة ملف التشخيص، ويحتوي صور لحظات الفشل والتوقيتات ونتائج OCR وGemini وحالة النطق"',
        '"مشاركة ملف التشخيص، ويحتوي سلسلة لقطات التحليل السريعة والتوقيتات ونتائج OCR وGemini وحالة النطق"',
        1,
    )
    SETTINGS.write_text(text)


def patch_dense_shortcut() -> None:
    text = SHORTCUT.read_text()
    if DENSE_MARKER in text:
        return
    text = replace_once(
        text,
        "object EvidenceShortcut {\n",
        f"// {DENSE_MARKER}\nobject EvidenceShortcut {{\n",
        "dense shortcut marker",
    )
    text = replace_once(
        text,
        '                        append(" لم تُحفظ أي لقطة، لأن القراءة لم تُخفق أثناء التشغيل.")\n',
        '                        append(" لم تُحفظ أي لقطة خلال فترة تشغيل الحفظ.")\n',
        "dense zero-frame wording",
    )
    text = replace_once(
        text,
        '''                append("شُغّل حفظ لقطات التشخيص.")
                append(" ستُحفظ صورة الشاشة عند كل إخفاق قراءة، بحد أقصى $FRAME_LIMIT لقطة.")
''',
        '''                append("شُغّل حفظ لقطات التشخيص السريع.")
                append(" ستُحفظ المدخلات الفعلية للتحليل بصورة متتابعة، مع لقطات فشل الجودة، بحد أقصى $FRAME_LIMIT لقطة.")
''',
        "dense spoken announcement",
    )
    text = replace_once(
        text,
        "    const val FRAME_LIMIT = 40\n",
        "    const val FRAME_LIMIT = 160\n",
        "dense shortcut limit",
    )
    SHORTCUT.write_text(text)


patch_current_live()
patch_dense_hub()
patch_dense_store()
patch_dense_recorder()
patch_dense_settings()
patch_dense_shortcut()
print("Applied current Gemini Live-only UI plus VisionBridge 3.8.1 dense diagnostic evidence")
