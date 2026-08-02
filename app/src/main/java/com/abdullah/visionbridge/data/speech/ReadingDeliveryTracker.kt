package com.abdullah.visionbridge.data.speech

/**
 * Accounts for how much of a reading the user actually heard.
 *
 * A reading is opened with the blocks it intends to speak, each block reports its own
 * [SpeechOutcome], and when every block has reported the tracker says what was delivered and what
 * is still owed. Speech is sequential, so the delivered part is the longest run of completed blocks
 * from the start: if block three was cut off, blocks four and five were never spoken either, no
 * matter what their individual outcomes claim.
 *
 * This is deliberately pure. It is the piece that decides whether a page may be marked as read, and
 * that decision was previously made by the mere act of putting text on a queue.
 */
class ReadingDeliveryTracker {

    /**
     * The verdict on one finished reading.
     *
     * [alreadyHeard] is what the user had heard of this page before the reading began, so the
     * ledger can record the union rather than a fragment that would make the first half look unread.
     */
    data class Delivery(
        val alreadyHeard: String,
        val deliveredText: String,
        val owedText: String,
        val outcomes: List<SpeechOutcome>,
    ) {
        val complete: Boolean get() = owedText.isBlank()
    }

    private class InFlight(val alreadyHeard: String, val blocks: List<String>) {
        val outcomes = arrayOfNulls<SpeechOutcome>(blocks.size)
        val settled: Boolean get() = outcomes.all { it != null }
    }

    private val readings = LinkedHashMap<Long, InFlight>()

    /** Registers the blocks [readingId] intends to speak, in the order they will be spoken. */
    @Synchronized
    fun open(readingId: Long, alreadyHeard: String, blocks: List<String>) {
        if (blocks.isEmpty()) return
        readings[readingId] = InFlight(alreadyHeard, blocks)
        while (readings.size > MAX_TRACKED_READINGS) {
            readings.remove(readings.keys.first())
        }
    }

    /**
     * Records what became of one block, and returns the [Delivery] once every block of [readingId]
     * has reported. Returns null while the reading is still in flight, and for a block that belongs
     * to a reading this tracker has already settled or never saw.
     */
    @Synchronized
    fun record(readingId: Long, blockIndex: Int, outcome: SpeechOutcome): Delivery? {
        val reading = readings[readingId] ?: return null
        if (blockIndex !in reading.blocks.indices) return null
        // First outcome wins. An interrupt can race the engine's own terminal callback, and the
        // first one to arrive is the one that describes what the user experienced.
        if (reading.outcomes[blockIndex] == null) reading.outcomes[blockIndex] = outcome
        if (!reading.settled) return null
        readings.remove(readingId)
        return deliveryOf(reading)
    }

    /**
     * Settles [readingId] immediately, treating blocks that never reported as not delivered.
     *
     * This is the backstop for a reading whose blocks can no longer report at all — the engine was
     * shut down, or the coordinator was reset underneath it. Silence about a block is not evidence
     * that it was spoken.
     */
    @Synchronized
    fun abandon(readingId: Long, outcome: SpeechOutcome): Delivery? {
        val reading = readings.remove(readingId) ?: return null
        for (index in reading.outcomes.indices) {
            if (reading.outcomes[index] == null) reading.outcomes[index] = outcome
        }
        return deliveryOf(reading)
    }

    @Synchronized
    fun reset() = readings.clear()

    private fun deliveryOf(reading: InFlight): Delivery {
        val outcomes = reading.outcomes.map { it ?: SpeechOutcome.INTERRUPTED }
        val deliveredCount = outcomes.takeWhile { it.delivered }.size
        return Delivery(
            alreadyHeard = reading.alreadyHeard,
            deliveredText = reading.blocks.take(deliveredCount).joinToString("\n"),
            owedText = reading.blocks.drop(deliveredCount).joinToString("\n"),
            outcomes = outcomes,
        )
    }

    private companion object {
        /** Only the current reading and a little history matter; older ones can never report. */
        const val MAX_TRACKED_READINGS = 8
    }
}
