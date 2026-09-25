package com.abdullah.visionbridge.data.gemini

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings

/**
 * Which frames go to the Gemini Live socket, decided in one place.
 *
 * Two callers need this answer and they must not be able to disagree: the capture loop chooses a
 * lane with it before it does any work, and the transport refuses anything that reaches it anyway.
 * When those two were written separately, reading entered the Live lane, was refused inside it, and
 * fell back — paying a four-second setup timeout per frame while the socket was unhealthy.
 *
 * The rule itself is about what the Live model can produce. It answers in native audio, and an
 * audio session returns text only as `outputAudioTranscription`: a transcript of the synthesised
 * speech, made from the voice rather than from the image. For a description that costs nothing, and
 * the audio is what makes it feel live. For a reading it destroys the answer — digits become the
 * words that were spoken, punctuation and line structure are gone, and an Arabic voice returns
 * every Latin word on the page transliterated.
 *
 * Asking that same session for TEXT is not the way out, and the 2026-09-25 18:24 bundle settles it:
 * setup accepted at 1.0 s with a resumption handle at 1.05 s, then three frames and three client
 * turns sent and not one token produced — no content, no turnComplete, no error — until the socket
 * read timed out at 30.6 s, after which every reconnect closed 1011 "Internal error encountered."
 *
 * So reading goes to [FrameTurnTransport] over SSE, which asks for text and returns the page's own
 * characters. In that same session it was the path that worked.
 */
object LiveTransportRouting {

    /** True when this frame belongs on the Live socket rather than the frame-bound SSE lane. */
    fun carriedByLive(settings: AppSettings): Boolean =
        !settings.forceCellular && settings.mode == AnalysisMode.SCENE_DESCRIPTION
}
