package com.abdullah.visionbridge.capture

/**
 * Defines whether work from an older visual generation may finish and reach the user.
 *
 * Disabling interruption is a deliberate promise to let the active response complete. The
 * coordinator still retains only the newest pending frame, so it catches up without building a
 * backlog and without repeatedly cancelling the network request.
 */
internal object VisualChangeDeliveryPolicy {
    fun mayDeliver(
        generationAtCapture: Long,
        currentGeneration: Long,
        interruptOnVisualChange: Boolean,
    ): Boolean = generationAtCapture == currentGeneration || !interruptOnVisualChange

    fun shouldCancelActiveRequest(interruptOnVisualChange: Boolean): Boolean =
        interruptOnVisualChange
}
