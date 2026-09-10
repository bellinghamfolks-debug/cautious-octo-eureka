#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
HUB = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/diagnostics/DiagnosticHub.kt"
STORE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/diagnostics/EvidenceStore.kt"
SETTINGS = ROOT / "app/src/main/java/com/abdullah/visionbridge/ui/SettingsScreen.kt"
MARKER = "EVERY_ANALYSIS_INPUT_EVIDENCE_V383"

def sub1(text, pattern, replacement, label, flags=0):
    out, n = re.subn(pattern, replacement, text, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f"3.8.3 patch failed: {label} count={n}")
    return out

h = HUB.read_text()
if MARKER not in h:
    h = h.replace("// TEN_MINUTE_DIAGNOSTIC_TIMELINE_HARDENING_V382\nobject DiagnosticHub {", f"// TEN_MINUTE_DIAGNOSTIC_TIMELINE_HARDENING_V382\n// {MARKER}\nobject DiagnosticHub {{", 1)
    h = sub1(
        h,
        r'''        captureDenseSelectedInputEvidence\(bitmap, frameId, stage, metadata\)\n    \}\n\n    private fun captureDenseSelectedInputEvidence\(.*?\n    \}\n\n    /\*\* Records a representative rejected frame''',
        '''        captureExactAnalysisInputEvidence(bitmap, frameId, stage, metadata)\n    }\n\n    /** Save every selected OCR/Gemini visual input while opt-in diagnostics are enabled. */\n    private fun captureExactAnalysisInputEvidence(\n        bitmap: Bitmap,\n        frameId: String,\n        stage: String,\n        metadata: Map<String, Any?>,\n    ) {\n        val store = evidence ?: return\n        if (!store.enabled) return\n        val safeStage = stage.replace(Regex("[^A-Za-z0-9_-]"), "_").take(20)\n        evidence(\n            bitmap = bitmap,\n            frameId = frameId,\n            reason = "analysis_input_$safeStage",\n            fields = metadata + mapOf(\n                "evidenceRole" to "analysis_input",\n                "sourceStage" to stage,\n                "analysisInputCapturePolicy" to "every_selected_input",\n                "analysisInputThrottled" to false,\n            ),\n        )\n    }\n\n    /** Records a representative rejected frame''',
        "exact analysis input hook",
        re.S,
    )
    h = h.replace('"selectedInputIntervalMs" to DENSE_SELECTED_EVIDENCE_INTERVAL_MS,', '"selectedInputCapturePolicy" to "every_selected_input",')
    HUB.write_text(h)

s = STORE.read_text()
if MARKER not in s:
    s = s.replace("// TEN_MINUTE_DIAGNOSTIC_TIMELINE_HARDENING_V382\nclass EvidenceStore", f"// TEN_MINUTE_DIAGNOSTIC_TIMELINE_HARDENING_V382\n// {MARKER}\nclass EvidenceStore", 1)
    s = s.replace(
        "    private val supplementalBytes = AtomicLong(0L)\n    private val skipped = AtomicInteger(0)",
        "    private val supplementalBytes = AtomicLong(0L)\n    private val analysisInputWritten = AtomicInteger(0)\n    private val analysisInputBytes = AtomicLong(0L)\n    private val analysisInputSkipped = AtomicInteger(0)\n    private val skipped = AtomicInteger(0)", 1)
    s = sub1(
        s,
        r'''        val timelineFrame = reason == "timeline_1s"\n        if \(\n            !timelineFrame &&\n            \(supplementalWritten.get\(\) >= MAX_SUPPLEMENTAL_FRAMES \|\|\n                supplementalBytes.get\(\) >= MAX_SUPPLEMENTAL_BYTES\)\n        \) \{\n            skipped.incrementAndGet\(\)\n            return null\n        \}\n        if \(written.get\(\) >= MAX_FRAMES \|\| bytes.get\(\) >= MAX_TOTAL_BYTES\) \{\n            skipped.incrementAndGet\(\)\n            return null\n        \}''',
        '''        val timelineFrame = reason == "timeline_1s"\n        val analysisInputFrame = reason.startsWith("analysis_input_")\n        val supplementalFrame = !timelineFrame && !analysisInputFrame\n        if (analysisInputFrame && (analysisInputWritten.get() >= MAX_ANALYSIS_INPUT_FRAMES || analysisInputBytes.get() >= MAX_ANALYSIS_INPUT_BYTES)) {\n            analysisInputSkipped.incrementAndGet()\n            skipped.incrementAndGet()\n            return null\n        }\n        if (supplementalFrame && (supplementalWritten.get() >= MAX_SUPPLEMENTAL_FRAMES || supplementalBytes.get() >= MAX_SUPPLEMENTAL_BYTES)) {\n            skipped.incrementAndGet()\n            return null\n        }\n        if (written.get() >= MAX_FRAMES || bytes.get() >= MAX_TOTAL_BYTES) {\n            if (analysisInputFrame) analysisInputSkipped.incrementAndGet()\n            skipped.incrementAndGet()\n            return null\n        }''',
        "category budget gate",
    )
    s = s.replace(
        "            written.incrementAndGet()\n            if (!timelineFrame) {\n                supplementalWritten.incrementAndGet()\n                supplementalBytes.addAndGet(file.length())\n            }\n            bytes.addAndGet(file.length())",
        "            written.incrementAndGet()\n            when {\n                analysisInputFrame -> { analysisInputWritten.incrementAndGet(); analysisInputBytes.addAndGet(file.length()) }\n                supplementalFrame -> { supplementalWritten.incrementAndGet(); supplementalBytes.addAndGet(file.length()) }\n            }\n            bytes.addAndGet(file.length())", 1)
    s = s.replace(
        '        "evidenceSupplementalFrameCount" to supplementalWritten.get(),',
        '        "evidenceAnalysisInputCapturePolicy" to "every_selected_input",\n        "evidenceAnalysisInputFrameCount" to analysisInputWritten.get(),\n        "evidenceAnalysisInputFrameLimit" to MAX_ANALYSIS_INPUT_FRAMES,\n        "evidenceAnalysisInputFramesSkipped" to analysisInputSkipped.get(),\n        "evidenceAnalysisInputBytes" to analysisInputBytes.get(),\n        "evidenceAnalysisInputByteLimit" to MAX_ANALYSIS_INPUT_BYTES,\n        "evidenceSupplementalFrameCount" to supplementalWritten.get(),', 1)
    s = s.replace(
        "        supplementalBytes.set(0L)\n        skipped.set(0)",
        "        supplementalBytes.set(0L)\n        analysisInputWritten.set(0)\n        analysisInputBytes.set(0L)\n        analysisInputSkipped.set(0)\n        skipped.set(0)", 1)
    s = s.replace(
        "        const val MAX_FRAMES = 820\n        const val MAX_SUPPLEMENTAL_FRAMES = 200\n        // Supplemental evidence gets its own byte ceiling so even unusually noisy JPEGs cannot\n        // consume the space intended for the 600 timeline frames.\n        const val MAX_SUPPLEMENTAL_BYTES = 96L * 1024 * 1024\n        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024",
        "        const val MAX_ANALYSIS_INPUT_FRAMES = 2_400\n        const val MAX_ANALYSIS_INPUT_BYTES = 768L * 1024 * 1024\n        const val MAX_SUPPLEMENTAL_FRAMES = 200\n        const val MAX_SUPPLEMENTAL_BYTES = 96L * 1024 * 1024\n        const val MAX_FRAMES = 3_200\n        const val MAX_TOTAL_BYTES = 1_280L * 1024 * 1024", 1)
    STORE.write_text(s)

u = SETTINGS.read_text()
if MARKER not in u:
    u = u.replace("// TEN_MINUTE_DIAGNOSTIC_TIMELINE_V382\n                Text(", f"// TEN_MINUTE_DIAGNOSTIC_TIMELINE_V382\n                // {MARKER}\n                Text(", 1)
    u = u.replace(
        '"مُفعّل. يحفظ لقطة زمنية كل ثانية لمدة عشر دقائق، أي نحو ٦٠٠ لقطة تغطي المدة كاملة، " +\n                            "مع مساحة منفصلة تصل إلى ٢٠٠ لقطة إضافية لفشل الجودة ومدخلات التحليل المهمة. " +\n                            "يتوقف الخط الزمني تلقائيًا بعد عشر دقائق. أطفئ الخيار بعد انتهاء الاختبار."',
        '"مُفعّل. يحفظ لقطة زمنية كل ثانية لمدة عشر دقائق، أي نحو ٦٠٠ لقطة تغطي المدة كاملة، " +\n                            "ويحفظ أيضًا كل صورة تُختار فعليًا لتدخل OCR أو Gemini، سواء نجحت القراءة أم فشلت، " +\n                            "مع لقطات فشل الجودة. قد يصبح ملف التشخيص كبيرًا؛ أطفئ الخيار بعد انتهاء الاختبار."', 1)
    SETTINGS.write_text(u)

print("Applied VisionBridge 3.8.3 every-analysis-input evidence")
