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
import java.io.ByteArrayOutputStream
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
        val imageBytes = withContext(kotlinx.coroutines.Dispatchers.Default) { bitmapToJpeg(bitmap) }
        val payload = GenerateContentRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(
                            inlineData = InlineData(
                                mimeType = "image/jpeg",
                                data = Base64.encodeToString(imageBytes, Base64.NO_WRAP),
                            )
                        ),
                        GeminiPart(text = promptFor(mode, sceneDescriptionStyle)),
                    )
                )
            ),
            generationConfig = GenerationConfig(maxOutputTokens = maxOutputTokens(mode, sceneDescriptionStyle)),
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
            onSpeechChunk = onSpeechChunk,
        )
    }

    private suspend fun consumeEventStream(
        client: OkHttpClient,
        request: Request,
        onSpeechChunk: suspend (text: String, urgent: Boolean) -> Unit,
    ): AnalysisResult {
        val signals = Channel<StreamSignal>(Channel.UNLIMITED)
        val accumulator = GeminiStreamAccumulator()
        val speechBuffer = StreamingSpeechBuffer()
        var completedNormally = false

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
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

        val fullText = accumulator.fullText
        if (fullText.isBlank()) throw IllegalStateException("لم يُرجع Gemini نصاً قابلاً للقراءة")
        return AnalysisResult(
            text = fullText,
            source = AnalysisSource.GEMINI,
            language = accumulator.language,
            urgent = accumulator.urgent,
        )
    }

    private fun bitmapToJpeg(source: Bitmap): ByteArray {
        val scaled = scaleDown(source, MAX_IMAGE_EDGE)
        return ByteArrayOutputStream().use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "تعذر ضغط إطار الشاشة"
            }
            if (scaled !== source) scaled.recycle()
            output.toByteArray()
        }
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxEdge) return source
        val ratio = maxEdge.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
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
        const val MAX_IMAGE_EDGE = 1280
        const val JPEG_QUALITY = 72

        val OCR_PROMPT = """
            أنت محرك OCR دقيق لمستخدم كفيف. افحص الصورة كاملة واستخرج النص العربي والإنجليزي الظاهر.
            أخرج نصاً عادياً فقط، دون JSON ودون Markdown، بهذا البروتوكول الإلزامي:
            السطر الأول: META|language=ar أو en أو mixed أو none|urgent=false
            من السطر الثاني: النص المنطوق فقط.
            القواعد:
            1) انسخ النص كما يظهر دون شرح أو تلخيص.
            2) حافظ بدقة على ترتيب القراءة البصري نفسه من بداية النص إلى نهايته، ولا تجمع الإنجليزية أولاً أو العربية أولاً.
            3) عند اختلاط العربية والإنجليزية في السطر نفسه، أبق الكلمات في مواقعها المتعاقبة كما تظهر في الصورة.
            4) حافظ على ترتيب الأسطر والعناوين والأرقام المهمة.
            5) استخدم علامات ترقيم طبيعية عند انتهاء الجمل لتسمح بالنطق المتدفق على وحدات مكتملة.
            6) لا تخترع كلمات غير واضحة. ضع [غير واضح] فقط عند الضرورة.
            7) إن لم يوجد نص، استخدم language=none واترك ما بعد السطر الأول فارغاً.
        """.trimIndent()

        val SCENE_COMPREHENSIVE_PROMPT = """
            أنت مساعد بصري عملي لمستخدم كفيف. حلل الصورة باعتبارها بثاً من نظارة يظهر على شاشة هاتف.
            أخرج نصاً عادياً فقط، دون JSON ودون Markdown، بهذا البروتوكول الإلزامي:
            السطر الأول: META|language=ar|urgent=true أو false
            من السطر الثاني: الوصف العربي المنطوق فقط.
            رتب الوصف هكذا: خطر فوري، الاتجاه والموقع النسبي، المسار الأكثر وضوحاً، الأشخاص والأشياء، ثم أي نص مهم.
            استخدم اتجاهات واضحة مثل أمامك، يمينك، يسارك، أعلى، أسفل، قريب، بعيد تقريباً.
            اذكر التفاصيل المفيدة للاستقلالية دون حشو، وحافظ على جودة الوصف الشامل الحالية.
            اكتب جملاً مكتملة بعلامات ترقيم واضحة كي يبدأ النطق المتدفق بعد اكتمال المعنى، لا كلمة كلمة.
            لا تدّع دقة مسافات أو سلامة طريق لا يمكن إثباتها من صورة واحدة.
            اجعل urgent=true فقط عند وجود عائق أو خطر واضح يحتاج انتباهاً فورياً.
            لا تزد على 90 كلمة، ولا تضف مقدمات أو عبارات مجاملة.
        """.trimIndent()

        val SCENE_BRIEF_PROMPT = """
            أنت مساعد بصري سريع لمستخدم كفيف. حلل الصورة باعتبارها بثاً من نظارة يظهر على شاشة هاتف.
            أخرج نصاً عادياً فقط، دون JSON ودون Markdown، بهذا البروتوكول الإلزامي:
            السطر الأول: META|language=ar|urgent=true أو false
            من السطر الثاني: الوصف العربي المنطوق فقط.
            اذكر فقط، بهذا الترتيب: الخطر الفوري إن وجد، اتجاه المسار أو أهم عائق، ثم أهم شخص أو شيء أو نص.
            استخدم كلمات اتجاهية مباشرة: أمامك، يمينك، يسارك، قريب، بعيد تقريباً.
            اكتب جملة أو جملتين مكتملتين بعلامات ترقيم واضحة.
            لا تكرر المعنى، ولا تذكر تفاصيل زخرفية، ولا تضف مقدمة.
            اجعل urgent=true فقط عند خطر واضح يحتاج انتباهاً فورياً.
            الحد الأقصى 28 كلمة.
        """.trimIndent()
    }
}
