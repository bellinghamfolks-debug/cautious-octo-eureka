package com.abdullah.visionbridge.data.gemini

import org.json.JSONArray
import org.json.JSONObject

/**
 * The function the model calls to hand back a page's exact characters.
 *
 * A Live session on this account can only answer in audio, and the only text an audio session
 * returns is `outputAudioTranscription` — made from the synthesised speech, so digits arrive as the
 * words that were spoken, punctuation and line structure are gone, and an Arabic voice returns
 * every Latin word transliterated. `responseModalities: ["TEXT"]` is not the way out either: it was
 * removed from the general-purpose Live families, and this device saw every candidate refuse it.
 *
 * A tool call is the way out. `tools` is a sibling of `generationConfig` inside `setup`, with no
 * modality gate, and Google's own raw-WebSocket cookbook runs function declarations at
 * `modality="AUDIO"` with a real `toolCall` in its saved output. The arguments are JSON generated as
 * ordinary output tokens — not speech that was then transcribed — so a digit stays a digit.
 *
 * Three things about this are honestly unknown and the diagnostics are built to measure them:
 * there is no documented size limit on a call's arguments, the arguments arrive in one atomic
 * message rather than streaming (`partialArgs` and `willContinue` are documented as unsupported on
 * this API), and there is no precedent anywhere — first-party or community — for carrying long
 * verbatim OCR text this way. The protocol allows it; whether the model is willing to write a whole
 * label into an argument is a question only the device answers.
 */
object LiveReadingTool {

    /** Short, because a declaration's name is capped at 128 characters. */
    const val NAME = "report_visible_text"

    /** The array of visual lines; one entry per line, top to bottom. */
    const val LINES = "lines"

    /** Set by the model when part of the text could not be read. */
    const val UNREADABLE = "unreadable"

    /**
     * One short Arabic sentence about the surroundings, asked for only when reading-with-description
     * is on. It travels in the same call as the page because the model's own voice is not played
     * while reading, so this is the only way a closing scene sentence can reach the user.
     */
    const val SCENE = "scene"

    /**
     * Never [BLOCKING]: a blocking call halts generation until the client answers, which for a
     * blind user is speech stopping mid-sentence. `NON_BLOCKING` is the default on gemini-3.8-live
     * and mandatory on the extended-thinking variant, which rejects blocking declarations outright.
     */
    private const val NON_BLOCKING = "NON_BLOCKING"

    /**
     * How the acknowledgement re-enters the conversation.
     *
     * `SILENT` adds the result to context without prompting generation. The default, `WHEN_IDLE`,
     * would make the model speak about the acknowledgement — narrating its own bookkeeping to
     * someone who asked to hear a label.
     */
    const val SILENT = "SILENT"

    /** The `tools` array for a reading session. */
    fun declaration(): JSONArray = JSONArray().put(
        JSONObject().put(
            "functionDeclarations",
            JSONArray().put(
                JSONObject()
                    .put("name", NAME)
                    .put(
                        "description",
                        "Report the text visible in the current image, character for character, " +
                            "in its original script. Call this whenever any readable text is " +
                            "present.",
                    )
                    .put("behavior", NON_BLOCKING)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "OBJECT")
                            .put(
                                "properties",
                                JSONObject()
                                    .put(
                                        LINES,
                                        JSONObject()
                                            .put("type", "ARRAY")
                                            .put(
                                                "items",
                                                JSONObject().put("type", "STRING"),
                                            )
                                            .put(
                                                "description",
                                                "One entry per visual line, top to bottom, " +
                                                    "exactly as printed.",
                                            ),
                                    )
                                    .put(
                                        UNREADABLE,
                                        JSONObject()
                                            .put("type", "BOOLEAN")
                                            .put(
                                                "description",
                                                "True when any part of the text could not be read.",
                                            ),
                                    )
                                    .put(
                                        SCENE,
                                        JSONObject()
                                            .put("type", "STRING")
                                            .put(
                                                "description",
                                                "Only when asked: one very short Arabic sentence " +
                                                    "about the surroundings. Otherwise omit it.",
                                            ),
                                    ),
                            )
                            .put("required", JSONArray().put(LINES)),
                    ),
            ),
        ),
    )

    /**
     * The reply. Its content is a formality — the tool's purpose is what the model sent up — but it
     * must be sent, and sent from the same loop that drains audio: a call left unanswered has no
     * documented server-side timeout, and under a blocking declaration it would stall the turn.
     */
    fun acknowledgement(id: String?, name: String): String = JSONObject()
        .put(
            "toolResponse",
            JSONObject().put(
                "functionResponses",
                JSONArray().put(
                    JSONObject()
                        .apply { if (!id.isNullOrBlank()) put("id", id) }
                        .put("name", name)
                        .put("response", JSONObject().put("output", JSONObject().put("ok", true)))
                        .put("scheduling", SILENT),
                ),
            ),
        )
        .toString()

    /**
     * Joins the reported lines into the text to publish and speak.
     *
     * Lines are kept as lines: the layout of a page is part of what was read, and the speech engine
     * uses a newline as a clause boundary, so preserving them also paces the reading.
     */
    fun textFrom(args: JSONObject?): String {
        val lines = args?.optJSONArray(LINES) ?: return ""
        return (0 until lines.length())
            .map { lines.optString(it).trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** The optional closing scene sentence, trimmed, or empty. */
    fun sceneFrom(args: JSONObject?): String = args?.optString(SCENE).orEmpty().trim()
}
