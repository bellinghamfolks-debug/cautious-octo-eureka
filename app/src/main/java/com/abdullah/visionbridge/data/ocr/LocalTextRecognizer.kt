package com.abdullah.visionbridge.data.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** Fast on-device OCR for Latin-script text. Arabic recovery is delegated to Gemini. */
class LocalTextRecognizer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): String {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.text.trim()
    }

    fun close() = recognizer.close()
}
