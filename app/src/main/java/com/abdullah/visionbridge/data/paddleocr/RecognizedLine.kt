package com.abdullah.visionbridge.data.paddleocr

/** One recognition attempt on one cropped line. */
data class LineReading(val text: String, val confidence: Float, val script: OcrScript)

enum class OcrScript { ARABIC, LATIN }

/**
 * Chooses between the Arabic and Latin readings of the same cropped line, and assembles the page.
 *
 * The two confidences are not comparable, and that is the whole difficulty. A recognizer handed the
 * wrong script does not report failure — it returns its most likely characters in its own alphabet,
 * at an ordinary-looking confidence. On a real device the Latin model shown Arabic returned
 * "Library phone not font" and "SIM 2 ¿ lay eSIM Jluis" at confidences above the Arabic model's
 * correct reading of the same lines. Any rule that picks the higher number reproduces that bug, and
 * so does penalising "off-script" output: the Latin model's gibberish is Latin, so it is never
 * off-script and never penalised.
 *
 * The signal that does work is an asymmetry between the two dictionaries. PP-OCRv5's Arabic head is
 * multilingual — its dictionary carries Latin letters and digits alongside Arabic — while the
 * English head has no Arabic characters at all. So Arabic output is reachable only by a model that
 * had the choice and took it, while Latin output is all the English head can ever produce. Arabic
 * characters from the Arabic head are therefore evidence about the line; Latin characters from the
 * English head are evidence about nothing.
 *
 * Hence: an Arabic-looking Arabic reading decides the line outright, regardless of what the English
 * head claims. Only when the Arabic head itself transcribes the line as Latin does the English
 * specialist take over, and it is preferred there because it is the more accurate of the two on its
 * own script.
 */
object BilingualLineSelector {

    /** Below this, a reading is treated as a failed recognition rather than a weak one. */
    const val MIN_ACCEPTABLE_CONFIDENCE = 0.55f

    /** Share of letters that must belong to a script before the line is called that script. */
    private const val SCRIPT_MAJORITY = 0.5f

    fun select(arabic: LineReading?, latin: LineReading?): LineReading? {
        val arabicReading = arabic?.takeIf(::isUsable)
        val latinReading = latin?.takeIf(::isUsable)
        if (arabicReading != null && isDecisiveArabic(arabicReading)) return arabicReading
        if (latinReading != null && latinRatio(latinReading.text) >= SCRIPT_MAJORITY) {
            return latinReading
        }
        // Neither reading settled on a script — digits, symbols or a bad crop. Nothing about the
        // alphabet separates them any more, so the confidences are all that is left.
        return listOfNotNull(arabicReading, latinReading).maxByOrNull { it.confidence }
    }

    /**
     * True when this Arabic-head reading settles the line on its own, so the English head does not
     * need to be run on the crop at all.
     */
    fun isDecisiveArabic(reading: LineReading): Boolean =
        reading.script == OcrScript.ARABIC &&
            isUsable(reading) &&
            arabicRatio(reading.text) >= SCRIPT_MAJORITY

    private fun isUsable(reading: LineReading): Boolean =
        reading.text.isNotBlank() && reading.confidence >= MIN_ACCEPTABLE_CONFIDENCE

    fun arabicRatio(text: String): Float {
        val letters = text.count(Char::isLetter)
        if (letters == 0) return 0f
        return text.count(::isArabicLetter).toFloat() / letters
    }

    fun latinRatio(text: String): Float {
        val letters = text.count(Char::isLetter)
        if (letters == 0) return 0f
        return text.count { it in 'A'..'Z' || it in 'a'..'z' }.toFloat() / letters
    }

    /** True when the line should be read right to left, decided by which script dominates it. */
    fun isRightToLeft(text: String): Boolean = arabicRatio(text) > latinRatio(text)

    private fun isArabicLetter(value: Char): Boolean =
        value in '؀'..'ۿ' || value in 'ݐ'..'ݿ' || value in 'ࢠ'..'ࣿ'
}

/**
 * Assembles ordered line readings into the page text that will be spoken.
 *
 * One detected box is a word or a phrase, not a line, so boxes on the same visual line are joined
 * with spaces and every visual line becomes exactly one output line. The reading pipeline
 * downstream treats a line as its unit for deciding what the user has already heard, so getting
 * this boundary right is what keeps a page from being re-read.
 */
object PageAssembler {

    fun assemble(lines: List<List<LineReading>>): String = lines
        .map { line -> line.joinToString(" ") { it.text.trim() }.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

    /** Mean confidence across every accepted reading, used to reject a hopeless frame outright. */
    fun meanConfidence(lines: List<List<LineReading>>): Float {
        val readings = lines.flatten()
        if (readings.isEmpty()) return 0f
        return readings.map { it.confidence }.average().toFloat()
    }
}
