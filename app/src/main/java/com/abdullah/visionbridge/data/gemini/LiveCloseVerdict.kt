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

    /** Correctable: re-open the same model without asking for a spoken language. */
    LANGUAGE_UNSUPPORTED,

    /**
     * Correctable, and the cause of almost everything in the 2026-09-26 00:09 session: the setup
     * carried a session-resumption handle that did not belong to it. Re-open without resuming.
     */
    STALE_RESUMPTION,

    /**
     * The connection failed on the server's side. This says nothing about what the model can do,
     * so the model keeps its place in the queue — judging it here is what stripped the app of all
     * nine models in thirteen seconds and left reading with no live transport at all.
     */
    TRANSPORT_ERROR,

    /** Permanent for this modality: the model cannot answer this way. */
    MODALITY_REFUSED,

    /** The setup payload was rejected for some other reason; do not keep trying it. */
    INVALID_ARGUMENT,

    /** No explanation given. */
    UNEXPLAINED,
    ;

    /** Whether the same model deserves another attempt, configured differently. */
    val correctable: Boolean
        get() = this == NEEDS_THINKING_LEVEL ||
            this == LANGUAGE_UNSUPPORTED ||
            this == STALE_RESUMPTION

    /**
     * Whether this verdict is evidence about the model's capability at all.
     *
     * Only a refusal is. A transport error is about the connection, and a correctable refusal is a
     * request; treating either as "this model cannot answer" removes a working model permanently.
     */
    val provesIncapable: Boolean
        get() = this == MODALITY_REFUSED || this == INVALID_ARGUMENT

    companion object {
        /** Reads [code] and [reason] exactly as the server sent them. */
        fun of(code: Int, reason: String): LiveCloseVerdict {
            val text = reason.lowercase()
            return when {
                text.contains("thinking level") -> NEEDS_THINKING_LEVEL
                // "Unsupported language code 'ar-XA' for model models/gemini-2.5-flash-native-*"
                text.contains("language code") -> LANGUAGE_UNSUPPORTED
                // "BidiGenerateContent session history not found"
                text.contains("session history") -> STALE_RESUMPTION
                // "modalities" in the long form, "modality" in the short; match the stem.
                text.contains("modalit") -> MODALITY_REFUSED
                text.contains("not supported") -> MODALITY_REFUSED
                code == INTERNAL_ERROR_CODE -> TRANSPORT_ERROR
                code == POLICY_VIOLATION_CODE -> TRANSPORT_ERROR
                code == INVALID_ARGUMENT_CODE -> INVALID_ARGUMENT
                else -> UNEXPLAINED
            }
        }

        /** RFC 6455 1007: the peer rejected the payload. Gemini uses it for a refused setup. */
        const val INVALID_ARGUMENT_CODE = 1007

        /** RFC 6455 1008: Gemini uses it for a resumption handle it no longer holds. */
        const val POLICY_VIOLATION_CODE = 1008

        /** RFC 6455 1011: the server failed, which is not the model's verdict on itself. */
        const val INTERNAL_ERROR_CODE = 1011
    }
}
