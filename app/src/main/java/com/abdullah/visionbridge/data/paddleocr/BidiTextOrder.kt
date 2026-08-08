package com.abdullah.visionbridge.data.paddleocr

import java.text.Bidi

/**
 * Turns a recognizer's visual character order into logical order.
 *
 * A CTC recognizer walks the cropped line left to right across image columns and emits one
 * character per column group. For Latin that is already logical order. For Arabic it is exactly
 * backwards: the leftmost glyph on screen is the *last* character of the word. CTC alignment is
 * monotonic, so no Arabic model can emit logical order — undoing the reversal is the caller's job,
 * and skipping it is why a correct model produced "ةلومحملالاصتالاةطقن" for
 * "نقطة الاتصال المحمولة" on a real device.
 *
 * Reversing the whole string is not enough. An Arabic line that contains a phone number, a version
 * number or an English product name carries left-to-right runs inside it, and those runs are already
 * in logical order when they arrive. Reversing everything would turn "Wi-Fi" into "iF-iW". So the
 * string is reversed once and each left-to-right run is then reversed back into place.
 */
object BidiTextOrder {

    /**
     * @param visual one recognizer's output for one crop, in image-column order.
     * @return the same characters in logical order, or [visual] unchanged when the line carries no
     * right-to-left script and therefore was never reversed to begin with.
     *
     * The work is done by [java.text.Bidi], which is the platform's complete implementation of the
     * Unicode Bidirectional Algorithm (UAX #9) backed by ICU. It replaced a hand-written subset
     * that recognised left-to-right runs from a character set of its own: `A-Z`, `a-z`, digits, and
     * the punctuation `.-:/+@#%`. That set was assembled from the cases that had gone wrong, which
     * is exactly the wrong way to arrive at a rule — it had no notion of European versus Arabic
     * numbers, of how a neutral between two runs resolves, of bracket pairs, or of isolate
     * characters, and every line that used something outside the list was a defect waiting to be
     * reported.
     *
     * ## Why running a *logical to visual* algorithm on visual input is correct here
     *
     * UAX #9 describes reordering in one direction. What arrives from a CTC recogniser is the other
     * one: the head walks the crop left to right across image columns and emits one character per
     * column group, so its output is in visual order and the logical string has to be recovered.
     *
     * For the structures this pipeline produces — one paragraph direction, with left-to-right runs
     * such as a product name or a phone number embedded inside it — the reordering is an involution:
     * levels are 1 and 2, and rule L2's reversals undo themselves when applied twice. So resolving
     * levels over the visual string and applying the same reordering returns logical order, while
     * every classification decision inside it is the standard's rather than this file's.
     *
     * The assumption is stated rather than hidden. A line with deeper nesting — explicit embeddings
     * or overrides at level 3 and above — is not an involution and would not be recovered exactly.
     * No recogniser in this app can emit one: those levels only arise from explicit formatting
     * characters, which OCR does not produce.
     */
    fun toLogicalOrder(visual: String): String {
        if (visual.none(::isRightToLeftLetter)) return visual

        // Forced, not inferred. `DIRECTION_DEFAULT_RIGHT_TO_LEFT` takes the paragraph direction
        // from the first strong character, and in visual order that is often a Latin word: the
        // display of "شبكة Wi-Fi" begins, on the left, with "W". Inferring from it resolves the
        // line as left-to-right and returns it unchanged. The gate above has already established
        // that this line contains right-to-left script, which is what makes the direction known.
        val bidi = Bidi(visual, Bidi.DIRECTION_RIGHT_TO_LEFT)
        val runCount = bidi.runCount
        if (runCount <= 0) return visual

        val levels = ByteArray(runCount)
        val runs = arrayOfNulls<Any>(runCount)
        for (index in 0 until runCount) {
            val level = bidi.getRunLevel(index)
            levels[index] = level.toByte()
            val text = visual.substring(bidi.getRunStart(index), bidi.getRunLimit(index))
            // An odd level is a right-to-left run: its characters arrived in visual order and are
            // flipped back, mirroring the paired punctuation as rule L4 requires.
            runs[index] = if (level % 2 == 1) {
                buildString(text.length) {
                    for (position in text.length - 1 downTo 0) append(mirrored(text[position]))
                }
            } else {
                text
            }
        }

        Bidi.reorderVisually(levels, 0, runs, 0, runCount)
        return buildString(visual.length) { runs.forEach { append(it as String) } }
    }


    /**
     * Ranges are written as escapes rather than as literal characters on purpose. The top of the
     * Arabic Presentation Forms-B block is U+FEFF, which is also the byte-order mark: writing it
     * literally puts a real BOM in the middle of this file. The range stops at U+FEFC, the last
     * assigned presentation form, so the BOM is not treated as a letter either way.
     */
    private fun isRightToLeftLetter(value: Char): Boolean =
        value in '\u0590'..'\u05FF' || // Hebrew
            value in '\u0600'..'\u06FF' || // Arabic
            value in '\u0750'..'\u077F' || // Arabic Supplement
            value in '\u08A0'..'\u08FF' || // Arabic Extended-A
            value in '\uFB50'..'\uFDFF' || // Arabic Presentation Forms-A
            value in '\uFE70'..'\uFEFC' // Arabic Presentation Forms-B

    /**
     * Arabic-Indic digits are excluded deliberately: they belong to the Arabic block but are written
     * left to right, so they are handled as part of a left-to-right run rather than as Arabic text.
     */
    private fun isLeftToRightRun(value: Char): Boolean =
        value in 'A'..'Z' || value in 'a'..'z' ||
            value.isDigit() ||
            isRunBinder(value)

    private fun isRunBinder(value: Char): Boolean = value in ".-:/+@#%"

    /**
     * Brackets are stored by their meaning, not their shape, so the glyph that opened a span on the
     * left of the image closes it once the span is read the other way round.
     */
    private fun mirrored(value: Char): Char = when (value) {
        '(' -> ')'
        ')' -> '('
        '[' -> ']'
        ']' -> '['
        '{' -> '}'
        '}' -> '{'
        '<' -> '>'
        '>' -> '<'
        else -> value
    }
}
