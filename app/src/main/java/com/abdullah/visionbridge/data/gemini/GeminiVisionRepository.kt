package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.util.Base64
import com.abdullah.visionbridge.data.network.CellularNetworkManager
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun analyzeStreaming(
        bitmap: Bitmap,
        mode: AnalysisMode,
        model: String,
        apiKey: String,
        forceCellular: Boolean,
        sceneDescriptionStyle: SceneDescriptionStyle,
        onSpeechChunk: suspend (text: String, urgent: Boolean) -> Unit,
    ): AnalysisResult = networkManager.withNetwork(forceCellular) { network ->
        val encodedImage = withContext(Dispatchers.Default) { imageEnhancer.prepare(bitmap, mode) }
        val payload = GenerateContentRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = promptFor(mode, sceneDescriptionStyle)),
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
                mediaResolution = if (mode == AnalysisMode.TEXT_READING) {
                    "MEDIA_RESOLUTION_HIGH"
                } else {
                    "MEDIA_RESOLUTION_MEDIUM"
                },
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
            onSpeechChunk = onSpeechChunk,
        )
    }

    private suspend fun consumeEventStream(
        client: OkHttpClient,
        request: Request,
        mode: AnalysisMode,
        onSpeechChunk: suspend (text: String, urgent: Boolean) -> Unit,
    ): AnalysisResult {
        val signals = Channel<StreamSignal>(Channel.UNLIMITED)
        val accumulator = GeminiStreamAccumulator(
            requireQualityHeader = mode == AnalysisMode.TEXT_READING,
        )
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

        if (mode == AnalysisMode.TEXT_READING && !accumulator.ocrAccepted) {
            throw IllegalStateException(
                "النص غير واضح بصرياً بما يكفي للقراءة الموثوقة، لم يتم نطق تخمينات"
            )
        }
        val fullText = accumulator.fullText
        if (fullText.isBlank()) {
            throw IllegalStateException(
                if (mode == AnalysisMode.TEXT_READING) "لم يظهر نص واضح في الإطار" else "لم يرجع Gemini وصفاً"
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
            SceneDescriptionStyle.COMPREHENSIVE -> 500
            SceneDescriptionStyle.BRIEF -> 180
        }
    }

    private fun promptFor(
        mode: AnalysisMode,
        sceneDescriptionStyle: SceneDescriptionStyle,
    ): String = when (mode) {
        AnalysisMode.TEXT_READING -> OCR_PROMPT
        AnalysisMode.SCENE_DESCRIPTION -> when (sceneDescriptionStyle) {
            SceneDescriptionStyle.COMPREHENSIVE -> SCENE_COMPREHENSIVE_PROMPT
            SceneDescriptionStyle.BRIEF -> SCENE_BRIEF_PROMPT
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val SCENE_TEMPERATURE = 0.15

        val OCR_PROMPT = """
            المهمة نسخ بصري حرفي فقط، وليست فهم النص أو إكماله. اقرأ النص العربي والإنجليزي الظاهر في الصورة المحسنة.
            أخرج نصاً عادياً فقط، دون JSON أو Markdown، بهذا البروتوكول الإلزامي ذي السطرين:
            السطر الأول بالضبط: META|language=ar أو en أو mixed أو none|urgent=false
            السطر الثاني بالضبط: QUALITY|legible=true أو false|confidence=عدد من 0 إلى 100|inferred=true أو false
            يبدأ النص المنسوخ من السطر الثالث.

            قواعد عدم التأليف، وهي أعلى أولوية من إكمال الإجابة:
            1) انسخ الحروف التي تستطيع رؤيتها فعلياً فقط. لا تستخدم معنى الجملة أو المعرفة العامة أو اسم علامة تجارية لتوقع حرف مفقود.
            2) ممنوع التصحيح الإملائي، وإكمال الكلمات، وتوسيع الاختصارات، وإعادة الصياغة، والترجمة، والتلخيص.
            3) إذا لم تكن حروف كلمة كاملة قابلة للتمييز، اكتب [غير واضح] مكانها ولا تحاول تخمينها.
            4) inferred=false فقط عندما لم تضف أو تصحح أو تكمل أي كلمة من السياق. إن خالفت ذلك اجعل inferred=true، وسيقوم التطبيق برفض النتيجة.
            5) confidence هو ثقتك البصرية في أضعف جزء من النص الذي نسخته، وليس ثقتك في معنى الجملة. لا ترفعه بسبب شيوع العبارة.
            6) اجعل legible=false إذا كان معظم النص صغيراً أو ضبابياً إلى درجة تتطلب التخمين، واترك ما بعد السطر الثاني فارغاً.
            7) حافظ على ترتيب القراءة البصري نفسه. لا تجمع الإنجليزية أولاً ولا العربية أولاً، وأبق الكلمات المختلطة في مواضعها المتعاقبة.
            8) حافظ على ترتيب الأسطر والعناوين والأرقام، واستخدم علامات الترقيم الموجودة أو الطبيعية عند نهاية جملة مكتملة.
            9) إن لم يوجد نص، استخدم language=none وlegible=false وconfidence=0 وinferred=false، واترك المتن فارغاً.
        """.trimIndent()

        val SCENE_COMPREHENSIVE_PROMPT = """
            أنت مساعد بصري عملي لمستخدم كفيف. حلل الصورة باعتبارها أحدث إطار من بث النظارة.
            أخرج نصاً عادياً فقط، دون JSON أو Markdown، بهذا البروتوكول الإلزامي:
            السطر الأول: META|language=ar|urgent=true أو false
            من السطر الثاني: الوصف العربي المنطوق فقط.
            صف ما يظهر في هذا الإطار الآن، ولا تتحدث عن إطار سابق ولا تفترض أشياء خارج الصورة.
            رتب الوصف هكذا: خطر فوري، الاتجاه والموقع النسبي، المسار الأكثر وضوحاً، الأشخاص والأشياء، ثم أي نص مهم.
            استخدم اتجاهات واضحة مثل أمامك، يمينك، يسارك، أعلى، أسفل، قريب، بعيد تقريباً.
            اذكر التفاصيل المفيدة للاستقلالية دون حشو، واكتب جملاً مكتملة للنطق المتدفق.
            لا تدّع دقة مسافات أو سلامة طريق لا يمكن إثباتها من صورة واحدة.
            اجعل urgent=true فقط عند وجود عائق أو خطر واضح يحتاج انتباهاً فورياً.
            لا تزد على 90 كلمة، ولا تضف مقدمة أو مجاملة.
        """.trimIndent()

        val SCENE_BRIEF_PROMPT = """
            أنت مساعد بصري سريع لمستخدم كفيف. حلل أحدث إطار من بث النظارة.
            أخرج نصاً عادياً فقط، دون JSON أو Markdown، بهذا البروتوكول الإلزامي:
            السطر الأول: META|language=ar|urgent=true أو false
            من السطر الثاني: الوصف العربي المنطوق فقط.
            اذكر فقط: الخطر الفوري إن وجد، اتجاه المسار أو أهم عائق، ثم أهم شخص أو شيء أو نص.
            صف ما يظهر الآن فقط ولا تعتمد على إطار سابق. استخدم كلمات اتجاهية مباشرة.
            اكتب جملة أو جملتين مكتملتين، بلا تكرار أو تفاصيل زخرفية أو مقدمة.
            اجعل urgent=true فقط عند خطر واضح. الحد الأقصى 28 كلمة.
        """.trimIndent()
    }
}
