package localfirstllm.backends

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localfirstllm.ChatRequest
import localfirstllm.FinishReason
import localfirstllm.Message
import localfirstllm.ResponseFormat
import localfirstllm.Role
import localfirstllm.ToolCall
import localfirstllm.ToolChoice
import localfirstllm.ToolDefinition
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mapping tests for the native Anthropic Messages-API adapter — the four
 * documented divergences from OpenAI. Mostly pure-function tests over
 * [AnthropicBackend.buildRequestBody] / [AnthropicBackend.toChatResponse] /
 * [toAnthropicMessages], plus one MockWebServer round-trip for headers/path.
 */
class AnthropicBackendTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val backend = AnthropicBackend(apiKey = "sk-ant", model = "claude-test")

    private fun body(request: ChatRequest) = backend.buildRequestBody(request)

    // ── Mapping #1: max_tokens is required ──────────────────────────────────

    @Test
    fun `injects the default max_tokens when the request omits it`() {
        val b = body(ChatRequest(messages = listOf(Message(Role.User, "hi"))))
        assertEquals(AnthropicBackend.DEFAULT_MAX_TOKENS, b["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `honors a request-provided max_tokens`() {
        val b = body(ChatRequest(messages = listOf(Message(Role.User, "hi")), maxTokens = 64))
        assertEquals(64, b["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `honors a backend-level defaultMaxTokens`() {
        val custom = AnthropicBackend(apiKey = "k", model = "m", defaultMaxTokens = 200)
        val b = custom.buildRequestBody(ChatRequest(messages = listOf(Message(Role.User, "hi"))))
        assertEquals(200, b["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    // ── Mapping #2: system prompt is a top-level param ──────────────────────

    @Test
    fun `lifts system messages to the top-level system param and out of messages`() {
        val b = body(
            ChatRequest(
                messages = listOf(
                    Message(Role.System, "You are terse."),
                    Message(Role.System, "Answer in English."),
                    Message(Role.User, "hello"),
                ),
            ),
        )
        assertEquals("You are terse.\n\nAnswer in English.", b["system"]!!.jsonPrimitive.content)
        val messages = b["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `omits the system param when there are no system messages`() {
        val b = body(ChatRequest(messages = listOf(Message(Role.User, "hi"))))
        assertNull(b["system"])
    }

    // ── Mapping #3: tool_use / tool_result blocks ───────────────────────────

    @Test
    fun `converts assistant tool_calls to tool_use blocks with parsed input`() {
        val b = body(
            ChatRequest(
                messages = listOf(
                    Message(Role.User, "weather?"),
                    Message(
                        role = Role.Assistant,
                        content = "Let me check.",
                        toolCalls = listOf(ToolCall("call_1", "get_weather", """{"city":"NYC"}""")),
                    ),
                ),
            ),
        )
        val assistant = b["messages"]!!.jsonArray[1].jsonObject
        val blocks = assistant["content"]!!.jsonArray
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        val toolUse = blocks[1].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", toolUse["id"]!!.jsonPrimitive.content)
        assertEquals("get_weather", toolUse["name"]!!.jsonPrimitive.content)
        // input is a parsed object, not the JSON string.
        assertEquals("NYC", toolUse["input"]!!.jsonObject["city"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sends tool results as tool_result blocks inside a user message, merging consecutive ones`() {
        val b = body(
            ChatRequest(
                messages = listOf(
                    Message(Role.User, "weather?"),
                    Message(Role.Tool, content = "sunny", toolCallId = "call_1"),
                    Message(Role.Tool, content = "22C", toolCallId = "call_2"),
                ),
            ),
        )
        val messages = b["messages"]!!.jsonArray
        // The two tool results merge into a single user turn.
        assertEquals(2, messages.size)
        val toolTurn = messages[1].jsonObject
        assertEquals("user", toolTurn["role"]!!.jsonPrimitive.content)
        val blocks = toolTurn["content"]!!.jsonArray
        assertEquals(2, blocks.size)
        assertEquals("tool_result", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", blocks[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("call_2", blocks[1].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a plain-text assistant turn is sent as a string, not a block array`() {
        val b = body(
            ChatRequest(
                messages = listOf(
                    Message(Role.User, "hi"),
                    Message(Role.Assistant, content = "hello there"),
                ),
            ),
        )
        val assistant = b["messages"]!!.jsonArray[1].jsonObject
        assertEquals("hello there", assistant["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `maps tools to input_schema and tool_choice required to any`() {
        val b = body(
            ChatRequest(
                messages = listOf(Message(Role.User, "x")),
                tools = listOf(
                    ToolDefinition("get_weather", "Look up weather", """{"type":"object"}"""),
                ),
                toolChoice = ToolChoice.Required,
            ),
        )
        val tool = b["tools"]!!.jsonArray[0].jsonObject
        assertEquals("get_weather", tool["name"]!!.jsonPrimitive.content)
        assertEquals("object", tool["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("any", b["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `maps stop sequences and passes sampling params only when set`() {
        val withParams = body(
            ChatRequest(
                messages = listOf(Message(Role.User, "x")),
                temperature = 0.3,
                stop = listOf("END"),
            ),
        )
        assertEquals(0.3, withParams["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals("END", withParams["stop_sequences"]!!.jsonArray[0].jsonPrimitive.content)

        val withoutParams = body(ChatRequest(messages = listOf(Message(Role.User, "x"))))
        assertNull(withoutParams["temperature"])
        assertNull(withoutParams["top_p"])
    }

    @Test
    fun `does not translate response_format — no lossless Messages-API equivalent`() {
        val b = body(
            ChatRequest(
                messages = listOf(Message(Role.User, "x")),
                responseFormat = ResponseFormat.JsonObject,
            ),
        )
        assertNull(b["response_format"])
    }

    @Test
    fun `throws a clear error for tool-call arguments that are not valid JSON`() {
        assertFailsWith<IllegalArgumentException> {
            body(
                ChatRequest(
                    messages = listOf(
                        Message(
                            role = Role.Assistant,
                            toolCalls = listOf(ToolCall("c", "f", "not json")),
                        ),
                    ),
                ),
            )
        }
    }

    // ── Mapping #4: stop_reason → finish_reason ─────────────────────────────

    @Test
    fun `maps every known stop_reason and defaults unknown to Stop`() {
        assertEquals(FinishReason.Stop, AnthropicBackend.mapStopReason("end_turn"))
        assertEquals(FinishReason.Stop, AnthropicBackend.mapStopReason("stop_sequence"))
        assertEquals(FinishReason.Length, AnthropicBackend.mapStopReason("max_tokens"))
        assertEquals(FinishReason.Length, AnthropicBackend.mapStopReason("model_context_window_exceeded"))
        assertEquals(FinishReason.ToolCalls, AnthropicBackend.mapStopReason("tool_use"))
        assertEquals(FinishReason.ContentFilter, AnthropicBackend.mapStopReason("refusal"))
        assertEquals(FinishReason.Stop, AnthropicBackend.mapStopReason("pause_turn"))
        assertEquals(FinishReason.Stop, AnthropicBackend.mapStopReason("something_new"))
        assertNull(AnthropicBackend.mapStopReason(null))
    }

    // ── Response folding ────────────────────────────────────────────────────

    @Test
    fun `folds text and tool_use content blocks into one assistant message`() {
        val root = json.parseToJsonElement(
            """{"id":"msg_1","model":"claude-test","stop_reason":"tool_use","content":[{"type":"text","text":"Let me check. "},{"type":"tool_use","id":"tu_1","name":"get_weather","input":{"city":"NYC"}}],"usage":{"input_tokens":10,"output_tokens":8}}""",
        ).jsonObject
        val response = backend.toChatResponse(root, "fallback")
        assertEquals("msg_1", response.id)
        assertEquals("claude-test", response.model)
        assertEquals("Let me check. ", response.message.content)
        assertEquals(FinishReason.ToolCalls, response.finishReason)
        assertEquals(1, response.message.toolCalls.size)
        val call = response.message.toolCalls[0]
        assertEquals("tu_1", call.id)
        assertEquals("get_weather", call.name)
        // input re-stringified back to JSON-string arguments.
        assertEquals("NYC", json.parseToJsonElement(call.arguments).jsonObject["city"]!!.jsonPrimitive.content)
        assertEquals(10, response.usage!!.promptTokens)
        assertEquals(8, response.usage!!.completionTokens)
        assertEquals(18, response.usage!!.totalTokens)
    }

    @Test
    fun `a tool-only response has null text content`() {
        val root = json.parseToJsonElement(
            """{"id":"m","model":"c","stop_reason":"tool_use","content":[{"type":"tool_use","id":"t","name":"f","input":{}}]}""",
        ).jsonObject
        val response = backend.toChatResponse(root, "fallback")
        assertNull(response.message.content)
        assertEquals(1, response.message.toolCalls.size)
    }

    // ── End-to-end wire check ───────────────────────────────────────────────

    @Test
    fun `sends x-api-key and anthropic-version to the messages endpoint`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"id":"m","model":"claude-test","stop_reason":"end_turn","content":[{"type":"text","text":"hi"}]}""",
                ),
            )
            val wired = AnthropicBackend(
                apiKey = "sk-ant",
                model = "claude-test",
                baseUrl = server.url("").toString(),
            )
            val response = wired.complete(ChatRequest(messages = listOf(Message(Role.User, "hi"))))
            assertEquals("hi", response.message.content)

            val recorded = server.takeRequest()
            assertEquals("/v1/messages", recorded.path)
            assertEquals("sk-ant", recorded.getHeader("x-api-key"))
            assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
            assertTrue(recorded.getHeader("Authorization") == null)
        } finally {
            server.shutdown()
        }
    }
}
