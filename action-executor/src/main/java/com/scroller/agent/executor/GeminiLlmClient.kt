package com.scroller.agent.executor

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class GeminiLlmClient(
    private val apiKeyProvider: () -> String,
    private val model: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
) : LlmClient {

    override suspend fun decideNextAction(
        goal: String,
        screen: ScreenFrame,
        actionHistory: List<AgentAction>,
        memorySummary: String
    ): LlmDecision = withContext(ioDispatcher) {
        try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                val requestJson = buildRequestJson(goal, screen, actionHistory, memorySummary)
                val url = "$baseUrl/models/$model:generateContent".toHttpUrl().newBuilder()
                    .addQueryParameter("key", apiKeyProvider())
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = executeRequest(request)
                response.use { httpResponse ->
                    if (!httpResponse.isSuccessful) {
                        logFailure("http_${httpResponse.code}")
                        throw LlmException.NetworkError
                    }
                    val body = httpResponse.body?.string() ?: throw LlmException.InvalidResponse
                    val content = parseAssistantContent(body)
                    val actionSchema = parseActionSchema(content)
                    return@withTimeout toDecision(actionSchema)
                }
            }
        } catch (e: TimeoutCancellationException) {
            logFailure("timeout")
            throw LlmException.Timeout
        } catch (e: SocketTimeoutException) {
            logFailure("socket_timeout")
            throw LlmException.Timeout
        } catch (e: IOException) {
            logFailure("network_error")
            throw LlmException.NetworkError
        } catch (e: LlmException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(e.message ?: "unexpected")
            throw LlmException.InvalidResponse
        }
    }

    private suspend fun executeRequest(request: Request): Response {
        return suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }
    }

    private fun buildRequestJson(
        goal: String,
        screen: ScreenFrame,
        actionHistory: List<AgentAction>,
        memorySummary: String
    ): String {
        val systemPrompt = buildSystemPrompt()
        val userText = buildUserContent(goal, actionHistory, memorySummary)
        val imageData = encodeImage(screen.bitmap)

        val request = GeminiGenerateContentRequest(
            contents = listOf(
                GeminiContent(
                    role = "user",
                    parts = listOf(
                        GeminiPart(text = systemPrompt),
                        GeminiPart(text = userText),
                        GeminiPart(inlineData = InlineData(mimeType = "image/jpeg", data = imageData))
                    )
                )
            ),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseJsonSchema = buildActionSchema()
            )
        )

        val adapter = moshi.adapter(GeminiGenerateContentRequest::class.java)
        return adapter.toJson(request)
    }

    private fun buildSystemPrompt(): String {
        return """
You are a mobile UI control agent.
Return ONLY valid JSON matching the schema. No extra text.
Allowed actions: tap, scroll, press_back, type, open_app, done.
If you cannot proceed, return {"action":"done"}.
Do NOT tap search bars or text fields unless the goal explicitly requires search or text entry.
Prefer visible navigation and toggles; avoid focusing input fields that don't advance the goal.
""".trim()
    }

    private fun buildUserContent(goal: String, actionHistory: List<AgentAction>, memorySummary: String): String {
        val recent = actionHistory.takeLast(10).mapIndexed { index, action ->
            "${index + 1}. ${formatAction(action)}"
        }
        val historyText = if (recent.isEmpty()) "(none)" else recent.joinToString("\n")
        return """
Goal: $goal
Recent actions:
$historyText
Memory summary:
$memorySummary
The screenshot is attached as an image.
""".trim()
    }

    private fun formatAction(action: AgentAction): String {
        return when (action) {
            is AgentAction.Tap -> "tap(${action.x}, ${action.y})"
            is AgentAction.Scroll -> "scroll(${action.direction.name.lowercase()})"
            is AgentAction.PressBack -> "press_back"
            is AgentAction.Type -> "type(\"${action.text}\")"
            is AgentAction.OpenApp -> "open_app(${action.packageName})"
        }
    }

    private fun encodeImage(bitmap: Bitmap): String {
        val scaled = scaleBitmap(bitmap)
        val output = ByteArrayOutputStream()
        // JPEG at ~75 balances size and fidelity for UI parsing while avoiding large payloads.
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, output)
        val bytes = output.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val maxDimension = 1024
        val width = bitmap.width
        val height = bitmap.height
        val maxCurrent = maxOf(width, height)
        if (maxCurrent <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / maxCurrent.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun parseAssistantContent(body: String): String {
        val adapter = moshi.adapter(GeminiGenerateContentResponse::class.java)
        val parsed = adapter.fromJson(body) ?: throw LlmException.InvalidResponse
        val candidate = parsed.candidates.firstOrNull() ?: throw LlmException.InvalidResponse
        val parts = candidate.content.parts
        val textPart = parts.firstOrNull { !it.text.isNullOrBlank() }?.text
            ?: throw LlmException.InvalidResponse
        return textPart.trim()
    }

    private fun parseActionSchema(content: String): ActionSchema {
        val mapAdapter = moshi.adapter<Map<String, Any>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
        val raw = mapAdapter.fromJson(content) ?: throw LlmException.InvalidResponse
        validateNoUnknownKeys(raw.keys)
        val adapter = moshi.adapter(ActionSchema::class.java)
        return adapter.fromJson(content) ?: throw LlmException.InvalidResponse
    }

    private fun toDecision(schema: ActionSchema): LlmDecision {
        val action = schema.action
        return when (action) {
            "done" -> {
                requireOnly(schema, emptySet())
                LlmDecision.Done
            }
            "tap" -> {
                requireOnly(schema, setOf(ActionField.X, ActionField.Y))
                val x = schema.x ?: throw LlmException.SchemaViolation
                val y = schema.y ?: throw LlmException.SchemaViolation
                LlmDecision.Action(AgentAction.Tap(x, y))
            }
            "scroll" -> {
                requireOnly(schema, setOf(ActionField.DIRECTION))
                val direction = schema.direction ?: throw LlmException.SchemaViolation
                val parsed = when (direction.lowercase()) {
                    "up" -> ScrollDirection.UP
                    "down" -> ScrollDirection.DOWN
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> throw LlmException.SchemaViolation
                }
                LlmDecision.Action(AgentAction.Scroll(parsed))
            }
            "press_back" -> {
                requireOnly(schema, emptySet())
                LlmDecision.Action(AgentAction.PressBack)
            }
            "type" -> {
                requireOnly(schema, setOf(ActionField.TEXT))
                val text = schema.text ?: throw LlmException.SchemaViolation
                LlmDecision.Action(AgentAction.Type(text))
            }
            "open_app" -> {
                requireOnly(schema, setOf(ActionField.PACKAGE_NAME))
                val pkg = schema.packageName ?: throw LlmException.SchemaViolation
                LlmDecision.Action(AgentAction.OpenApp(pkg))
            }
            else -> throw LlmException.SchemaViolation
        }
    }

    private fun requireOnly(schema: ActionSchema, allowed: Set<ActionField>) {
        val present = mutableSetOf<ActionField>()
        if (schema.x != null) present.add(ActionField.X)
        if (schema.y != null) present.add(ActionField.Y)
        if (schema.direction != null) present.add(ActionField.DIRECTION)
        if (schema.text != null) present.add(ActionField.TEXT)
        if (schema.packageName != null) present.add(ActionField.PACKAGE_NAME)
        if (!allowed.containsAll(present)) throw LlmException.SchemaViolation
        if (allowed.contains(ActionField.X) && schema.x == null) throw LlmException.SchemaViolation
        if (allowed.contains(ActionField.Y) && schema.y == null) throw LlmException.SchemaViolation
        if (allowed.contains(ActionField.DIRECTION) && schema.direction == null) throw LlmException.SchemaViolation
        if (allowed.contains(ActionField.TEXT) && schema.text == null) throw LlmException.SchemaViolation
        if (allowed.contains(ActionField.PACKAGE_NAME) && schema.packageName == null) throw LlmException.SchemaViolation
    }

    private fun buildActionSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("action"),
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "enum" to listOf("tap", "scroll", "press_back", "type", "open_app", "done")
                ),
                "x" to mapOf("type" to "integer"),
                "y" to mapOf("type" to "integer"),
                "direction" to mapOf("type" to "string"),
                "text" to mapOf("type" to "string"),
                "packageName" to mapOf("type" to "string")
            )
        )
    }

    private fun validateNoUnknownKeys(keys: Set<String>) {
        val allowed = setOf("action", "x", "y", "direction", "text", "packageName")
        if (!allowed.containsAll(keys)) {
            throw LlmException.SchemaViolation
        }
    }

    private fun logFailure(reason: String) {
        Log.i(LOG_TAG, "{\"event\":\"llm_failure\",\"reason\":\"$reason\"}")
    }

    companion object {
        private const val LOG_TAG = "GeminiLlmClient"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val REQUEST_TIMEOUT_MS = 15_000L
    }
}

@JsonClass(generateAdapter = false)
private data class GeminiGenerateContentRequest(
    val contents: List<GeminiContent>,
    @Json(name = "generationConfig") val generationConfig: GenerationConfig
)

@JsonClass(generateAdapter = false)
private data class GeminiContent(
    val role: String,
    val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = false)
private data class GeminiPart(
    val text: String? = null,
    @Json(name = "inline_data") val inlineData: InlineData? = null
)

@JsonClass(generateAdapter = false)
private data class InlineData(
    @Json(name = "mime_type") val mimeType: String,
    val data: String
)

@JsonClass(generateAdapter = false)
private data class GenerationConfig(
    @Json(name = "responseMimeType") val responseMimeType: String,
    @Json(name = "responseJsonSchema") val responseJsonSchema: Map<String, Any>
)

@JsonClass(generateAdapter = false)
private data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate>
)

@JsonClass(generateAdapter = false)
private data class GeminiCandidate(
    val content: GeminiContent
)
