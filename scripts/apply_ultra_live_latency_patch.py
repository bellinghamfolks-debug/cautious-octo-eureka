#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MEDIA = ROOT / "app/src/main/java/com/abdullah/visionbridge/capture/MediaProjectionService.kt"
LIVE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/gemini/GeminiLiveSession.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"live patch failed: {label} anchor not found")
    return text.replace(old, new, 1)


def remove_once(text: str, old: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"live patch failed: {label} anchor not found")
    return text.replace(old, "", 1)


def patch_media() -> None:
    text = MEDIA.read_text()
    if "LIVE_BACKPRESSURE_CAPTURE_V35" in text:
        return

    text = replace_once(
        text,
        """        val settings = activeSettings\n        val now = System.currentTimeMillis()\n""",
        """        val settings = activeSettings\n        // LIVE_BACKPRESSURE_CAPTURE_V35: cloud work skips the expensive registration stack, but\n        // unlike 3.4 it does NOT announce every accepted cloud frame as a new visual target. The\n        // service already owns one active frame plus one latest pending frame; GeminiLiveSession now\n        // stays suspended until its turn really finishes so this existing queue becomes the single\n        // backpressure boundary. Local PP-OCR keeps the proven tracking/stability path unchanged.\n        val cloudLive = settings.mode == AnalysisMode.SCENE_DESCRIPTION || !settings.useLocalOcr\n        val now = System.currentTimeMillis()\n""",
        "cloud-live mode",
    )

    text = replace_once(
        text,
        """        val minimumInterval = when {\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> SCENE_FRAME_INTERVAL_MS\n            settings.captureProfile == CaptureProfile.FAST_TEXT -> FAST_FRAME_INTERVAL_MS\n            else -> STABLE_FRAME_INTERVAL_MS\n        }\n""",
        """        val minimumInterval = when {\n            cloudLive && settings.mode == AnalysisMode.SCENE_DESCRIPTION -> 1_000L\n            cloudLive -> 120L\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> SCENE_FRAME_INTERVAL_MS\n            settings.captureProfile == CaptureProfile.FAST_TEXT -> FAST_FRAME_INTERVAL_MS\n            else -> STABLE_FRAME_INTERVAL_MS\n        }\n""",
        "capture interval",
    )

    text = replace_once(
        text,
        """        val changeDecision = when {\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> frameChangeDetector.evaluateFast(\n                bitmap = bitmap,\n                minimumMeanDifference = SCENE_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = SCENE_MIN_CHANGED_RATIO,\n            )\n            settings.captureProfile == CaptureProfile.STABLE -> frameChangeDetector.evaluateStable(\n                bitmap = bitmap,\n                minimumMeanDifference = STABLE_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = STABLE_MIN_CHANGED_RATIO,\n                stableForMs = STABLE_FRAME_DURATION_MS,\n                now = now,\n            )\n            else -> frameChangeDetector.evaluateFast(\n                bitmap = bitmap,\n                minimumMeanDifference = FAST_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = FAST_MIN_CHANGED_RATIO,\n            )\n        }\n""",
        """        val cloudTextMeanThreshold =\n            if (settings.captureProfile == CaptureProfile.FAST_TEXT) 3.5 else 6.0\n        val cloudTextRatioThreshold =\n            if (settings.captureProfile == CaptureProfile.FAST_TEXT) 0.028 else 0.050\n        val changeDecision = when {\n            // Scene mode samples the newest usable view once a second. Semantic/proactive Gemini\n            // decides whether it is worth speaking. The service queue prevents those samples from\n            // piling on top of a response that is still being spoken.\n            cloudLive && settings.mode == AnalysisMode.SCENE_DESCRIPTION ->\n                frameChangeDetector.evaluateFast(bitmap, 0.0, 0.0)\n            // Cloud text gets a meaningfully changed frame immediately, without the old settling\n            // delay. While Gemini is busy, later frames replace only the single pending slot.\n            cloudLive -> frameChangeDetector.evaluateFast(\n                bitmap = bitmap,\n                minimumMeanDifference = cloudTextMeanThreshold,\n                minimumChangedRatio = cloudTextRatioThreshold,\n            )\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> frameChangeDetector.evaluateFast(\n                bitmap = bitmap,\n                minimumMeanDifference = SCENE_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = SCENE_MIN_CHANGED_RATIO,\n            )\n            settings.captureProfile == CaptureProfile.STABLE -> frameChangeDetector.evaluateStable(\n                bitmap = bitmap,\n                minimumMeanDifference = STABLE_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = STABLE_MIN_CHANGED_RATIO,\n                stableForMs = STABLE_FRAME_DURATION_MS,\n                now = now,\n            )\n            else -> frameChangeDetector.evaluateFast(\n                bitmap = bitmap,\n                minimumMeanDifference = FAST_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = FAST_MIN_CHANGED_RATIO,\n            )\n        }\n""",
        "change detector",
    )

    text = replace_once(
        text,
        """        val changeThresholds = when {\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> Pair(\n                SCENE_MIN_MEAN_DIFFERENCE,\n                SCENE_MIN_CHANGED_RATIO,\n            )\n            settings.captureProfile == CaptureProfile.STABLE -> Pair(\n                STABLE_MIN_MEAN_DIFFERENCE,\n                STABLE_MIN_CHANGED_RATIO,\n            )\n            else -> Pair(FAST_MIN_MEAN_DIFFERENCE, FAST_MIN_CHANGED_RATIO)\n        }\n""",
        """        val changeThresholds = when {\n            cloudLive && settings.mode == AnalysisMode.SCENE_DESCRIPTION -> Pair(0.0, 0.0)\n            cloudLive -> Pair(cloudTextMeanThreshold, cloudTextRatioThreshold)\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> Pair(\n                SCENE_MIN_MEAN_DIFFERENCE,\n                SCENE_MIN_CHANGED_RATIO,\n            )\n            settings.captureProfile == CaptureProfile.STABLE -> Pair(\n                STABLE_MIN_MEAN_DIFFERENCE,\n                STABLE_MIN_CHANGED_RATIO,\n            )\n            else -> Pair(FAST_MIN_MEAN_DIFFERENCE, FAST_MIN_CHANGED_RATIO)\n        }\n""",
        "diagnostic thresholds",
    )

    text = text.replace(
        '"requiredStableMs" to if (settings.captureProfile == CaptureProfile.STABLE) STABLE_FRAME_DURATION_MS else 0L,',
        '"requiredStableMs" to if (!cloudLive && settings.captureProfile == CaptureProfile.STABLE) STABLE_FRAME_DURATION_MS else 0L,',
        1,
    ).replace(
        '"thresholdLogic" to if (settings.captureProfile == CaptureProfile.STABLE) "AND" else "OR",',
        '"thresholdLogic" to if (cloudLive) "LIVE_BACKPRESSURE" else if (settings.captureProfile == CaptureProfile.STABLE) "AND" else "OR",',
        1,
    )

    text = replace_once(
        text,
        """        val scene = settings.mode == AnalysisMode.SCENE_DESCRIPTION\n        val tracker = if (scene) sceneTargetTracker else textTargetTracker\n""",
        """        val scene = settings.mode == AnalysisMode.SCENE_DESCRIPTION\n\n        if (cloudLive) {\n            // Do not call onVisualTargetChanged here. 3.4 did that for every accepted cloud-text\n            // frame and produced 291 target changes plus 475 audio flushes in one field session.\n            // A cloud frame is merely a candidate for the latest-frame lane, not permission to cut\n            // the answer currently being heard.\n            DiagnosticHub.record(\n                "VISUAL_TARGET_DECISION",\n                trace.fields(\n                    mapOf(\n                        "targetChanged" to false,\n                        "decisionReason" to if (scene) "cloud_live_semantic_gate" else "cloud_live_latest_frame_lane",\n                        "targetTrackId" to null,\n                        "registrationMethod" to "BYPASSED_FOR_LIVE",\n                        "trackingMs" to 0.0,\n                        "cloudLiveDirect" to true,\n                        "backpressure" to "ONE_ACTIVE_ONE_LATEST",\n                    ),\n                ),\n            )\n            DiagnosticHub.frame(\n                bitmap = view,\n                frameId = frameId,\n                stage = "selected_input",\n                metadata = trace.fields(\n                    mapOf(\n                        "mode" to settings.mode.name,\n                        "captureProfile" to settings.captureProfile.name,\n                        "targetChanged" to false,\n                        "cloudLiveDirect" to true,\n                        "frameMeanAbsoluteDifference" to changeDecision.meanAbsoluteDifference,\n                        "frameChangedPixelRatio" to changeDecision.changedPixelRatio,\n                    ),\n                ),\n            )\n            DiagnosticHub.record(\n                "FRAME_SELECTED_FOR_ANALYSIS",\n                trace.fields(mapOf("cloudLiveDirect" to true, "backpressure" to "ONE_ACTIVE_ONE_LATEST")),\n            )\n            submitLatestFrame(PendingFrame(view, trace))\n            return\n        }\n\n        val tracker = if (scene) sceneTargetTracker else textTargetTracker\n""",
        "tracker bypass",
    )

    MEDIA.write_text(text)


def patch_live() -> None:
    text = LIVE.read_text()
    if "LIVE_TURN_BACKPRESSURE_V35" in text:
        return

    text = replace_once(
        text,
        """    private data class LiveProfile(\n        val model: String,\n        val proactiveAudio: Boolean,\n        val semanticSceneGate: Boolean,\n    )\n""",
        """    private data class LiveProfile(\n        val model: String,\n        val proactiveAudio: Boolean,\n        val semanticSceneGate: Boolean,\n        val mediaResolution: String,\n        val thinkingLevel: String? = null,\n        val thinkingBudget: Int? = null,\n    )\n""",
        "live profile",
    )

    text = replace_once(
        text,
        """    @Volatile private var responseInFlight = false\n    @Volatile private var staleAudioBlocked = false\n    @Volatile private var staleAudioPacketsBlocked = 0\n""",
        """    @Volatile private var responseInFlight = false\n    @Volatile private var staleAudioBlocked = false\n    @Volatile private var staleAudioPacketsBlocked = 0\n\n    // LIVE_TURN_BACKPRESSURE_V35: submitFrame does not return when bytes merely leave the phone.\n    // It stays suspended until Gemini closes the turn, allowing MediaProjectionService's existing\n    // one-active/one-latest queue to provide real backpressure.\n    @Volatile private var activeTurnCompletion: CompletableDeferred<Boolean>? = null\n    @Volatile private var activeFirstAudio: CompletableDeferred<Boolean>? = null\n""",
        "turn completion fields",
    )

    text = remove_once(
        text,
        """            if (\n                settings.mode == AnalysisMode.TEXT_READING &&\n                settings.captureProfile == CaptureProfile.STABLE &&\n                lastStableTextGenerationSent == generation\n            ) {\n                DiagnosticHub.record(\n                    "LIVE_TEXT_SAME_TARGET_SUPPRESSED",\n                    mapOf("visualGeneration" to generation),\n                )\n                return true\n            }\n\n""",
        "stable-generation suppression",
    )

    text = replace_once(
        text,
        """            val currentSocket = socket ?: return@coroutineScope false\n            val supersedingActiveResponse = responseInFlight\n            activeSpeechEnabled = settings.speechEnabled\n            activeResponseMode = settings.mode\n            firstAudioSeenForEpoch = Long.MIN_VALUE\n            sceneProbeHadAudio = false\n            staleAudioBlocked = supersedingActiveResponse\n            staleAudioPacketsBlocked = 0\n            synchronized(transcriptLock) { transcript = StringBuilder() }\n\n            if (settings.mode == AnalysisMode.TEXT_READING) {\n                activeTurnEpoch = audioPlayer.beginTurn(\n                    if (supersedingActiveResponse) "superseded_live_text_turn" else "new_live_text_turn"\n                )\n            }\n""",
        """            val currentSocket = socket ?: return@coroutineScope false\n            if (responseInFlight) {\n                // This should be prevented by the service queue. Never supersede an audible answer\n                // if another caller slips through; keep that answer intact and let the next capture\n                // replace the pending slot instead.\n                DiagnosticHub.record(\n                    "LIVE_BACKPRESSURE_GUARD",\n                    mapOf("reason" to "turn_already_in_flight", "mode" to settings.mode.name),\n                )\n                return@coroutineScope true\n            }\n\n            val supersedingActiveResponse = false\n            val turnCompletion = CompletableDeferred<Boolean>()\n            val firstAudio = CompletableDeferred<Boolean>()\n            activeTurnCompletion = turnCompletion\n            activeFirstAudio = firstAudio\n            activeSpeechEnabled = settings.speechEnabled\n            activeResponseMode = settings.mode\n            firstAudioSeenForEpoch = Long.MIN_VALUE\n            sceneProbeHadAudio = false\n            staleAudioBlocked = false\n            staleAudioPacketsBlocked = 0\n            synchronized(transcriptLock) { transcript = StringBuilder() }\n\n            if (settings.mode == AnalysisMode.TEXT_READING) {\n                activeTurnEpoch = audioPlayer.beginTurn("new_live_text_turn")\n            }\n""",
        "turn initialization",
    )

    text = replace_once(
        text,
        """            activeTurnSentAtNanos = SystemClock.elapsedRealtimeNanos()\n            val videoSent = currentSocket.send(videoMessage(imageBase64, image.mimeType))\n            val instructionSent = currentSocket.send(realtimeTextMessage(instructionFor(settings)))\n""",
        """            activeTurnSentAtNanos = SystemClock.elapsedRealtimeNanos()\n            responseInFlight = true\n            val videoSent = currentSocket.send(videoMessage(imageBase64, image.mimeType))\n            val instructionSent = currentSocket.send(realtimeTextMessage(instructionFor(settings)))\n""",
        "mark turn active before send",
    )

    text = text.replace(
        """            responseInFlight = true\n            synchronized(sendLock) {\n                lastFrameSentAtElapsedMs = SystemClock.elapsedRealtime()\n                if (\n                    settings.mode == AnalysisMode.TEXT_READING &&\n                    settings.captureProfile == CaptureProfile.STABLE\n                ) {\n                    lastStableTextGenerationSent = generation\n                }\n                if (settings.mode == AnalysisMode.SCENE_DESCRIPTION) {\n""",
        """            synchronized(sendLock) {\n                lastFrameSentAtElapsedMs = SystemClock.elapsedRealtime()\n                if (settings.mode == AnalysisMode.SCENE_DESCRIPTION) {\n""",
        1,
    )

    text = replace_once(
        text,
        """            if (profile.semanticSceneGate) {\n                DiagnosticHub.record(\n                    "LIVE_SEMANTIC_PROBE_SENT",\n                    mapOf("model" to profile.model, "visualGeneration" to generation),\n                )\n            }\n            true\n""",
        """            if (profile.semanticSceneGate) {\n                DiagnosticHub.record(\n                    "LIVE_SEMANTIC_PROBE_SENT",\n                    mapOf("model" to profile.model, "visualGeneration" to generation),\n                )\n            }\n\n            DiagnosticHub.record(\n                "LIVE_TURN_WAIT_STARTED",\n                mapOf("mode" to settings.mode.name, "epoch" to activeTurnEpoch, "model" to profile.model),\n            )\n\n            if (settings.mode == AnalysisMode.TEXT_READING) {\n                val heard = withTimeoutOrNull(TEXT_FIRST_AUDIO_TIMEOUT_MS) { firstAudio.await() } == true\n                if (!heard) {\n                    DiagnosticHub.record(\n                        "LIVE_FIRST_AUDIO_TIMEOUT",\n                        mapOf("timeoutMs" to TEXT_FIRST_AUDIO_TIMEOUT_MS, "model" to profile.model),\n                    )\n                    completeActiveTurn(false, "first_audio_timeout")\n                    invalidateSocket("first_audio_timeout")\n                    return@coroutineScope false\n                }\n            }\n\n            val turnTimeout = if (settings.mode == AnalysisMode.SCENE_DESCRIPTION) {\n                SCENE_TURN_TIMEOUT_MS\n            } else {\n                TEXT_TURN_TIMEOUT_MS\n            }\n            val completed = withTimeoutOrNull(turnTimeout) { turnCompletion.await() } == true\n            if (!completed) {\n                DiagnosticHub.record(\n                    "LIVE_TURN_WAIT_TIMEOUT",\n                    mapOf("timeoutMs" to turnTimeout, "mode" to settings.mode.name, "model" to profile.model),\n                )\n                completeActiveTurn(false, "turn_wait_timeout")\n                invalidateSocket("turn_wait_timeout")\n                return@coroutineScope false\n            }\n            DiagnosticHub.record(\n                "LIVE_TURN_WAIT_COMPLETED",\n                mapOf("mode" to settings.mode.name, "epoch" to activeTurnEpoch, "model" to profile.model),\n            )\n            true\n""",
        "turn wait",
    )

    text = text.replace(
        'val fingerprint = fingerprint("$apiKey|${profile.model}|${profile.proactiveAudio}")',
        'val fingerprint = fingerprint("$apiKey|${profile.model}|${profile.proactiveAudio}|${profile.mediaResolution}|${profile.thinkingLevel}|${profile.thinkingBudget}")',
        1,
    )

    text = replace_once(
        text,
        """        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {\n            if (!ready.isCompleted) ready.complete(false)\n            clearSocketIfCurrent(webSocket)\n""",
        """        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {\n            if (!ready.isCompleted) ready.complete(false)\n            completeActiveTurn(false, "socket_closed")\n            clearSocketIfCurrent(webSocket)\n""",
        "socket closed completion",
    )

    text = replace_once(
        text,
        """        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {\n            if (!ready.isCompleted) ready.complete(false)\n            clearSocketIfCurrent(webSocket)\n""",
        """        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {\n            if (!ready.isCompleted) ready.complete(false)\n            completeActiveTurn(false, "socket_failure")\n            clearSocketIfCurrent(webSocket)\n""",
        "socket failure completion",
    )

    text = replace_once(
        text,
        """        if (serverContent["interrupted"]?.jsonPrimitive?.contentOrNull == "true") {\n            releaseStaleBoundary("interrupted")\n            firstAudioSeenForEpoch = Long.MIN_VALUE\n            synchronized(transcriptLock) { transcript = StringBuilder() }\n""",
        """        if (serverContent["interrupted"]?.jsonPrimitive?.contentOrNull == "true") {\n            releaseStaleBoundary("interrupted")\n            firstAudioSeenForEpoch = Long.MIN_VALUE\n            synchronized(transcriptLock) { transcript = StringBuilder() }\n            completeActiveTurn(false, "server_interrupted")\n""",
        "server interruption completion",
    )

    text = replace_once(
        text,
        """                if (firstAudioSeenForEpoch == Long.MIN_VALUE) {\n                    if (scene) {\n""",
        """                if (firstAudioSeenForEpoch == Long.MIN_VALUE) {\n                    activeFirstAudio?.let { if (!it.isCompleted) it.complete(true) }\n                    if (scene) {\n""",
        "first audio signal",
    )

    text = replace_once(
        text,
        """            if (staleAudioBlocked) {\n                releaseStaleBoundary("turn_complete")\n                firstAudioSeenForEpoch = Long.MIN_VALUE\n                synchronized(transcriptLock) { transcript = StringBuilder() }\n                return\n            }\n\n            responseInFlight = false\n""",
        """            if (staleAudioBlocked) {\n                releaseStaleBoundary("turn_complete")\n                firstAudioSeenForEpoch = Long.MIN_VALUE\n                synchronized(transcriptLock) { transcript = StringBuilder() }\n                completeActiveTurn(false, "stale_turn_complete")\n                return\n            }\n\n            responseInFlight = false\n            activeFirstAudio?.let { if (!it.isCompleted) it.complete(false) }\n""",
        "turn complete prelude",
    )

    text = replace_once(
        text,
        """                DiagnosticHub.record(\n                    "LIVE_TURN_COMPLETE",\n                    mapOf(\n                        "characters" to finalText.length,\n                        "epoch" to activeTurnEpoch,\n                        "mode" to activeResponseMode?.name,\n                    ),\n                )\n            }\n        }\n    }\n\n    private fun releaseStaleBoundary(reason: String) {\n""",
        """                DiagnosticHub.record(\n                    "LIVE_TURN_COMPLETE",\n                    mapOf(\n                        "characters" to finalText.length,\n                        "epoch" to activeTurnEpoch,\n                        "mode" to activeResponseMode?.name,\n                    ),\n                )\n            }\n            completeActiveTurn(true, "turn_complete")\n        }\n    }\n\n    private fun completeActiveTurn(success: Boolean, reason: String) {\n        activeFirstAudio?.let { if (!it.isCompleted) it.complete(false) }\n        activeTurnCompletion?.let { if (!it.isCompleted) it.complete(success) }\n        activeFirstAudio = null\n        activeTurnCompletion = null\n        responseInFlight = false\n        DiagnosticHub.record(\n            "LIVE_TURN_GATE_RELEASED",\n            mapOf("success" to success, "reason" to reason, "mode" to activeResponseMode?.name),\n        )\n    }\n\n    private fun releaseStaleBoundary(reason: String) {\n""",
        "turn completion helper",
    )

    text = replace_once(
        text,
        """            put("generationConfig", buildJsonObject {\n                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })\n                put("mediaResolution", "MEDIA_RESOLUTION_MEDIUM")\n            })\n""",
        """            put("generationConfig", buildJsonObject {\n                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })\n                put("mediaResolution", profile.mediaResolution)\n                put("thinkingConfig", buildJsonObject {\n                    profile.thinkingLevel?.let { put("thinkingLevel", it) }\n                    profile.thinkingBudget?.let { put("thinkingBudget", it) }\n                })\n                put("speechConfig", buildJsonObject { put("languageCode", "ar-XA") })\n            })\n""",
        "generation config",
    )

    text = replace_once(
        text,
        """            val descriptionTail = if (settings.describeAlongsideText) {\n                " بعد قراءة النص، أضف جملة قصيرة جداً تضع النص في سياقه المكاني إن بقي المشهد نفسه، دون إعادة النص."\n            } else ""\n            if (settings.captureProfile == CaptureProfile.STABLE) {\n                "MODE=TEXT_ACCURATE. اقرأ النص الظاهر الآن مباشرة وبهدوء ووضوح. ابدأ بأول سطر واضح بلا مقدمة، ولا تصحح أو تكمل أو تترجم. إذا تعذرت كلمة قل غير واضح. تجاهل واجهة eSight.$descriptionTail"\n            } else {\n                "MODE=TEXT_FAST. اقرأ فوراً أول عبارة واضحة ثم أكمل النص المرئي فقط. لا تنتظر الصفحة كاملة ولا تشرح أو تترجم أو تتوقع حروفاً غير ظاهرة. تجاهل واجهة eSight.$descriptionTail"\n            }\n""",
        """            val descriptionTail = if (settings.describeAlongsideText) {\n                " بعد إنهاء كل النص المقروء، أضف جملة قصيرة جداً عن موضعه. وإذا لم يوجد أي نص مقروء إطلاقاً، صف أهم ما يظهر في المشهد بجملة واحدة بدلاً من الصمت."\n            } else ""\n            if (settings.captureProfile == CaptureProfile.STABLE) {\n                "MODE=TEXT_ACCURATE. اقرأ كل النص المقروء الظاهر في الإطار الحالي من البداية إلى النهاية. لا تتوقف بعد كلمة أو سطر واحد إذا بقي نص واضح. ابدأ فوراً بلا مقدمة، ولا تصحح أو تكمل أو تترجم أو تخمن. إذا تعذرت كلمة قل غير واضح ثم واصل ما بعدها. تجاهل واجهة eSight.$descriptionTail"\n            } else {\n                "MODE=TEXT_FAST. اقرأ فوراً كل عبارة واضحة تراها في الإطار الحالي بالترتيب، ولا تتوقف بعد أول كلمة إن كان المزيد واضحاً. لا تشرح أو تترجم أو تتوقع حروفاً غير ظاهرة. تجاهل واجهة eSight.$descriptionTail"\n            }\n""",
        "text prompt",
    )

    text = replace_once(
        text,
        """            SceneDescriptionStyle.BRIEF -> "MODE=SCENE_BRIEF_SEMANTIC. قارن المعنى بالمشهد السابق. اهتزاز الكاميرا أو الالتفات أو التكبير أو الإضاءة ليس تغيراً. إن لم يتغير شيء مهم فابق صامتاً تماماً. عند تغير حقيقي اذكر أهم تغير أولاً بجملة قصيرة واضحة، خصوصاً الخطر أو العائق أو الشخص أو الاتجاه أو النص المفيد. لا تخمن."\n            SceneDescriptionStyle.COMPREHENSIVE -> "MODE=SCENE_COMPREHENSIVE_SEMANTIC. قارن المعنى بالمشهد السابق لا البكسلات. تجاهل حركة الكاميرا والدوران والتكبير والإضاءة والتركيز وواجهة eSight. ابق صامتاً إذا بقي المحتوى العملي نفسه. تكلم فقط عند ظهور أو اختفاء أو تغير شيء حقيقي ومفيد، وابدأ بالأهم ثم أكمل باختصار ووضوح بلا تخمين."\n""",
        """            SceneDescriptionStyle.BRIEF -> "MODE=SCENE_BRIEF_SEMANTIC. إذا لم يكن لديك مشهد سابق في هذه الجلسة فصف هذه اللقطة دائماً بجملة قصيرة. بعد ذلك قارن المعنى بالمشهد السابق. تجاهل اهتزاز الكاميرا والالتفات والتكبير وتغير الإضاءة البسيط إذا بقي ما يمكن رؤيته نفسه. لكن الانتقال من ظلام إلى نور يكشف أشياء جديدة، أو من نور إلى ظلام يخفيها، تغير حقيقي ويجب وصفه. عند تغير حقيقي اذكر أهم شيء أولاً، خصوصاً الخطر أو العائق أو الشخص أو الاتجاه أو النص المفيد. لا تخمن."\n            SceneDescriptionStyle.COMPREHENSIVE -> "MODE=SCENE_COMPREHENSIVE_SEMANTIC. إذا كانت هذه أول لقطة وصف في الجلسة فصف المشهد دائماً. بعد ذلك قارن المعنى لا البكسلات. تجاهل حركة الكاميرا والدوران والتكبير والتركيز وتغير الإضاءة الصغير إذا ظل المحتوى واضحاً نفسه. إذا كشف تشغيل النور محتوى كان مخفياً أو أخفى انطفاؤه محتوى كان ظاهراً فهذا تغير حقيقي. ابق صامتاً فقط عندما يبقى المحتوى العملي نفسه فعلاً. عند ظهور أو اختفاء أو تغير شيء مفيد ابدأ بالأهم ثم أكمل باختصار ووضوح بلا تخمين."\n""",
        "scene prompt",
    )

    text = replace_once(
        text,
        """        AnalysisMode.TEXT_READING -> LiveProfile(\n            model = TEXT_LIVE_MODEL,\n            proactiveAudio = false,\n            semanticSceneGate = false,\n        )\n        AnalysisMode.SCENE_DESCRIPTION -> LiveProfile(\n            model = SCENE_SEMANTIC_LIVE_MODEL,\n            proactiveAudio = true,\n            semanticSceneGate = true,\n        )\n""",
        """        AnalysisMode.TEXT_READING -> LiveProfile(\n            model = TEXT_LIVE_MODEL,\n            proactiveAudio = false,\n            semanticSceneGate = false,\n            mediaResolution = "MEDIA_RESOLUTION_MEDIUM",\n            thinkingLevel = "MINIMAL",\n        )\n        AnalysisMode.SCENE_DESCRIPTION -> LiveProfile(\n            model = SCENE_SEMANTIC_LIVE_MODEL,\n            proactiveAudio = true,\n            semanticSceneGate = true,\n            mediaResolution = "MEDIA_RESOLUTION_LOW",\n            thinkingBudget = 0,\n        )\n""",
        "profile latency config",
    )

    text = replace_once(
        text,
        """        responseInFlight = false\n        staleAudioBlocked = false\n        DiagnosticHub.record("LIVE_SOCKET_INVALIDATED", mapOf("reason" to reason))\n""",
        """        completeActiveTurn(false, "socket_invalidated_$reason")\n        staleAudioBlocked = false\n        DiagnosticHub.record("LIVE_SOCKET_INVALIDATED", mapOf("reason" to reason))\n""",
        "invalidate completion",
    )

    text = replace_once(
        text,
        """        responseInFlight = false\n        staleAudioBlocked = false\n    }\n\n    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")\n""",
        """        completeActiveTurn(false, "socket_cleared")\n        staleAudioBlocked = false\n    }\n\n    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")\n""",
        "clear socket completion",
    )

    text = replace_once(
        text,
        """        const val LIVE_SETUP_TIMEOUT_MS = 5_000L\n        const val LIVE_VIDEO_INTERVAL_MS = 1_000L\n        const val SCENE_PROBE_TIMEOUT_MS = 3_500L\n""",
        """        const val LIVE_SETUP_TIMEOUT_MS = 5_000L\n        const val LIVE_VIDEO_INTERVAL_MS = 1_000L\n        const val SCENE_PROBE_TIMEOUT_MS = 3_500L\n        const val TEXT_FIRST_AUDIO_TIMEOUT_MS = 3_500L\n        const val TEXT_TURN_TIMEOUT_MS = 20_000L\n        const val SCENE_TURN_TIMEOUT_MS = 8_000L\n""",
        "turn timeout constants",
    )

    LIVE.write_text(text)


patch_media()
patch_live()
print("Applied VisionBridge 3.5 Live backpressure patch")
