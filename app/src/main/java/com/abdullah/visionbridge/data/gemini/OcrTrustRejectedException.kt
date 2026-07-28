package com.abdullah.visionbridge.data.gemini

/**
 * A safe, expected OCR rejection. It is not treated as a network or application failure.
 * The spoken message gives the blind user immediate feedback instead of leaving the app silent.
 */
class OcrTrustRejectedException(
    val spokenMessage: String,
    message: String = spokenMessage,
) : IllegalStateException(message)
