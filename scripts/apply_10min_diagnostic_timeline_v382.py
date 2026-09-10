#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/abdullah/visionbridge/capture/MediaProjectionService.kt"
HUB = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/diagnostics/DiagnosticHub.kt"
STORE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/diagnostics/EvidenceStore.kt"
RECORDER = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/diagnostics/DiagnosticRecorder.kt"
SETTINGS = ROOT / "app/src/main/java/com/abdullah/visionbridge/ui/SettingsScreen.kt"
SHORTCUT = ROOT / "app/src/main/java/com/abdullah/visionbridge/accessibility/EvidenceShortcut.kt"

MARKER = "TEN_MINUTE_DIAGNOSTIC_TIMELINE_V382"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"3.8.2 ten-minute diagnostics patch failed: {label} anchor not found")
    return text.replace(old, new, 1)


def patch_hub() -> None:
    text = HUB.read_text()
    if MARKER in text:
        return

    text = replace_once(
        text,
        "// DENSE_DIAGNOSTIC_EVIDENCE_V381\nobject DiagnosticHub {\n"
        "    private const val FATAL_FLUSH_TIMEOUT_MS = 2_000L\n"
        "    private const val DENSE_SELECTED_EVIDENCE_INTERVAL_MS = 240L\n",
        "// DENSE_DIAGNOSTIC_EVIDENCE_V381\n"
        f"// {MARKER}\n"
        "object DiagnosticHub {\n"
        "    private const val FATAL_FLUSH_TIMEOUT_MS = 2_000L\n"
        "    // The 10-minute timeline is the primary visual record. Selected-input snapshots are\n"
        "    // supplemental and deliberately sparse so they cannot consume the timeline budget.\n"
        "    private const val DENSE_SELECTED_EVIDENCE_INTERVAL_MS = 5_000L\n"
        "    private const val TIMELINE_EVIDENCE_INTERVAL_MS = 1_000L\n"
        "    private const val TIMELINE_EVIDENCE_WINDOW_MS = 10L * 60L * 1_000L\n",
        "timeline constants",
    )

    text = replace_once(
        text,
        "    private val lastDenseEvidenceAtElapsedMs = AtomicLong(0L)\n",
        "    private val lastDenseEvidenceAtElapsedMs = AtomicLong(0L)\n"
        "    private val timelineWindowStartedAtElapsedMs = AtomicLong(0L)\n"
        "    private val lastTimelineEvidenceAtElapsedMs = AtomicLong(0L)\n"
        "    private val timelineCompletionRecorded = AtomicLong(0L)\n",
        "timeline clocks",
    )

    text = replace_once(
        text,
        '''        store.enabled = enabled
        record(
            "EVIDENCE_CAPTURE_SETTING",
            mapOf(
                "enabled" to enabled,
                "framesHeld" to store.frameCount(),
                "captureMode" to "dense_selected_input_plus_quality_failures",
                "selectedInputIntervalMs" to DENSE_SELECTED_EVIDENCE_INTERVAL_MS,
            ),
        )
''',
        '''        store.enabled = enabled
        timelineWindowStartedAtElapsedMs.set(0L)
        lastTimelineEvidenceAtElapsedMs.set(0L)
        timelineCompletionRecorded.set(0L)
        lastDenseEvidenceAtElapsedMs.set(0L)
        record(
            "EVIDENCE_CAPTURE_SETTING",
            mapOf(
                "enabled" to enabled,
                "framesHeld" to store.frameCount(),
                "captureMode" to "ten_minute_timeline_plus_supplemental_failures",
                "timelineIntervalMs" to TIMELINE_EVIDENCE_INTERVAL_MS,
                "timelineWindowMs" to TIMELINE_EVIDENCE_WINDOW_MS,
                "selectedInputIntervalMs" to DENSE_SELECTED_EVIDENCE_INTERVAL_MS,
            ),
        )
''',
        "timeline setting state",
    )

    evidence_anchor = '''    fun evidence(bitmap: Bitmap, frameId: String, reason: String, fields: Map<String, Any?> = emptyMap()) {
        val store = evidence ?: return
        if (!store.enabled) return
        val started = SystemClock.elapsedRealtimeNanos()
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
    }

'''
    timeline_function = evidence_anchor + '''    /**
     * Keeps one real screen frame per second for ten minutes while image evidence is enabled.
     * This runs before change-detection throttling, so a static page is still represented across
     * the whole diagnostic window rather than by a single accepted analysis frame.
     */
    fun timelineFrame(
        bitmap: Bitmap,
        frameId: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        val store = evidence ?: return
        if (!store.enabled) return
        val now = SystemClock.elapsedRealtime()
        var windowStart = timelineWindowStartedAtElapsedMs.get()
        if (windowStart == 0L) {
            if (timelineWindowStartedAtElapsedMs.compareAndSet(0L, now)) {
                windowStart = now
                record(
                    "EVIDENCE_TIMELINE_STARTED",
                    mapOf(
                        "durationMs" to TIMELINE_EVIDENCE_WINDOW_MS,
                        "intervalMs" to TIMELINE_EVIDENCE_INTERVAL_MS,
                        "targetFrames" to 600,
                    ),
                )
            } else {
                windowStart = timelineWindowStartedAtElapsedMs.get()
            }
        }
        val elapsed = (now - windowStart).coerceAtLeast(0L)
        if (elapsed > TIMELINE_EVIDENCE_WINDOW_MS) {
            if (timelineCompletionRecorded.compareAndSet(0L, 1L)) {
                record(
                    "EVIDENCE_TIMELINE_COMPLETED",
                    mapOf(
                        "elapsedMs" to elapsed,
                        "framesHeld" to store.frameCount(),
                        "targetFrames" to 600,
                    ),
                )
            }
            return
        }

        while (true) {
            val previous = lastTimelineEvidenceAtElapsedMs.get()
            if (previous > 0L && now - previous < TIMELINE_EVIDENCE_INTERVAL_MS) return
            if (lastTimelineEvidenceAtElapsedMs.compareAndSet(previous, now)) break
        }

        evidence(
            bitmap = bitmap,
            frameId = frameId,
            reason = "timeline_1s",
            fields = fields + mapOf(
                "evidenceRole" to "timeline",
                "timelineElapsedMs" to elapsed,
                "timelineSecond" to (elapsed / 1_000L),
                "timelineIntervalMs" to TIMELINE_EVIDENCE_INTERVAL_MS,
                "timelineWindowMs" to TIMELINE_EVIDENCE_WINDOW_MS,
            ),
        )
    }

'''
    text = replace_once(text, evidence_anchor, timeline_function, "timeline capture function")

    text = replace_once(
        text,
        '''                VisualFingerprintAnalyzer.reset()
                lastDenseEvidenceAtElapsedMs.set(0L)
                target.startSession(command.settings)
''',
        '''                VisualFingerprintAnalyzer.reset()
                lastDenseEvidenceAtElapsedMs.set(0L)
                timelineWindowStartedAtElapsedMs.set(0L)
                lastTimelineEvidenceAtElapsedMs.set(0L)
                timelineCompletionRecorded.set(0L)
                target.startSession(command.settings)
''',
        "timeline session reset",
    )

    HUB.write_text(text)


def patch_service() -> None:
    text = SERVICE.read_text()
    if MARKER in text:
        return

    anchor = '''        if (!container.runtime.analysing.value) {
            recordDroppedFrame(bitmap, trace, "analysis_stopped_by_user", emptyMap())
            bitmap.recycle()
            return
        }

        val intervalSincePrevious = if (lastFrameAt == 0L) Long.MAX_VALUE else now - lastFrameAt
'''
    replacement = '''        if (!container.runtime.analysing.value) {
            recordDroppedFrame(bitmap, trace, "analysis_stopped_by_user", emptyMap())
            bitmap.recycle()
            return
        }

        // TEN_MINUTE_DIAGNOSTIC_TIMELINE_V382: this happens before the normal analysis throttle.
        // Therefore unchanged pages and frames later rejected by the detector remain represented
        // across the full ten-minute diagnostic window when the user explicitly enables images.
        DiagnosticHub.timelineFrame(
            bitmap = bitmap,
            frameId = trace.frameId,
            fields = trace.fields(
                mapOf(
                    "mode" to settings.mode.name,
                    "captureProfile" to settings.captureProfile.name,
                    "captureWidth" to bitmap.width,
                    "captureHeight" to bitmap.height,
                ),
            ),
        )

        val intervalSincePrevious = if (lastFrameAt == 0L) Long.MAX_VALUE else now - lastFrameAt
'''
    text = replace_once(text, anchor, replacement, "service timeline hook")
    SERVICE.write_text(text)


def patch_store() -> None:
    text = STORE.read_text()
    if MARKER in text:
        return

    text = replace_once(
        text,
        "// DENSE_DIAGNOSTIC_EVIDENCE_V381\nclass EvidenceStore(val directory: File) {\n",
        "// DENSE_DIAGNOSTIC_EVIDENCE_V381\n"
        f"// {MARKER}\n"
        "class EvidenceStore(val directory: File) {\n",
        "store marker",
    )

    text = replace_once(
        text,
        '''    private val written = AtomicInteger(0)
    private val skipped = AtomicInteger(0)
    private val bytes = AtomicLong(0L)
''',
        '''    private val written = AtomicInteger(0)
    private val supplementalWritten = AtomicInteger(0)
    private val skipped = AtomicInteger(0)
    private val bytes = AtomicLong(0L)
''',
        "supplemental counter",
    )

    text = replace_once(
        text,
        '''        if (!enabled) return null
        if (written.get() >= MAX_FRAMES || bytes.get() >= MAX_TOTAL_BYTES) {
            skipped.incrementAndGet()
            return null
        }
        return runCatching {
''',
        '''        if (!enabled) return null
        val timelineFrame = reason == "timeline_1s"
        if (!timelineFrame && supplementalWritten.get() >= MAX_SUPPLEMENTAL_FRAMES) {
            skipped.incrementAndGet()
            return null
        }
        if (written.get() >= MAX_FRAMES || bytes.get() >= MAX_TOTAL_BYTES) {
            skipped.incrementAndGet()
            return null
        }
        return runCatching {
''',
        "supplemental budget gate",
    )

    text = replace_once(
        text,
        '''            written.incrementAndGet()
            bytes.addAndGet(file.length())
            name
''',
        '''            written.incrementAndGet()
            if (!timelineFrame) supplementalWritten.incrementAndGet()
            bytes.addAndGet(file.length())
            name
''',
        "supplemental counter increment",
    )

    text = replace_once(
        text,
        '''        "evidenceFrameLimit" to MAX_FRAMES,
        "evidenceByteLimit" to MAX_TOTAL_BYTES,
        "evidenceCaptureMode" to "dense_selected_input_plus_quality_failures",
        "evidenceLongEdgeLimitPx" to MAX_EDGE,
        "evidenceJpegQuality" to JPEG_QUALITY,
''',
        '''        "evidenceFrameLimit" to MAX_FRAMES,
        "evidenceByteLimit" to MAX_TOTAL_BYTES,
        "evidenceCaptureMode" to "ten_minute_timeline_plus_supplemental_failures",
        "evidenceTimelineIntervalMs" to 1_000L,
        "evidenceTimelineWindowMs" to 600_000L,
        "evidenceTimelineTargetFrames" to 600,
        "evidenceSupplementalFrameCount" to supplementalWritten.get(),
        "evidenceSupplementalFrameLimit" to MAX_SUPPLEMENTAL_FRAMES,
        "evidenceLongEdgeLimitPx" to MAX_EDGE,
        "evidenceJpegQuality" to JPEG_QUALITY,
''',
        "timeline manifest",
    )

    text = replace_once(
        text,
        '''        written.set(0)
        skipped.set(0)
        bytes.set(0L)
''',
        '''        written.set(0)
        supplementalWritten.set(0)
        skipped.set(0)
        bytes.set(0L)
''',
        "supplemental clear",
    )

    text = replace_once(
        text,
        '''        const val MAX_FRAMES = 160
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_EDGE = 1920
        const val JPEG_QUALITY = 88
''',
        '''        // 600 timeline frames cover ten minutes at one frame per second. The separate
        // supplemental cap leaves room for failures and selected analysis inputs without allowing
        // them to exhaust the timeline allocation early.
        const val MAX_FRAMES = 820
        const val MAX_SUPPLEMENTAL_FRAMES = 200
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
        const val MAX_EDGE = 1600
        const val JPEG_QUALITY = 82
''',
        "ten-minute storage budget",
    )

    STORE.write_text(text)


def patch_settings() -> None:
    text = SETTINGS.read_text()
    if MARKER in text:
        return

    text = replace_once(
        text,
        "// DENSE_DIAGNOSTIC_EVIDENCE_V381\n                Text(\n",
        "// DENSE_DIAGNOSTIC_EVIDENCE_V381\n"
        f"                // {MARKER}\n"
        "                Text(\n",
        "settings marker",
    )

    text = replace_once(
        text,
        '''                    "يسجل VisionBridge التشخيص تلقائيًا من دون صور افتراضيًا. عند تشغيل حفظ اللقطات، " +
                        "يضيف سلسلة صور متتابعة من المدخل الفعلي للتحليل لتتبع فقد الأسطر والقص والترتيب.",
''',
        '''                    "يسجل VisionBridge التشخيص تلقائيًا من دون صور افتراضيًا. عند تشغيل حفظ اللقطات، " +
                        "ينشئ خطًا بصريًا يغطي عشر دقائق كاملة، مع ربط الصور بنتائج OCR وGemini والتوقيتات.",
''',
        "settings intro",
    )

    text = replace_once(
        text,
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
        '''                    title = "حفظ تشخيص بصري لمدة 10 دقائق",
                    description = if (state.settings.captureFailureEvidence) {
                        "مُفعّل. يحفظ لقطة زمنية كل ثانية لمدة عشر دقائق، أي نحو ٦٠٠ لقطة تغطي المدة كاملة، " +
                            "مع مساحة منفصلة تصل إلى ٢٠٠ لقطة إضافية لفشل الجودة ومدخلات التحليل المهمة. " +
                            "يتوقف الخط الزمني تلقائيًا بعد عشر دقائق. أطفئ الخيار بعد انتهاء الاختبار."
                    } else {
                        "مطفأ. لا تُحفظ صور جديدة. فعّله عند بدء اختبار المشكلة؛ أول صورة فعلية تبدأ نافذة " +
                            "العشر دقائق، ثم تُحفظ صورة كل ثانية حتى نهاية النافذة حتى لو ظل المحتوى ثابتًا."
                    },
''',
        "settings ten-minute switch",
    )

    text = replace_once(
        text,
        '''                        text = "تنبيه: ملف التشخيص القادم سيحتوي سلسلة صور لما كان يدخل التحليل أثناء " +
                            "فترة الاختبار. اسم الملف سيذكر عدد اللقطات.",
''',
        '''                        text = "تنبيه: ملف التشخيص القادم قد يحتوي نحو ٦٠٠ لقطة زمنية لعشر دقائق، " +
                            "إضافة إلى لقطات الأخطاء. قد يكون الملف كبيرًا، واسم الملف سيذكر عدد الصور.",
''',
        "settings storage warning",
    )

    text = text.replace(
        '"مشاركة ملف التشخيص، ويحتوي سلسلة لقطات التحليل السريعة والتوقيتات ونتائج OCR وGemini وحالة النطق"',
        '"مشاركة ملف التشخيص، ويحتوي خطًا بصريًا لمدة عشر دقائق والتوقيتات ونتائج OCR وGemini وحالة النطق"',
        1,
    )
    SETTINGS.write_text(text)


def patch_shortcut() -> None:
    text = SHORTCUT.read_text()
    if MARKER in text:
        return

    text = replace_once(
        text,
        "// DENSE_DIAGNOSTIC_EVIDENCE_V381\nobject EvidenceShortcut {\n",
        "// DENSE_DIAGNOSTIC_EVIDENCE_V381\n"
        f"// {MARKER}\n"
        "object EvidenceShortcut {\n",
        "shortcut marker",
    )

    text = replace_once(
        text,
        '''                append("شُغّل حفظ لقطات التشخيص السريع.")
                append(" ستُحفظ المدخلات الفعلية للتحليل بصورة متتابعة، مع لقطات فشل الجودة، بحد أقصى $FRAME_LIMIT لقطة.")
''',
        '''                append("شُغّل التشخيص البصري لعشر دقائق.")
                append(" سيُحفظ نحو ٦٠٠ لقطة زمنية، صورة كل ثانية، مع مساحة للقطات فشل إضافية. الحد الكلي $FRAME_LIMIT لقطة.")
''',
        "shortcut announcement",
    )

    text = replace_once(
        text,
        "    const val FRAME_LIMIT = 160\n",
        "    const val FRAME_LIMIT = 820\n",
        "shortcut frame limit",
    )
    SHORTCUT.write_text(text)


def patch_recorder() -> None:
    text = RECORDER.read_text()
    if MARKER in text:
        return

    text = replace_once(
        text,
        "// DENSE_DIAGNOSTIC_EVIDENCE_V381\nclass DiagnosticRecorder(context: Context) {\n",
        "// DENSE_DIAGNOSTIC_EVIDENCE_V381\n"
        f"// {MARKER}\n"
        "class DiagnosticRecorder(context: Context) {\n",
        "recorder marker",
    )

    text = text.replace(
        '"«حفظ لقطات التشخيص السريع». تشمل مدخلات التحليل المتتابعة ولقطات فشل الجودة. مجلد evidence/."',
        '"«حفظ تشخيص بصري لمدة 10 دقائق». تشمل لقطة زمنية كل ثانية ولقطات فشل إضافية. مجلد evidence/."',
        1,
    )
    text = text.replace(
        '"selected_input_dense هي المدخلات الفعلية المتتابعة التي دخلت التحليل، " +\n'
        '                        "وتوجد أيضاً لقطات لأعطال جودة الصورة عند وقوعها. إيقاف الحفظ من إشعار " +',
        '"timeline_1s تكوّن الخط الزمني، صورة كل ثانية حتى عشر دقائق، وselected_input_dense " +\n'
        '                        "عينات إضافية من مدخلات التحليل، مع لقطات أعطال الجودة. إيقاف الحفظ من إشعار " +',
        1,
    )
    RECORDER.write_text(text)


patch_hub()
patch_service()
patch_store()
patch_settings()
patch_shortcut()
patch_recorder()
print("Applied VisionBridge 3.8.2 ten-minute one-frame-per-second diagnostic timeline")
