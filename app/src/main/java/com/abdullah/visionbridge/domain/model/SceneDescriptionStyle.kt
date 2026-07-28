package com.abdullah.visionbridge.domain.model

enum class SceneDescriptionStyle {
    COMPREHENSIVE,
    BRIEF;

    companion object {
        fun fromStored(value: String?): SceneDescriptionStyle =
            entries.firstOrNull { it.name == value } ?: COMPREHENSIVE
    }
}
