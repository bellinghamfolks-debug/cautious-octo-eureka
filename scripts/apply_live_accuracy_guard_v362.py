#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIVE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/gemini/GeminiLiveSession.kt"
MEDIA = ROOT / "app/src/main/java/com/abdullah/visionbridge/capture/MediaProjectionService.kt"
ENCODER = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/gemini/LiveFrameEncoder.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"3.6.2 accuracy patch failed: {label} anchor not found")
    return text.replace(old, new, 1)


def replace_mode_prompt(text: str, mode_prefix: str, replacement_line: str, label: str) -> str:
    """Replace one Kotlin prompt line by its stable MODE marker, not fragile Arabic wording."""
    if replacement_line.strip() in text:
        return text
    pattern = rf'^[ \t]*"MODE={re.escape(mode_prefix)}\.[^\n]*\$descriptionTail"[ \t]*$'
    updated, count = re.subn(pattern, replacement_line, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"3.6.2 accuracy patch failed: {label} marker not found uniquely (count={count})")
    return updated


text = LIVE.read_text()
if "LIVE_TEXT_ACCURACY_V362" not in text:
    text = replace_once(
        text,
        '''        AnalysisMode.TEXT_READING -> LiveProfile(
            model = TEXT_LIVE_MODEL,
            proactiveAudio = false,
            semanticSceneGate = false,
            mediaResolution = "MEDIA_RESOLUTION_MEDIUM",
            thinkingLevel = "MINIMAL",
        )
''',
        '''        AnalysisMode.TEXT_READING -> LiveProfile(
            model = TEXT_LIVE_MODEL,
            proactiveAudio = false,
            semanticSceneGate = false,
            // LIVE_TEXT_ACCURACY_V362: dense text needs more visual detail than the old latency-first profile.
            mediaResolution = "MEDIA_RESOLUTION_HIGH",
            thinkingLevel = "MEDIUM",
        )
''',
        "text Live profile",
    )

    old_tail = '''            val descriptionTail = if (settings.describeAlongsideText) {
                " بعد إكمال كل النص المرئي، اختم بجملة وصفية واحدة قصيرة تبدأ بكلمة الوصف: وتذكر ما هو الشيء الذي يحمل النص وأين يظهر تقريباً في المشهد. لا تعيد النص داخل الجملة الوصفية. وإذا لم يوجد أي نص مقروء إطلاقاً، صف أهم ما يظهر في المشهد بجملة واحدة بدلاً من الصمت."
            } else ""
'''
    new_tail = '''            val descriptionTail = if (settings.describeAlongsideText) {
                " بعد القراءة الحرفية فقط، يمكنك إضافة جملة واحدة تبدأ بكلمة الوصف:. صف فقط شيئاً مرئياً مباشرة في اللقطة الحالية ومكانه التقريبي. لا تستنتج نوع المنتج أو اسم الشيء من النص المقروء أو من شكل مألوف، ولا تصف ظلاماً أو ضبابية أو عدم وضوح إلا إذا كان ذلك حقيقة بصرية قاطعة. إذا لم تكن واثقاً من الوصف فلا تضفه. وإذا لم يوجد نص موثوق فلا تحاول تعويضه بوصف تخميني."
            } else ""
'''
    text = replace_once(text, old_tail, new_tail, "strict hybrid description tail")

    stable_line = '                "MODE=TEXT_ACCURATE_V362. هذه اللقطة الحالية وحدها هي المصدر. اقرأ حرفياً فقط الأحرف والكلمات والأرقام التي تراها فعلاً في البكسلات الحالية. ممنوع تماماً التخمين أو إكمال كلمة أو رقم أو موديل أو معيار أو رمز شائع من الذاكرة أو من شكل الملصق، وممنوع إنشاء بدائل مثل X أو XX أو XYZ أو XXXXX. لا تستخدم أي نص من إطار أو رد سابق. إذا لم تستطع التحقق بصرياً من جزء فاحذفه ولا تقل غير واضح. إذا لم يوجد نص يمكن الوثوق به فقل NO_TEXT فقط. لا تصف الإضاءة أو الضبابية أو الظلام داخل جزء القراءة. تجاهل واجهة eSight.$descriptionTail"'
    text = replace_mode_prompt(text, "TEXT_ACCURATE", stable_line, "stable accuracy prompt")

    fast_line = '                "MODE=TEXT_FAST_V362. استخدم اللقطة الحالية وحدها. اقرأ فقط النص المرئي المؤكد حرفياً وبالترتيب. لا تكمل أنماطاً مألوفة ولا تخمن أرقاماً أو رموزاً أو موديلات ولا تستخدم محتوى من رد سابق. إذا لم يوجد نص موثوق فقل NO_TEXT فقط. تجاهل واجهة eSight.$descriptionTail"'
    text = replace_mode_prompt(text, "TEXT_FAST", fast_line, "fast accuracy prompt")

    old_wait_tail = '''            DiagnosticHub.record(
                "LIVE_TURN_WAIT_COMPLETED",
                mapOf("mode" to settings.mode.name, "epoch" to activeTurnEpoch, "model" to profile.model),
            )
            true
'''
    new_wait_tail = '''            DiagnosticHub.record(
                "LIVE_TURN_WAIT_COMPLETED",
                mapOf("mode" to settings.mode.name, "epoch" to activeTurnEpoch, "model" to profile.model),
            )
            if (settings.mode == AnalysisMode.TEXT_READING) {
                DiagnosticHub.record(
                    "LIVE_TEXT_CONTEXT_RESET",
                    mapOf("reason" to "accuracy_fresh_context_per_text_turn", "model" to profile.model),
                )
                invalidateSocket("text_accuracy_context_reset")
            }
            true
'''
    text = replace_once(text, old_wait_tail, new_wait_tail, "fresh text context")

    old_semantic = '''            val semanticNoChange = scene && finalText.trim().equals("NO_CHANGE", ignoreCase = true)
            if (semanticNoChange) {
'''
    new_semantic = '''            val textNoReliableContent = !scene && (
                finalText.trim().equals("NO_TEXT", ignoreCase = true) ||
                    finalText.trim().equals("NO_CHANGE", ignoreCase = true)
            )
            val semanticNoChange = scene && finalText.trim().equals("NO_CHANGE", ignoreCase = true)
            if (textNoReliableContent) {
                DiagnosticHub.record(
                    "LIVE_TEXT_NO_RELIABLE_CONTENT",
                    mapOf(
                        "reason" to finalText.trim(),
                        "model" to activeProfile?.model,
                        "accuracyPolicy" to "OMIT_UNVERIFIED_DO_NOT_GUESS",
                    ),
                )
            } else if (semanticNoChange) {
'''
    text = replace_once(text, old_semantic, new_semantic, "suppress text sentinels")

    LIVE.write_text(text)

media = MEDIA.read_text()
if "LIVE_TEXT_HORIZONTAL_CROP_GUARD_V362" not in media:
    old_rect = '''        val rect = activeViewport ?: return source
        val left = (rect.left * source.width).toInt().coerceIn(0, source.width - 1)
'''
    new_rect = '''        val detectedRect = activeViewport ?: return source
        // LIVE_TEXT_HORIZONTAL_CROP_GUARD_V362: preserve the full horizontal label width for cloud OCR.
        val rect = if (
            activeSettings.mode == AnalysisMode.TEXT_READING && !activeSettings.useLocalOcr
        ) {
            if (detectedRect.left != 0f || detectedRect.right != 1f) {
                DiagnosticHub.record(
                    "LIVE_TEXT_HORIZONTAL_CROP_SUPPRESSED",
                    trace.fields(
                        detectedRect.fields() + mapOf(
                            "appliedLeft" to 0f,
                            "appliedRight" to 1f,
                            "reason" to "preserve_full_label_width",
                        ),
                    ),
                )
            }
            detectedRect.copy(left = 0f, right = 1f)
        } else {
            detectedRect
        }
        val left = (rect.left * source.width).toInt().coerceIn(0, source.width - 1)
'''
    media = replace_once(media, old_rect, new_rect, "horizontal viewport guard")
    MEDIA.write_text(media)

encoder = ENCODER.read_text()
if "TEXT_STABLE_EDGE = 1800" not in encoder:
    encoder = replace_once(encoder, "const val TEXT_STABLE_EDGE = 1440", "const val TEXT_STABLE_EDGE = 1800", "stable text edge")
    encoder = replace_once(encoder, "const val TEXT_FAST_EDGE = 960", "const val TEXT_FAST_EDGE = 1440", "fast text edge")
    encoder = replace_once(encoder, "const val TEXT_STABLE_QUALITY = 88", "const val TEXT_STABLE_QUALITY = 96", "stable JPEG quality")
    encoder = replace_once(encoder, "const val TEXT_FAST_QUALITY = 82", "const val TEXT_FAST_QUALITY = 92", "fast JPEG quality")
    ENCODER.write_text(encoder)

print("Applied VisionBridge 3.6.2 Live accuracy guardrails")
