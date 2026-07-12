/*
 * Backend adapter for the **native Anthropic Messages API** (POST /v1/messages).
 *
 * Anthropic ships an OpenAI-compatibility shim, but documents it as intended
 * for quick testing rather than production use — so this adapter deliberately
 * targets the native Messages API and translates the wire shapes itself. The
 * four mappings that differ from OpenAI, each handled and documented below at
 * its implementation site (a faithful port of local-first-llm's
 * `backends/anthropic.ts`):
 *
 *   1. `max_tokens` is REQUIRED by Anthropic (optional in OpenAI) — a default
 *      is injected when the request doesn't set one.
 *   2. The system prompt is a top-level `system` parameter — Role.System
 *      messages are lifted out of the messages array, never sent inline as a
 *      `{role:"system"}` message.
 *   3. Tool calls arrive as `tool_use` content blocks (not `tool_calls`), and
 *      tool results are sent as `tool_result` blocks inside a user message
 *      (not `{role:"tool"}` messages).
 *   4. `stop_reason` values differ from OpenAI's `finish_reason` values — see
 *      [STOP_REASON_TO_FINISH_REASON].
 */
package localfirstllm.backends

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import localfirstllm.ChatRequest
import localfirstllm.ChatResponse
import localfirstllm.FinishReason
import localfirstllm.Message
import localfirstllm.Role
import localfirstllm.ToolCall
import localfirstllm.ToolChoice
import localfirstllm.ToolDefinition
import localfirstllm.Usage
import okhttp3.OkHttpClient

/**
 * @property apiKey Anthropic API key, sent as the `x-api-key` header.
 * @property model Default model for requests that don't set one, e.g.
 *   `"claude-opus-4-8"` or a Claude 5 model id.
 * @property baseUrl Override for proxies/gateways; defaults to
 *   `https://api.anthropic.com`. `/v1/messages` is appended.
 * @property defaultMaxTokens Injected when neither the request nor this option
 *   sets `max_tokens` (the Messages API rejects requests without it). Defaults
 *   to [DEFAULT_MAX_TOKENS] (1024), matching the policy engine's
 *   `assumedMaxTokens` so estimation and execution agree.
 * @property headers Extra headers merged into every request.
 * @property client Injectable OkHttp client (tests, custom timeouts/proxies).
 */
public class AnthropicBackend(
    private val apiKey: String,
    private val model: String,
    baseUrl: String = "https://api.anthropic.com",
    private val defaultMaxTokens: Int = DEFAULT_MAX_TOKENS,
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = defaultHttpClient(),
) : Backend {

    private val url: String = "${baseUrl.trimEnd('/')}/v1/messages"

    override val name: String = "anthropic"

    override suspend fun complete(request: ChatRequest): ChatResponse {
        val body = buildRequestBody(request).toString()
        val requestHeaders = buildMap {
            put("x-api-key", apiKey)
            put("anthropic-version", ANTHROPIC_VERSION)
            putAll(headers)
        }
        val response = client.postJson(url, requestHeaders, body)
        if (!response.isSuccessful) {
            throw BackendHttpException(name, response.status, response.body)
        }
        return toChatResponse(WireJson.parseToJsonElement(response.body).jsonObject, request.model ?: model)
    }

    /** Builds the Messages-API request body, applying mappings #1–#3 (request side). */
    internal fun buildRequestBody(request: ChatRequest): JsonObject {
        val mapped = toAnthropicMessages(request.messages)
        return buildJsonObject {
            put("model", request.model ?: model)
            // Mapping #1: max_tokens is required; inject the documented default
            // when the request omits it.
            put("max_tokens", request.maxTokens ?: defaultMaxTokens)
            // Mapping #2: system prompt is a top-level parameter.
            mapped.system?.let { put("system", it) }
            put("messages", mapped.messages)
            // Newest Anthropic models reject sampling params (temperature / top_p
            // return 400) — pass through only when the caller sets them, so the
            // caller controls model compatibility.
            request.temperature?.let { put("temperature", it) }
            request.topP?.let { put("top_p", it) }
            if (request.stop.isNotEmpty()) {
                put("stop_sequences", buildJsonArray { request.stop.forEach { add(it) } })
            }
            if (request.tools.isNotEmpty()) {
                put("tools", encodeTools(request.tools))
            }
            request.toolChoice?.let { put("tool_choice", encodeToolChoice(it)) }
            // request.responseFormat is intentionally NOT translated: OpenAI's
            // {type:"json_object"} has no lossless Messages-API equivalent
            // (Anthropic structured output wants a concrete schema). Documented
            // in the README.
        }
    }

    private fun encodeTools(tools: List<ToolDefinition>): JsonArray = buildJsonArray {
        for (tool in tools) {
            addJsonObject {
                put("name", tool.name)
                tool.description?.let { put("description", it) }
                // OpenAI's function.parameters becomes Anthropic's input_schema.
                put("input_schema", parseSchema(tool.parametersJson))
            }
        }
    }

    private fun encodeToolChoice(choice: ToolChoice): JsonElement = when (choice) {
        ToolChoice.Auto -> buildJsonObject { put("type", "auto") }
        ToolChoice.None -> buildJsonObject { put("type", "none") }
        // OpenAI 'required' ("must call some tool") is Anthropic's {type:"any"}.
        ToolChoice.Required -> buildJsonObject { put("type", "any") }
        is ToolChoice.Function -> buildJsonObject {
            put("type", "tool")
            put("name", choice.name)
        }
    }

    /**
     * Mapping #3 (response side) and #4: fold Anthropic content blocks into a
     * single assistant [Message] — `text` blocks concatenate into [Message.content],
     * `tool_use` blocks become [ToolCall]s with re-stringified arguments — and map
     * `stop_reason` to a [FinishReason].
     */
    internal fun toChatResponse(root: JsonObject, fallbackModel: String): ChatResponse {
        val textParts = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        for (element in root["content"]?.jsonArray ?: JsonArray(emptyList())) {
            val block = element.jsonObject
            when (block["type"]?.jsonPrimitiveContentOrNull()) {
                "text" -> block["text"]?.jsonPrimitiveContentOrNull()?.let { textParts.append(it) }
                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitiveContentOrNull()
                    val toolName = block["name"]?.jsonPrimitiveContentOrNull()
                    if (id != null && toolName != null) {
                        val input = block["input"] ?: buildJsonObject { }
                        // JsonElement.toString() already emits valid JSON.
                        toolCalls.add(ToolCall(id = id, name = toolName, arguments = input.toString()))
                    }
                }
                // Other block types (thinking, server tool results) have no
                // chat-completion equivalent and are not surfaced.
            }
        }

        val message = Message(
            role = Role.Assistant,
            content = if (textParts.isNotEmpty()) textParts.toString() else null,
            toolCalls = toolCalls,
        )
        val usage = root["usage"]?.jsonObject?.let { u ->
            val input = u.intOrZero("input_tokens")
            val output = u.intOrZero("output_tokens")
            Usage(promptTokens = input, completionTokens = output, totalTokens = input + output)
        }
        return ChatResponse(
            id = root["id"]?.jsonPrimitiveContentOrNull() ?: "",
            model = root["model"]?.jsonPrimitiveContentOrNull() ?: fallbackModel,
            message = message,
            finishReason = mapStopReason(root["stop_reason"]?.jsonPrimitiveContentOrNull()),
            usage = usage,
        )
    }

    public companion object {
        /**
         * Injected when neither the request nor the backend option sets
         * max_tokens — the Messages API rejects requests without it. 1024 matches
         * the policy engine's `assumedMaxTokens` so estimation and execution agree.
         */
        public const val DEFAULT_MAX_TOKENS: Int = 1024

        /** Pinned API version header — the Messages API requires it. */
        internal const val ANTHROPIC_VERSION: String = "2023-06-01"

        /**
         * Mapping #4: Anthropic `stop_reason` → [FinishReason].
         * - `end_turn` / `stop_sequence` → [FinishReason.Stop]
         * - `max_tokens` → [FinishReason.Length]
         * - `model_context_window_exceeded` → [FinishReason.Length] (context, not
         *   output, ran out — no distinct OpenAI value; Length is closest)
         * - `tool_use` → [FinishReason.ToolCalls]
         * - `refusal` → [FinishReason.ContentFilter] (safety decline)
         * - `pause_turn` → [FinishReason.Stop] (server-tool pause; from a chat
         *   consumer's view the turn ended)
         * - unknown/other → [FinishReason.Stop]; null → null.
         */
        internal val STOP_REASON_TO_FINISH_REASON: Map<String, FinishReason> = mapOf(
            "end_turn" to FinishReason.Stop,
            "stop_sequence" to FinishReason.Stop,
            "max_tokens" to FinishReason.Length,
            "model_context_window_exceeded" to FinishReason.Length,
            "tool_use" to FinishReason.ToolCalls,
            "refusal" to FinishReason.ContentFilter,
            "pause_turn" to FinishReason.Stop,
        )

        internal fun mapStopReason(stopReason: String?): FinishReason? {
            if (stopReason == null) return null
            return STOP_REASON_TO_FINISH_REASON[stopReason] ?: FinishReason.Stop
        }
    }
}

/** Result of mapping OpenAI-shaped messages to Anthropic's shape (mappings #2, #3). */
internal data class MappedAnthropicRequest(val system: String?, val messages: JsonArray)

/**
 * Mapping #2 and #3 (request side): lift Role.System messages to the top-level
 * `system` param; convert assistant [Message.toolCalls] to `tool_use` blocks and
 * Role.Tool results to `tool_result` blocks in user messages (merging
 * consecutive tool results into one user turn, as Anthropic expects for a
 * parallel-call batch).
 */
internal fun toAnthropicMessages(messages: List<Message>): MappedAnthropicRequest {
    val systemParts = mutableListOf<String>()
    // We build user/assistant turns, occasionally appending to the previous
    // user turn when it is a run of tool_result blocks. Model each as a small
    // mutable holder, then serialize at the end.
    val turns = mutableListOf<AnthropicTurn>()

    for (message in messages) {
        when (message.role) {
            Role.System -> {
                // Mapping #2: multiple system messages join into the one top-level param.
                message.content?.takeIf { it.isNotEmpty() }?.let { systemParts.add(it) }
            }
            Role.User -> turns.add(AnthropicTurn.Text("user", message.content ?: ""))
            Role.Assistant -> {
                val blocks = buildJsonArray {
                    message.content?.takeIf { it.isNotEmpty() }?.let {
                        addJsonObject { put("type", "text"); put("text", it) }
                    }
                    // Mapping #3: OpenAI tool_calls (arguments = JSON string) become
                    // tool_use content blocks (input = parsed JSON object).
                    for (call in message.toolCalls) {
                        addJsonObject {
                            put("type", "tool_use")
                            put("id", call.id)
                            put("name", call.name)
                            put("input", parseToolArguments(call))
                        }
                    }
                }
                // A pure-text assistant turn is sent as a plain string; otherwise
                // as the block array.
                if (blocks.size == 1 && blocks[0].jsonObject["type"]?.jsonPrimitiveContentOrNull() == "text") {
                    turns.add(AnthropicTurn.Text("assistant", message.content ?: ""))
                } else {
                    turns.add(AnthropicTurn.Blocks("assistant", blocks.toMutableList()))
                }
            }
            Role.Tool -> {
                // Mapping #3: a tool result is a tool_result block inside a *user*
                // message. Consecutive tool messages merge into one user turn.
                val block = buildJsonObject {
                    put("type", "tool_result")
                    message.toolCallId?.let { put("tool_use_id", it) }
                    put("content", message.content ?: "")
                }
                val previous = turns.lastOrNull()
                if (previous is AnthropicTurn.Blocks && previous.role == "user" && previous.allToolResults()) {
                    previous.blocks.add(block)
                } else {
                    turns.add(AnthropicTurn.Blocks("user", mutableListOf(block)))
                }
            }
        }
    }

    val messagesArray = buildJsonArray {
        for (turn in turns) {
            addJsonObject {
                put("role", turn.role)
                when (turn) {
                    is AnthropicTurn.Text -> put("content", turn.text)
                    is AnthropicTurn.Blocks -> put("content", JsonArray(turn.blocks))
                }
            }
        }
    }
    return MappedAnthropicRequest(
        system = if (systemParts.isEmpty()) null else systemParts.joinToString("\n\n"),
        messages = messagesArray,
    )
}

/** A partially-built Anthropic message turn. */
private sealed interface AnthropicTurn {
    val role: String

    data class Text(override val role: String, val text: String) : AnthropicTurn
    data class Blocks(override val role: String, val blocks: MutableList<JsonElement>) : AnthropicTurn {
        fun allToolResults(): Boolean =
            blocks.all { (it as? JsonObject)?.get("type")?.jsonPrimitiveContentOrNull() == "tool_result" }
    }
}

/** Parse an OpenAI tool call's JSON-string arguments into an object (empty → `{}`). */
internal fun parseToolArguments(call: ToolCall): JsonElement {
    val raw = call.arguments.ifBlank { "{}" }
    return runCatching { WireJson.parseToJsonElement(raw) }.getOrElse {
        throw IllegalArgumentException(
            "anthropic: tool call ${call.id} (${call.name}) has arguments that are not valid JSON",
            it,
        )
    }
}
