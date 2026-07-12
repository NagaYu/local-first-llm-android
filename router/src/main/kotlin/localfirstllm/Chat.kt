/*
 * The library's chat-completion-shaped request/response surface.
 *
 * These shapes are deliberately modeled on the OpenAI chat-completions API
 * (roles, tool calls with JSON-string arguments, finish reasons) so that
 * adopting this library from an existing OpenAI-shaped codebase is a close
 * type mapping — but they are defined here, in the zero-dependency core, and
 * carry no dependency on any provider SDK.
 *
 * A [ChatRequest] additionally carries the routing hints ([hint], [privacy],
 * budgets) the policy engine consumes. Backends ignore those fields; they only
 * read the inference-shaped fields when building their wire request.
 */
package localfirstllm

/** The author of a [Message]. Mirrors OpenAI's four chat roles. */
public enum class Role {
    System,
    User,
    Assistant,
    Tool,
}

/**
 * An assistant-requested tool invocation, in the OpenAI wire shape.
 *
 * @property id Stable id the matching tool result ([Message.toolCallId]) refers
 *   back to.
 * @property name The tool/function name the model wants to call.
 * @property arguments The call arguments as a **JSON-encoded string**, exactly
 *   as on the OpenAI wire (not a parsed object). Backends that need an object
 *   (e.g. Anthropic's `tool_use.input`) parse it themselves.
 */
public data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * One turn in a conversation.
 *
 * @property role Who authored the turn.
 * @property content The text content. `null` is valid for an assistant turn
 *   that only requests tool calls.
 * @property name Optional author name (OpenAI's `name` field).
 * @property toolCalls Present on an assistant turn that requests tool calls.
 * @property toolCallId Present on a [Role.Tool] turn; links the result back to
 *   the [ToolCall.id] it answers.
 */
public data class Message(
    val role: Role,
    val content: String? = null,
    val name: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/**
 * A tool the model may call.
 *
 * @property name Function name.
 * @property description Optional natural-language description.
 * @property parametersJson JSON Schema for the arguments, as a **raw JSON
 *   string** (e.g. `"""{"type":"object","properties":{...}}"""`). Kept as a
 *   string rather than a typed object so the public API stays free of any
 *   serialization dependency. When null, backends default to
 *   `{"type":"object"}`.
 */
public data class ToolDefinition(
    val name: String,
    val description: String? = null,
    val parametersJson: String? = null,
)

/**
 * How the model should decide whether to call a tool. Mirrors OpenAI's
 * `tool_choice`; backends map it to their own vocabulary (see
 * `AnthropicBackend` for the Anthropic translation).
 */
public sealed interface ToolChoice {
    /** Model decides freely (OpenAI `"auto"`). */
    public data object Auto : ToolChoice

    /** Model must not call a tool (OpenAI `"none"`). */
    public data object None : ToolChoice

    /** Model must call some tool (OpenAI `"required"`). */
    public data object Required : ToolChoice

    /** Model must call this specific tool. */
    public data class Function(val name: String) : ToolChoice
}

/** Requested output format. Mirrors OpenAI's `response_format.type`. */
public enum class ResponseFormat {
    Text,
    JsonObject,
}

/**
 * A single chat request. The inference-shaped fields ([messages] … [responseFormat])
 * are what a [localfirstllm.backends.Backend] sends on the wire; the routing
 * fields ([hint] … [latencyBudgetMs]) steer [decide] and are never sent to a
 * backend.
 *
 * @property messages The conversation so far. Required, non-empty in practice.
 * @property model Optional model id; when null the target backend uses its own
 *   configured default.
 * @property maxTokens Requested max output tokens. Feeds both the policy
 *   heuristic and (for Anthropic) the required `max_tokens` wire field.
 * @property temperature Optional sampling temperature (passed through only when
 *   set — some newer models reject it).
 * @property topP Optional nucleus-sampling parameter (passed through only when set).
 * @property stop Optional stop sequences.
 * @property tools Tools the model may call. A non-empty list sets
 *   [PolicyInput.hasTools].
 * @property toolChoice Optional tool-choice constraint.
 * @property responseFormat Optional output-format request. [ResponseFormat.JsonObject]
 *   sets [PolicyInput.wantsStructuredOutput].
 * @property hint Optional task-type hint; see the taxonomy table in [decide].
 * @property privacy [PrivacyLevel.Sensitive] requests are served on-device or
 *   blocked — never sent to the cloud backend. Defaults to [PrivacyLevel.Normal].
 * @property costBudgetUsd Optional per-request cost budget (USD); overrides
 *   [PolicyConfig.costBudgetUsd].
 * @property latencyBudgetMs Optional per-request latency budget (ms); overrides
 *   [PolicyConfig.latencyBudgetMs].
 */
public data class ChatRequest(
    val messages: List<Message>,
    val model: String? = null,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stop: List<String> = emptyList(),
    val tools: List<ToolDefinition> = emptyList(),
    val toolChoice: ToolChoice? = null,
    val responseFormat: ResponseFormat? = null,
    val hint: TaskHint? = null,
    val privacy: PrivacyLevel = PrivacyLevel.Normal,
    val costBudgetUsd: Double? = null,
    val latencyBudgetMs: Long? = null,
)

/**
 * Why generation stopped. Mirrors OpenAI's `finish_reason`; provider-specific
 * stop reasons (e.g. Anthropic's `stop_reason`) are mapped onto these values by
 * the backends, with the mapping documented at each mapping site.
 */
public enum class FinishReason {
    /** Natural end of turn or a stop sequence was hit. */
    Stop,

    /** The output token cap (or context) was reached. */
    Length,

    /** The model requested one or more tool calls. */
    ToolCalls,

    /** Generation was stopped by a safety/content filter. */
    ContentFilter,
}

/** Token accounting for a completion, when the backend reports it. */
public data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

/**
 * A backend's completion result, before routing metadata is attached. The
 * router wraps this in a [RoutedResponse].
 */
public data class ChatResponse(
    val id: String,
    val model: String,
    val message: Message,
    val finishReason: FinishReason?,
    val usage: Usage? = null,
)

/**
 * What [HybridRouter.complete] returns: the backend's [ChatResponse] fields plus
 * the routing metadata every result carries, so callers can always observe what
 * actually happened.
 *
 * @property routedTo Which mode actually served the request (after any fallback).
 * @property routingReason Human-readable trail of the routing decision and any
 *   fallback, suitable for logs.
 */
public data class RoutedResponse(
    val message: Message,
    val routedTo: RouteMode,
    val routingReason: String,
    val id: String,
    val model: String,
    val finishReason: FinishReason?,
    val usage: Usage? = null,
) {
    public companion object {
        /** Wrap a backend [response] with the mode it was served from and why. */
        public fun of(response: ChatResponse, routedTo: RouteMode, routingReason: String): RoutedResponse =
            RoutedResponse(
                message = response.message,
                routedTo = routedTo,
                routingReason = routingReason,
                id = response.id,
                model = response.model,
                finishReason = response.finishReason,
                usage = response.usage,
            )
    }
}
