package com.abdullah.visionbridge.capture

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Decouples a request's transport budget from optical verification and publication.
 * Values must be cumulative snapshots of ONE immutable turn, never text deltas.
 * At most one snapshot waits behind the consumer; the final snapshot is always consumed.
 * Both jobs belong to the caller: superseding the turn cancels the request AND its verifier.
 */
internal suspend fun <T> consumeLatestTurnOutput(
    produce: suspend (emit: (T) -> Unit) -> T,
    consume: suspend (T) -> Unit,
): T = coroutineScope {
    val pending = Channel<T>(Channel.CONFLATED)
    val consumer = launch {
        for (snapshot in pending) consume(snapshot)
    }
    try {
        val final = produce { snapshot -> pending.trySend(snapshot).getOrThrow() }
        pending.trySend(final).getOrThrow()
        pending.close()
        consumer.join()
        final
    } finally {
        pending.cancel()
        consumer.cancel()
    }
}
