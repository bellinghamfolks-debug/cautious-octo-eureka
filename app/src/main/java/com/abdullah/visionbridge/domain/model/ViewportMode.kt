package com.abdullah.visionbridge.domain.model

/** Which part of the mirrored screen VisionBridge sends to OCR/Gemini. */
enum class ViewportMode {
    /** Detect inert black margins and app chrome dynamically. Best for non-eSight sources. */
    AUTO,

    /** Use the calibrated eSight Share Your View camera rectangle for every analysis mode. */
    ESIGHT_FIXED,

    /** Use the calibrated eSight rectangle for text; scene mode keeps automatic viewport detection. */
    ESIGHT_TEXT_SAFE;

    companion object {
        fun fromStored(value: String?): ViewportMode =
            entries.firstOrNull { it.name == value } ?: ESIGHT_TEXT_SAFE
    }
}
