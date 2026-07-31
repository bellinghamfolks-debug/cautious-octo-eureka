package com.abdullah.visionbridge.data.paddleocr

/**
 * Decodes a PP-OCR recognition head into text.
 *
 * The head emits, for each horizontal step, a probability across the character dictionary. CTC
 * decoding collapses runs of the same index and drops the blank class, which is index 0 in every
 * PaddleOCR dictionary.
 *
 * Confidence is the mean probability of the characters actually kept, not of every step. Blank
 * steps dominate a typical output, and including them makes a wrong reading look confident.
 */
object CtcDecoder {

    /** Index 0 is the CTC blank in PaddleOCR's dictionaries. */
    private const val BLANK_INDEX = 0

    data class Result(val text: String, val confidence: Float)

    /**
     * @param logits row-major [steps] x [classes] probabilities from the recognition head.
     * @param dictionary character for each class index above the blank, in dictionary order.
     */
    fun decode(logits: FloatArray, steps: Int, classes: Int, dictionary: List<String>): Result {
        if (steps <= 0 || classes <= 0) return Result("", 0f)
        require(logits.size >= steps * classes) { "logits smaller than declared shape" }

        val builder = StringBuilder()
        var confidenceSum = 0.0
        var kept = 0
        var previousIndex = -1

        for (step in 0 until steps) {
            val offset = step * classes
            var bestIndex = 0
            var bestValue = logits[offset]
            for (classIndex in 1 until classes) {
                val value = logits[offset + classIndex]
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = classIndex
                }
            }

            // Collapse repeats, drop blanks: the two rules that define CTC decoding.
            if (bestIndex != BLANK_INDEX && bestIndex != previousIndex) {
                val symbol = dictionary.getOrNull(bestIndex - 1)
                if (symbol != null) {
                    builder.append(symbol)
                    confidenceSum += bestValue
                    kept++
                }
            }
            previousIndex = bestIndex
        }

        val confidence = if (kept == 0) 0f else (confidenceSum / kept).toFloat()
        return Result(builder.toString(), confidence)
    }
}
