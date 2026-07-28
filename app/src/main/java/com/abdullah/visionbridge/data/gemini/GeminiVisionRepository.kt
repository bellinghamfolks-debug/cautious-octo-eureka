package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.util.Base64
import com.abdullah.visionbridge.data.network.CellularNetworkManager
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.VisionAiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
import kotlin.coroutines.coroutineContext

class GeminiVisionRepository(
    private val networkManager: CellularNetworkManager,
) : VisionAiRepository {
    private sealed interface StreamSignal {
        data class Delta(val text: String) : StreamSignal
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
    ): AnalysisResult = networkManager.withNetwork(forceCellular) { network ->
        val encodedImage = withContext(Dispatchers.Default) {
            imageEnhancer.prepare(
                source = bitmap,
                mode = mode,
                captureProfile = captureProfile,
                sceneDescriptionStyle = sceneDescriptionStyle,
            )
        }
        val payload = GenerateContentRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = promptFor(mode, sceneDescriptionStyle, trustGateEnabled)),
                        GeminiPart(
                            inlineData = InlineData(
                                mimeType = encodedImage.mimeType,
                                data = Base64.encodeToString(encodedImage.bytes, Base64.NO_WRAP),
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

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .header("Accept", "text/event-stream")
            .post(
                json.encodeToString(GenerateContentRequest.serializer(), payload)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        consumeEventStream(
            client = client,
            request = request,
            mode = mode,
            trustGateEnabled = trustGateEnabled,
            onSpeechChunk = onSpeechChunk,
        )
    }

    private suspend fun consumeEventStream(
        client: OkHttpClient,
        request: Request,
        mode: AnalysisMode,
        trustGateEnabled: Boolean,
        onSpeechChunk: suspend (text: String, urgent: Boolean) -> Unit,
    ): AnalysisResult {
        val signals = Channel<StreamSignal>(Channel.UNLIMITED)
        val requireQualityHeader = mode == AnalysisMode.TEXT_READING && trustGateEnabled
        val accumulator = GeminiStreamAccumulator(requireQualityHeader = requireQualityHeader)
        val speechBuffer = StreamingSpeechBuffer()
        var completedNormally = false

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    signals.trySend(StreamSignal.Closed)
                    return
                }
                val parsed = runCatching {
                    json.decodeFromString(GenerateContentResponse.serializer(), data)
                }.getOrElse {
                    signals.trySend(StreamSignal.Failure(IllegalStateException("تعذر قراءة جزء Gemini المتدفق", it)))
                    eventSource.cancel()
                    return
                }

                parsed.error?.let { apiError ->
                    signals.trySend(
                        StreamSignal.Failure(
                            IllegalStateException(apiError.message ?: "أعاد Gemini خطأ أثناء البث")
                        )
                    )
                    eventSource.cancel()
                    return
                }

                val delta = parsed.candidates
                    .asSequence()
                    .mapNotNull { it.content }
                    .flatMap { it.parts.asSequence() }
                    .mapNotNull { it.text }
                    .joinToString("")
                if (delta.isNotEmpty()) signals.trySend(StreamSignal.Delta(delta))
            }

            override fun onClosed(eventSource: EventSource) {
                signals.trySend(StreamSignal.Closed)
            }

            override fun onFailure(eventSource: EventSource, throwable: Throwable?, response: Response?) {
                val error = throwable ?: IllegalStateException(
                    "انقطع بث Gemini${response?.code?.let { " برمز HTTP $it" }.orEmpty()}"
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
                        speechBuffer.append(bodyDelta, accumulator.urgent).forEach { block ->
                            coroutineContext.ensureActive()
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
            throw cancellation
        } finally {
            eventSource.cancel()
            signals.close()
        }

        if (completedNormally) {
            val unresolved = accumulator.finish()
            speechBuffer.append(unresolved, accumulator.urgent).forEach { block ->
                onSpeechChunk(block, accumulator.urgent)
            }
            speechBuffer.finish().forEach { block ->
                onSpeechChunk(block, accumulator.urgent)
            }
        }

        if (requireQualityHeader && !accumulator.ocrAccepted) {
            val feedback = when {
                accumulator.inferred -> "النص غير واضح بما يكفي، وتم رفض قراءة غير مؤكدة."
                !accumulator.legible -> "النص غير واضح. قرّب الصورة أو ثبّت النظرة."
                else -> "النص غير واضح بما يكفي للقراءة الموثوقة."
            }
            throw OcrTrustRejectedException(feedback)
        }

        val fullText = accumulator.fullText
        if (fullText.isBlank()) {
            if (requireQualityHeader) {
                throw OcrTrustRejectedException("لم يظهر نص واضح. قرّب الصورة أو غيّر زاوية النظر.")
            }
            throw IllegalStateException(
                if (mode == AnalysisMode.TEXT_READING) "لم يظهر نص في الإطار" else "لم يرجع Gemini وصفاً"
            )
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
        AnalysisMode.TEXT_READING -> 900
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
