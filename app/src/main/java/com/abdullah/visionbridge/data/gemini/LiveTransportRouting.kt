package com.abdullah.visionbridge.data.gemini

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings

/**
 * Which frames go to the Gemini Live socket, decided in one place.
 *
 * Two callers need this answer and they must not be able to disagree: the capture loop chooses a
 * lane with it before it does any work, and the transport refuses anything that reaches it anyway.
 * When those two were written separately, a mode the transport would not serve still entered the
 * Live lane, was refused inside it, and fell back — paying a four-second setup timeout per frame
 * while the socket was unhealthy.
 *
 * Both modes belong on Live, on one pinned model; [LiveTurnPolicy] says which and why. Describing is
 * answered in the model's own voice, reading by the `report_visible_text` call that the app speaks.
 * Falling back to [FrameTurnTransport] over SSE stays the last resort rather than the plan.
 *
 * Forcing cellular is the one setting that takes a frame off Live outright: the per-request binding
 * covers Gemini's HTTP sockets, and this is a WebSocket the app holds open across requests.
 */
object LiveTransportRouting {

    /** True when this frame belongs on the Live socket rather than the frame-bound SSE lane. */
    fun carriedByLive(settings: AppSettings): Boolean =
        !settings.forceCellular &&
            (settings.mode == AnalysisMode.SCENE_DESCRIPTION || !settings.useLocalOcr)
}
