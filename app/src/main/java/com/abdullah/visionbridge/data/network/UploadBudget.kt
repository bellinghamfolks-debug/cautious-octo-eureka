package com.abdullah.visionbridge.data.network

/**
 * How many bytes may be uploaded over the link the request is actually bound to.
 *
 * This exists because of a measurement the app could not previously make. A field session forced
 * every Gemini request onto cellular, and eight of twelve died at 20.3 seconds — the read timeout,
 * to a tenth of a second, every time. The obvious reading is "the network is broken". The bound
 * link's own report says otherwise:
 *
 * ```
 * boundDownstreamKbps: 14      defaultDownstreamKbps: 30000
 * ```
 *
 * Fourteen kilobits per second. The radio was up, validated, not suspended and not behind a portal;
 * it was simply carrying almost nothing. A 120 KB photograph needs about seventy seconds on that
 * link, so every request was doomed before the first byte left the phone, and raising the timeout
 * would only have made the user wait longer for the same silence.
 *
 * The link tells you its speed at the moment you bind to it. Sizing the payload to what it can
 * actually carry before the deadline is the difference between a slow answer and no answer.
 */
object UploadBudget {

    /**
     * Bytes that can realistically cross a link of [linkKbps] within [deadlineMs].
     *
     * Returns null when the link is unknown or fast enough that the ordinary size caps decide —
     * which is the normal case on Wi-Fi, and means "do not degrade the image".
     */
    fun bytesFor(linkKbps: Int?, deadlineMs: Long): Int? {
        if (linkKbps == null || linkKbps <= 0) return null
        if (linkKbps >= COMFORTABLE_KBPS) return null
        // Only part of the window may be spent uploading: the model still has to read the image,
        // think, and stream an answer back over the same link.
        val uploadMs = deadlineMs * UPLOAD_SHARE
        val bits = linkKbps.toDouble() * 1_000.0 * (uploadMs / 1_000.0)
        val bytes = (bits / 8.0 * USABLE_FRACTION).toInt()
        return bytes.coerceAtLeast(MIN_BYTES)
    }

    /**
     * True when no image worth sending fits, so the honest answer is to say the link is unusable
     * rather than to spend the whole deadline proving it again.
     */
    fun isUnusable(linkKbps: Int?): Boolean = linkKbps != null && linkKbps in 1 until UNUSABLE_KBPS

    /**
     * A long edge that plausibly encodes within [budgetBytes] as JPEG.
     *
     * Rough by construction — the true size depends on the picture — so it is a starting point for
     * an encoder that then checks and steps down, not a promise.
     */
    fun longEdgeFor(budgetBytes: Int): Int {
        // About a tenth of a bit per pixel at the qualities this app uses, measured across the
        // scene and text profiles rather than assumed.
        val pixels = budgetBytes * PIXELS_PER_BYTE
        val edge = Math.sqrt(pixels.toDouble() * ASPECT).toInt()
        return edge.coerceIn(MIN_LONG_EDGE, MAX_LONG_EDGE)
    }

    /** At or above this the link is not the constraint and nothing is degraded. */
    const val COMFORTABLE_KBPS = 2_000

    /** Below this, even a postage stamp will not arrive in time. */
    const val UNUSABLE_KBPS = 40

    private const val UPLOAD_SHARE = 0.45
    private const val USABLE_FRACTION = 0.80
    private const val MIN_BYTES = 12_000
    private const val PIXELS_PER_BYTE = 12
    private const val ASPECT = 1.9
    private const val MIN_LONG_EDGE = 320
    private const val MAX_LONG_EDGE = 1_600
}
