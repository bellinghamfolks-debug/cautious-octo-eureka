#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIVE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/gemini/GeminiLiveSession.kt"
TTS = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/speech/BilingualTtsEngine.kt"
CONTAINER = ROOT / "app/src/main/java/com/abdullah/visionbridge/di/AppContainer.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"3.6 patch failed: {label} anchor not found")
    return text.replace(old, new, 1)


def remove_once(text: str, old: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"3.6 patch failed: {label} anchor not found")
    return text.replace(old, "", 1)


def patch_live() -> None:
    text = LIVE.read_text()
    if "LIVE_TEXT_LOCAL_TTS_V36" in text:
        return

    text = replace_once(
        text,
        "import com.abdullah.visionbridge.data.speech.LivePcmAudioPlayer\n",
        "import com.abdullah.visionbridge.data.speech.BilingualTtsEngine\nimport com.abdullah.visionbridge.data.speech.LivePcmAudioPlayer\n",
        "tts import",
    )

    text = replace_once(
        text,
        """class GeminiLiveSession(\n    private val runtime: CaptureRuntime,\n    private val audioPlayer: LivePcmAudioPlayer,\n) {\n""",
        """class GeminiLiveSession(\n    private val runtime: CaptureRuntime,\n    private val audioPlayer: LivePcmAudioPlayer,\n    private val tts: BilingualTtsEngine,\n) {\n""",
        "tts constructor",
    )

    text = replace_once(
        text,
        """    @Volatile private var activeSpeechEnabled = true\n    @Volatile private var activeResponseMode: AnalysisMode? = null\n""",
        """    @Volatile private var activeSpeechEnabled = true\n    @Volatile private var activeSpeechRate = 1.0f\n    @Volatile private var activeResponseMode: AnalysisMode? = null\n""",
        "speech rate state",
    )

    text = replace_once(
        text,
        """        val profile = profileFor(settings)\n        val now = SystemClock.elapsedRealtime()\n        val generation = visualGeneration\n""",
        """        val profile = profileFor(settings)\n        val now = SystemClock.elapsedRealtime()\n        val generation = visualGeneration\n\n        // LIVE_TEXT_LOCAL_TTS_V36: Gemini is the eye; Android TTS is the mouth. Do not\n        // buy another cloud reading while the current Live text is still being spoken.\n        if (settings.mode == AnalysisMode.TEXT_READING && tts.isLiveSpeechInProgress()) {\n            DiagnosticHub.record(\n                \"LIVE_LOCAL_SPEECH_BACKPRESSURE\",\n                mapOf(\"mode\" to settings.mode.name, \"reason\" to \"current_live_text_still_speaking\"),\n            )\n            return true\n        }\n""",
        "local speech backpressure",
    )

    text = replace_once(
        text,
        """            activeSpeechEnabled = settings.speechEnabled\n            activeResponseMode = settings.mode\n""",
        """            activeSpeechEnabled = settings.speechEnabled\n            activeSpeechRate = settings.speechRate\n            activeResponseMode = settings.mode\n""",
        "active speech rate",
    )

    # Native PCM was the false failure gate in 3.5: diagnostics showed 28/28 audio timeouts while
    # 27 turns already contained useful text. Text output has no first-audio concept.
    audio_wait = """            if (settings.mode == AnalysisMode.TEXT_READING) {\n                val heard = withTimeoutOrNull(TEXT_FIRST_AUDIO_TIMEOUT_MS) { firstAudio.await() } == true\n                if (!heard) {\n                    DiagnosticHub.record(\n                        \"LIVE_FIRST_AUDIO_TIMEOUT\",\n                        mapOf(\"timeoutMs\" to TEXT_FIRST_AUDIO_TIMEOUT_MS, \"model\" to profile.model),\n                    )\n                    completeActiveTurn(false, \"first_audio_timeout\")\n                    invalidateSocket(\"first_audio_timeout\")\n                    return@coroutineScope false\n                }\n            }\n\n"""
    text = remove_once(text, audio_wait, "remove first audio timeout")

    # Parse every text part in modelTurn. Keep the old inline-data branch as a compatibility logger;
    # under TEXT modality it should be dormant.
    text = replace_once(
        text,
        """            ?.get(\"parts\")?.jsonArray\n            ?.forEach { partElement ->\n                val inlineData = partElement.jsonObject[\"inlineData\"]?.jsonObject ?: return@forEach\n""",
        """            ?.get(\"parts\")?.jsonArray\n            ?.forEach { partElement ->\n                val part = partElement.jsonObject\n                val textPart = part[\"text\"]?.jsonPrimitive?.contentOrNull.orEmpty()\n                if (textPart.isNotBlank() && !staleAudioBlocked) {\n                    synchronized(transcriptLock) { transcript.append(textPart) }\n                    DiagnosticHub.record(\n                        \"LIVE_TEXT_PART_RECEIVED\",\n                        mapOf(\n                            \"characters\" to textPart.length,\n                            \"mode\" to activeResponseMode?.name,\n                            \"model\" to activeProfile?.model,\n                        ),\n                    )\n                }\n                val inlineData = part[\"inlineData\"]?.jsonObject ?: return@forEach\n""",
        "parse model text parts",
    )

    # With TEXT modality outputTranscription is absent. Avoid accidental duplication if a server
    # revision supplies both a text part and a transcription field.
    old_transcription = """        val delta = serverContent[\"outputTranscription\"]?.jsonObject\n            ?.get(\"text\")?.jsonPrimitive?.contentOrNull.orEmpty()\n        if (delta.isNotBlank() && !staleAudioBlocked) {\n            synchronized(transcriptLock) { transcript.append(delta) }\n        }\n\n"""
    text = replace_once(
        text,
        old_transcription,
        """        val delta = serverContent[\"outputTranscription\"]?.jsonObject\n            ?.get(\"text\")?.jsonPrimitive?.contentOrNull.orEmpty()\n        if (delta.isNotBlank() && !staleAudioBlocked && transcript.isEmpty()) {\n            synchronized(transcriptLock) { transcript.append(delta) }\n            DiagnosticHub.record(\n                \"LIVE_TRANSCRIPTION_COMPATIBILITY_USED\",\n                mapOf(\"characters\" to delta.length, \"mode\" to activeResponseMode?.name),\n            )\n        }\n\n""",
        "transcription compatibility",
    )

    # Remove the old "no audio means semantic silence" inference. In 3.6 semantic silence is an
    # explicit NO_CHANGE text marker, so a real description without PCM is never misclassified.
    old_scene_audio_silence = """                if (!sceneProbeHadAudio) {\n                    DiagnosticHub.record(\n                        \"LIVE_SEMANTIC_SILENCE\",\n                        mapOf(\n                            \"reason\" to \"no_meaningful_scene_change\",\n                            \"model\" to activeProfile?.model,\n                            \"visualGeneration\" to visualGeneration,\n                        ),\n                    )\n                }\n"""
    text = remove_once(text, old_scene_audio_silence, "remove audio-based semantic silence")

    old_final = """            if (finalText.isNotBlank()) {\n                runtime.result(\n                    AnalysisResult(\n                        text = finalText,\n                        source = AnalysisSource.GEMINI,\n                        language = if (finalText.any { it in '\\u0600'..'\\u06FF' }) \"mixed\" else \"en\",\n                    )\n                )\n                DiagnosticHub.record(\n                    \"LIVE_TURN_COMPLETE\",\n                    mapOf(\n                        \"characters\" to finalText.length,\n                        \"epoch\" to activeTurnEpoch,\n                        \"mode\" to activeResponseMode?.name,\n                    ),\n                )\n            }\n            completeActiveTurn(true, \"turn_complete\")\n"""
    new_final = """            val semanticNoChange = scene && finalText.trim().equals(\"NO_CHANGE\", ignoreCase = true)\n            if (semanticNoChange) {\n                DiagnosticHub.record(\n                    \"LIVE_SEMANTIC_SILENCE\",\n                    mapOf(\n                        \"reason\" to \"explicit_no_change_marker\",\n                        \"model\" to activeProfile?.model,\n                        \"visualGeneration\" to visualGeneration,\n                    ),\n                )\n            } else if (finalText.isNotBlank()) {\n                val turnLatencyMs = if (activeTurnSentAtNanos > 0L) {\n                    (SystemClock.elapsedRealtimeNanos() - activeTurnSentAtNanos) / 1_000_000.0\n                } else null\n                runtime.result(\n                    AnalysisResult(\n                        text = finalText,\n                        source = AnalysisSource.GEMINI,\n                        language = if (finalText.any { it in '\\u0600'..'\\u06FF' }) \"mixed\" else \"en\",\n                    )\n                )\n                DiagnosticHub.record(\n                    \"LIVE_TEXT_TURN_READY\",\n                    mapOf(\n                        \"characters\" to finalText.length,\n                        \"mode\" to activeResponseMode?.name,\n                        \"turnLatencyMs\" to turnLatencyMs,\n                    ),\n                )\n                if (activeSpeechEnabled) {\n                    if (scene) tts.supersedeLiveSpeech(\"new_live_scene_text_ready\")\n                    tts.speakLiveResult(\n                        text = finalText,\n                        rate = activeSpeechRate,\n                        interruptPrevious = false,\n                        live = true,\n                    )\n                    DiagnosticHub.record(\n                        \"LIVE_LOCAL_TTS_DISPATCHED\",\n                        mapOf(\n                            \"characters\" to finalText.length,\n                            \"rate\" to activeSpeechRate,\n                            \"mode\" to activeResponseMode?.name,\n                            \"voicePolicy\" to \"FEMALE_FIRST_LOCAL_TTS\",\n                        ),\n                    )\n                }\n                DiagnosticHub.record(\n                    \"LIVE_TURN_COMPLETE\",\n                    mapOf(\n                        \"characters\" to finalText.length,\n                        \"epoch\" to activeTurnEpoch,\n                        \"mode\" to activeResponseMode?.name,\n                    ),\n                )\n            }\n            completeActiveTurn(true, \"turn_complete\")\n"""
    text = replace_once(text, old_final, new_final, "local TTS final dispatch")

    # One 3.1 text-output session architecture for both modes. It removes the failing PCM transport,
    # removes unsupported native-audio languageCode, and avoids a model reconnect just to change voice.
    old_setup = """            put(\"generationConfig\", buildJsonObject {\n                put(\"responseModalities\", buildJsonArray { add(JsonPrimitive(\"AUDIO\")) })\n                put(\"mediaResolution\", profile.mediaResolution)\n                put(\"thinkingConfig\", buildJsonObject {\n                    profile.thinkingLevel?.let { put(\"thinkingLevel\", it) }\n                    profile.thinkingBudget?.let { put(\"thinkingBudget\", it) }\n                })\n                put(\"speechConfig\", buildJsonObject { put(\"languageCode\", \"ar-XA\") })\n            })\n"""
    new_setup = """            put(\"generationConfig\", buildJsonObject {\n                put(\"responseModalities\", buildJsonArray { add(JsonPrimitive(\"TEXT\")) })\n                put(\"mediaResolution\", profile.mediaResolution)\n                put(\"thinkingConfig\", buildJsonObject {\n                    profile.thinkingLevel?.let { put(\"thinkingLevel\", it) }\n                })\n            })\n"""
    text = replace_once(text, old_setup, new_setup, "text response modality")

    text = text.replace(
        '            put("outputAudioTranscription", buildJsonObject { })\n',
        '',
        1,
    )

    # Prompts now request textual output. For scene mode, semantic silence is deterministic and
    # testable instead of depending on proactive native audio choosing not to speak.
    text = text.replace(
        '"MODE=TEXT_ACCURATE. اقرأ كل النص المقروء الظاهر في الإطار الحالي من البداية إلى النهاية. لا تتوقف بعد كلمة أو سطر واحد إذا بقي نص واضح. ابدأ فوراً بلا مقدمة، ولا تصحح أو تكمل أو تترجم أو تخمن. إذا تعذرت كلمة قل غير واضح ثم واصل ما بعدها. تجاهل واجهة eSight.$descriptionTail"',
        '"MODE=TEXT_ACCURATE. استخرج كل النص المقروء الظاهر في الإطار الحالي من البداية إلى النهاية وأعده كنص فقط. لا تتوقف بعد كلمة أو سطر واحد إذا بقي نص واضح. لا تضف مقدمة ولا شرحاً ولا ترجمة ولا تخميناً. إذا تعذرت كلمة اكتب غير واضح ثم واصل ما بعدها. تجاهل واجهة eSight.$descriptionTail"',
        1,
    )
    text = text.replace(
        '"MODE=TEXT_FAST. اقرأ فوراً كل عبارة واضحة تراها في الإطار الحالي بالترتيب، ولا تتوقف بعد أول كلمة إن كان المزيد واضحاً. لا تشرح أو تترجم أو تتوقع حروفاً غير ظاهرة. تجاهل واجهة eSight.$descriptionTail"',
        '"MODE=TEXT_FAST. استخرج فوراً كل عبارة واضحة تراها في الإطار الحالي بالترتيب وأعد النص فقط. لا تتوقف بعد أول كلمة إن كان المزيد واضحاً، ولا تشرح أو تترجم أو تتوقع حروفاً غير ظاهرة. تجاهل واجهة eSight.$descriptionTail"',
        1,
    )
    text = text.replace(
        'SceneDescriptionStyle.BRIEF -> "MODE=SCENE_BRIEF_SEMANTIC. إذا لم يكن لديك مشهد سابق في هذه الجلسة فصف هذه اللقطة دائماً بجملة قصيرة. بعد ذلك قارن المعنى بالمشهد السابق. تجاهل اهتزاز الكاميرا والالتفات والتكبير وتغير الإضاءة البسيط إذا بقي ما يمكن رؤيته نفسه. لكن الانتقال من ظلام إلى نور يكشف أشياء جديدة، أو من نور إلى ظلام يخفيها، تغير حقيقي ويجب وصفه. عند تغير حقيقي اذكر أهم شيء أولاً، خصوصاً الخطر أو العائق أو الشخص أو الاتجاه أو النص المفيد. لا تخمن."',
        'SceneDescriptionStyle.BRIEF -> "MODE=SCENE_BRIEF_SEMANTIC. إذا كانت هذه أول لقطة وصف في الجلسة فأعد وصفاً قصيراً دائماً. بعد ذلك قارن المعنى بالمشهد السابق. تجاهل اهتزاز الكاميرا والالتفات والتكبير وتغير الإضاءة البسيط إذا بقي المحتوى نفسه. تشغيل النور الذي يكشف أشياء جديدة أو انطفاؤه الذي يخفيها تغير حقيقي. إذا لم يتغير المحتوى العملي فأعد بالضبط NO_CHANGE فقط. عند تغير حقيقي أعد وصفاً عربياً قصيراً يبدأ بالأهم، خصوصاً الخطر أو العائق أو الشخص أو الاتجاه أو النص المفيد. لا تخمن."',
        1,
    )
    text = text.replace(
        'SceneDescriptionStyle.COMPREHENSIVE -> "MODE=SCENE_COMPREHENSIVE_SEMANTIC. إذا كانت هذه أول لقطة وصف في الجلسة فصف المشهد دائماً. بعد ذلك قارن المعنى لا البكسلات. تجاهل حركة الكاميرا والدوران والتكبير والتركيز وتغير الإضاءة الصغير إذا ظل المحتوى واضحاً نفسه. إذا كشف تشغيل النور محتوى كان مخفياً أو أخفى انطفاؤه محتوى كان ظاهراً فهذا تغير حقيقي. ابق صامتاً فقط عندما يبقى المحتوى العملي نفسه فعلاً. عند ظهور أو اختفاء أو تغير شيء مفيد ابدأ بالأهم ثم أكمل باختصار ووضوح بلا تخمين."',
        'SceneDescriptionStyle.COMPREHENSIVE -> "MODE=SCENE_COMPREHENSIVE_SEMANTIC. إذا كانت هذه أول لقطة وصف في الجلسة فأعد وصفاً واضحاً دائماً. بعد ذلك قارن المعنى لا البكسلات. تجاهل حركة الكاميرا والدوران والتكبير والتركيز وتغير الإضاءة الصغير إذا بقي المحتوى العملي نفسه. إذا كشف تشغيل النور محتوى كان مخفياً أو أخفى انطفاؤه محتوى كان ظاهراً فهذا تغير حقيقي. إذا لم يتغير شيء عملي فأعد بالضبط NO_CHANGE فقط. عند ظهور أو اختفاء أو تغير شيء مفيد أعد وصفاً عربياً يبدأ بالأهم ثم يكمل باختصار ووضوح بلا تخمين."',
        1,
    )

    old_profiles = """        AnalysisMode.TEXT_READING -> LiveProfile(\n            model = TEXT_LIVE_MODEL,\n            proactiveAudio = false,\n            semanticSceneGate = false,\n            mediaResolution = \"MEDIA_RESOLUTION_MEDIUM\",\n            thinkingLevel = \"MINIMAL\",\n        )\n        AnalysisMode.SCENE_DESCRIPTION -> LiveProfile(\n            model = SCENE_SEMANTIC_LIVE_MODEL,\n            proactiveAudio = true,\n            semanticSceneGate = true,\n            mediaResolution = \"MEDIA_RESOLUTION_LOW\",\n            thinkingBudget = 0,\n        )\n"""
    new_profiles = """        AnalysisMode.TEXT_READING -> LiveProfile(\n            model = TEXT_LIVE_MODEL,\n            proactiveAudio = false,\n            semanticSceneGate = false,\n            mediaResolution = \"MEDIA_RESOLUTION_MEDIUM\",\n            thinkingLevel = \"MINIMAL\",\n        )\n        AnalysisMode.SCENE_DESCRIPTION -> LiveProfile(\n            model = TEXT_LIVE_MODEL,\n            proactiveAudio = false,\n            semanticSceneGate = true,\n            mediaResolution = \"MEDIA_RESOLUTION_LOW\",\n            thinkingLevel = \"MINIMAL\",\n        )\n"""
    text = replace_once(text, old_profiles, new_profiles, "unified 3.1 profiles")

    # The system instruction must not ask the model to "speak" when response modality is TEXT.
    text = text.replace(
        '            تكلم بالعربية الطبيعية الواضحة مباشرة بلا مقدمة أو Markdown، وانطق الإنجليزية والأرقام كما تظهر عند الحاجة.\n',
        '            أعد النتيجة كنص عربي طبيعي واضح مباشرة بلا مقدمة أو Markdown، وأبق الإنجليزية والأرقام كما تظهر عند الحاجة.\n',
        1,
    ).replace(
        '            في القراءة اقرأ البكسلات الحالية نفسها. في الوصف تجاهل حركة الكاميرا وابق صامتاً إذا لم يتغير معنى المشهد.\n',
        '            في القراءة استخرج النص من البكسلات الحالية نفسها. في الوصف تجاهل حركة الكاميرا، وإذا لم يتغير معنى المشهد فأعد NO_CHANGE فقط.\n',
        1,
    )

    # Text output is normally quicker; keep a generous ceiling for dense labels while failing real
    # protocol stalls sooner than the old 20-second gate.
    text = text.replace('const val TEXT_TURN_TIMEOUT_MS = 20_000L', 'const val TEXT_TURN_TIMEOUT_MS = 12_000L', 1)
    text = text.replace('const val SCENE_TURN_TIMEOUT_MS = 8_000L', 'const val SCENE_TURN_TIMEOUT_MS = 6_000L', 1)

    LIVE.write_text(text)


def patch_tts() -> None:
    text = TTS.read_text()
    if "FEMALE_FIRST_VOICE_V36" in text:
        return

    text = replace_once(
        text,
        "import android.speech.tts.TextToSpeech\n",
        "import android.speech.tts.TextToSpeech\nimport android.speech.tts.Voice\n",
        "Voice import",
    )
    text = replace_once(
        text,
        "import java.util.UUID\n",
        "import java.util.Locale\nimport java.util.UUID\n",
        "Locale import",
    )

    text = replace_once(
        text,
        """    /** Status speech intentionally bypasses content deduplication. */\n    suspend fun speakFeedback(\n""",
        """    /**\n     * LIVE_TEXT_LOCAL_TTS_V36: non-suspending entry point for a WebSocket callback. The request is\n     * stamped as live speech so its outstanding counter remains true until the actual utterance\n     * finishes, giving cloud text a real speech backpressure signal.\n     */\n    fun speakLiveResult(\n        text: String,\n        rate: Float = 1.0f,\n        interruptPrevious: Boolean = false,\n        live: Boolean = true,\n    ) {\n        if (text.isBlank()) return\n        scope.launch {\n            speak(\n                text = text,\n                rate = rate,\n                interruptPrevious = interruptPrevious,\n                live = live,\n            )\n        }\n    }\n\n    /** True while a Live result is queued or physically being spoken. */\n    fun isLiveSpeechInProgress(): Boolean = liveBlocksOutstanding.get() > 0\n\n    /** Status speech intentionally bypasses content deduplication. */\n    suspend fun speakFeedback(\n""",
        "live TTS API",
    )

    old_language = """            val availability = engine.isLanguageAvailable(segment.language.locale)\n            if (availability >= TextToSpeech.LANG_AVAILABLE) {\n                engine.language = segment.language.locale\n            }\n            engine.setSpeechRate(request.rate)\n"""
    new_language = """            val availability = engine.isLanguageAvailable(segment.language.locale)\n            if (availability >= TextToSpeech.LANG_AVAILABLE) {\n                engine.language = segment.language.locale\n            }\n            // FEMALE_FIRST_VOICE_V36: prefer a female Google/engine voice for every spoken segment.\n            // Android does not expose a universal gender field, so explicit female voice names and\n            // known Google Speech Services female variants are preferred; if this engine exposes no\n            // such metadata we keep its locale default instead of selecting a random voice.\n            val selectedVoice = selectPreferredFemaleVoice(engine, segment.language.locale)\n            engine.setSpeechRate(request.rate)\n            DiagnosticHub.record(\n                \"TTS_VOICE_ACTIVE\",\n                request.trace.fieldsOrEmpty(\n                    mapOf(\n                        \"voiceName\" to selectedVoice?.name,\n                        \"voiceLocale\" to selectedVoice?.locale?.toLanguageTag(),\n                        \"femaleHintScore\" to selectedVoice?.let(::femaleVoiceScore),\n                        \"networkRequired\" to selectedVoice?.isNetworkConnectionRequired,\n                    ),\n                ),\n            )\n"""
    text = replace_once(text, old_language, new_language, "female voice selection call")

    marker = """    private fun recoverEngineIfNeeded(reason: String, trace: DiagnosticTrace?) {\n"""
    helper = """    private fun selectPreferredFemaleVoice(engine: TextToSpeech, locale: Locale): Voice? {\n        val candidates = engine.voices.orEmpty()\n            .filter { it.locale.language.equals(locale.language, ignoreCase = true) }\n        val chosen = candidates\n            .sortedWith(\n                compareByDescending<Voice> { femaleVoiceScore(it) }\n                    .thenBy { it.isNetworkConnectionRequired }\n                    .thenByDescending { it.quality }\n                    .thenBy { it.latency }\n                    .thenBy { it.name },\n            )\n            .firstOrNull { femaleVoiceScore(it) > 0 }\n        if (chosen != null) {\n            if (engine.voice?.name != chosen.name) {\n                engine.voice = chosen\n                DiagnosticHub.record(\n                    \"TTS_FEMALE_VOICE_SELECTED\",\n                    mapOf(\n                        \"voiceName\" to chosen.name,\n                        \"locale\" to chosen.locale.toLanguageTag(),\n                        \"score\" to femaleVoiceScore(chosen),\n                        \"networkRequired\" to chosen.isNetworkConnectionRequired,\n                    ),\n                )\n            }\n            return chosen\n        }\n        return engine.voice\n    }\n\n    private fun femaleVoiceScore(voice: Voice): Int {\n        val name = voice.name.lowercase(Locale.ROOT)\n        val features = voice.features.orEmpty().joinToString(\" \" ).lowercase(Locale.ROOT)\n        var score = 0\n        if (\"female\" in name || \"female\" in features || \"#female\" in name) score += 100\n        if (name == \"ar-language\" || \"ar-xa-x-arc\" in name || \"ar-xa-x-arz\" in name) score += 80\n        if (name == \"en-us-language\" || \"en-us-x-sfg\" in name) score += 80\n        // Many engines expose numbered female variants through feature/name suffixes.\n        if (Regex(\"female[_-]?[1-9]\").containsMatchIn(name + \" \" + features)) score += 40\n        return score\n    }\n\n"""
    text = replace_once(text, marker, helper + marker, "female voice helpers")

    TTS.write_text(text)


def patch_container() -> None:
    text = CONTAINER.read_text()
    if "tts = tts," in text[text.find("private val liveSession"):text.find("private val liveSession") + 300]:
        return
    text = replace_once(
        text,
        """    private val liveSession = GeminiLiveSession(\n        runtime = runtime,\n        audioPlayer = liveAudioPlayer,\n    )\n""",
        """    private val liveSession = GeminiLiveSession(\n        runtime = runtime,\n        audioPlayer = liveAudioPlayer,\n        tts = tts,\n    )\n""",
        "inject local TTS",
    )
    CONTAINER.write_text(text)


patch_live()
patch_tts()
patch_container()
print("Applied VisionBridge 3.6 text-first Live + female local TTS patch")
