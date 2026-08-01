package com.abdullah.visionbridge.data.paddleocr

/**
 * Builds a recognition head's class list from the character list stored in its ONNX metadata.
 *
 * The full class list a PP-OCR recognizer emits is `blank`, then the characters, then a space. The
 * blank is index 0 and is handled by the decoder, so what is returned here is everything above it.
 *
 * The trailing-newline handling is not a detail. The metadata ends with a newline, and keeping the
 * empty entry it produces adds one phantom class before the space — which put the model's space
 * index onto the phantom, so every space decoded as an empty string. Whole screens came back as one
 * run-on word in both Arabic and English while every other character was perfectly correct.
 */
object RecognitionDictionary {

    fun parse(raw: String): List<String> {
        val characters = raw.split('\n')
            .map { it.trimEnd('\r') }
            .let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        return characters + " "
    }
}
