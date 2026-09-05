#!/usr/bin/env python3
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


# --- Gemini Live: accuracy-first text profile and strict visual grounding ---
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
            // LIVE_TEXT_ACCURACY_V362: Google's current guidance recommends HIGH for
            // text-heavy video frames. MEDIUM + MINIMAL was fast but field data showed
            // severe OCR hallucination, including invented regulatory/model strings.
            mediaResolution = "MEDIA_RESOLUTION_HIGH",
            thinkingLevel = "MEDIUM",
        )
''',
        "text Live profile",
    )

    old_tail = '''            val descriptionTail = if (settings.describeAlongsideText) {
                " بعد إكمال كل النص المرئي، اختم بجملة وصفية واحدة قصيرة تبدأ بكلمة الوصف: وتذكر ما هو الشيء الذي يحمل النص وأين يظهر تقريباً في المشهد. لا تعيد النص داخل الجملة الوصفية."
            } else ""
'''
    new_tail = '''            val descriptionTail = if (settings.describeAlongsideText) {
                " بعد القراءة الحرفية فقط، يمكنك إضافة جملة واحدة تبدأ بكلمة الوصف:. صف فقط شيئاً مرئياً مباشرة في اللقطة الحالية ومكانه التقريبي. لا تستنتج نوع المنتج أو اسم الشيء من النص المقروء، ولا تصف ظلاماً أو ضبابية أو عدم وضوح إلا إذا كان ذلك حقيقة بصرية قاطعة. إذا لم تكن واثقاً من الوصف فلا تضفه."
            } else ""
'''
    text = replace_once(text, old_tail, new_tail, "strict hybrid description tail")

    old_stable = '''                "MODE=TEXT_ACCURATE. اقرأ كل النص المقروء الظاهر في الإطار الحالي من البداية إلى النهاية. لا تتوقف بعد كلمة أو سطر واحد إذا بقي نص واضح. ابدأ فوراً بلا مقدمة، ولا تصحح أو تكمل أو تترجم أو تخمن. إذا تعذرت كلمة قل غير واضح ثم واصل ما بعدها. تجاهل واجهة eSight.$descriptionTail"
'''
    new_stable = '''                "MODE=TEXT_ACCURATE_V362. هذه اللقطة الحالية وحدها هي المصدر. اقرأ حرفياً فقط الأحرف والكلمات والأرقام التي تراها فعلاً في البكسلات الحالية. ممنوع تماماً التخمين أو إكمال كلمة أو رقم أو موديل أو معيار أو رمز شائع من الذاكرة أو من شكل الملصق، وممنوع إنشاء بدائل مثل X أو XX أو XYZ أو XXXXX. لا تستخدم أي نص من إطار أو رد سابق. إذا لم تستطع التحقق بصرياً من جزء فاحذفه ولا تقل غير واضح. إذا لم يوجد نص يمكن الوثوق به فقل NO_TEXT فقط. لا تصف الإضاءة أو الضبابية أو الظلام داخل جزء القراءة. تجاهل واجهة eSight.$descriptionTail"
'''
    text = replace_once(text, old_stable, new_stable, "stable accuracy prompt")

    old_fast = '''                "MODE=TEXT_FAST. اقرأ فوراً كل عبارة واضحة تراها في الإطار الحالي بالترتيب، ولا تتوقف بعد أول كلمة إن كان المزيد واضحاً. لا تشرح أو تترجم أو تتوقع حروفاً غير ظاهرة. تجاهل واجهة eSight.$descriptionTail"
'''
    new_fast = '''                "MODE=TEXT_FAST_V362. استخدم اللقطة الحالية وحدها. اقرأ فقط النص المرئي المؤكد حرفياً وبالترتيب. لا تكمل أنماطاً مألوفة ولا تخمن أرقاماً أو رموزاً أو موديلات ولا تستخدم محتوى من رد سابق. إذا لم يوجد نص موثوق فقل NO_TEXT فقط. تجاهل واجهة eSight.$descriptionTail"
'''
    text = replace_once(text, old_fast, new_fast, "fast accuracy prompt")

    # A Live connection is stateful. For OCR, retaining prior visual/text turns is a liability:
    # the next label must not inherit food-label text, model numbers, or scene semantics from the
    # previous target. Close only after the completed text turn; the next frame opens a clean Live
    # session while preserving the Live-only architecture.
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

    # Never speak model control sentinels in text-reading mode. A refusal to guess is success,
    # not something the user should hear as literal English protocol text.
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

# --- Capture geometry: never let dark content masquerade as a right-side UI gutter in cloud OCR ---
media = MEDIA.read_text()
if "LIVE_TEXT_HORIZONTAL_CROP_GUARD_V362" not in media:
    old_rect = '''        val rect = activeViewport ?: return source
        val left = (rect.left * source.width).toInt().coerceIn(0, source.width - 1)
'''
    new_rect = '''        val detectedRect = activeViewport ?: return source
        // LIVE_TEXT_HORIZONTAL_CROP_GUARD_V362: field diagnostics showed the right edge
        // oscillating between 100% and about 73% of the screen while the user held the same
        // label. Dark/flat content was being mistaken for eSight's inert control gutter and could
        // remove a quarter of the actual card. In cloud text reading we keep the full horizontal
        // field and only retain the detector's safer top/bottom letterbox trim.
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

# --- Pixel fidelity: don't downscale a 1627px eSight crop to 1440 for dense labels ---
encoder = ENCODER.read_text()
if "TEXT_STABLE_EDGE = 1800" not in encoder:
    encoder = replace_once(encoder, "const val TEXT_STABLE_EDGE = 1440", "const val TEXT_STABLE_EDGE = 1800", "stable text edge")
    encoder = replace_once(encoder, "const val TEXT_FAST_EDGE = 960", "const val TEXT_FAST_EDGE = 1440", "fast text edge")
    encoder = replace_once(encoder, "const val TEXT_STABLE_QUALITY = 88", "const val TEXT_STABLE_QUALITY = 96", "stable JPEG quality")
    encoder = replace_once(encoder, "const val TEXT_FAST_QUALITY = 82", "const val TEXT_FAST_QUALITY = 92", "fast JPEG quality")
    ENCODER.write_text(encoder)

print("Applied VisionBridge 3.6.2 Live accuracy guardrails")
