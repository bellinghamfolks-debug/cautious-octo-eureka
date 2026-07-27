package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.util.Base64
import com.abdullah.visionbridge.data.network.CellularNetworkManager
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
import com.abdullah.visionbridge.domain.repository.VisionAiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class GeminiVisionRepository(
    private val networkManager: CellularNetworkManager,
) : VisionAiRepository {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun analyze(
        bitmap: Bitmap,
        mode: AnalysisMode,
        model: String,
        apiKey: String,
        forceCellular: Boolean,
    ): AnalysisResult = networkManager.withNetwork(forceCellular) { network ->
        withContext(Dispatchers.IO) {
            val imageBytes = bitmapToJpeg(bitmap)
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
                            GeminiPart(text = promptFor(mode)),
                        )
                    )
                ),
                generationConfig = GenerationConfig(),
            )

            val client = if (network == null) {
                baseClient
            } else {
                baseClient.newBuilder()
                    .socketFactory(network.socketFactory)
                    .dns(Dns { hostname -> network.getAllByName(hostname).toList() })
                    .build()
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .header("Accept", "application/json")
                .post(json.encodeToString(GenerateContentRequest.serializer(), payload)
                    .toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val parsedError = runCatching {
                        json.decodeFromString(GenerateContentResponse.serializer(), body).error?.message
                    }.getOrNull()
                    throw IllegalStateException(parsedError ?: "فشل Gemini برمز HTTP ${response.code}")
                }

                val apiResponse = json.decodeFromString(GenerateContentResponse.serializer(), body)
                val rawText = apiResponse.candidates
                    .asSequence()
                    .mapNotNull { it.content }
                    .flatMap { it.parts.asSequence() }
                    .mapNotNull { it.text }
                    .joinToString("\n")
                    .trim()

                if (rawText.isBlank()) throw IllegalStateException("لم يُرجع Gemini وصفاً قابلاً للقراءة")
                val parsed = parseVisionResponse(rawText)
                AnalysisResult(
                    text = parsed.spokenText.trim(),
                    source = AnalysisSource.GEMINI,
                    language = parsed.detectedLanguage,
                    urgent = parsed.urgent,
                )
            }
        }
    }

    private fun parseVisionResponse(raw: String): ParsedVisionResponse {
        val clean = raw
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            json.decodeFromString(ParsedVisionResponse.serializer(), clean)
        }.getOrElse {
            ParsedVisionResponse(spokenText = clean, detectedLanguage = "und", urgent = false)
        }
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

    private fun promptFor(mode: AnalysisMode): String = when (mode) {
        AnalysisMode.TEXT_READING -> OCR_PROMPT
        AnalysisMode.SCENE_DESCRIPTION -> SCENE_PROMPT
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_IMAGE_EDGE = 1280
        const val JPEG_QUALITY = 72

        val OCR_PROMPT = """
            أنت محرك OCR دقيق لمستخدم كفيف. افحص الصورة كاملة واستخرج النص العربي والإنجليزي الظاهر.
            أعد كائن JSON فقط بهذه المفاتيح:
            {"spokenText":"النص بالترتيب الطبيعي للقراءة","detectedLanguage":"ar أو en أو mixed أو none","urgent":false}
            القواعد:
            1) انسخ النص كما يظهر دون شرح أو تلخيص.
            2) حافظ على ترتيب الأسطر والعناوين والأرقام المهمة.
            3) لا تخترع كلمات غير واضحة. ضع [غير واضح] فقط عند الضرورة.
            4) إن لم يوجد نص، اجعل spokenText فارغاً وdetectedLanguage يساوي none.
        """.trimIndent()

        val SCENE_PROMPT = """
            أنت مساعد بصري عملي لمستخدم كفيف. حلل الصورة باعتبارها بثاً من نظارة يظهر على شاشة هاتف.
            أعد كائن JSON فقط بهذه المفاتيح:
            {"spokenText":"وصف عربي موجز وعملي","detectedLanguage":"ar","urgent":true أو false}
            رتب الوصف هكذا: خطر فوري، الاتجاه والموقع النسبي، المسار الأكثر وضوحاً، الأشخاص والأشياء، ثم أي نص مهم.
            استخدم اتجاهات واضحة مثل أمامك، يمينك، يسارك، أعلى، أسفل، قريب، بعيد تقريباً.
            لا تدّع دقة مسافات أو سلامة طريق لا يمكن إثباتها من صورة واحدة.
            اجعل urgent=true فقط عند وجود عائق أو خطر واضح يحتاج انتباهاً فورياً.
            لا تزد على 90 كلمة، ولا تضف مقدمات أو عبارات مجاملة.
        """.trimIndent()
    }
}
