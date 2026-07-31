package com.abdullah.visionbridge.data.paddleocr

import android.content.Context

/**
 * The PP-OCR models shipped inside the APK.
 *
 * They used to be six files the user installed by hand through a file picker, which was three
 * chances to install the wrong file and one chance to pair a model with a dictionary from a
 * different model — a mismatch that produces confident, fluent, completely wrong text rather than
 * an error. Nothing about that belonged in the hands of a blind user.
 *
 * They are now fetched at build time from a pinned URL and verified against a pinned SHA-256
 * (`scripts/fetch_ocr_models.py`) and packaged as assets, so the reader either works on first launch
 * or the build failed. There is no install step and no way to install the wrong thing.
 *
 * There are four files rather than six because RapidOCR's ONNX exports carry their character
 * dictionary inside the model's own metadata, so a dictionary can no longer be separated from the
 * model it belongs to.
 */
object BundledOcrModels {

    private const val ASSET_DIRECTORY = "ppocr"

    const val DETECTION = "ppocr-det.onnx"
    const val ARABIC_RECOGNITION = "ppocr-rec-ar.onnx"
    const val ENGLISH_RECOGNITION = "ppocr-rec-en.onnx"
    const val ORIENTATION = "ppocr-cls.onnx"

    val ALL = listOf(DETECTION, ARABIC_RECOGNITION, ENGLISH_RECOGNITION, ORIENTATION)

    /** Reads one packaged model into memory. ONNX Runtime takes the bytes directly. */
    fun read(context: Context, name: String): ByteArray =
        context.assets.open("$ASSET_DIRECTORY/$name").use { it.readBytes() }

    /**
     * True when every model is packaged.
     *
     * A build without them is a build mistake, not a user state, so this exists to turn that
     * mistake into one clear message instead of four confusing ones at load time.
     */
    fun allPresent(context: Context): Boolean = runCatching {
        val packaged = context.assets.list(ASSET_DIRECTORY)?.toSet().orEmpty()
        ALL.all { it in packaged }
    }.getOrDefault(false)
}
