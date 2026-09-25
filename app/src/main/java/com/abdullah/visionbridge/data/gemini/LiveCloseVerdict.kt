package com.abdullah.visionbridge.data.gemini

/**
 * What a Live socket's closing frame means for the model that sent it.
 *
 * The server explains itself when it refuses a session, and the four refusals seen in the field on
 * 2026-09-25 were not the same refusal:
 *
 * ```
 * 1007  The requested combination of response modalities (TEXT) is not supported by the model.
 *       models/gemini-3.1-flash-live-preview
 * 1007  Thinking level must be specified for this model.
 * 1007  Request contains an invalid argument.
 * 1000  (no reason — the socket simply ended)
 * ```
 *
 * Only the first is a permanent no about this modality. The second is a model asking to be called
 * differently — it is the sibling of the model this app already speaks with, and treating it as a
 * refusal threw away the best remaining candidate for reading. Telling them apart is the whole
 * point of this file, and it is pure so the distinction can be tested against those exact strings.
 */
enum class LiveCloseVerdict {
    /** Correctable: re-open the same model carrying a thinking level. */
    NEEDS_THINKING_LEVEL,

    /** Permanent for this modality: the model cannot answer this way. */
    MODALITY_REFUSED,

    /** The setup payload was rejected for some other reason; do not keep trying it. */
    INVALID_ARGUMENT,

    /** No explanation given. */
    UNEXPLAINED,
    ;

    /** Whether this verdict leaves the model worth another attempt. */
    val correctable: Boolean get() = this == NEEDS_THINKING_LEVEL

    companion object {
        /** Reads [code] and [reason] exactly as the server sent them. */
        fun of(code: Int, reason: String): LiveCloseVerdict {
            val text = reason.lowercase()
            return when {
                text.contains("thinking level") -> NEEDS_THINKING_LEVEL
                // "modalities" in the long form, "modality" in the short; match the stem.
                text.contains("modalit") -> MODALITY_REFUSED
                text.contains("not supported") -> MODALITY_REFUSED
                code == INVALID_ARGUMENT_CODE -> INVALID_ARGUMENT
                else -> UNEXPLAINED
            }
        }

        /** RFC 6455 1007: the peer rejected the payload. Gemini uses it for a refused setup. */
        const val INVALID_ARGUMENT_CODE = 1007
    }
}
