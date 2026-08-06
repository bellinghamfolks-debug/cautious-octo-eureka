package com.abdullah.visionbridge.data.diagnostics

/**
 * Decides which repeated per-event findings are worth writing down.
 *
 * One field bundle carried 2,109 automatic findings. Every one of them said either "a stage was
 * slow" or "a frame was dropped", and together they said nothing: the same observation repeated two
 * thousand times is one observation plus two thousand lines of cost. Worse, it buried the events
 * that mattered, so reading the bundle meant filtering out the app's own commentary first.
 *
 * The rule here is that a finding earns its line by being new. The first few of a kind establish
 * that it happens; after that, only a materially worse instance says anything the earlier ones did
 * not, and everything else is counted rather than written. The counts are not lost — they are
 * reported once, at the end, with the worst value each kind reached, which is the form the reader
 * wanted in the first place.
 *
 * Pure and synchronous. The recorder already serialises every write behind one lock, so this needs
 * no locking of its own.
 */
class FindingBudget(
    private val warningBurst: Int = WARNING_BURST,
    private val criticalBurst: Int = CRITICAL_BURST,
    private val escalation: Double = ESCALATION,
) {

    private class Tally {
        var emitted = 0
        var suppressed = 0
        var worstEmitted: Double? = null
        var worstSeen: Double? = null
    }

    /** What was left out, and how bad it got, for the one summary that replaces the repetition. */
    data class Suppression(val key: String, val suppressedCount: Int, val worstValue: Double?)

    private val tallies = LinkedHashMap<String, Tally>()

    /**
     * @param key what makes two findings the same observation — the code, plus whatever
     *   distinguishes one instance of it from another, such as which stage was slow.
     * @param severity `critical` findings get a larger burst, because a crash repeating twenty
     *   times is a different fact from a crash happening once.
     * @param value the measurement the finding is about, when it has one. A later instance is
     *   admitted only if it is materially worse than the worst already written.
     */
    fun admit(key: String, severity: String, value: Double?): Boolean {
        val tally = tallies.getOrPut(key) { Tally() }
        if (value != null && (tally.worstSeen == null || value > tally.worstSeen!!)) {
            tally.worstSeen = value
        }

        val burst = if (severity == "critical") criticalBurst else warningBurst
        if (tally.emitted < burst) {
            tally.emitted++
            if (value != null && (tally.worstEmitted == null || value > tally.worstEmitted!!)) {
                tally.worstEmitted = value
            }
            return true
        }

        // Past the burst, a finding has to beat the record to be worth a line. A finding with no
        // measurement can never beat anything, which is the right answer: after eight identical
        // unmeasured observations there is nothing a ninth can add.
        val previous = tally.worstEmitted
        if (value != null && previous != null && value >= previous * escalation) {
            tally.emitted++
            tally.worstEmitted = value
            return true
        }

        tally.suppressed++
        return false
    }

    /** Every kind that had instances left out, worst first. Empty when nothing was suppressed. */
    fun suppressions(): List<Suppression> = tallies
        .filterValues { it.suppressed > 0 }
        .map { (key, tally) -> Suppression(key, tally.suppressed, tally.worstSeen) }
        .sortedByDescending { it.suppressedCount }

    fun totalSuppressed(): Int = tallies.values.sumOf { it.suppressed }

    /** A new session starts with a clean record, or the second session inherits the first's noise. */
    fun reset() = tallies.clear()

    companion object {
        /**
         * Enough instances to show a pattern and its spread, few enough that the timeline stays
         * readable. Chosen against the bundle that motivated this: eight covers every distinct
         * slow-stage shape that session actually produced.
         */
        const val WARNING_BURST = 8
        const val CRITICAL_BURST = 25

        /** A repeat has to be a quarter worse than the record before it is a new observation. */
        const val ESCALATION = 1.25
    }
}
