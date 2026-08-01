package com.abdullah.visionbridge.data.paddleocr

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
     */
    fun toLogicalOrder(visual: String): String {
        if (visual.none(::isRightToLeftLetter)) return visual

        val reversed = visual.reversed()
        val result = StringBuilder(reversed.length)
        var index = 0
        while (index < reversed.length) {
            if (!isLeftToRightRun(reversed[index])) {
                result.append(mirrored(reversed[index]))
                index++
                continue
            }
            // A run that reads left to right even inside Arabic: Latin words, digits, and the
            // punctuation that binds them together such as the dot in "1.2" or the dash in "Wi-Fi".
            var end = index
            while (end < reversed.length && isLeftToRightRun(reversed[end])) end++
            // Trailing binder characters belong to the Arabic side, not to the run.
            var trimmedEnd = end
            while (trimmedEnd > index && isRunBinder(reversed[trimmedEnd - 1])) trimmedEnd--
            if (trimmedEnd == index) {
                result.append(mirrored(reversed[index]))
                index++
                continue
            }
            result.append(reversed, index, trimmedEnd)
            result.reverse(result.length - (trimmedEnd - index), result.length)
            index = trimmedEnd
        }
        return result.toString()
    }

    private fun StringBuilder.reverse(from: Int, to: Int) {
        var start = from
        var end = to - 1
        while (start < end) {
            val swap = this[start]
            this[start] = this[end]
            this[end] = swap
            start++
            end--
        }
    }

    private fun isRightToLeftLetter(value: Char): Boolean =
        value in '֐'..'׿' || // Hebrew
            value in '؀'..'ۿ' || // Arabic
            value in 'ݐ'..'ݿ' || // Arabic Supplement
            value in 'ࢠ'..'ࣿ' || // Arabic Extended-A
            value in 'ﭐ'..'﷿' || // Arabic Presentation Forms-A
            value in 'ﹰ'..'﻿' // Arabic Presentation Forms-B

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
