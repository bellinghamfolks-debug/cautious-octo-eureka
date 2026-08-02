package com.abdullah.visionbridge.data.speech

/**
 * What actually became of one piece of speech.
 *
 * The engine used to resolve a single `CompletableDeferred<Unit>` for `onDone`, `onError`,
 * `onStop` and for an interrupt, so every caller above it saw the same thing whether the user had
 * heard a word or not. A device bundle shows what that cost: of 29 utterances submitted while
 * someone held a perfume bottle, 15 reached `onDone` and 14 were cut off mid-word, and the layer
 * that decides what still needs saying could not tell the two apart. It recorded all 29 as heard.
 *
 * Anything other than [COMPLETED] means the user did not hear it, and it is still owed to them.
 */
enum class SpeechOutcome {
    /** The engine reported `onDone`. This is the only outcome that discharges the debt. */
    COMPLETED,

    /** Speech had begun or was queued and a new target, a new reading or an error cut it off. */
    INTERRUPTED,

    /** The engine reported an error, or never became available. */
    FAILED,

    /** A newer reading replaced this one before the block was ever handed to the engine. */
    SUPERSEDED_BEFORE_START,

    /** The user stopped capture or stopped speech deliberately. */
    CANCELLED_BY_USER,
    ;

    /** True only when the words reached the user's ears. */
    val delivered: Boolean get() = this == COMPLETED
}
