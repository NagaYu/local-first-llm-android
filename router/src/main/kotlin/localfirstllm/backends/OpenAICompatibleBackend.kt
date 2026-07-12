/*
 * Generic adapter for any OpenAI-wire-compatible chat-completions endpoint:
 * OpenAI itself, or an OpenRouter / LiteLLM / vLLM / Ollama / Together endpoint.
 * This is the usual way to plug an existing cloud setup in as the `cloud`
 * backend. Mirrors local-first-llm's `backends/openai-compatible.ts`.
 */
package localfirstllm.backends

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import localfirstllm.ChatRequest
import localfirstllm.ChatResponse
import localfirstllm.FinishReason
import localfirstllm.Message
import localfirstllm.ResponseFormat
import localfirstllm.Role
import localfirstllm.ToolCall
import localfirstllm.ToolChoice
import localfirstllm.ToolDefinition
import localfirstllm.Usage
import okhttp3.OkHttpClient

/**
 * @property baseUrl e.g. `"https://api.openai.com/v1"` — `/chat/completions` is
 *   appended. A trailing slash is tolerated.
 * @property apiKey Sent as `Authorization: Bearer <apiKey>` when set. Optional
 *   for local endpoints (Ollama, vLLM) that need no auth.
 * @property model Default model id for requests that don't set one.
 * @property headers Extra headers merged into every request (e.g. gateway
 *   routing headers).
 * @property client Injectable OkHttp client (tests, custom timeouts/proxies).
 */
public class OpenAICompatibleBackend(
    baseUrl: String,
    private val apiKey: String? = null,
    private val model: String,
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = defaultHttpClient(),
) : Backend {

    private val url: String = "${baseUrl.trimEnd('/')}/chat/completions"

    override val name: String = "openai-compatible"

    override suspend fun complete(request: ChatRequest): ChatResponse {
        val body = buildRequestBody(request).toString()
        val requestHeaders = buildMap {
            if (apiKey != null) put("Authorization", "Bearer $apiKey")
            putAll(headers)
        }
        val response = client.postJson(url, requestHeaders, body)
        if (!response.isSuccessful) {
            throw BackendHttpException(name, response.status, response.body)
        }
        return parseResponse(WireJson.parseToJsonElement(response.body).jsonObject, request.model ?: model)
    }

    private fun buildRequestBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model ?: model)
        put("messages", encodeMessages(request.messages))
        request.maxTokens?.let { put("max_tokens", it) }
        request.temperature?.let { put("temperature", it) }
        request.topP?.let { put("top_p", it) }
        if (request.stop.isNotEmpty()) {
            put("stop", buildJsonArray { request.stop.forEach { add(it) } })
        }
        if (request.tools.isNotEmpty()) {
            put("tools", encodeTools(request.tools))
        }
        request.toolChoice?.let { put("tool_choice", encodeToolChoice(it)) }
        request.responseFormat?.let {
            putJsonObject("response_format") {
                put("type", if (it == ResponseFormat.JsonObject) "json_object" else "text")
            }
        }
    }

    private fun encodeMessages(messages: List<Message>): JsonArray = buildJsonArray {
        for (message in messages) {
            addJsonObject {
                put("role", message.role.wireName())
                // content is null-valid for an assistant turn that only calls tools.
                put("content", message.content?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
                message.name?.let { put("name", it) }
                if (message.toolCalls.isNotEmpty()) {
                    put("tool_calls", buildJsonArray {
                        for (call in message.toolCalls) {
                            addJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.arguments)
                                }
                            }
                        }
                    })
                }
                message.toolCallId?.let { put("tool_call_id", it) }
            }
        }
    }

    private fun encodeTools(tools: List<ToolDefinition>): JsonArray = buildJsonArray {
        for (tool in tools) {
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    tool.description?.let { put("description", it) }
                    put("parameters", parseSchema(tool.parametersJson))
                }
            }
        }
    }

    private fun encodeToolChoice(choice: ToolChoice): JsonElement = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Function -> buildJsonObject {
            put("type", "function")
            putJsonObject("function") { put("name", choice.name) }
        }
    }

    private fun parseResponse(root: JsonObject, fallbackModel: String): ChatResponse {
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val messageObj = choice?.get("message")?.jsonObject
        val message = messageObj?.let { decodeMessage(it) }
            ?: Message(role = Role.Assistant, content = null)
        val finish = mapFinishReason(choice?.get("finish_reason")?.jsonPrimitiveContentOrNull())
        val usage = root["usage"]?.jsonObject?.let { u ->
            Usage(
                promptTokens = u.intOrZero("prompt_tokens"),
                completionTokens = u.intOrZero("completion_tokens"),
                totalTokens = u.intOrZero("total_tokens"),
            )
        }
        return ChatResponse(
            id = root["id"]?.jsonPrimitiveContentOrNull() ?: "",
            model = root["model"]?.jsonPrimitiveContentOrNull() ?: fallbackModel,
            message = message,
            finishReason = finish,
            usage = usage,
        )
    }

    private fun decodeMessage(obj: JsonObject): Message {
        val toolCalls = obj["tool_calls"]?.jsonArray?.mapNotNull { element ->
            val call = element.jsonObject
            val fn = call["function"]?.jsonObject ?: return@mapNotNull null
            ToolCall(
                id = call["id"]?.jsonPrimitiveContentOrNull() ?: "",
                name = fn["name"]?.jsonPrimitiveContentOrNull() ?: "",
                arguments = fn["arguments"]?.jsonPrimitiveContentOrNull() ?: "",
            )
        } ?: emptyList()
        return Message(
            role = Role.Assistant,
            content = obj["content"]?.jsonPrimitiveContentOrNull(),
            toolCalls = toolCalls,
        )
    }

    public companion object {
        /** OpenAI `finish_reason` → [FinishReason]; unknown/other values fall back to [FinishReason.Stop]. */
        internal fun mapFinishReason(value: String?): FinishReason? = when (value) {
            null -> null
            "stop" -> FinishReason.Stop
            "length" -> FinishReason.Length
            "tool_calls" -> FinishReason.ToolCalls
            "content_filter" -> FinishReason.ContentFilter
            else -> FinishReason.Stop
        }
    }
}

internal fun Role.wireName(): String = when (this) {
    Role.System -> "system"
    Role.User -> "user"
    Role.Assistant -> "assistant"
    Role.Tool -> "tool"
}

/** Parse a raw JSON-schema string to an element, defaulting to `{"type":"object"}`. */
internal fun parseSchema(parametersJson: String?): JsonObject =
    if (parametersJson.isNullOrBlank()) {
        buildJsonObject { put("type", "object") }
    } else {
        runCatching { WireJson.parseToJsonElement(parametersJson).jsonObject }
            .getOrElse { buildJsonObject { put("type", "object") } }
    }

/** Content of a JSON primitive, or null for a missing value or JSON `null`. */
internal fun JsonElement.jsonPrimitiveContentOrNull(): String? {
    if (this is JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content
}

internal fun JsonObject.intOrZero(key: String): Int =
    this[key]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
