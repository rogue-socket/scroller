package com.scroller.agent.executor

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @Json(name = "response_format") val responseFormat: ResponseFormat? = null
)

@JsonClass(generateAdapter = false)
internal data class ChatMessage(
    val role: String,
    val content: List<ContentPart>
)

@JsonClass(generateAdapter = false)
internal data class ContentPart(
    val type: String,
    val text: String? = null,
    @Json(name = "image_url") val imageUrl: ImageUrl? = null
)

@JsonClass(generateAdapter = false)
internal data class ImageUrl(
    val url: String,
    val detail: String = "low"
)

@JsonClass(generateAdapter = false)
internal data class ResponseFormat(
    val type: String,
    @Json(name = "json_schema") val jsonSchema: JsonSchema
)

@JsonClass(generateAdapter = false)
internal data class JsonSchema(
    val name: String,
    val strict: Boolean,
    val schema: Map<String, Any>
)

@JsonClass(generateAdapter = false)
internal data class ChatCompletionResponse(
    val choices: List<ChatChoice>
)

@JsonClass(generateAdapter = false)
internal data class ChatChoice(
    val message: ChatAssistantMessage
)

@JsonClass(generateAdapter = false)
internal data class ChatAssistantMessage(
    val content: String?
)
