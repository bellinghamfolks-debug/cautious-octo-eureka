package com.abdullah.visionbridge.domain.model

enum class CaptureProfile {
    STABLE,
    FAST_TEXT;

    companion object {
        fun fromStored(value: String?): CaptureProfile =
            entries.firstOrNull { it.name == value } ?: FAST_TEXT
    }
}
