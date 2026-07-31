package com.abdullah.visionbridge.data.localvlm

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle

/**
 * Prompts for the on-device model, and the chat template that wraps them.
 *
 * These are deliberately much shorter than the cloud prompts. A 3B quantized
 * model does not reliably follow a nine-clause instruction list; long prompts
 * make it start summarizing the instructions instead of the image, and each
 * extra instruction token also costs prefill time on a phone. Every rule here
 * earned its place by addressing a failure mode that reaches the user as audio.
 */
object LocalVlmPrompts {

    /** Media placeholder libmtmd substitutes with the encoded image tokens. */
    const val MEDIA_MARKER = "<__media__>"

    private val READ_SYSTEM = """
        أنت أداة نسخ بصري. تنسخ النص كما هو، ولا تشرح ولا تترجم ولا تلخص.
        You are a visual transcription tool. You copy text exactly. You never explain, translate or summarize.
    """.trimIndent()

    /**
     * Reading.
     *
     * "Once, from top to bottom" and the explicit stop instruction are the
     * anti-loop clauses. A local model that is not told the task is finite will
     * happily re-read a page it has already transcribed until the token budget
     * is exhausted, which is exactly the fragment loop this app exists to avoid.
     */
    private val READ_USER = """
        انسخ كل النص الظاهر في الصورة مرة واحدة فقط، من أعلى الصورة إلى أسفلها بالترتيب البصري نفسه.
        Transcribe every visible line of text exactly once, top to bottom, in the same visual order.

        القواعد:
        - اكتب العربية بالعربية والإنجليزية بالإنجليزية، في مواضعها كما تظهر.
        - سطر واحد في الصورة يساوي سطراً واحداً في الإخراج.
        - لا تكرر أي سطر أو عبارة نسختها من قبل.
        - لا تخمّن حرفاً لا تراه. اكتب [غير واضح] مكانه.
        - لا تضف مقدمة ولا تعليقاً ولا علامات تنسيق.
        - عندما تنتهي من آخر سطر في الصورة، توقف فوراً.
        - إذا لم يكن في الصورة نص، اكتب: لا يوجد نص
    """.trimIndent()

    private val DESCRIBE_SYSTEM = """
        أنت مساعد بصري لمستخدم كفيف. تصف ما تراه بدقة وإيجاز، ولا تخمّن ما لا تراه.
        You are a visual assistant for a blind user. You describe what is visible, accurately and briefly.
    """.trimIndent()

    private val DESCRIBE_COMPREHENSIVE_USER = """
        صف هذه الصورة لمستخدم كفيف بالعربية.
        ابدأ بجملة واحدة عن أهم شيء أو أوضح خطر، ثم أضف التفاصيل المفيدة بالترتيب:
        الأشخاص، ثم الأشياء والعوائق ومواضعها، ثم أي نص مهم ظاهر.
        استخدم أمامك ويمينك ويسارك وقريب وبعيد. لا تذكر مسافات بالأمتار.
        صف ما تراه فقط ولا تفترض شيئاً خارج الصورة. لا تكرر أي جملة.
        لا تزد على 70 كلمة، ولا تضف مقدمة. عند انتهاء الوصف توقف فوراً.
    """.trimIndent()

    private val DESCRIBE_BRIEF_USER = """
        صف هذه الصورة لمستخدم كفيف بالعربية في جملة أو جملتين فقط.
        ابدأ بأهم شيء أو أوضح خطر، ثم اذكر الاتجاه أو أهم عائق أو شخص.
        لا تزد على 25 كلمة. لا مقدمة ولا تكرار. عند الانتهاء توقف فوراً.
    """.trimIndent()

    /**
     * Builds a ChatML prompt with the image placed before the instruction.
     *
     * Image-first matters: Qwen-VL attends noticeably better to an instruction
     * that follows its visual tokens than to one that precedes them.
     */
    fun build(mode: AnalysisMode, sceneDescriptionStyle: SceneDescriptionStyle): String {
        val system = if (mode == AnalysisMode.TEXT_READING) READ_SYSTEM else DESCRIBE_SYSTEM
        val user = when (mode) {
            AnalysisMode.TEXT_READING -> READ_USER
            AnalysisMode.SCENE_DESCRIPTION -> when (sceneDescriptionStyle) {
                SceneDescriptionStyle.COMPREHENSIVE -> DESCRIBE_COMPREHENSIVE_USER
                SceneDescriptionStyle.BRIEF -> DESCRIBE_BRIEF_USER
            }
        }
        return buildString {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            append("<|im_start|>user\n").append(MEDIA_MARKER).append('\n')
            append(user).append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    /** Phrases the model returns for an empty frame, which must become silence. */
    private val NO_CONTENT_ANSWERS = setOf(
        "لا يوجد نص",
        "لايوجد نص",
        "no text",
        "there is no text",
        "no text visible",
        "none",
    )

    fun isNoContentAnswer(text: String): Boolean {
        val normalized = text.trim().trimEnd('.', '،', '!').lowercase()
        return normalized.isEmpty() || normalized in NO_CONTENT_ANSWERS
    }

    /**
     * Token budgets. Reading needs room for a dense page; a brief description
     * that runs long is a description that has started to ramble or loop.
     */
    fun maxTokens(mode: AnalysisMode, sceneDescriptionStyle: SceneDescriptionStyle): Int =
        when (mode) {
            // Sized for what a phone CPU can actually decode, not for what a page might contain.
            // A device log shows a 1,536-token budget producing nothing at all in fifty-six
            // seconds: at the few tokens per second a 3B model manages on ARM, that budget is
            // several minutes of decoding, so the reading never arrived and the user gave up.
            AnalysisMode.TEXT_READING -> 640
            AnalysisMode.SCENE_DESCRIPTION -> when (sceneDescriptionStyle) {
                SceneDescriptionStyle.COMPREHENSIVE -> 220
                SceneDescriptionStyle.BRIEF -> 80
            }
        }

    /**
     * Transcription is greedy: sampling invents plausible characters, and an
     * invented character in a bank balance is worse than a missing one.
     * Description samples very lightly to avoid a flat, repetitive cadence.
     */
    fun temperature(mode: AnalysisMode): Float =
        if (mode == AnalysisMode.TEXT_READING) 0.0f else 0.2f
}
