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
 * Fixture-based request/response mapping tests for [OpenAICompatibleBackend] —
 * no live network. Uses MockWebServer to capture the exact bytes sent and to
 * serve canned responses.
 */
class OpenAICompatibleBackendTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun teardown() {
        server.shutdown()
    }

    private fun backend(model: String = "gpt-test", apiKey: String? = "sk-test") =
        OpenAICompatibleBackend(
            baseUrl = server.url("/v1").toString(),
            apiKey = apiKey,
            model = model,
        )

    @Test
    fun `posts to chat completions with bearer auth and default model`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"c1","model":"gpt-test","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}""",
            ),
        )
        backend().complete(ChatRequest(messages = listOf(Message(Role.User, "hello"))))

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("gpt-test", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hello", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `omits Authorization header when no api key is set`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"c","model":"m","choices":[]}"""))
        backend(apiKey = null).complete(ChatRequest(messages = listOf(Message(Role.User, "x"))))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `serializes optional params and request-level model override`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"c","model":"m","choices":[]}"""))
        backend().complete(
            ChatRequest(
                messages = listOf(Message(Role.User, "x")),
                model = "gpt-override",
                maxTokens = 128,
                temperature = 0.5,
                topP = 0.9,
                stop = listOf("STOP"),
                responseFormat = ResponseFormat.JsonObject,
            ),
        )
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("gpt-override", body["model"]!!.jsonPrimitive.content)
        assertEquals(128, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.5, body["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.9, body["top_p"]!!.jsonPrimitive.content.toDouble())
        assertEquals("STOP", body["stop"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `serializes tools and a specific tool choice`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"c","model":"m","choices":[]}"""))
        backend().complete(
            ChatRequest(
                messages = listOf(Message(Role.User, "weather?")),
                tools = listOf(
                    ToolDefinition(
                        name = "get_weather",
                        description = "Look up weather",
                        parametersJson = """{"type":"object","properties":{"city":{"type":"string"}}}""",
                    ),
                ),
                toolChoice = ToolChoice.Function("get_weather"),
            ),
        )
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        // parameters carried through as a JSON object, not a string.
        assertEquals(
            "object",
            tool["function"]!!.jsonObject["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        val choice = body["tool_choice"]!!.jsonObject
        assertEquals("get_weather", choice["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses a text completion including usage and finish reason`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"chatcmpl-1","model":"gpt-4o-mini","choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}""",
            ),
        )
        val response = backend().complete(ChatRequest(messages = listOf(Message(Role.User, "hi"))))
        assertEquals("chatcmpl-1", response.id)
        assertEquals("gpt-4o-mini", response.model)
        assertEquals("Hello!", response.message.content)
        assertEquals(FinishReason.Stop, response.finishReason)
        assertEquals(5, response.usage!!.promptTokens)
        assertEquals(7, response.usage!!.totalTokens)
    }

    @Test
    fun `parses a tool-call completion with null content`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"c2","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"NYC\"}"}}]},"finish_reason":"tool_calls"}]}""",
            ),
        )
        val response = backend().complete(ChatRequest(messages = listOf(Message(Role.User, "weather?"))))
        assertNull(response.message.content)
        assertEquals(FinishReason.ToolCalls, response.finishReason)
        assertEquals(1, response.message.toolCalls.size)
        val call = response.message.toolCalls[0]
        assertEquals("call_1", call.id)
        assertEquals("get_weather", call.name)
        assertEquals("""{"city":"NYC"}""", call.arguments)
    }

    @Test
    fun `throws BackendHttpException on a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        val ex = assertFailsWith<BackendHttpException> {
            backend().complete(ChatRequest(messages = listOf(Message(Role.User, "x"))))
        }
        assertEquals(429, ex.status)
        assertEquals("openai-compatible", ex.backend)
        assertTrue("rate limited" in ex.body)
    }
}
