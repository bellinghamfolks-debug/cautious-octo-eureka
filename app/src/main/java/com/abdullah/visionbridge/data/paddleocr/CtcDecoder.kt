package com.abdullah.visionbridge.data.paddleocr

import kotlin.math.ln

/**
 * Decodes a PP-OCR recognition head into text.
 *
 * The head emits, for each horizontal step, a probability across the character dictionary. CTC
 * decoding must collapse runs of the same index and drop the blank class, which is index 0 in every
 * PaddleOCR dictionary.
 *
 * ## Greedy, then beam
 *
 * The obvious decoding takes the most likely class at each step independently. That is *not* the
 * most likely string: CTC defines the probability of a string as the sum over every alignment that
 * collapses to it, and picking the best class per step maximises the best single alignment instead.
 * The two disagree exactly where the head is unsure — a letter split across a column boundary, a
 * glyph the model is torn between — which is where a reading is most likely to be wrong, and which
 * on this app's content means a product name or a room number.
 *
 * **Prefix beam search** (Graves & Jaitly 2014) sums those alignments properly. It costs more, so it
 * is spent where it can change the answer: the greedy pass runs first, and the beam runs only when
 * the greedy result looks uncertain. On a clean page of text the two agree and the beam never
 * starts; on the marginal crop that decides whether a label is read, it does.
 *
 * Confidence is the mean probability of the characters actually kept, not of every step. Blank
 * steps dominate a typical output, and including them makes a wrong reading look confident.
 */
object CtcDecoder {

    /** Index 0 is the CTC blank in PaddleOCR's dictionaries. */
    private const val BLANK_INDEX = 0

    /**
     * [beamSearched] records which path produced this result, so a diagnostic bundle shows how often
     * the extra work was spent and whether it changed anything.
     */
    data class Result(
        val text: String,
        val confidence: Float,
        val beamSearched: Boolean = false,
        val changedByBeam: Boolean = false,
    )

    /**
     * @param logits row-major [steps] x [classes] probabilities from the recognition head.
     * @param dictionary character for each class index above the blank, in dictionary order.
     * @param beamWidth hypotheses carried forward; 1 disables the beam entirely.
     */
    fun decode(
        logits: FloatArray,
        steps: Int,
        classes: Int,
        dictionary: List<String>,
        beamWidth: Int = DEFAULT_BEAM_WIDTH,
    ): Result {
        if (steps <= 0 || classes <= 0) return Result("", 0f)
        require(logits.size >= steps * classes) { "logits smaller than declared shape" }

        val greedy = decodeGreedy(logits, steps, classes, dictionary)
        if (beamWidth <= 1 || greedy.confidence >= BEAM_CONFIDENCE_FLOOR || greedy.text.isEmpty()) {
            return greedy
        }

        val beam = decodeBeam(logits, steps, classes, dictionary, beamWidth) ?: return greedy
        return Result(
            text = beam.text,
            // The confidence stays the per-character measure the rest of the pipeline is calibrated
            // against, recomputed for whichever string won.
            confidence = beam.confidence,
            beamSearched = true,
            changedByBeam = beam.text != greedy.text,
        )
    }

    /** Best class per step, repeats collapsed, blanks dropped. The classic decoding. */
    private fun decodeGreedy(
        logits: FloatArray,
        steps: Int,
        classes: Int,
        dictionary: List<String>,
    ): Result {
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

    /**
     * One hypothesis: a collapsed label, with the probability of every alignment reaching it split
     * by whether that alignment currently ends in a blank.
     *
     * Keeping the two apart is what makes the collapse rule correct. Extending `ab` by `b` is a new
     * character only if the alignment so far ended in a blank; otherwise it merges into the `b`
     * already there. A decoder that tracks one combined probability cannot tell those apart and
     * silently drops doubled letters.
     */
    private class Prefix(val label: IntArray) {
        var blank = Double.NEGATIVE_INFINITY
        var nonBlank = Double.NEGATIVE_INFINITY
        val total: Double get() = logSumExp(blank, nonBlank)
        val last: Int get() = if (label.isEmpty()) -1 else label[label.size - 1]
        val key: String get() = label.joinToString(",")
    }

    private fun decodeBeam(
        logits: FloatArray,
        steps: Int,
        classes: Int,
        dictionary: List<String>,
        beamWidth: Int,
    ): Result? {
        val empty = Prefix(IntArray(0))
        empty.blank = 0.0
        var beam = listOf(empty)

        for (step in 0 until steps) {
            val offset = step * classes
            // Only the classes worth extending by. A dictionary is thousands of symbols wide and
            // all but a handful carry no probability at any given step; considering them all would
            // make the beam cost proportional to the alphabet for no gain.
            val candidates = topClasses(logits, offset, classes)
            val next = HashMap<String, Prefix>(beam.size * (candidates.size + 1))

            fun at(label: IntArray): Prefix =
                next.getOrPut(label.joinToString(",")) { Prefix(label) }

            for (prefix in beam) {
                // Extend by blank: the label is unchanged and now ends in a blank.
                val blankProbability = ln(logits[offset + BLANK_INDEX].toDouble().coerceAtLeast(FLOOR))
                val same = at(prefix.label)
                same.blank = logSumExp(same.blank, prefix.total + blankProbability)

                for (classIndex in candidates) {
                    if (classIndex == BLANK_INDEX) continue
                    if (dictionary.getOrNull(classIndex - 1) == null) continue
                    val probability = ln(logits[offset + classIndex].toDouble().coerceAtLeast(FLOOR))

                    if (classIndex == prefix.last) {
                        // Repeat of the last character. Without an intervening blank it merges;
                        // with one it becomes a genuine second character.
                        val merged = at(prefix.label)
                        merged.nonBlank = logSumExp(merged.nonBlank, prefix.nonBlank + probability)

                        val extended = at(prefix.label + classIndex)
                        extended.nonBlank = logSumExp(extended.nonBlank, prefix.blank + probability)
                    } else {
                        val extended = at(prefix.label + classIndex)
                        extended.nonBlank = logSumExp(extended.nonBlank, prefix.total + probability)
                    }
                }
            }

            if (next.isEmpty()) return null
            beam = next.values.sortedByDescending { it.total }.take(beamWidth)
        }

        val best = beam.firstOrNull() ?: return null
        if (best.label.isEmpty()) return null

        val text = buildString {
            for (index in best.label) append(dictionary.getOrNull(index - 1) ?: "")
        }
        return Result(text, confidenceOf(logits, steps, classes, best.label))
    }

    /**
     * The per-character confidence of a decoded label, measured the same way the greedy path
     * measures it so the two are comparable and the calibration downstream still holds.
     *
     * Each character is scored by the highest probability that class reaches anywhere in the frames
     * it could have been emitted from.
     */
    private fun confidenceOf(
        logits: FloatArray,
        steps: Int,
        classes: Int,
        label: IntArray,
    ): Float {
        if (label.isEmpty()) return 0f
        var sum = 0.0
        for (classIndex in label) {
            var best = 0f
            for (step in 0 until steps) {
                val value = logits[step * classes + classIndex]
                if (value > best) best = value
            }
            sum += best
        }
        return (sum / label.size).toFloat()
    }

    /** Indices of the few classes with real probability at this step, blank always included. */
    private fun topClasses(logits: FloatArray, offset: Int, classes: Int): IntArray {
        val kept = ArrayList<Int>(CANDIDATES_PER_STEP)
        val scores = ArrayList<Float>(CANDIDATES_PER_STEP)
        for (classIndex in 0 until classes) {
            val value = logits[offset + classIndex]
            if (value < CANDIDATE_FLOOR) continue
            var position = kept.size
            while (position > 0 && scores[position - 1] < value) position--
            kept.add(position, classIndex)
            scores.add(position, value)
            if (kept.size > CANDIDATES_PER_STEP) {
                kept.removeAt(kept.size - 1)
                scores.removeAt(scores.size - 1)
            }
        }
        return kept.toIntArray()
    }

    private fun logSumExp(a: Double, b: Double): Double {
        if (a == Double.NEGATIVE_INFINITY) return b
        if (b == Double.NEGATIVE_INFINITY) return a
        val high = maxOf(a, b)
        val low = minOf(a, b)
        return high + kotlin.math.ln(1.0 + kotlin.math.exp(low - high))
    }

    private operator fun IntArray.plus(value: Int): IntArray {
        val grown = copyOf(size + 1)
        grown[size] = value
        return grown
    }

    /**
     * Above this the greedy reading is confident enough that the beam has nothing to add, and a
     * page of clean text decodes at greedy cost. Chosen at the point where field readings start
     * being wrong rather than merely imperfect.
     */
    const val BEAM_CONFIDENCE_FLOOR = 0.90f

    /** Small on purpose: the gain is in the first few hypotheses, and this runs per text line. */
    const val DEFAULT_BEAM_WIDTH = 8

    /** Classes considered per step. Beyond a handful they contribute nothing but time. */
    private const val CANDIDATES_PER_STEP = 6

    /** A class below this probability cannot survive to the end of a line. */
    private const val CANDIDATE_FLOOR = 0.001f

    /** Keeps log(0) out of the arithmetic. */
    private const val FLOOR = 1e-12
}
