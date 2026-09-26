package com.abdullah.visionbridge.data.speech

import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.gemini.StreamingSpeechBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one voice a live reading is spoken in, and the memory of what it has already said.
 *
 * Everything this needs already existed and was tested — [ReadingLedger] to decide whether a page
 * is new, continued or already heard, [ReadingDeliveryTracker] to learn what actually reached the
 * user's ears, and the engine's reading queue to speak a page in order without dropping a line —
 * but none of it was connected to the live path. The 2026-09-26 16:33 session shows the price: the
 * same 747-character page was reported at 79.64 s and again at 100.86 s, and nothing stood between
 * the second report and a second reading.
 *
 * A page that is still being spoken counts as heard for this purpose. The ledger only learns a page
 * once its last block has finished, and a page of thirty-five lines takes longer to say than the
 * model takes to report it again, so without that the second report would restart the page from
 * its first line while the user was halfway down it.
 */
class LiveReadingSpeaker(private val tts: BilingualTtsEngine) {

    private val ledger = ReadingLedger()
    private val tracker = ReadingDeliveryTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Keeps readings entering the engine in the order they were accepted. */
    private val order = Mutex()

    private val lock = Any()

    /** The page currently being spoken, until the engine reports its last block. */
    private var inFlight: InFlight? = null

    private class InFlight(val document: String, val alreadyHeard: String) {
        @Volatile var readingId: Long = UNASSIGNED
        val acceptedAtMs: Long = System.currentTimeMillis()
    }

    /**
     * The page being spoken, unless it has been in flight so long that its report was lost.
     *
     * A reading whose blocks never report — the engine restarted underneath it — would otherwise
     * stand in for its page for the rest of the session and silence every later report of it.
     */
    private fun liveInFlight(): InFlight? =
        inFlight?.takeIf { System.currentTimeMillis() - it.acceptedAtMs < IN_FLIGHT_MAX_MS }

    init {
        tts.onBlockDelivered { readingId, blockIndex, outcome ->
            commit(readingId, tracker.record(readingId, blockIndex, outcome))
        }
    }

    /**
     * Speaks [text] if the user has not heard it, or only the part they have not heard.
     *
     * [scene] describes the object and its surroundings; it is spoken before a new page and not
     * recorded as part of it. [fields] identify the turn in the diagnostics. Returns whether anything was
     * queued.
     */
    fun speak(
        text: String,
        rate: Float,
        interruptPrevious: Boolean,
        scene: String = "",
        fields: Map<String, Any?> = emptyMap(),
    ): Boolean {
        val samePageAsInFlight: Boolean
        val (outcome, accepted) = synchronized(lock) {
            val current = liveInFlight()
            val continued = continuesInFlight(current?.document, current?.alreadyHeard, text)
            samePageAsInFlight = continued != null
            val verdict = continued ?: ledger.evaluate(text)
            val entry = (verdict as? ReadingLedger.Decision.Speak)
                ?.let { InFlight(it.document, it.alreadyHeard) }
            if (entry != null) inFlight = entry
            verdict to entry
        }
        if (outcome is ReadingLedger.Decision.Skip) {
            DiagnosticHub.record(
                "LIVE_READING_SKIPPED",
                fields + mapOf(
                    "reason" to outcome.reason,
                    "characters" to text.length,
                    "contentHash" to contentHash(text),
                ),
            )
            return false
        }
        val decision = outcome as ReadingLedger.Decision.Speak
        val pending = checkNotNull(accepted)
        val blocks = blocksOf(decision.text)
        // Only a different page cuts the one being read. The ledger may still call it a
        // continuation — a page that shares a stray line with an old one is "continued" by it —
        // but if it is not the page in flight, the page in flight is no longer what is in view.
        val interrupting = interruptPrevious && !samePageAsInFlight
        // Said first, and once per page: it says what is being read before the reading starts.
        // After a thirty-line page it arrived minutes late, or never, once a new page cut in.
        val intro = if (decision.continuation) "" else scene
        DiagnosticHub.record(
            "LIVE_READING_ACCEPTED",
            fields + mapOf(
                "characters" to decision.text.length,
                "documentCharacters" to decision.document.length,
                "blocks" to blocks.size,
                "continuation" to decision.continuation,
                "interruptPrevious" to interrupting,
                "contentHash" to contentHash(decision.document),
                "sceneCharacters" to intro.length,
            ),
        )
        scope.launch {
            order.withLock {
                val readingId = tts.beginReading(interruptPrevious = interrupting)
                pending.readingId = readingId
                tracker.open(readingId, decision.alreadyHeard, blocks)
                // Outside the tracker's blocks, so the description can neither hold up the page's
                // accounting nor be mistaken for a line of it.
                if (intro.isNotBlank()) tts.speakReadingBlock(readingId, SCENE_BLOCK, intro, rate)
                blocks.forEachIndexed { index, block ->
                    tts.speakReadingBlock(readingId, index, block, rate)
                }
                tts.finishReading(readingId)
            }
        }
        return true
    }

    /** Forgets every page, for a new session. */
    fun reset() {
        synchronized(lock) {
            inFlight = null
            ledger.reset()
            tracker.reset()
        }
    }

    private fun commit(readingId: Long, delivery: ReadingDeliveryTracker.Delivery?) {
        delivery ?: return
        synchronized(lock) {
            if (inFlight?.readingId == readingId) inFlight = null
            if (delivery.deliveredText.isNotBlank()) {
                ledger.recordDelivered(delivery.alreadyHeard, delivery.deliveredText)
            }
        }
        DiagnosticHub.record(
            "LIVE_READING_DELIVERED",
            mapOf(
                "readingId" to readingId,
                "deliveredCharacters" to delivery.deliveredText.length,
                "owedCharacters" to delivery.owedText.length,
                "complete" to delivery.complete,
                "outcomes" to delivery.outcomes.joinToString(",") { it.name },
            ),
        )
    }

    companion object {
        private const val UNASSIGNED = -1L

        /** The description's block index: outside every page's range, so the tracker ignores it. */
        private const val SCENE_BLOCK = -2

        /** Longer than any page takes to read aloud; see [liveInFlight]. */
        private const val IN_FLIGHT_MAX_MS = 180_000L

        /** How much of a report must match the page being spoken for it to be that page. */
        private const val IN_FLIGHT_COVERAGE = 0.5

        /**
         * What to do with [text] while [inFlightDocument] is still being spoken, or null when the two
         * are unrelated and the ledger should decide.
         *
         * The same page again is skipped rather than restarted; the same page with lines the first
         * report did not have is continued with only those lines, queued behind the reading rather
         * than cutting into it.
         */
        fun continuesInFlight(
            inFlightDocument: String?,
            inFlightAlreadyHeard: String?,
            text: String,
        ): ReadingLedger.Decision? {
            if (inFlightDocument.isNullOrBlank()) return null
            val candidate = DocumentSpeechPolicy.readableLines(text).joinToString("\n")
            if (candidate.isBlank()) return ReadingLedger.Decision.Skip("no_readable_text")
            val familiar =
                DocumentSpeechPolicy.coverageOf(inFlightDocument, candidate) >= IN_FLIGHT_COVERAGE ||
                    DocumentSpeechPolicy.sameDocument(inFlightDocument, candidate) ||
                    DocumentSpeechPolicy.covers(container = candidate, contained = inFlightDocument)
            if (!familiar) return null
            val addition = DocumentSpeechPolicy.newContent(
                alreadySpoken = inFlightDocument,
                current = candidate,
            )
            if (!carriesAWord(addition)) return ReadingLedger.Decision.Skip("already_being_read")
            val heard = listOf(inFlightAlreadyHeard.orEmpty(), inFlightDocument)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            return ReadingLedger.Decision.Speak(
                addition,
                document = candidate,
                continuation = true,
                alreadyHeard = heard,
            )
        }

        /** A page split the way the engine paces it: whole lines, long ones at clause boundaries. */
        fun blocksOf(text: String): List<String> {
            val buffer = StreamingSpeechBuffer(StreamingSpeechBuffer.Profile.DOCUMENT)
            return (buffer.append(text, urgent = false) + buffer.finish()).filter { it.isNotBlank() }
        }

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        private fun carriesAWord(text: String): Boolean =
            NON_WORD.split(text).any { it.length >= 2 }

        private fun contentHash(text: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .take(6)
                .joinToString("") { "%02x".format(it) }
    }
}
