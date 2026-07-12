package localfirstllm

import kotlinx.coroutines.test.runTest
import localfirstllm.backends.Backend
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Orchestration + fallback tests for [HybridRouter], using fake backends so no
 * network or device is involved. The routing *decision* itself is covered
 * exhaustively by PolicyTest; here we test that the router dispatches to the
 * decided backend, enforces the privacy rule, and applies exactly one bounded
 * fallback.
 */
class HybridRouterTest {

    /** A backend that records its calls and either returns a canned reply or throws. */
    private class FakeBackend(
        override val name: String,
        private val behavior: (ChatRequest) -> ChatResponse,
    ) : Backend {
        val calls = AtomicInteger(0)

        override suspend fun complete(request: ChatRequest): ChatResponse {
            calls.incrementAndGet()
            return behavior(request)
        }

        companion object {
            fun replying(name: String, text: String) = FakeBackend(name) {
                ChatResponse(
                    id = "$name-1",
                    model = name,
                    message = Message(Role.Assistant, "$text ($name)"),
                    finishReason = FinishReason.Stop,
                )
            }

            fun failing(name: String) = FakeBackend(name) { throw RuntimeException("$name down") }
        }
    }

    private fun fixed(level: CapabilityLevel) = FixedCapabilityProvider(level)

    @Test
    fun `an on-device-preferred request routes to the on-device backend`() = runTest {
        val onDevice = FakeBackend.replying("aicore", "hi")
        val cloud = FakeBackend.replying("cloud", "hi")
        val router = HybridRouter(fixed(CapabilityLevel.OnDeviceFull), onDevice, cloud)

        val result = router.complete(
            ChatRequest(messages = listOf(Message(Role.User, "label this")), hint = TaskHint.Classification),
        )
        assertEquals(RouteMode.OnDevice, result.routedTo)
        assertEquals(1, onDevice.calls.get())
        assertEquals(0, cloud.calls.get())
        assertTrue("hint=Classification" in result.routingReason)
    }

    @Test
    fun `a cloud-preferred request routes to the cloud backend`() = runTest {
        val onDevice = FakeBackend.replying("aicore", "hi")
        val cloud = FakeBackend.replying("cloud", "hi")
        val router = HybridRouter(fixed(CapabilityLevel.OnDeviceFull), onDevice, cloud)

        val result = router.complete(
            ChatRequest(messages = listOf(Message(Role.User, "write a poem")), hint = TaskHint.Creative),
        )
        assertEquals(RouteMode.Cloud, result.routedTo)
        assertEquals(1, cloud.calls.get())
    }

    @Test
    fun `a sensitive request routes on-device and never touches cloud`() = runTest {
        val onDevice = FakeBackend.replying("aicore", "secret")
        val cloud = FakeBackend.replying("cloud", "secret")
        val router = HybridRouter(fixed(CapabilityLevel.OnDeviceFull), onDevice, cloud)

        val result = router.complete(
            ChatRequest(
                messages = listOf(Message(Role.User, "my SSN is ...")),
                hint = TaskHint.Chat, // would prefer cloud, but privacy overrides
                privacy = PrivacyLevel.Sensitive,
            ),
        )
        assertEquals(RouteMode.OnDevice, result.routedTo)
        assertEquals(0, cloud.calls.get())
    }

    @Test
    fun `a sensitive request on a not-viable device is blocked, never sent to cloud`() = runTest {
        val cloud = FakeBackend.replying("cloud", "secret")
        val router = HybridRouter(fixed(CapabilityLevel.NotViable), onDevice = null, cloud = cloud)

        val ex = assertFailsWith<RoutingBlockedException> {
            router.complete(
                ChatRequest(
                    messages = listOf(Message(Role.User, "sensitive")),
                    privacy = PrivacyLevel.Sensitive,
                ),
            )
        }
        assertEquals(RoutingBlockedCode.PrivacySensitiveOnDeviceNotViable, ex.code)
        assertEquals(0, cloud.calls.get())
    }

    @Test
    fun `a sensitive request with a viable device but no on-device backend is blocked`() = runTest {
        val cloud = FakeBackend.replying("cloud", "secret")
        val router = HybridRouter(fixed(CapabilityLevel.OnDeviceFull), onDevice = null, cloud = cloud)

        val ex = assertFailsWith<RoutingBlockedException> {
            router.complete(
                ChatRequest(
                    messages = listOf(Message(Role.User, "sensitive")),
                    privacy = PrivacyLevel.Sensitive,
                ),
            )
        }
        assertEquals(RoutingBlockedCode.PrivacySensitiveOnDeviceBackendMissing, ex.code)
        assertEquals(0, cloud.calls.get())
    }

    @Test
    fun `falls back once from a failing cloud backend to on-device`() = runTest {
        val onDevice = FakeBackend.replying("aicore", "ok")
        val cloud = FakeBackend.failing("cloud")
        val router = HybridRouter(fixed(CapabilityLevel.OnDeviceFull), onDevice, cloud)

        val result = router.complete(
            ChatRequest(messages = listOf(Message(Role.User, "chat")), hint = TaskHint.Chat),
        )
        assertEquals(RouteMode.OnDevice, result.routedTo)
        assertEquals(1, cloud.calls.get())
        assertEquals(1, onDevice.calls.get())
        assertTrue("fallback" in result.routingReason)
    }

    @Test
    fun `a sensitive request never falls back to cloud even when on-device fails`() = runTest {
        val onDevice = FakeBackend.failing("aicore")
        val cloud = FakeBackend.replying("cloud", "secret")
        val router = HybridRouter(fixed(CapabilityLevel.OnDeviceFull), onDevice, cloud)

        assertFailsWith<RuntimeException> {
            router.complete(
                ChatRequest(
                    messages = listOf(Message(Role.User, "sensitive")),
                    privacy = PrivacyLevel.Sensitive,
                ),
            )
        }
        assertEquals(1, onDevice.calls.get())
        assertEquals(0, cloud.calls.get())
    }

    @Test
    fun `fallback disabled surfaces the backend failure directly`() = runTest {
        val onDevice = FakeBackend.replying("aicore", "ok")
        val cloud = FakeBackend.failing("cloud")
        val router = HybridRouter(fixed(CapabilityLevel.OnDeviceFull), onDevice, cloud, fallback = false)

        assertFailsWith<RuntimeException> {
            router.complete(ChatRequest(messages = listOf(Message(Role.User, "chat")), hint = TaskHint.Chat))
        }
        assertEquals(0, onDevice.calls.get())
    }

    @Test
    fun `only one fallback is attempted — a failing pair does not loop`() = runTest {
        val onDevice = FakeBackend.failing("aicore")
        val cloud = FakeBackend.failing("cloud")
        val router = HybridRouter(fixed(CapabilityLevel.OnDeviceFull), onDevice, cloud)

        assertFailsWith<RuntimeException> {
            router.complete(ChatRequest(messages = listOf(Message(Role.User, "chat")), hint = TaskHint.Chat))
        }
        assertEquals(1, cloud.calls.get())
        assertEquals(1, onDevice.calls.get())
    }

    @Test
    fun `a cloud-preferred request serves on-device when only an on-device backend is configured`() = runTest {
        val onDevice = FakeBackend.replying("aicore", "ok")
        val router = HybridRouter(fixed(CapabilityLevel.OnDeviceFull), onDevice = onDevice, cloud = null)

        val result = router.complete(
            ChatRequest(messages = listOf(Message(Role.User, "chat")), hint = TaskHint.Chat),
        )
        assertEquals(RouteMode.OnDevice, result.routedTo)
        assertTrue("no cloud backend configured" in result.routingReason)
    }

    @Test
    fun `capability is probed once and cached across requests by default`() = runTest {
        val probes = AtomicInteger(0)
        val counting = object : CapabilityProvider {
            override suspend fun probe(): CapabilityLevel {
                probes.incrementAndGet()
                return CapabilityLevel.OnDeviceFull
            }
        }
        val router = HybridRouter(counting, onDevice = FakeBackend.replying("aicore", "ok"))
        repeat(3) {
            router.complete(ChatRequest(messages = listOf(Message(Role.User, "x")), hint = TaskHint.Classification))
        }
        assertEquals(1, probes.get())
    }

    @Test
    fun `capability caching can be disabled to re-probe each request`() = runTest {
        val probes = AtomicInteger(0)
        val counting = object : CapabilityProvider {
            override suspend fun probe(): CapabilityLevel {
                probes.incrementAndGet()
                return CapabilityLevel.OnDeviceFull
            }
        }
        val router = HybridRouter(
            counting,
            onDevice = FakeBackend.replying("aicore", "ok"),
            cacheCapability = false,
        )
        repeat(3) {
            router.complete(ChatRequest(messages = listOf(Message(Role.User, "x")), hint = TaskHint.Classification))
        }
        assertEquals(3, probes.get())
    }

    @Test
    fun `constructing a router with no backends fails fast`() {
        assertFailsWith<IllegalArgumentException> {
            HybridRouter(fixed(CapabilityLevel.OnDeviceFull), onDevice = null, cloud = null)
        }
    }

    @Test
    fun `input-token estimation counts message and tool-call characters`() {
        // 8 chars of content + tool name "f"(1) + args "{}"(2) = 11 chars → ceil(11/4)=3.
        val tokens = HybridRouter.estimateInputTokens(
            listOf(
                Message(Role.User, "12345678", toolCalls = listOf(ToolCall("id", "f", "{}"))),
            ),
        )
        assertEquals(3, tokens)
    }
}
