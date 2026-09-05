#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIVE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/gemini/GeminiLiveSession.kt"
SERVICE = ROOT / "app/src/main/java/com/abdullah/visionbridge/capture/MediaProjectionService.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"3.6.1 Live-required patch failed: {label} anchor not found")
    return text.replace(old, new, 1)


text = LIVE.read_text()
if "LIVE_REQUIRED_AUDIO_TRANSCRIPT_V361" not in text:
    # Gemini 3.1 Live rejects TEXT as a response modality on the Bidi Live session used on-device.
    # Keep the officially supported AUDIO modality, enable outputAudioTranscription, discard native
    # PCM, and speak the transcript through the local female-first Android TTS engine.
    text = replace_once(
        text,
        'put("responseModalities", buildJsonArray { add(JsonPrimitive("TEXT")) })',
        'put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })',
        "restore supported AUDIO modality",
    )

    system_block = '''            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", SYSTEM_INSTRUCTION) }) })
            })
'''
    text = replace_once(
        text,
        system_block,
        system_block + '            put("outputAudioTranscription", buildJsonObject { })\n',
        "enable output audio transcription",
    )

    old_transcription = '''        val delta = serverContent["outputTranscription"]?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (delta.isNotBlank() && !staleAudioBlocked && transcript.isEmpty()) {
            synchronized(transcriptLock) { transcript.append(delta) }
            DiagnosticHub.record(
                "LIVE_TRANSCRIPTION_COMPATIBILITY_USED",
                mapOf("characters" to delta.length, "mode" to activeResponseMode?.name),
            )
        }

'''
    new_transcription = '''        val delta = serverContent["outputTranscription"]?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (delta.isNotBlank() && !staleAudioBlocked) {
            synchronized(transcriptLock) { transcript.append(delta) }
            DiagnosticHub.record(
                "LIVE_AUDIO_TRANSCRIPT_DELTA",
                mapOf(
                    "characters" to delta.length,
                    "mode" to activeResponseMode?.name,
                    "model" to activeProfile?.model,
                ),
            )
        }

'''
    text = replace_once(
        text,
        old_transcription,
        new_transcription,
        "append every audio transcript delta",
    )

    pcm_anchor = '''                val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val data = inlineData["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!mimeType.startsWith("audio/pcm") || data.isBlank()) return@forEach
'''
    pcm_replacement = '''                val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val data = inlineData["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                // LIVE_REQUIRED_AUDIO_TRANSCRIPT_V361: AUDIO is requested only because the Live
                // protocol requires it. Native PCM is deliberately never played; the corresponding
                // outputAudioTranscription is the source of truth and local Android TTS speaks it.
                if (mimeType.startsWith("audio/") && data.isNotBlank()) {
                    DiagnosticHub.record(
                        "LIVE_NATIVE_AUDIO_DISCARDED",
                        mapOf("mimeType" to mimeType, "bytesBase64" to data.length, "mode" to activeResponseMode?.name),
                    )
                    return@forEach
                }
                if (!mimeType.startsWith("audio/pcm") || data.isBlank()) return@forEach
'''
    text = replace_once(text, pcm_anchor, pcm_replacement, "discard native PCM")

    old_tail = '''            val descriptionTail = if (settings.describeAlongsideText) {
                " بعد قراءة النص، أضف جملة قصيرة جداً تضع النص في سياقه المكاني إن بقي المشهد نفسه، دون إعادة النص."
            } else ""
'''
    new_tail = '''            val descriptionTail = if (settings.describeAlongsideText) {
                " بعد إكمال كل النص المرئي، اختم بجملة وصفية واحدة قصيرة تبدأ بكلمة الوصف: وتذكر ما هو الشيء الذي يحمل النص وأين يظهر تقريباً في المشهد. لا تعيد النص داخل الجملة الوصفية."
            } else ""
'''
    text = replace_once(text, old_tail, new_tail, "hybrid text plus one description sentence")

    text = text.replace(
        "استخرج كل النص المقروء الظاهر في الإطار الحالي من البداية إلى النهاية وأعده كنص فقط.",
        "اقرأ كل النص المقروء الظاهر في الإطار الحالي من البداية إلى النهاية واجعل القراءة أول جزء من الرد.",
        1,
    )
    text = text.replace(
        "استخرج فوراً كل عبارة واضحة تراها في الإطار الحالي بالترتيب وأعد النص فقط.",
        "اقرأ فوراً كل عبارة واضحة تراها في الإطار الحالي بالترتيب واجعل القراءة أول جزء من الرد.",
        1,
    )

    # System instruction describes the model's hidden native-audio generation correctly; the app
    # discards PCM and speaks only the transcription locally.
    text = text.replace(
        "أعد النتيجة كنص عربي طبيعي واضح مباشرة بلا مقدمة أو Markdown، وأبق الإنجليزية والأرقام كما تظهر عند الحاجة.",
        "أنشئ استجابة عربية طبيعية واضحة مباشرة بلا مقدمة أو Markdown، وانطق الإنجليزية والأرقام كما تظهر عند الحاجة.",
        1,
    )
    text = text.replace(
        "في القراءة استخرج النص من البكسلات الحالية نفسها. في الوصف تجاهل حركة الكاميرا، وإذا لم يتغير معنى المشهد فأعد NO_CHANGE فقط.",
        "في القراءة اقرأ البكسلات الحالية نفسها كاملة. إذا طُلبت جملة وصفية مع النص فاجعلها جملة واحدة بعد القراءة. في الوصف المنفصل تجاهل حركة الكاميرا، وإذا لم يتغير معنى المشهد فقل NO_CHANGE فقط.",
        1,
    )

    # Expose a clear UI failure without initiating any secondary cloud analysis path.
    marker = '''    fun onVisualTargetChanged(interruptSpeech: Boolean) {
'''
    helper = '''    fun reportLiveRequiredFailure(reason: String) {
        runtime.error("فشل Gemini Live: $reason. لم يتم تشغيل أي مسار احتياطي.")
        DiagnosticHub.record(
            "LIVE_REQUIRED_FAILURE",
            mapOf("reason" to reason, "fallbackAllowed" to false),
        )
    }

'''
    text = replace_once(text, marker, helper + marker, "explicit Live-required failure API")

    LIVE.write_text(text)

# Make hybrid reading state explicit in every capture-settings diagnostic snapshot.
service = SERVICE.read_text()
if '"describeAlongsideText" to settings.describeAlongsideText' not in service:
    service = replace_once(
        service,
        '        "useLocalOcr" to settings.useLocalOcr,\n',
        '        "useLocalOcr" to settings.useLocalOcr,\n        "describeAlongsideText" to settings.describeAlongsideText,\n',
        "diagnose describeAlongsideText",
    )
    SERVICE.write_text(service)

print("Applied VisionBridge 3.6.1 Live-required AUDIO+transcription patch")
