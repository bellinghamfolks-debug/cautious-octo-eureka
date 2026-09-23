package com.abdullah.visionbridge.domain.model

/** Shared by the start action and each turn; local text never needs a cloud credential. */
object AnalysisReadiness {
    const val MISSING_KEY = "أضف مفتاح Gemini في الإعدادات، أو فعّل القراءة المحلية لقراءة النص دون مفتاح"
    fun requiresCloud(settings: AppSettings): Boolean =
        settings.mode == AnalysisMode.SCENE_DESCRIPTION || !settings.useLocalOcr
    fun error(settings: AppSettings, hasKey: Boolean): String? =
        if (requiresCloud(settings) && !hasKey) MISSING_KEY else null
}
