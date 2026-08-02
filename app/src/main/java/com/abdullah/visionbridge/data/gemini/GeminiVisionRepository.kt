package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
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

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val imageEnhancer = TextImageEnhancer()
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
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

                val encodeStarted = SystemClock.elapsedRealtimeNanos()
                val encodedImage = withContext(Dispatchers.Default) {
                    imageEnhancer.prepare(
                        source = bitmap,
                        mode = mode,
                        captureProfile = captureProfile,
                        sceneDescriptionStyle = sceneDescriptionStyle,
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

                val payload = GenerateContentRequest(
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(
                                GeminiPart(text = promptFor(mode, sceneDescriptionStyle, trustGateEnabled)),
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
                        maxOutputTokens = maxOutputTokens(mode, sceneDescriptionStyle),
                        temperature = if (mode == AnalysisMode.TEXT_READING) 0.0 else SCENE_TEMPERATURE,
                        mediaResolution = mediaResolution(mode, captureProfile, sceneDescriptionStyle),
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
                            "maxOutputTokens" to maxOutputTokens(mode, sceneDescriptionStyle),
                            "temperature" to if (mode == AnalysisMode.TEXT_READING) 0.0 else SCENE_TEMPERATURE,
                            "mediaResolution" to mediaResolution(mode, captureProfile, sceneDescriptionStyle),
                        ),
                    ),
                )

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

                val requestBuilder = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
                    .header("x-goog-api-key", apiKey)
                    .header("Accept", "text/event-stream")
                    .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
                if (trace != null) requestBuilder.tag(DiagnosticTrace::class.java, trace)
                val request = requestBuilder.build()

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
                        ),
                    ),
                )
                consumeEventStream(
                    client = client,
                    request = request,
                    mode = mode,
                    trustGateEnabled = trustGateEnabled,
                    trace = trace,
                    requestStartedElapsedNanos = requestStarted,
                    onSpeechChunk = onSpeechChunk,
                )
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
        onSpeechChunk: suspend (text: String, urgent: Boolean) -> Unit,
    ): AnalysisResult {
        val signals = Channel<StreamSignal>(Channel.UNLIMITED)
        val requireQualityHeader = mode == AnalysisMode.TEXT_READING && trustGateEnabled
        val accumulator = GeminiStreamAccumulator(requireQualityHeader = requireQualityHeader)
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

                val delta = parsed.candidates
                    .asSequence()
                    .mapNotNull { it.content }
                    .flatMap { it.parts.asSequence() }
                    .mapNotNull { it.text }
                    .joinToString("")
                DiagnosticHub.record(
                    "SSE_DELTA_PARSED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "sseSequence" to sequence,
                            "delta" to delta,
                            "deltaCharacters" to delta.length,
                        ),
                    ),
                )
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
                val error = throwable ?: IllegalStateException(
                    "انقطع بث Gemini${response?.code?.let { "، HTTP $it" }.orEmpty()}"
                )
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
        )
    }

    private fun maxOutputTokens(
        mode: AnalysisMode,
        sceneDescriptionStyle: SceneDescriptionStyle,
    ): Int = when (mode) {
        // A dense screen of Arabic and Latin text runs well past the old 900-token ceiling, and the
        // response was being cut off mid-page. The user then heard a partial read and the retry loop
        // started over from the top.
        AnalysisMode.TEXT_READING -> 3_000
        AnalysisMode.SCENE_DESCRIPTION -> when (sceneDescriptionStyle) {
            SceneDescriptionStyle.COMPREHENSIVE -> 360
            SceneDescriptionStyle.BRIEF -> 96
        }
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
    ): String = when (mode) {
        AnalysisMode.TEXT_READING -> if (trustGateEnabled) OCR_TRUSTED_PROMPT else OCR_FAST_PROMPT
        AnalysisMode.SCENE_DESCRIPTION -> when (sceneDescriptionStyle) {
            SceneDescriptionStyle.COMPREHENSIVE -> SCENE_COMPREHENSIVE_PROMPT
            SceneDescriptionStyle.BRIEF -> SCENE_BRIEF_PROMPT
        }
    }

    private fun DiagnosticTrace?.fieldsOrEmpty(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        this?.fields(extra) ?: extra

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val SCENE_TEMPERATURE = 0.1

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
