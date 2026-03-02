package com.scroller.agent.executor

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class Orchestrator(
    private val loopFactory: (LoopConfig) -> AgentLoopController,
    private val decomposer: SubgoalDecomposer
) {
    suspend fun runGoal(goal: String): OrchestrationResult {
        val subgoals = decomposer.decompose(goal)
        for (subgoal in subgoals) {
            val loop = loopFactory(LoopConfig())
            try {
                val result = loop.run(subgoal.description)
                if (result !is LoopResult.Success) {
                    loop.cancel()
                    return OrchestrationResult.Failed(subgoal.description)
                }
            } catch (e: CancellationException) {
                loop.cancel()
                throw e
            }
        }
        return OrchestrationResult.Success
    }
}

interface SubgoalDecomposer {
    suspend fun decompose(goal: String): List<Subgoal>
}

class OpenAiSubgoalDecomposer(
    private val apiKeyProvider: () -> String,
    private val model: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
) : SubgoalDecomposer {

    override suspend fun decompose(goal: String): List<Subgoal> = withContext(ioDispatcher) {
        try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                val requestJson = buildRequestJson(goal)
                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .addHeader("Authorization", "Bearer ${apiKeyProvider()}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = executeRequest(request)
                response.use { httpResponse ->
                    if (!httpResponse.isSuccessful) {
                        throw OrchestrationException.NetworkError
                    }
                    val body = httpResponse.body?.string() ?: throw OrchestrationException.InvalidResponse
                    val content = parseAssistantContent(body)
                    val list = parseSubgoalList(content)
                    return@withTimeout list.map { Subgoal(it) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw OrchestrationException.Timeout
        } catch (e: SocketTimeoutException) {
            throw OrchestrationException.Timeout
        } catch (e: IOException) {
            throw OrchestrationException.NetworkError
        } catch (e: OrchestrationException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OrchestrationException.InvalidResponse
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

    private fun buildRequestJson(goal: String): String {
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage(
                    role = "system",
                    content = listOf(ContentPart(type = "text", text = systemPrompt()))
                ),
                ChatMessage(
                    role = "user",
                    content = listOf(ContentPart(type = "text", text = userPrompt(goal)))
                )
            ),
            responseFormat = ResponseFormat(
                type = "json_schema",
                jsonSchema = JsonSchema(
                    name = "subgoals",
                    strict = true,
                    schema = buildSubgoalSchema()
                )
            )
        )
        return moshi.adapter(ChatCompletionRequest::class.java).toJson(request)
    }

    private fun systemPrompt(): String {
        return """
You are a task decomposer.
Return ONLY valid JSON matching the schema. No extra text.
Return 1 to 5 concise subgoals.
""".trim()
    }

    private fun userPrompt(goal: String): String {
        return "Goal: $goal"
    }

    private fun parseAssistantContent(body: String): String {
        val adapter = moshi.adapter(ChatCompletionResponse::class.java)
        val parsed = adapter.fromJson(body) ?: throw OrchestrationException.InvalidResponse
        val content = parsed.choices.firstOrNull()?.message?.content ?: throw OrchestrationException.InvalidResponse
        return content.trim()
    }

    private fun parseSubgoalList(content: String): List<String> {
        val listAdapter = moshi.adapter<List<String>>(
            Types.newParameterizedType(List::class.java, String::class.java)
        )
        val list = listAdapter.fromJson(content) ?: throw OrchestrationException.InvalidResponse
        if (list.isEmpty() || list.size > MAX_SUBGOALS) {
            throw OrchestrationException.SchemaViolation
        }
        if (list.any { it.isBlank() }) {
            throw OrchestrationException.SchemaViolation
        }
        return list
    }

    private fun buildSubgoalSchema(): Map<String, Any> {
        return mapOf(
            "type" to "array",
            "minItems" to 1,
            "maxItems" to MAX_SUBGOALS,
            "items" to mapOf("type" to "string")
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val MAX_SUBGOALS = 5
    }
}

sealed class OrchestrationException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    data object NetworkError : OrchestrationException("Network error")
    data object Timeout : OrchestrationException("Request timed out")
    data object InvalidResponse : OrchestrationException("Invalid response")
    data object SchemaViolation : OrchestrationException("Schema violation")
}

@JsonClass(generateAdapter = false)
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @Json(name = "response_format") val responseFormat: ResponseFormat
)

@JsonClass(generateAdapter = false)
private data class ChatMessage(
    val role: String,
    val content: List<ContentPart>
)

@JsonClass(generateAdapter = false)
private data class ContentPart(
    val type: String,
    val text: String? = null
)

@JsonClass(generateAdapter = false)
private data class ResponseFormat(
    val type: String,
    @Json(name = "json_schema") val jsonSchema: JsonSchema
)

@JsonClass(generateAdapter = false)
private data class JsonSchema(
    val name: String,
    val strict: Boolean,
    val schema: Map<String, Any>
)

@JsonClass(generateAdapter = false)
private data class ChatCompletionResponse(
    val choices: List<ChatChoice>
)

@JsonClass(generateAdapter = false)
private data class ChatChoice(
    val message: ChatAssistantMessage
)

@JsonClass(generateAdapter = false)
private data class ChatAssistantMessage(
    val content: String
)
