#!/usr/bin/env python3
"""Patch the generated PaddleOCR integration with schema-v4 image-free diagnostics."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/abdullah/visionbridge"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}: {old[:100]!r}; got {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def patch_media_projection() -> None:
    path = JAVA / "capture/MediaProjectionService.kt"
    replace_once(
        path,
        '                    "captureProfile" to settings.captureProfile.name,\n',
        '                    "captureProfile" to settings.captureProfile.name,\n'
        '                    "textRecognitionEngine" to settings.textRecognitionEngine.name,\n',
    )
    replace_once(
        path,
        '        "localOcrEnabled" to settings.localOcrEnabled,\n',
        '        "localOcrEnabled" to settings.localOcrEnabled,\n'
        '        "textRecognitionEngine" to settings.textRecognitionEngine.name,\n'
        '        "localOcrImplementation" to if (settings.textRecognitionEngine.name == "PADDLE_LOCAL") "PP-OCRv5 Arabic+English arm64" else "Gemini",\n',
    )


def patch_hub() -> None:
    path = JAVA / "data/diagnostics/DiagnosticHub.kt"
    replace_once(
        path,
        '                "visualEvidence" to "aggregate_fingerprint",\n',
        '                "visualEvidence" to "aggregate_fingerprint",\n'
        '                "diagnosticSchema" to 4,\n'
        '                "localOcrDiagnostics" to "PP-OCRv5 Arabic+English per-line confidence and timing",\n'
        '                "storesPaddleInputImages" to false,\n',
    )


def patch_native() -> None:
    path = ROOT / "app/src/main/cpp/visionbridge_paddleocr.cpp"
    replace_once(path, '#include <climits>\n', '#include <climits>\n#include <chrono>\n')
    replace_once(
        path,
        '            auto boxes = g_detector->Predict(bgr, config, nullptr, nullptr, nullptr);\n'
        '            const int limit = std::min(static_cast<int>(boxes.size()),\n',
        '            const auto native_started = std::chrono::steady_clock::now();\n'
        '            const auto detector_started = std::chrono::steady_clock::now();\n'
        '            auto boxes = g_detector->Predict(bgr, config, nullptr, nullptr, nullptr);\n'
        '            const double detector_ms = std::chrono::duration<double, std::milli>(\n'
        '                std::chrono::steady_clock::now() - detector_started).count();\n'
        '            const int limit = std::min(static_cast<int>(boxes.size()),\n',
    )
    replace_once(
        path,
        '            json << "{\\\"error\\\":null,\\\"width\\\":" << info.width\n'
        '                 << ",\\\"height\\\":" << info.height << ",\\\"lines\\\":[";\n',
        '            json << "{\\\"error\\\":null,\\\"width\\\":" << info.width\n'
        '                 << ",\\\"height\\\":" << info.height\n'
        '                 << ",\\\"detectorMs\\\":" << detector_ms << ",\\\"lines\\\":[";\n',
    )
    replace_once(
        path,
        '                auto ar = g_arabic->Predict(crop, nullptr, nullptr, nullptr, g_arabic_dict);\n'
        '                auto en = g_english->Predict(crop, nullptr, nullptr, nullptr, g_english_dict);\n',
        '                const auto ar_started = std::chrono::steady_clock::now();\n'
        '                auto ar = g_arabic->Predict(crop, nullptr, nullptr, nullptr, g_arabic_dict);\n'
        '                const double ar_ms = std::chrono::duration<double, std::milli>(\n'
        '                    std::chrono::steady_clock::now() - ar_started).count();\n'
        '                const auto en_started = std::chrono::steady_clock::now();\n'
        '                auto en = g_english->Predict(crop, nullptr, nullptr, nullptr, g_english_dict);\n'
        '                const double en_ms = std::chrono::duration<double, std::milli>(\n'
        '                    std::chrono::steady_clock::now() - en_started).count();\n',
    )
    replace_once(
        path,
        '                json << "],\\\"arabic\\\":\\\"" << JsonEscape(ar.first) << "\\\",\\\"arabicScore\\\":" << ar.second\n'
        '                     << ",\\\"english\\\":\\\"" << JsonEscape(en.first) << "\\\",\\\"englishScore\\\":" << en.second << \'}\';\n',
        '                json << "],\\\"arabic\\\":\\\"" << JsonEscape(ar.first) << "\\\",\\\"arabicScore\\\":" << ar.second\n'
        '                     << ",\\\"arabicMs\\\":" << ar_ms\n'
        '                     << ",\\\"english\\\":\\\"" << JsonEscape(en.first) << "\\\",\\\"englishScore\\\":" << en.second\n'
        '                     << ",\\\"englishMs\\\":" << en_ms << \'}\';\n',
    )
    replace_once(
        path,
        '            json << "]}";\n'
        '            result = json.str();\n',
        '            const double native_total_ms = std::chrono::duration<double, std::milli>(\n'
        '                std::chrono::steady_clock::now() - native_started).count();\n'
        '            json << "],\\\"nativeTotalMs\\\":" << native_total_ms << "}";\n'
        '            result = json.str();\n',
    )


def patch_policy() -> None:
    path = JAVA / "data/ocr/PaddleOcrTextPolicy.kt"
    anchor = '''
            fun readingOrder(lines: List<PaddleAcceptedLine>): List<PaddleAcceptedLine> {
    '''
    addition = '''
            fun rejectionReason(
                raw: PaddleRawLine,
                width: Int,
                height: Int,
                profile: CaptureProfile,
            ): String? {
                if (width <= 0 || height <= 0) return "invalid_frame_geometry"
                if (raw.box.size != 4 || raw.box.any { it.size < 2 }) return "invalid_text_box"
                val ar = sanitize(raw.arabic)
                val en = sanitize(raw.english)
                val arCandidate = scoreCandidate(ar, raw.arabicScore, expectsArabic = true)
                val enCandidate = scoreCandidate(en, raw.englishScore, expectsArabic = false)
                val chosen = choose(arCandidate, enCandidate) ?: return "both_recognizers_blank_or_invalid"
                if (chosen.second < threshold(chosen.first, profile)) return "confidence_below_${threshold(chosen.first, profile)}"
                if (suspicious(chosen.first)) return "suspicious_symbols_or_unicode"
                return null
            }

'''
    replace_once(path, anchor, addition + anchor)


def patch_models() -> None:
    path = JAVA / "data/ocr/PaddleOcrModels.kt"
    replace_once(
        path,
        '            suspend fun install(context: Context): PaddleOcrModelPaths = withContext(Dispatchers.IO) {\n'
        '                val destination = File(context.noBackupFilesDir, "paddleocr-v5-arm64").apply { mkdirs() }\n',
        '            suspend fun install(context: Context): PaddleOcrModelPaths = withContext(Dispatchers.IO) {\n'
        '                val installStarted = android.os.SystemClock.elapsedRealtimeNanos()\n'
        '                val destination = File(context.noBackupFilesDir, "paddleocr-v5-arm64").apply { mkdirs() }\n',
    )
    replace_once(
        path,
        '                DiagnosticHub.record(\n'
        '                    "PADDLE_OCR_MODELS_READY",\n'
        '                    mapOf(\n'
        '                        "engine" to "PaddleOCR",\n'
        '                        "version" to "PP-OCRv5",\n'
        '                        "abi" to "arm64-v8a",\n'
        '                        "arabicModel" to "arabic_PP-OCRv5_mobile_rec",\n'
        '                        "englishModel" to "en_PP-OCRv5_mobile_rec",\n'
        '                        "orientationClassifier" to false,\n'
        '                    ),\n'
        '                )\n',
        '                val installedFiles = required.map { name ->\n'
        '                    val file = File(destination, name)\n'
        '                    mapOf(\n'
        '                        "name" to name,\n'
        '                        "bytes" to file.length(),\n'
        '                        "expectedSha256" to expected.getProperty(name),\n'
        '                        "actualSha256" to sha256(file),\n'
        '                    )\n'
        '                }\n'
        '                DiagnosticHub.record(\n'
        '                    "PADDLE_OCR_MODELS_READY",\n'
        '                    mapOf(\n'
        '                        "engine" to "PaddleOCR",\n'
        '                        "version" to "PP-OCRv5",\n'
        '                        "abi" to "arm64-v8a",\n'
        '                        "arabicModel" to "arabic_PP-OCRv5_mobile_rec",\n'
        '                        "englishModel" to "en_PP-OCRv5_mobile_rec",\n'
        '                        "detectorModel" to "PP-OCRv5_mobile_det",\n'
        '                        "orientationClassifier" to false,\n'
        '                        "integrityVerified" to installedFiles.all { it["expectedSha256"] == it["actualSha256"] },\n'
        '                        "files" to installedFiles,\n'
        '                        "modelInstallMs" to (android.os.SystemClock.elapsedRealtimeNanos() - installStarted) / 1_000_000.0,\n'
        '                    ),\n'
        '                )\n',
    )


def patch_recognizer() -> None:
    path = JAVA / "data/ocr/LocalTextRecognizer.kt"
    replace_once(
        path,
        '                val started = SystemClock.elapsedRealtimeNanos()\n'
        '                val payload = withContext(Dispatchers.Default) {\n',
        '                val started = SystemClock.elapsedRealtimeNanos()\n'
        '                val runtime = Runtime.getRuntime()\n'
        '                val memoryBefore = runtime.totalMemory() - runtime.freeMemory()\n'
        '                val payload = runCatching {\n'
        '                    withContext(Dispatchers.Default) {\n',
    )
    replace_once(
        path,
        '                    PaddleOcrNative.nativeRecognize(bitmap, if (profile == CaptureProfile.FAST_TEXT) 24 else 48)\n'
        '                }\n'
        '                val root = JSONObject(payload)\n',
        '                        PaddleOcrNative.nativeRecognize(bitmap, if (profile == CaptureProfile.FAST_TEXT) 24 else 48)\n'
        '                    }\n'
        '                }.getOrElse { error ->\n'
        '                    DiagnosticHub.failure("PADDLE_OCR_NATIVE", error, trace.fieldsOrEmpty(mapOf("captureProfile" to profile.name)))\n'
        '                    throw error\n'
        '                }\n'
        '                val root = JSONObject(payload)\n',
    )
    replace_once(
        path,
        '                                englishScore = item.optDouble("englishScore", 0.0),\n'
        '                            ),\n',
        '                                englishScore = item.optDouble("englishScore", 0.0),\n'
        '                            ),\n',
    )
    replace_once(
        path,
        '                val accepted = PaddleOcrTextPolicy.readingOrder(\n'
        '                    rawLines.mapNotNull { PaddleOcrTextPolicy.accept(it, width, height, profile) },\n'
        '                )\n'
        '                val text = accepted.joinToString("\\n") { it.text }.trim()\n',
        '                val decisions = rawLines.map { raw ->\n'
        '                    val accepted = PaddleOcrTextPolicy.accept(raw, width, height, profile)\n'
        '                    val reason = if (accepted == null) PaddleOcrTextPolicy.rejectionReason(raw, width, height, profile) else null\n'
        '                    Triple(raw, accepted, reason)\n'
        '                }\n'
        '                val accepted = PaddleOcrTextPolicy.readingOrder(decisions.mapNotNull { it.second })\n'
        '                val text = accepted.joinToString("\\n") { it.text }.trim()\n'
        '                val memoryAfter = runtime.totalMemory() - runtime.freeMemory()\n'
        '                val linesJson = root.optJSONArray("lines")\n'
        '                val lineDiagnostics = decisions.mapIndexed { index, decision ->\n'
        '                    val native = linesJson?.optJSONObject(index)\n'
        '                    mapOf(\n'
        '                        "index" to index,\n'
        '                        "arabicCandidate" to decision.first.arabic,\n'
        '                        "arabicScore" to decision.first.arabicScore,\n'
        '                        "arabicMs" to native?.optDouble("arabicMs", 0.0),\n'
        '                        "englishCandidate" to decision.first.english,\n'
        '                        "englishScore" to decision.first.englishScore,\n'
        '                        "englishMs" to native?.optDouble("englishMs", 0.0),\n'
        '                        "accepted" to (decision.second != null),\n'
        '                        "acceptedText" to decision.second?.text,\n'
        '                        "acceptedConfidence" to decision.second?.confidence,\n'
        '                        "rejectionReason" to decision.third,\n'
        '                        "box" to decision.first.box,\n'
        '                    )\n'
        '                }\n'
        '                val rejectionReasons = decisions.mapNotNull { it.third }.groupingBy { it }.eachCount()\n',
    )
    replace_once(
        path,
        '                            "durationMs" to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,\n'
        '                            "rawLineCount" to rawLines.size,\n'
        '                            "acceptedLineCount" to accepted.size,\n'
        '                            "rejectedLineCount" to rawLines.size - accepted.size,\n',
        '                            "durationMs" to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,\n'
        '                            "detectorMs" to root.optDouble("detectorMs", 0.0),\n'
        '                            "nativeTotalMs" to root.optDouble("nativeTotalMs", 0.0),\n'
        '                            "arabicRecognitionMs" to lineDiagnostics.sumOf { (it["arabicMs"] as? Number)?.toDouble() ?: 0.0 },\n'
        '                            "englishRecognitionMs" to lineDiagnostics.sumOf { (it["englishMs"] as? Number)?.toDouble() ?: 0.0 },\n'
        '                            "rawLineCount" to rawLines.size,\n'
        '                            "acceptedLineCount" to accepted.size,\n'
        '                            "rejectedLineCount" to rawLines.size - accepted.size,\n'
        '                            "rejectionReasons" to rejectionReasons,\n'
        '                            "lineDiagnostics" to lineDiagnostics,\n'
        '                            "memoryBeforeBytes" to memoryBefore,\n'
        '                            "memoryAfterBytes" to memoryAfter,\n'
        '                            "memoryDeltaBytes" to memoryAfter - memoryBefore,\n'
        '                            "runtimeMaxMemoryBytes" to runtime.maxMemory(),\n'
        '                            "cpuThreads" to 4,\n'
        '                            "abi" to android.os.Build.SUPPORTED_ABIS.joinToString(","),\n',
    )
    replace_once(
        path,
        '                        val paths = PaddleOcrModels.install(context.applicationContext)\n'
        '                        check(\n',
        '                        DiagnosticHub.record("PADDLE_OCR_INITIALIZATION_STARTED", mapOf("engine" to "PP-OCRv5", "abi" to "arm64-v8a"))\n'
        '                        val initStarted = SystemClock.elapsedRealtimeNanos()\n'
        '                        val paths = runCatching { PaddleOcrModels.install(context.applicationContext) }.getOrElse { error ->\n'
        '                            DiagnosticHub.failure("PADDLE_OCR_MODEL_INSTALL", error)\n'
        '                            throw error\n'
        '                        }\n'
        '                        check(\n',
    )
    replace_once(
        path,
        '                        ) { "تعذر تهيئة PaddleOCR على هذا الجهاز" }\n'
        '                        initialized = true\n',
        '                        ) { "تعذر تهيئة PaddleOCR على هذا الجهاز" }\n'
        '                        initialized = true\n'
        '                        DiagnosticHub.record(\n'
        '                            "PADDLE_OCR_INITIALIZED",\n'
        '                            mapOf(\n'
        '                                "initializationMs" to (SystemClock.elapsedRealtimeNanos() - initStarted) / 1_000_000.0,\n'
        '                                "engine" to "PP-OCRv5",\n'
        '                                "detector" to "PP-OCRv5_mobile_det",\n'
        '                                "arabicRecognizer" to "arabic_PP-OCRv5_mobile_rec",\n'
        '                                "englishRecognizer" to "en_PP-OCRv5_mobile_rec",\n'
        '                                "orientationClassifier" to false,\n'
        '                                "manualRtlReversal" to false,\n'
        '                                "spellCorrection" to false,\n'
        '                                "generativeModel" to false,\n'
        '                                "networkUsed" to false,\n'
        '                            ),\n'
        '                        )\n',
    )


def patch_recorder() -> None:
    path = JAVA / "data/diagnostics/DiagnosticRecorder.kt"
    replace_once(
        path,
        '        var fingerprintCount: Int = 0,\n',
        '        var fingerprintCount: Int = 0,\n'
        '        var paddleRawLines: Int = 0,\n'
        '        var paddleAcceptedLines: Int = 0,\n'
        '        var paddleRejectedLines: Int = 0,\n'
        '        var paddleDetectorMs: Double? = null,\n'
        '        var paddleArabicMs: Double? = null,\n'
        '        var paddleEnglishMs: Double? = null,\n'
        '        var paddleNativeTotalMs: Double? = null,\n'
        '        var paddleMinConfidence: Double? = null,\n'
        '        var paddleMaxConfidence: Double? = null,\n'
        '        var paddleMemoryDeltaBytes: Long = 0L,\n',
    )
    replace_once(
        path,
        '            val traceAnalysis = buildTraceAnalysisLocked()\n',
        '            val traceAnalysis = buildTraceAnalysisLocked()\n'
        '            val paddleAnalysis = buildPaddleOcrAnalysisLocked()\n',
    )
    replace_once(
        path,
        '                writeZipText(zip, "trace_analysis.json", traceAnalysis.toString(2))\n',
        '                writeZipText(zip, "trace_analysis.json", traceAnalysis.toString(2))\n'
        '                writeZipText(zip, "paddleocr_analysis.json", paddleAnalysis.toString(2))\n',
    )
    replace_once(
        path,
        '                        put("includesPerTraceAnalysis", true)\n',
        '                        put("includesPerTraceAnalysis", true)\n'
        '                        put("includesPaddleOcrAnalysis", true)\n'
        '                        put("paddleOcrDiagnosticSchema", 1)\n'
        '                        put("includesPerLinePaddleCandidates", true)\n'
        '                        put("includesPaddleConfidenceAndRejectionReasons", true)\n'
        '                        put("includesPaddleNativeTimings", true)\n'
        '                        put("includesPaddleModelIntegrityHashes", true)\n'
        '                        put("includesPaddleMemoryTelemetry", true)\n'
        '                        put("storesPaddleInputImages", false)\n',
    )
    failure_anchor = '''
        if (type == "FAILURE") {
            output += finding(
                code = "APP_STAGE_FAILURE",
'''
    failure_new = '''
        if (type == "FAILURE") {
            output += finding(
                code = "APP_STAGE_FAILURE",
'''
    # Insert Paddle-specific failure after the existing generic failure block.
    generic_end = '''
            )
        }

        if (type.contains("CANCELLED") || type.contains("CANCELED")) {
'''
    paddle_failure = '''
            )
            val stage = fields["stage"]?.toString().orEmpty()
            if (stage.startsWith("PADDLE_OCR")) {
                output += finding(
                    code = "PADDLE_OCR_STAGE_FAILURE",
                    severity = "critical",
                    explanation = "فشل مسار PaddleOCR المحلي أو أحد نماذجه",
                    details = mapOf("stage" to stage, "exception" to fields["exception"], "message" to fields["message"]),
                )
            }
        }

        if (type == "PADDLE_OCR_COMPLETED") {
            val raw = (fields["rawLineCount"] as? Number)?.toInt() ?: 0
            val accepted = (fields["acceptedLineCount"] as? Number)?.toInt() ?: 0
            val rejected = (fields["rejectedLineCount"] as? Number)?.toInt() ?: 0
            val nativeMs = (fields["nativeTotalMs"] as? Number)?.toDouble() ?: 0.0
            val memoryDelta = (fields["memoryDeltaBytes"] as? Number)?.toLong() ?: 0L
            if (raw > 0 && accepted == 0) {
                output += finding(
                    code = "PADDLE_ALL_LINES_REJECTED",
                    severity = "warning",
                    explanation = "اكتشف PaddleOCR أسطراً لكنه رفضها كلها بسبب الثقة أو سلامة الرموز",
                    details = mapOf("rawLineCount" to raw, "rejectionReasons" to fields["rejectionReasons"]),
                )
            } else if (raw >= 4 && rejected.toDouble() / raw >= 0.75) {
                output += finding(
                    code = "PADDLE_HIGH_REJECTION_RATIO",
                    severity = "warning",
                    explanation = "نسبة كبيرة من أسطر PaddleOCR لم تجتز بوابة الثقة",
                    details = mapOf("rawLineCount" to raw, "acceptedLineCount" to accepted, "rejectedLineCount" to rejected),
                )
            }
            if (nativeMs >= PADDLE_SLOW_INFERENCE_MS) {
                output += finding(
                    code = "PADDLE_SLOW_LOCAL_INFERENCE",
                    severity = if (nativeMs >= PADDLE_CRITICAL_INFERENCE_MS) "critical" else "warning",
                    explanation = "استغرق تحليل PaddleOCR المحلي زمناً طويلاً",
                    details = mapOf("nativeTotalMs" to nativeMs, "detectorMs" to fields["detectorMs"], "arabicRecognitionMs" to fields["arabicRecognitionMs"], "englishRecognitionMs" to fields["englishRecognitionMs"]),
                )
            }
            if (memoryDelta >= PADDLE_MEMORY_WARNING_BYTES) {
                output += finding(
                    code = "PADDLE_MEMORY_PRESSURE",
                    severity = "warning",
                    explanation = "ارتفع استهلاك الذاكرة أثناء تحليل PaddleOCR المحلي",
                    details = mapOf("memoryDeltaBytes" to memoryDelta, "runtimeMaxMemoryBytes" to fields["runtimeMaxMemoryBytes"]),
                )
            }
        }

        if (type == "PADDLE_OCR_MODELS_READY" && fields["integrityVerified"] != true) {
            output += finding(
                code = "PADDLE_MODEL_INTEGRITY_NOT_VERIFIED",
                severity = "critical",
                explanation = "لم تنجح مطابقة بصمات نماذج PaddleOCR المثبتة",
                details = mapOf("files" to fields["files"]),
            )
        }

        if (type.contains("CANCELLED") || type.contains("CANCELED")) {
'''
    replace_once(path, generic_end, paddle_failure)

    update_anchor = '''
            "GEMINI_ANALYSIS_REQUESTED" ->
                stats.firstCloudRequestMs = minNullable(stats.firstCloudRequestMs, since)
'''
    update_new = '''
            "PADDLE_OCR_COMPLETED" -> {
                stats.maxLocalChars = maxOf(stats.maxLocalChars, textLength)
                stats.firstLocalMs = minNullable(stats.firstLocalMs, since)
                stats.paddleRawLines += event.optInt("rawLineCount", 0)
                stats.paddleAcceptedLines += event.optInt("acceptedLineCount", 0)
                stats.paddleRejectedLines += event.optInt("rejectedLineCount", 0)
                stats.paddleDetectorMs = maxNullable(stats.paddleDetectorMs, event.optDoubleOrNull("detectorMs"))
                stats.paddleArabicMs = maxNullable(stats.paddleArabicMs, event.optDoubleOrNull("arabicRecognitionMs"))
                stats.paddleEnglishMs = maxNullable(stats.paddleEnglishMs, event.optDoubleOrNull("englishRecognitionMs"))
                stats.paddleNativeTotalMs = maxNullable(stats.paddleNativeTotalMs, event.optDoubleOrNull("nativeTotalMs"))
                stats.paddleMinConfidence = minNullable(stats.paddleMinConfidence, event.optDoubleOrNull("minimumConfidence"))
                stats.paddleMaxConfidence = maxNullable(stats.paddleMaxConfidence, event.optDoubleOrNull("maximumConfidence"))
                stats.paddleMemoryDeltaBytes = maxOf(stats.paddleMemoryDeltaBytes, event.optLong("memoryDeltaBytes", 0L))
            }
            "GEMINI_ANALYSIS_REQUESTED" ->
                stats.firstCloudRequestMs = minNullable(stats.firstCloudRequestMs, since)
'''
    replace_once(path, update_anchor, update_new)

    trace_anchor = '''
            put("cancellationCount", stats.cancellationCount)
'''
    trace_new = '''
            put(
                "paddleOcr",
                JSONObject().apply {
                    put("rawLineCount", stats.paddleRawLines)
                    put("acceptedLineCount", stats.paddleAcceptedLines)
                    put("rejectedLineCount", stats.paddleRejectedLines)
                    putNullable("detectorMs", stats.paddleDetectorMs)
                    putNullable("arabicRecognitionMs", stats.paddleArabicMs)
                    putNullable("englishRecognitionMs", stats.paddleEnglishMs)
                    putNullable("nativeTotalMs", stats.paddleNativeTotalMs)
                    putNullable("minimumConfidence", stats.paddleMinConfidence)
                    putNullable("maximumConfidence", stats.paddleMaxConfidence)
                    put("maximumMemoryDeltaBytes", stats.paddleMemoryDeltaBytes)
                },
            )
            put("cancellationCount", stats.cancellationCount)
'''
    replace_once(path, trace_anchor, trace_new)

    function_anchor = '''
    private fun pruneRetainedSessionsLocked(): Map<String, Any?> {
'''
    paddle_function = '''
    private fun buildPaddleOcrAnalysisLocked(): JSONObject {
        var modelReadyEvents = 0
        var initializationEvents = 0
        var completedEvents = 0
        var failureEvents = 0
        var rawLines = 0
        var acceptedLines = 0
        var rejectedLines = 0
        var maximumNativeMs = 0.0
        var maximumDetectorMs = 0.0
        var maximumMemoryDelta = 0L
        var minimumConfidence: Double? = null
        var maximumConfidence: Double? = null
        val rejectionReasons = linkedMapOf<String, Int>()
        val models = JSONArray()
        val failures = JSONArray()

        sessionFiles().forEach { session ->
            forEachEvent(session) { event ->
                when (event.optString("type")) {
                    "PADDLE_OCR_MODELS_READY" -> {
                        modelReadyEvents++
                        models.put(JSONObject(event.toString()).apply { put("sourceSessionId", session.name) })
                    }
                    "PADDLE_OCR_INITIALIZED" -> initializationEvents++
                    "PADDLE_OCR_COMPLETED" -> {
                        completedEvents++
                        rawLines += event.optInt("rawLineCount", 0)
                        acceptedLines += event.optInt("acceptedLineCount", 0)
                        rejectedLines += event.optInt("rejectedLineCount", 0)
                        maximumNativeMs = maxOf(maximumNativeMs, event.optDouble("nativeTotalMs", 0.0))
                        maximumDetectorMs = maxOf(maximumDetectorMs, event.optDouble("detectorMs", 0.0))
                        maximumMemoryDelta = maxOf(maximumMemoryDelta, event.optLong("memoryDeltaBytes", 0L))
                        minimumConfidence = minNullable(minimumConfidence, event.optDoubleOrNull("minimumConfidence"))
                        maximumConfidence = maxNullable(maximumConfidence, event.optDoubleOrNull("maximumConfidence"))
                        val reasons = event.optJSONObject("rejectionReasons")
                        reasons?.keys()?.forEach { key ->
                            rejectionReasons[key] = (rejectionReasons[key] ?: 0) + reasons.optInt(key, 0)
                        }
                    }
                    "FAILURE" -> if (event.optString("stage").startsWith("PADDLE_OCR")) {
                        failureEvents++
                        failures.put(JSONObject(event.toString()).apply { put("sourceSessionId", session.name) })
                    }
                }
            }
        }

        return JSONObject().apply {
            put("schemaVersion", 1)
            put("generatedAtEpochMs", System.currentTimeMillis())
            put("engine", "PP-OCRv5 Arabic+English")
            put("localOnly", true)
            put("storesImages", false)
            put("manualRtlReversal", false)
            put("spellCorrection", false)
            put("generativeModel", false)
            put("orientationClassifier", false)
            put("modelReadyEventCount", modelReadyEvents)
            put("initializationEventCount", initializationEvents)
            put("completedRecognitionCount", completedEvents)
            put("failureCount", failureEvents)
            put("rawLineCount", rawLines)
            put("acceptedLineCount", acceptedLines)
            put("rejectedLineCount", rejectedLines)
            put("acceptanceRatio", if (rawLines > 0) acceptedLines.toDouble() / rawLines else JSONObject.NULL)
            put("rejectionReasons", JSONObject(rejectionReasons as Map<*, *>))
            put("maximumNativeTotalMs", maximumNativeMs)
            put("maximumDetectorMs", maximumDetectorMs)
            put("maximumMemoryDeltaBytes", maximumMemoryDelta)
            putNullable("minimumAcceptedConfidence", minimumConfidence)
            putNullable("maximumAcceptedConfidence", maximumConfidence)
            put("modelIntegrityEvents", models)
            put("failures", failures)
        }
    }

'''
    replace_once(path, function_anchor, paddle_function + function_anchor)

    replace_once(
        path,
        '    private fun minNullable(current: Double?, candidate: Double?): Double? = when {\n',
        '    private fun maxNullable(current: Double?, candidate: Double?): Double? = when {\n'
        '        candidate == null -> current\n'
        '        current == null -> candidate\n'
        '        else -> maxOf(current, candidate)\n'
        '    }\n\n'
        '    private fun minNullable(current: Double?, candidate: Double?): Double? = when {\n',
    )
    replace_once(path, '        const val SCHEMA_VERSION = 3\n', '        const val SCHEMA_VERSION = 4\n')
    replace_once(
        path,
        '        const val INCOMPLETE_DISPLAY_RATIO = 0.45\n',
        '        const val INCOMPLETE_DISPLAY_RATIO = 0.45\n'
        '        const val PADDLE_SLOW_INFERENCE_MS = 2_500.0\n'
        '        const val PADDLE_CRITICAL_INFERENCE_MS = 8_000.0\n'
        '        const val PADDLE_MEMORY_WARNING_BYTES = 64L * 1024L * 1024L\n',
    )
    replace_once(
        path,
        '            "totalDurationMs",\n',
        '            "totalDurationMs",\n'
        '            "modelInstallMs",\n'
        '            "initializationMs",\n'
        '            "detectorMs",\n'
        '            "arabicRecognitionMs",\n'
        '            "englishRecognitionMs",\n'
        '            "nativeTotalMs",\n',
    )
    replace_once(
        path,
        '            "LOCAL_OCR_COMPLETED",\n',
        '            "LOCAL_OCR_COMPLETED",\n'
        '            "PADDLE_OCR_COMPLETED",\n',
    )
    replace_once(
        path,
        '            - هندسة كتل وأسطر OCR كنسب موضعية، مع ثقة Tesseract.\n',
        '            - هندسة كتل وأسطر OCR كنسب موضعية، مع ثقة PaddleOCR العربية والإنجليزية وسبب قبول أو رفض كل سطر.\n'
        '            - بصمات SHA-256 للنماذج، أزمنة الكشف والتعرف، استهلاك الذاكرة، وترتيب RTL/LTR دون حفظ الصورة.\n',
    )
    replace_once(
        path,
        '            - trace_analysis.json: تحليل كل لقطة من الالتقاط حتى النص المعروض.\n',
        '            - trace_analysis.json: تحليل كل لقطة من الالتقاط حتى النص المعروض.\n'
        '            - paddleocr_analysis.json: ملخص نماذج PaddleOCR والثقة والرفض والأزمنة والذاكرة والأعطال.\n',
    )


def patch_ui_copy() -> None:
    vm = JAVA / "ui/MainViewModel.kt"
    replace_once(
        vm,
        '        message.value = "يجري ضغط سجل التشخيص التلقائي الشامل بلا صور"\n',
        '        message.value = "يجري ضغط التشخيص الشامل بلا صور، شاملاً PaddleOCR والنماذج والثقة والأزمنة"\n',
    )
    activity = JAVA / "ui/MainActivity.kt"
    replace_once(
        activity,
        '                    "حزمة تشخيص رفيق الرؤية التلقائية الشاملة. لا تحتوي صوراً، وتضم الخط الزمني الكامل والبصمات البصرية غير القابلة لإعادة البناء ونتائج OCR وGemini والنطق والتوقيتات والتحليل الآلي لكل لقطة.",\n',
        '                    "حزمة تشخيص رفيق الرؤية التلقائية الشاملة. لا تحتوي صوراً، وتضم الخط الزمني والبصمات البصرية غير القابلة لإعادة البناء وتشخيص PaddleOCR العربي والإنجليزي وبصمات النماذج ودرجات الثقة وأسباب الرفض والأزمنة والذاكرة ونتائج Gemini والنطق.",\n',
    )


def main() -> None:
    patch_media_projection()
    patch_hub()
    patch_native()
    patch_policy()
    patch_models()
    patch_recognizer()
    patch_recorder()
    patch_ui_copy()
    print("Applied VisionBridge PaddleOCR diagnostic schema v4")


if __name__ == "__main__":
    main()
