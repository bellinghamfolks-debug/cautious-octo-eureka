package com.abdullah.visionbridge.domain.model

enum class AnalysisMode {
    TEXT_READING,
    SCENE_DESCRIPTION;

    companion object {
        fun fromStored(value: String?): AnalysisMode = entries.firstOrNull { it.name == value } ?: TEXT_READING
    }
}
