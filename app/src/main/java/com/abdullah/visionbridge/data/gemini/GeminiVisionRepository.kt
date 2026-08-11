package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.network.UploadBudget
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.network.CellularNetworkManager
import com.abdullah.visionbridge.data.network.DiagnosticNetworkEventListener
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.VisionAiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class GeminiVisionRepository(
    private val networkManager: CellularNetworkManager,
) : VisionAiRepository {
    private sealed interface StreamSignal {
        data class Delta(val text: String, val eventSequence: Long) : StreamSignal
        data class Failure(val error: Throwable) : StreamSignal
        data object Closed : StreamSignal
    }

    /**
     * The service rejected the request as malformed rather than failing to serve it. Only the code
     * that built the request knows which optional field could have caused that, so the decision is
     * left to it instead of being guessed here.
     */
    private class UnsupportedRequestFieldException(
        val httpCode: Int,
        cause: Throwable?,
    ) : IllegalStateException("رفض Gemini صيغة الطلب، HTTP $httpCode", cause)

    /**
     * Set once if the service will not accept a reasoning level, so the rest of the process stops
     * paying for a round trip that is known to fail. Reset only by restarting the app, which is the
     * right granularity: the answer depends on the API version, not on the frame.
     */
    @Volatile
    private var thinkingLevelRejected = false

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val imageEnhancer = TextImageEnhancer()
    // Every bound sits below the 24 s analysis budget's backstop rather than far above it. A 45 s
    // call timeout can never fire before the coordinator has already given up, which is how a
    // stalled request was left holding the lane; and it never fired anyway when the process
    // stopped being scheduled, so it is a backstop to a backstop and is set accordingly.
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(26, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .eventListenerFactory(DiagnosticNetworkEventListener.Factory())
        .build()

    override suspend fun analyzeStreaming(
        bitmap: Bitmap,
        mode: AnalysisMode,
        model: String,
        apiKey: String,
        forceCellular: Boolean,
        sceneDescriptionStyle: SceneDescriptionStyle,
        captureProfile: CaptureProfile,
        trustGateEnabled: Boolean,
        describeAlongsideText: Boolean,
        onSpeechChunk: suspend (text: String, urgent: Boolean) -> Unit,
    ): AnalysisResult {
        val trace = currentCoroutineContext()[DiagnosticTrace]
        val repositoryStarted = SystemClock.elapsedRealtimeNanos()
        DiagnosticHub.record(
            "GEMINI_REPOSITORY_STARTED",
            trace.fieldsOrEmpty(
                mapOf(
                    "mode" to mode.name,
                    "model" to model,
                    "forceCellular" to forceCellular,
                    "captureProfile" to captureProfile.name,
                    "sceneDescriptionStyle" to sceneDescriptionStyle.name,
                    "trustGateEnabled" to trustGateEnabled,
                ),
            ),
        )

        return try {
            networkManager.withNetwork(forceCellular) { network ->
                DiagnosticHub.record(
                    "NETWORK_ROUTE_SELECTED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "forcedCellular" to forceCellular,
                            "boundNetworkAvailable" to (network != null),
                        ),
                    ),
                )

                // What the bound link says it can carry, not what the image would like to be.
                // On a cellular network reporting 14 kbps a full-size photograph cannot reach the
                // model before the deadline under any timeout, so it is encoded to fit or the
                // attempt is abandoned honestly instead of expiring in silence.
                val linkKbps = networkManager.boundLinkKbps
                if (UploadBudget.isUnusable(linkKbps)) {
                    DiagnosticHub.record(
                        "CLOUD_LINK_TOO_SLOW",
                        trace.fieldsOrEmpty(
                            mapOf(
                                "linkKbps" to linkKbps,
                                "unusableBelowKbps" to UploadBudget.UNUSABLE_KBPS,
                            ),
                        ),
                    )
                    throw IllegalStateException(
                        "شبكة بيانات الجوال بطيئة جدًا الآن ولا تكفي لإرسال الصورة. " +
                            "جرّب الواي فاي، أو استخدم القراءة على الجهاز.",
                    )
                }
                val uploadBudgetBytes = UploadBudget.bytesFor(linkKbps, UPLOAD_DEADLINE_MS)
                val encodeStarted = SystemClock.elapsedRealtimeNanos()
                val encodedImage = withContext(Dispatchers.Default) {
                    imageEnhancer.prepare(
                        source = bitmap,
                        mode = mode,
                        captureProfile = captureProfile,
                        sceneDescriptionStyle = sceneDescriptionStyle,
                        maxBytes = uploadBudgetBytes,
                    )
                }
                DiagnosticHub.record(
                    "IMAGE_ENHANCEMENT_AND_ENCODING_COMPLETED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "durationMs" to
                                (SystemClock.elapsedRealtimeNanos() - encodeStarted) / 1_000_000.0,
                            "sourceWidth" to bitmap.width,
                            "sourceHeight" to bitmap.height,
                            "outputWidth" to encodedImage.outputWidth,
                            "outputHeight" to encodedImage.outputHeight,
                            "encodedBytes" to encodedImage.bytes.size,
                            "mimeType" to encodedImage.mimeType,
                            "format" to encodedImage.format,
                            "quality" to encodedImage.quality,
                            "scaleMs" to encodedImage.scaleMs,
                            "enhancementMs" to encodedImage.enhancementMs,
                            "compressionMs" to encodedImage.compressionMs,
                            "contrastLowPercentile" to encodedImage.contrastLowPercentile,
                            "contrastHighPercentile" to encodedImage.contrastHighPercentile,
                            "mode" to mode.name,
                            "captureProfile" to captureProfile.name,
                            "sceneDescriptionStyle" to sceneDescriptionStyle.name,
                            "linkKbps" to linkKbps,
                            "uploadBudgetBytes" to uploadBudgetBytes,
                        ),
                    ),
                )

                val base64Started = SystemClock.elapsedRealtimeNanos()
                val base64Image = Base64.encodeToString(encodedImage.bytes, Base64.NO_WRAP)
                DiagnosticHub.record(
                    "IMAGE_BASE64_COMPLETED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "durationMs" to
                                (SystemClock.elapsedRealtimeNanos() - base64Started) / 1_000_000.0,
                            "base64Characters" to base64Image.length,
                            "base64Utf8Bytes" to base64Image.toByteArray(Charsets.UTF_8).size,
                        ),
                    ),
                )

                // Only a cloud reading can carry a description, and only when the user asked for
                // one. Computed once here so the prompt, the token ceiling and the parser cannot
                // disagree about whether a tail is coming.
                val tailRequested = describeAlongsideText && mode == AnalysisMode.TEXT_READING

                val client = if (network == null) {
                    baseClient
                } else {
                    baseClient.newBuilder()
                        .socketFactory(network.socketFactory)
                        .dns(object : Dns {
                            override fun lookup(hostname: String): List<InetAddress> =
                                network.getAllByName(hostname).toList()
                        })
                        .build()
                }

                // Built as a function of the reasoning level because the request may have to be
                // made twice: once as intended, and once without the field if this API version
                // will not accept it. Everything expensive — the encode, the base64 — is already
                // done and is shared by both attempts.
                fun buildRequest(thinkingLevel: String?): Pair<Request, Int> {
                    val payload = GenerateContentRequest(
                        contents = listOf(
                            GeminiContent(
                                parts = listOf(
                                    GeminiPart(
                                        text = promptFor(
                                            mode = mode,
                                            sceneDescriptionStyle = sceneDescriptionStyle,
                                            trustGateEnabled = trustGateEnabled,
                                            describeAlongsideText = tailRequested,
                                        )
                                    ),
                                    GeminiPart(
                                        inlineData = InlineData(
                                            mimeType = encodedImage.mimeType,
                                            data = base64Image,
                                        )
                                    ),
                                )
                            )
                        ),
                        generationConfig = GenerationConfig(
                            maxOutputTokens = TokenBudget.maxOutputTokens(mode, sceneDescriptionStyle, tailRequested),
                            temperature = if (mode == AnalysisMode.TEXT_READING) 0.0 else SCENE_TEMPERATURE,
                            mediaResolution = mediaResolution(mode, captureProfile, sceneDescriptionStyle),
                            thinkingConfig = thinkingLevel?.let { ThinkingConfig(thinkingLevel = it) },
                        ),
                    )

                    val serializationStarted = SystemClock.elapsedRealtimeNanos()
                    val payloadJson = json.encodeToString(GenerateContentRequest.serializer(), payload)
                    val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8).size
                    DiagnosticHub.record(
                        "GEMINI_PAYLOAD_SERIALIZED",
                        trace.fieldsOrEmpty(
                            mapOf(
                                "durationMs" to
                                    (SystemClock.elapsedRealtimeNanos() - serializationStarted) / 1_000_000.0,
                                "requestUtf8Bytes" to payloadBytes,
                                "maxOutputTokens" to TokenBudget.maxOutputTokens(mode, sceneDescriptionStyle, tailRequested),
                                "thinkingLevel" to thinkingLevel,
                                "temperature" to
                                    if (mode == AnalysisMode.TEXT_READING) 0.0 else SCENE_TEMPERATURE,
                                "mediaResolution" to
                                    mediaResolution(mode, captureProfile, sceneDescriptionStyle),
                            ),
                        ),
                    )

                    val builder = Request.Builder()
                        .url(
                            "https://generativelanguage.googleapis.com/v1beta/models/" +
                                "$model:streamGenerateContent?alt=sse"
                        )
                        .header("x-goog-api-key", apiKey)
                        .header("Accept", "text/event-stream")
                        .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
                    if (trace != null) builder.tag(DiagnosticTrace::class.java, trace)
                    return builder.build() to payloadBytes
                }

                suspend fun attempt(thinkingLevel: String?): AnalysisResult {
                    val (request, payloadBytes) = buildRequest(thinkingLevel)
                    val requestStarted = SystemClock.elapsedRealtimeNanos()
                    DiagnosticHub.record(
                        "HTTP_REQUEST_STARTED",
                        trace.fieldsOrEmpty(
                            mapOf(
                                "host" to request.url.host,
                                "model" to model,
                                "method" to request.method,
                                "forcedCellular" to forceCellular,
                                "requestUtf8Bytes" to payloadBytes,
                                "thinkingLevel" to thinkingLevel,
                            ),
                        ),
                    )
                    return consumeEventStream(
                        client = client,
                        request = request,
                        mode = mode,
                        trustGateEnabled = trustGateEnabled,
                        trace = trace,
                        requestStartedElapsedNanos = requestStarted,
                        maxOutputTokens = TokenBudget.maxOutputTokens(mode, sceneDescriptionStyle, tailRequested),
                        acceptSceneTail = tailRequested,
                        onSpeechChunk = onSpeechChunk,
                    )
                }

                val requestedThinkingLevel =
                    if (thinkingLevelRejected) null else TokenBudget.thinkingLevel(mode, sceneDescriptionStyle)
                if (requestedThinkingLevel == null) {
                    attempt(null)
                } else {
                    try {
                        attempt(requestedThinkingLevel)
                    } catch (rejected: UnsupportedRequestFieldException) {
                        // The service will not take the reasoning level. Remember it for the rest
                        // of the process and repeat the call without it — the token ceiling alone
                        // still leaves room for a full reasoning pass and the answer, so the
                        // reading completes either way.
                        thinkingLevelRejected = true
                        DiagnosticHub.record(
                            "THINKING_LEVEL_UNSUPPORTED",
                            trace.fieldsOrEmpty(
                                mapOf(
                                    "model" to model,
                                    "requestedLevel" to requestedThinkingLevel,
                                    "httpCode" to rejected.httpCode,
                                ),
                            ),
                        )
                        attempt(null)
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                DiagnosticHub.record(
                    "GEMINI_REPOSITORY_CANCELLED",
                    trace.fieldsOrEmpty(mapOf("reason" to error.message)),
                )
            } else {
                DiagnosticHub.failure("GEMINI_REPOSITORY", error, trace.fieldsOrEmpty())
            }
            throw error
        } finally {
            DiagnosticHub.record(
                "GEMINI_REPOSITORY_RETURNED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "durationMs" to
                            (SystemClock.elapsedRealtimeNanos() - repositoryStarted) / 1_000_000.0,
                    ),
                ),
            )
        }
    }

    private suspend fun consumeEventStream(
        client: OkHttpClient,
        request: Request,
        mode: AnalysisMode,
        trustGateEnabled: Boolean,
        trace: DiagnosticTrace?,
        requestStartedElapsedNanos: Long,
        maxOutputTokens: Int,
        acceptSceneTail: Boolean,
        onSpeechChunk: suspend (text: String, urgent: Boolean) -> Unit,
    ): AnalysisResult {
        val signals = Channel<StreamSignal>(Channel.UNLIMITED)
        val requireQualityHeader = mode == AnalysisMode.TEXT_READING && trustGateEnabled
        val accumulator = GeminiStreamAccumulator(
            requireQualityHeader = requireQualityHeader,
            acceptSceneTail = acceptSceneTail,
        )
        val speechBuffer = StreamingSpeechBuffer(
            profile = if (mode == AnalysisMode.TEXT_READING) {
                StreamingSpeechBuffer.Profile.DOCUMENT
            } else {
                StreamingSpeechBuffer.Profile.RESPONSIVE
            },
        )
        val firstEventSeen = AtomicBoolean(false)
        val eventSequence = AtomicLong(0L)
        var completedNormally = false

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                DiagnosticHub.record(
                    "HTTP_RESPONSE_HEADERS_RECEIVED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "httpCode" to response.code,
                            "protocol" to response.protocol.toString(),
                            "requestToHeadersMs" to
                                (SystemClock.elapsedRealtimeNanos() - requestStartedElapsedNanos) / 1_000_000.0,
                            "contentType" to response.header("content-type"),
                        ),
                    ),
                )
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val sequence = eventSequence.incrementAndGet()
                val first = firstEventSeen.compareAndSet(false, true)
                DiagnosticHub.record(
                    if (first) "FIRST_SSE_EVENT_RECEIVED" else "SSE_EVENT_RECEIVED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "sseSequence" to sequence,
                            "eventId" to id,
                            "eventType" to type,
                            "data" to data,
                            "dataCharacters" to data.length,
                            "requestToEventMs" to
                                (SystemClock.elapsedRealtimeNanos() - requestStartedElapsedNanos) / 1_000_000.0,
                        ),
                    ),
                )
                if (data == "[DONE]") {
                    signals.trySend(StreamSignal.Closed)
                    return
                }
                val parsed = runCatching {
                    json.decodeFromString(GenerateContentResponse.serializer(), data)
                }.getOrElse { error ->
                    DiagnosticHub.failure(
                        "SSE_JSON_PARSE",
                        error,
                        trace.fieldsOrEmpty(mapOf("sseSequence" to sequence, "data" to data)),
                    )
                    signals.trySend(
                        StreamSignal.Failure(
                            IllegalStateException("تعذر استقبال جزء من بث Gemini", error)
                        )
                    )
                    eventSource.cancel()
                    return
                }

                parsed.error?.let { apiError ->
                    val error = IllegalStateException(apiError.message ?: "أعاد Gemini خطأ أثناء بث الاستجابة")
                    DiagnosticHub.failure(
                        "GEMINI_STREAM_API_ERROR",
                        error,
                        trace.fieldsOrEmpty(mapOf("sseSequence" to sequence)),
                    )
                    signals.trySend(StreamSignal.Failure(error))
                    eventSource.cancel()
                    return
                }

                val parts = parsed.candidates
                    .asSequence()
                    .mapNotNull { it.content }
                    .flatMap { it.parts.asSequence() }
                    .toList()
                // Reasoning is not an answer. It is written for no reader, it is not in the user's
                // language, and when a response is cut short its tail arrives looking exactly like
                // a description — which is how "Word count: under 7" was once spoken aloud.
                val reasoningParts = parts.count { it.thought == true }
                val delta = parts.asSequence()
                    .filter { it.thought != true }
                    .mapNotNull { it.text }
                    .joinToString("")
                DiagnosticHub.record(
                    "SSE_DELTA_PARSED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "sseSequence" to sequence,
                            "delta" to delta,
                            "deltaCharacters" to delta.length,
                            "reasoningPartsDropped" to reasoningParts,
                        ),
                    ),
                )

                // The finish reason and the token split are the only evidence that separates a
                // short answer from a truncated one. Recording them per response is what turns the
                // next occurrence of this class of defect into one line of a diagnostic bundle.
                parsed.candidates.firstOrNull()?.finishReason?.let { finishReason ->
                    val usage = parsed.usageMetadata
                    DiagnosticHub.record(
                        "MODEL_FINISH_REASON",
                        trace.fieldsOrEmpty(
                            mapOf(
                                "finishReason" to finishReason,
                                "truncated" to TokenBudget.wasTruncated(finishReason),
                                "maxOutputTokens" to maxOutputTokens,
                                "promptTokens" to usage?.promptTokenCount,
                                "reasoningTokens" to usage?.thoughtsTokenCount,
                                "answerTokens" to usage?.candidatesTokenCount,
                                "totalTokens" to usage?.totalTokenCount,
                            ),
                        ),
                    )
                }
                if (delta.isNotEmpty()) signals.trySend(StreamSignal.Delta(delta, sequence))
            }

            override fun onClosed(eventSource: EventSource) {
                DiagnosticHub.record(
                    "SSE_CONNECTION_CLOSED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "eventCount" to eventSequence.get(),
                            "requestToCloseMs" to
                                (SystemClock.elapsedRealtimeNanos() - requestStartedElapsedNanos) / 1_000_000.0,
                        ),
                    ),
                )
                signals.trySend(StreamSignal.Closed)
            }

            override fun onFailure(eventSource: EventSource, throwable: Throwable?, response: Response?) {
                val error = if (response?.code == HTTP_BAD_REQUEST) {
                    // The service rejected the request itself rather than failing to serve it. The
                    // caller decides what that means: it knows which optional fields it sent.
                    UnsupportedRequestFieldException(response.code, throwable)
                } else {
                    throwable ?: IllegalStateException(
                        "انقطع بث Gemini${response?.code?.let { "، HTTP $it" }.orEmpty()}"
                    )
                }
                DiagnosticHub.failure(
                    "SSE_CONNECTION_FAILURE",
                    error,
                    trace.fieldsOrEmpty(
                        mapOf(
                            "httpCode" to response?.code,
                            "eventCount" to eventSequence.get(),
                            "requestToFailureMs" to
                                (SystemClock.elapsedRealtimeNanos() - requestStartedElapsedNanos) / 1_000_000.0,
                        ),
                    ),
                )
                signals.trySend(StreamSignal.Failure(error))
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        try {
            streamLoop@ for (signal in signals) {
                coroutineContext.ensureActive()
                when (signal) {
                    is StreamSignal.Delta -> {
                        val bodyDelta = accumulator.append(signal.text)
                        DiagnosticHub.record(
                            "STREAM_ACCUMULATOR_UPDATED",
                            trace.fieldsOrEmpty(
                                mapOf(
                                    "sseSequence" to signal.eventSequence,
                                    "inputDelta" to signal.text,
                                    "bodyDelta" to bodyDelta,
                                    "fullText" to accumulator.fullText,
                                    "language" to accumulator.language,
                                    "urgent" to accumulator.urgent,
                                    "legible" to accumulator.legible,
                                    "confidence" to accumulator.confidence,
                                    "inferred" to accumulator.inferred,
                                    "ocrAccepted" to accumulator.ocrAccepted,
                                ),
                            ),
                        )
                        speechBuffer.append(bodyDelta, accumulator.urgent).forEach { block ->
                            coroutineContext.ensureActive()
                            DiagnosticHub.record(
                                "SPEECH_BUFFER_BLOCK_READY",
                                trace.fieldsOrEmpty(
                                    mapOf(
                                        "text" to block,
                                        "urgent" to accumulator.urgent,
                                        "sseSequence" to signal.eventSequence,
                                    ),
                                ),
                            )
                            onSpeechChunk(block, accumulator.urgent)
                        }
                    }
                    is StreamSignal.Failure -> throw signal.error
                    StreamSignal.Closed -> {
                        completedNormally = true
                        break@streamLoop
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            DiagnosticHub.record(
                "SSE_CONSUMER_CANCELLED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to cancellation.message,
                        "eventCount" to eventSequence.get(),
                    ),
                ),
            )
            throw cancellation
        } finally {
            eventSource.cancel()
            signals.close()
        }

        if (completedNormally) {
            val unresolved = accumulator.finish()
            DiagnosticHub.record(
                "STREAM_FINISHING",
                trace.fieldsOrEmpty(
                    mapOf(
                        "unresolved" to unresolved,
                        "fullText" to accumulator.fullText,
                        "eventCount" to eventSequence.get(),
                    ),
                ),
            )
            speechBuffer.append(unresolved, accumulator.urgent).forEach { block ->
                DiagnosticHub.record(
                    "SPEECH_BUFFER_BLOCK_READY",
                    trace.fieldsOrEmpty(mapOf("text" to block, "urgent" to accumulator.urgent)),
                )
                onSpeechChunk(block, accumulator.urgent)
            }
            speechBuffer.finish().forEach { block ->
                DiagnosticHub.record(
                    "SPEECH_BUFFER_TAIL_READY",
                    trace.fieldsOrEmpty(mapOf("text" to block, "urgent" to accumulator.urgent)),
                )
                onSpeechChunk(block, accumulator.urgent)
            }
        }

        DiagnosticHub.record(
            "MODEL_QUALITY_DECISION",
            trace.fieldsOrEmpty(
                mapOf(
                    "requireQualityHeader" to requireQualityHeader,
                    "language" to accumulator.language,
                    "urgent" to accumulator.urgent,
                    "legible" to accumulator.legible,
                    "confidence" to accumulator.confidence,
                    "inferred" to accumulator.inferred,
                    "ocrAccepted" to accumulator.ocrAccepted,
                    "fullText" to accumulator.fullText,
                ),
            ),
        )

        if (requireQualityHeader && !accumulator.ocrAccepted) {
            val feedback = when {
                accumulator.inferred -> "رفض VisionBridge النتيجة لأنها تتضمن تخمينًا غير موثوق."
                !accumulator.legible -> "النص غير واضح. قرّب المحتوى أو ثبّت النظرة ثم حاول مرة أخرى."
                else -> "تعذر الحصول على قراءة موثوقة من هذه اللقطة."
            }
            throw OcrTrustRejectedException(feedback)
        }

        val fullText = accumulator.fullText
        if (fullText.isBlank()) {
            if (requireQualityHeader) {
                throw OcrTrustRejectedException("لم يظهر نص واضح. قرّب المحتوى أو غيّر زاوية النظر.")
            }
            // Pointing text reading at a screen with no text is ordinary use, not a fault. Raising
            // it as an exception produced a spoken error and a failure streak that pushed the whole
            // cloud lane into recovery. An empty result lets the coordinator stay silent instead.
            if (mode == AnalysisMode.TEXT_READING) {
                DiagnosticHub.record(
                    "MODEL_REPORTED_NO_TEXT",
                    trace.fieldsOrEmpty(mapOf("eventCount" to eventSequence.get())),
                )
                return AnalysisResult(
                    text = "",
                    source = AnalysisSource.GEMINI,
                    language = accumulator.language,
                    urgent = false,
                )
            }
            throw IllegalStateException("لم يُرجع Gemini وصفًا للمشهد")
        }
        return AnalysisResult(
            text = fullText,
            source = AnalysisSource.GEMINI,
            language = accumulator.language,
            urgent = accumulator.urgent,
            sceneTail = accumulator.sceneTail,
        )
    }

    private fun mediaResolution(
        mode: AnalysisMode,
        captureProfile: CaptureProfile,
        sceneDescriptionStyle: SceneDescriptionStyle,
    ): String = when (mode) {
        AnalysisMode.TEXT_READING -> if (captureProfile == CaptureProfile.FAST_TEXT) {
            "MEDIA_RESOLUTION_MEDIUM"
        } else {
            "MEDIA_RESOLUTION_HIGH"
        }
        AnalysisMode.SCENE_DESCRIPTION -> if (sceneDescriptionStyle == SceneDescriptionStyle.BRIEF) {
            "MEDIA_RESOLUTION_LOW"
        } else {
            "MEDIA_RESOLUTION_MEDIUM"
        }
    }

    private fun promptFor(
        mode: AnalysisMode,
        sceneDescriptionStyle: SceneDescriptionStyle,
        trustGateEnabled: Boolean,
        describeAlongsideText: Boolean = false,
    ): String = when (mode) {
        AnalysisMode.TEXT_READING -> {
            val base = if (trustGateEnabled) OCR_TRUSTED_PROMPT else OCR_FAST_PROMPT
            // Appended rather than written as a third prompt, so the transcription instructions the
            // reading depends on stay byte-identical whether or not the tail was asked for.
            if (describeAlongsideText) base + "\n" + SCENE_TAIL_INSTRUCTION else base
        }
        AnalysisMode.SCENE_DESCRIPTION -> when (sceneDescriptionStyle) {
            SceneDescriptionStyle.COMPREHENSIVE -> SCENE_COMPREHENSIVE_PROMPT
            SceneDescriptionStyle.BRIEF -> SCENE_BRIEF_PROMPT
        }
    }

    private fun DiagnosticTrace?.fieldsOrEmpty(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        this?.fields(extra) ?: extra

    private companion object {
        /** The window a request has to upload, think and answer within. */
        private const val UPLOAD_DEADLINE_MS = 24_000L

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val SCENE_TEMPERATURE = 0.1

        /** A rejected request, as opposed to a request the service failed to serve. */
        const val HTTP_BAD_REQUEST = 400

        val OCR_TRUSTED_PROMPT = """
            المهمة نسخ بصري حرفي فقط، وليست فهم النص أو إكماله. اقرأ النص العربي والإنجليزي الظاهر في الصورة المحسنة.
            أخرج نصاً عادياً فقط، دون JSON أو Markdown، بهذا البروتوكول الإلزامي ذي السطرين:
            السطر الأول بالضبط: META|language=ar أو en أو mixed أو none|urgent=false
            السطر الثاني بالضبط: QUALITY|legible=true أو false|confidence=عدد من 0 إلى 100|inferred=true أو false
            يبدأ النص المنسوخ من السطر الثالث.

            قواعد عدم التأليف، وهي أعلى أولوية من إكمال الإجابة:
            1) انسخ الحروف التي تستطيع رؤيتها فعلياً فقط. لا تستخدم معنى الجملة أو المعرفة العامة أو اسم علامة تجارية لتوقع حرف مفقود.
            2) ممنوع التصحيح الإملائي، وإكمال الكلمات، وتوسيع الاختصارات، وإعادة الصياغة، والترجمة، والتلخيص.
            3) إذا لم تكن حروف كلمة كاملة قابلة للتمييز، اكتب [غير واضح] مكانها ولا تحاول تخمينها.
            4) inferred=false فقط عندما لم تضف أو تصحح أو تكمل أي كلمة من السياق. إن خالفت ذلك اجعل inferred=true.
            5) confidence هو ثقتك البصرية في أضعف جزء من النص الذي نسخته، وليس ثقتك في معنى الجملة.
            6) اجعل legible=false إذا كان معظم النص صغيراً أو ضبابياً إلى درجة تتطلب التخمين.
            7) حافظ على ترتيب القراءة البصري نفسه، وأبق العربية والإنجليزية في مواضعها المتعاقبة.
            8) حافظ على ترتيب الأسطر والعناوين والأرقام وعلامات نهاية الجمل.
            9) إن لم يوجد نص، استخدم language=none وlegible=false وconfidence=0 وinferred=false، واترك المتن فارغاً.
        """.trimIndent()

        val OCR_FAST_PROMPT = """
            انسخ فوراً النص العربي والإنجليزي الظاهر في الصورة، دون شرح أو ترجمة أو تلخيص.
            السطر الأول بالضبط: META|language=ar أو en أو mixed أو none|urgent=false
            يبدأ النص من السطر الثاني.
            حافظ على ترتيب القراءة البصري ومواقع العربية والإنجليزية كما تظهر.
            لا تصحح الإملاء ولا تكمل كلمة من السياق. استخدم [غير واضح] للحروف التي لا تستطيع رؤيتها.
            استخدم علامات نهاية الجمل الموجودة، ولا تضف مقدمة.
        """.trimIndent()

        /**
         * Appended to a reading prompt when the user has asked for a description alongside it.
         *
         * Deliberately one sentence. The tail exists to place the text — "the bottle you are
         * holding", "a sign on the wall to your right" — not to describe the room a second time,
         * and a long tail would undo the reason reading is a separate mode at all.
         */
        val SCENE_TAIL_INSTRUCTION = """
            بعد انتهاء النص تماماً، أضف سطراً يبدأ بـ SCENE| ثم جملة عربية واحدة فقط تصف
            ما يحمل هذا النص وأين هو بالنسبة للمستخدم. لا تعد قراءة النص داخلها ولا تفسّره.
            إن لم يكن في الصورة نص، اكتب SCENE| مباشرة بعد سطر META واجعل الجملة وصفاً لما أمامك.
            لا تكتب SCENE| أكثر من مرة، ولا تضف شيئاً بعد الجملة.
        """.trimIndent()

        val SCENE_COMPREHENSIVE_PROMPT = """
            أنت مساعد بصري عملي لمستخدم كفيف. حلل أحدث إطار من بث النظارة.
            السطر الأول: META|language=ar|urgent=true أو false
            من السطر الثاني: الوصف العربي المنطوق فقط.
            ابدأ فوراً بأهم خطر أو تغير في جملة مكتملة قصيرة، ثم أكمل التفاصيل المفيدة.
            صف ما يظهر الآن فقط، ورتب الباقي: الاتجاه، المسار، الأشخاص والأشياء، ثم النص المهم.
            استخدم أمامك ويمينك ويسارك وقريب وبعيد تقريباً. لا تفترض شيئاً خارج الصورة.
            لا تدّع مسافة دقيقة أو سلامة طريق. اجعل urgent=true فقط لخطر واضح.
            لا تزد على 75 كلمة، ولا تضف مقدمة أو مجاملة.
        """.trimIndent()

        val SCENE_BRIEF_PROMPT = """
            أنت مساعد بصري فوري لمستخدم كفيف. حلل أحدث إطار فقط.
            السطر الأول: META|language=ar|urgent=true أو false
            من السطر الثاني: الوصف العربي المنطوق فقط.
            ابدأ مباشرة بجملة مكتملة عن الخطر أو أهم تغير، ثم اذكر الاتجاه أو أهم عائق أو شخص.
            استخدم كلمات اتجاهية مباشرة. لا تعتمد على إطار سابق ولا تضف مقدمة.
            الحد الأقصى 22 كلمة، ويفضل جملة واحدة. اجعل urgent=true فقط لخطر واضح.
        """.trimIndent()
    }
}
