package localfirstllm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Branch-coverage tests for [decide], mirroring the decision tree of
 * local-first-llm's `decide.ts`:
 *
 * 1. privacy gate (hard rule)
 * 2. taxonomy / no-hint heuristic
 * 3. capability cross-check
 * 4. budget check
 * 5. reason strings
 */
class PolicyPrivacyGateTest {

    @Test
    fun `sensitive with full capability routes on-device`() {
        val d = decide(PolicyInput(capability = CapabilityLevel.OnDeviceFull, privacy = PrivacyLevel.Sensitive))
        val onDevice = assertIs<RoutingDecision.OnDevice>(d)
        assertTrue("never leave the device" in onDevice.reason)
    }

    @Test
    fun `sensitive with degraded capability still routes on-device`() {
        val d = decide(PolicyInput(capability = CapabilityLevel.OnDeviceDegraded, privacy = PrivacyLevel.Sensitive))
        assertIs<RoutingDecision.OnDevice>(d)
    }

    @Test
    fun `sensitive overrides a cloud-preferring hint`() {
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Chat,
                privacy = PrivacyLevel.Sensitive,
            ),
        )
        assertIs<RoutingDecision.OnDevice>(d)
    }

    @Test
    fun `sensitive with not-viable capability blocks with typed code`() {
        val d = decide(PolicyInput(capability = CapabilityLevel.NotViable, privacy = PrivacyLevel.Sensitive))
        val blocked = assertIs<RoutingDecision.Blocked>(d)
        assertEquals(BlockedCode.PrivacySensitiveOnDeviceNotViable, blocked.code)
        assertTrue("never sent to the cloud" in blocked.reason)
    }

    @Test
    fun `sensitive with not-viable capability blocks even for an on-device-preferring hint`() {
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.NotViable,
                hint = TaskHint.Classification,
                privacy = PrivacyLevel.Sensitive,
            ),
        )
        assertIs<RoutingDecision.Blocked>(d)
    }

    @Test
    fun `privacy defaults to Normal — an unhinted small request is not treated as sensitive`() {
        // With the default privacy, a NotViable device must fall back to cloud
        // (a Sensitive request would have been Blocked instead).
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.NotViable,
                estimatedInputTokens = 10,
                maxTokens = 50,
            ),
        )
        assertIs<RoutingDecision.Cloud>(d)
    }

    @Test
    fun `sensitive overrides the budget check — never switched to cloud by a budget`() {
        // An impossible latency budget would normally trigger a switch attempt;
        // the privacy gate must short-circuit before the budget check runs.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceDegraded,
                privacy = PrivacyLevel.Sensitive,
                maxTokens = 2000,
                latencyBudgetMs = 1,
            ),
        )
        val onDevice = assertIs<RoutingDecision.OnDevice>(d)
        assertTrue("budget" !in onDevice.reason)
    }
}

class PolicyTaxonomyTest {

    private fun hint(hint: TaskHint, capability: CapabilityLevel) =
        decide(PolicyInput(capability = capability, hint = hint))

    @Test
    fun `classification, extraction, summarization prefer on-device with full capability`() {
        for (h in listOf(TaskHint.Classification, TaskHint.Extraction, TaskHint.Summarization)) {
            val d = hint(h, CapabilityLevel.OnDeviceFull)
            val onDevice = assertIs<RoutingDecision.OnDevice>(d, "hint=$h")
            assertTrue("hint=$h" in onDevice.reason)
        }
    }

    @Test
    fun `chat, reasoning, creative prefer cloud with full capability`() {
        for (h in listOf(TaskHint.Chat, TaskHint.Reasoning, TaskHint.Creative)) {
            val d = hint(h, CapabilityLevel.OnDeviceFull)
            val cloud = assertIs<RoutingDecision.Cloud>(d, "hint=$h")
            assertTrue("hint=$h" in cloud.reason)
        }
    }

    @Test
    fun `on-device-preferring hints fall back to cloud when not viable`() {
        for (h in listOf(TaskHint.Classification, TaskHint.Extraction, TaskHint.Summarization)) {
            val d = hint(h, CapabilityLevel.NotViable)
            val cloud = assertIs<RoutingDecision.Cloud>(d, "hint=$h")
            assertTrue("falling back to cloud" in cloud.reason)
        }
    }

    @Test
    fun `cloud-preferring hints stay cloud when not viable`() {
        for (h in listOf(TaskHint.Chat, TaskHint.Reasoning, TaskHint.Creative)) {
            assertIs<RoutingDecision.Cloud>(hint(h, CapabilityLevel.NotViable), "hint=$h")
        }
    }

    @Test
    fun `degraded capability still honors an on-device preference`() {
        for (h in listOf(TaskHint.Classification, TaskHint.Extraction, TaskHint.Summarization)) {
            assertIs<RoutingDecision.OnDevice>(hint(h, CapabilityLevel.OnDeviceDegraded), "hint=$h")
        }
    }

    @Test
    fun `degraded capability leaves cloud preferences on cloud`() {
        for (h in listOf(TaskHint.Chat, TaskHint.Reasoning, TaskHint.Creative)) {
            assertIs<RoutingDecision.Cloud>(hint(h, CapabilityLevel.OnDeviceDegraded), "hint=$h")
        }
    }

    @Test
    fun `taxonomy override redirects a hint`() {
        val config = PolicyConfig(taxonomyOverrides = mapOf(TaskHint.Chat to RouteMode.OnDevice))
        val d = decide(PolicyInput(capability = CapabilityLevel.OnDeviceFull, hint = TaskHint.Chat), config)
        assertIs<RoutingDecision.OnDevice>(d)
    }

    @Test
    fun `taxonomy override also steers the no-hint heuristic`() {
        // The heuristic resolves through the (overridden) taxonomy table, so
        // overriding `classification` re-routes classification-like requests.
        val config = PolicyConfig(taxonomyOverrides = mapOf(TaskHint.Classification to RouteMode.Cloud))
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                estimatedInputTokens = 10,
                maxTokens = 50,
            ),
            config,
        )
        assertIs<RoutingDecision.Cloud>(d)
    }

    @Test
    fun `taxonomy override steers the chat-like side of the heuristic too`() {
        // The else-branch of the heuristic resolves through the (overridden)
        // taxonomy table via TaskHint.Chat, so overriding `chat` re-routes
        // chat-like requests. A chat-like shape (tools requested) with a
        // Chat→OnDevice override must route on-device — guarding against a
        // regression that hardcodes cloud for the chat-like branch.
        val config = PolicyConfig(taxonomyOverrides = mapOf(TaskHint.Chat to RouteMode.OnDevice))
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                estimatedInputTokens = 100,
                maxTokens = 64,
                hasTools = true,
            ),
            config,
        )
        val onDevice = assertIs<RoutingDecision.OnDevice>(d)
        assertTrue("chat-like" in onDevice.reason)
    }
}

class PolicyHeuristicTest {

    private fun shape(
        inputTokens: Int,
        maxTokens: Int?,
        hasTools: Boolean = false,
        wantsStructuredOutput: Boolean = false,
        capability: CapabilityLevel = CapabilityLevel.OnDeviceFull,
        config: PolicyConfig = PolicyConfig(),
    ) = decide(
        PolicyInput(
            capability = capability,
            estimatedInputTokens = inputTokens,
            maxTokens = maxTokens,
            hasTools = hasTools,
            wantsStructuredOutput = wantsStructuredOutput,
        ),
        config,
    )

    @Test
    fun `short input, no tools, modest max output is classification-like — on-device`() {
        val d = shape(inputTokens = 100, maxTokens = 64)
        val onDevice = assertIs<RoutingDecision.OnDevice>(d)
        assertTrue("classification-like" in onDevice.reason)
    }

    @Test
    fun `thresholds are inclusive — exactly 512 input and 256 output is still classification-like`() {
        assertIs<RoutingDecision.OnDevice>(shape(inputTokens = 512, maxTokens = 256))
    }

    @Test
    fun `input one token over the threshold flips to chat-like — cloud`() {
        val d = shape(inputTokens = 513, maxTokens = 256)
        val cloud = assertIs<RoutingDecision.Cloud>(d)
        assertTrue("chat-like" in cloud.reason)
    }

    @Test
    fun `max output one token over the threshold flips to chat-like — cloud`() {
        assertIs<RoutingDecision.Cloud>(shape(inputTokens = 512, maxTokens = 257))
    }

    @Test
    fun `tool use flips an otherwise small request to chat-like — cloud`() {
        assertIs<RoutingDecision.Cloud>(shape(inputTokens = 100, maxTokens = 64, hasTools = true))
    }

    @Test
    fun `no max output cap assumes 1024 tokens — chat-like, cloud`() {
        val d = shape(inputTokens = 100, maxTokens = null)
        val cloud = assertIs<RoutingDecision.Cloud>(d)
        assertTrue("maxTokens=1024" in cloud.reason)
    }

    @Test
    fun `structured output request does not affect the heuristic`() {
        assertIs<RoutingDecision.OnDevice>(
            shape(inputTokens = 100, maxTokens = 64, wantsStructuredOutput = true),
        )
    }

    @Test
    fun `classification-like heuristic still falls back to cloud when not viable`() {
        val d = shape(inputTokens = 100, maxTokens = 64, capability = CapabilityLevel.NotViable)
        val cloud = assertIs<RoutingDecision.Cloud>(d)
        assertTrue("falling back to cloud" in cloud.reason)
    }

    @Test
    fun `classification-like heuristic routes on-device on a degraded device`() {
        assertIs<RoutingDecision.OnDevice>(
            shape(inputTokens = 100, maxTokens = 64, capability = CapabilityLevel.OnDeviceDegraded),
        )
    }

    @Test
    fun `heuristic thresholds are overridable`() {
        val tight = PolicyConfig(heuristic = HeuristicThresholds(maxInputTokens = 50))
        assertIs<RoutingDecision.Cloud>(shape(inputTokens = 100, maxTokens = 64, config = tight))
    }

    @Test
    fun `assumed max tokens is overridable — a small assumption keeps uncapped requests on-device`() {
        val config = PolicyConfig(heuristic = HeuristicThresholds(assumedMaxTokens = 100))
        assertIs<RoutingDecision.OnDevice>(shape(inputTokens = 100, maxTokens = null, config = config))
    }
}

class PolicyBudgetTest {

    @Test
    fun `latency budget switches an on-device preference to cloud`() {
        // on-device: 250 + 900/15×1000 = 60250ms; cloud: 600 + 900/40×1000 = 23100ms.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Summarization,
                estimatedInputTokens = 100,
                maxTokens = 900,
                latencyBudgetMs = 30_000,
            ),
        )
        val cloud = assertIs<RoutingDecision.Cloud>(d)
        assertTrue("switched to Cloud" in cloud.reason)
    }

    @Test
    fun `latency budget respected — no switch when the estimate fits`() {
        // on-device: 250 + 100/15×1000 ≈ 6917ms ≤ 10000ms.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Extraction,
                estimatedInputTokens = 50,
                maxTokens = 100,
                latencyBudgetMs = 10_000,
            ),
        )
        val onDevice = assertIs<RoutingDecision.OnDevice>(d)
        assertTrue("budget" !in onDevice.reason)
    }

    @Test
    fun `degraded capability uses the slower decode estimate — same request switches`() {
        // degraded: 250 + 100/5×1000 = 20250ms > 10000ms; cloud: 600 + 2500 = 3100ms.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceDegraded,
                hint = TaskHint.Extraction,
                estimatedInputTokens = 50,
                maxTokens = 100,
                latencyBudgetMs = 10_000,
            ),
        )
        assertIs<RoutingDecision.Cloud>(d)
    }

    @Test
    fun `cost budget switches a cloud preference to on-device`() {
        // cloud: 1000/1e6×$1 + 1000/1e6×$4 = $0.005 > $0.001; on-device: $0.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Chat,
                estimatedInputTokens = 1000,
                maxTokens = 1000,
                costBudgetUsd = 0.001,
            ),
        )
        val onDevice = assertIs<RoutingDecision.OnDevice>(d)
        assertTrue("switched to OnDevice" in onDevice.reason)
    }

    @Test
    fun `cost budget violation with a not-viable device keeps cloud — no alternative`() {
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.NotViable,
                hint = TaskHint.Chat,
                estimatedInputTokens = 1000,
                maxTokens = 1000,
                costBudgetUsd = 0.001,
            ),
        )
        val cloud = assertIs<RoutingDecision.Cloud>(d)
        assertTrue("no viable alternative" in cloud.reason)
    }

    @Test
    fun `unsatisfiable budget keeps the policy choice rather than blocking`() {
        // 1ms is under both fixed overheads: neither mode can satisfy it.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Chat,
                estimatedInputTokens = 100,
                maxTokens = 100,
                latencyBudgetMs = 1,
            ),
        )
        val cloud = assertIs<RoutingDecision.Cloud>(d)
        assertTrue("no viable alternative" in cloud.reason)
    }

    @Test
    fun `the other mode must satisfy every set budget, not just the violated one`() {
        // cloud violates the cost budget; on-device is free but at
        // 250 + 1000/15×1000 ≈ 66917ms violates the latency budget → keep cloud.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Chat,
                estimatedInputTokens = 1000,
                maxTokens = 1000,
                costBudgetUsd = 0.001,
                latencyBudgetMs = 30_000,
            ),
        )
        assertIs<RoutingDecision.Cloud>(d)
    }

    @Test
    fun `router-level config budget applies when the request sets none`() {
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Summarization,
                estimatedInputTokens = 100,
                maxTokens = 900,
            ),
            PolicyConfig(latencyBudgetMs = 30_000),
        )
        assertIs<RoutingDecision.Cloud>(d)
    }

    @Test
    fun `per-request budget overrides the router-level config budget`() {
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Summarization,
                estimatedInputTokens = 100,
                maxTokens = 900,
                latencyBudgetMs = 100_000,
            ),
            PolicyConfig(latencyBudgetMs = 30_000),
        )
        assertIs<RoutingDecision.OnDevice>(d)
    }

    @Test
    fun `a tighter per-request budget wins over a looser config budget — not most-permissive`() {
        // Reverse direction of the test above: per-request is TIGHTER than the
        // config budget. "per-request wins" must yield the per-request outcome
        // (Cloud), not a max()/most-permissive merge (which would keep OnDevice).
        // on-device: 250 + 900/15×1000 = 60250ms > 30000; cloud: 600 + 900/40×1000 = 23100ms ≤ 30000.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Summarization,
                estimatedInputTokens = 100,
                maxTokens = 900,
                latencyBudgetMs = 30_000,
            ),
            PolicyConfig(latencyBudgetMs = 100_000),
        )
        assertIs<RoutingDecision.Cloud>(d)
    }

    @Test
    fun `router-level cost budget applies when the request sets none`() {
        // Exercises the config.costBudgetUsd fallback specifically (the request
        // sets no cost budget). cloud: 1000/1e6×$1 + 1000/1e6×$4 = $0.005 > $0.001;
        // on-device: $0 → switch to on-device.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Chat,
                estimatedInputTokens = 1000,
                maxTokens = 1000,
            ),
            PolicyConfig(costBudgetUsd = 0.001),
        )
        val onDevice = assertIs<RoutingDecision.OnDevice>(d)
        assertTrue("switched to OnDevice" in onDevice.reason)
    }

    @Test
    fun `a tighter per-request cost budget wins over a looser config cost budget`() {
        // per-request $0.001 (violated by cloud's $0.005) is tighter than config
        // $1.0 (satisfied). "per-request wins" must switch to on-device.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Chat,
                estimatedInputTokens = 1000,
                maxTokens = 1000,
                costBudgetUsd = 0.001,
            ),
            PolicyConfig(costBudgetUsd = 1.0),
        )
        assertIs<RoutingDecision.OnDevice>(d)
    }

    @Test
    fun `per-field budget fallback — request latency and config cost both apply`() {
        // The request sets only latency; the config sets only cost. Both must be
        // in force (per-field fallback), not "any per-request budget suppresses
        // all config budgets". Here the cost budget (config) forces the switch
        // off cloud while the latency budget (request) is generous.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Chat,
                estimatedInputTokens = 1000,
                maxTokens = 1000,
                latencyBudgetMs = 1_000_000,
            ),
            PolicyConfig(costBudgetUsd = 0.001),
        )
        val onDevice = assertIs<RoutingDecision.OnDevice>(d)
        assertTrue("switched to OnDevice" in onDevice.reason)
    }

    @Test
    fun `budget check runs after the capability fallback — evaluates the fallen-back mode`() {
        // Classification prefers on-device, NotViable falls back to cloud, and
        // the budget check must then estimate *cloud*, find the cost budget
        // violated, and keep cloud because on-device is not viable.
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.NotViable,
                hint = TaskHint.Classification,
                estimatedInputTokens = 1000,
                maxTokens = 200,
                costBudgetUsd = 0.0001,
            ),
        )
        val cloud = assertIs<RoutingDecision.Cloud>(d)
        assertTrue("falling back to cloud" in cloud.reason)
        assertTrue("no viable alternative" in cloud.reason)
    }

    @Test
    fun `estimates are overridable — a fast device estimate avoids the switch`() {
        val config = PolicyConfig(
            estimates = RoutingEstimates(onDeviceTokensPerSecondFull = 1000.0),
        )
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Summarization,
                estimatedInputTokens = 100,
                maxTokens = 900,
                latencyBudgetMs = 30_000,
            ),
            config,
        )
        // on-device: 250 + 900/1000×1000 = 1150ms ≤ 30000ms.
        assertIs<RoutingDecision.OnDevice>(d)
    }

    @Test
    fun `no budgets set means no budget notes in the reason`() {
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Summarization,
                estimatedInputTokens = 100,
                maxTokens = 900,
            ),
        )
        val onDevice = assertIs<RoutingDecision.OnDevice>(d)
        assertTrue("budget" !in onDevice.reason)
    }
}

class PolicyReasonTest {

    @Test
    fun `reasons carry the hint and capability facts`() {
        val d = decide(PolicyInput(capability = CapabilityLevel.OnDeviceDegraded, hint = TaskHint.Extraction))
        assertTrue("hint=Extraction" in d.reason)
        assertTrue("capability=OnDeviceDegraded" in d.reason)
    }

    @Test
    fun `budget switch reasons carry both estimates`() {
        val d = decide(
            PolicyInput(
                capability = CapabilityLevel.OnDeviceFull,
                hint = TaskHint.Summarization,
                estimatedInputTokens = 100,
                maxTokens = 900,
                latencyBudgetMs = 30_000,
            ),
        )
        assertTrue("~60250ms" in d.reason)
        assertTrue("~23100ms" in d.reason)
    }

    @Test
    fun `every decision variant carries a non-empty reason`() {
        val decisions = listOf(
            decide(PolicyInput(capability = CapabilityLevel.OnDeviceFull, hint = TaskHint.Extraction)),
            decide(PolicyInput(capability = CapabilityLevel.OnDeviceFull, hint = TaskHint.Chat)),
            decide(PolicyInput(capability = CapabilityLevel.NotViable, privacy = PrivacyLevel.Sensitive)),
            decide(PolicyInput(capability = CapabilityLevel.NotViable)),
        )
        for (d in decisions) {
            assertTrue(d.reason.isNotBlank(), "decision $d must carry a human-readable reason")
        }
    }

    @Test
    fun `decide is deterministic — identical inputs produce identical decisions`() {
        val input = PolicyInput(
            capability = CapabilityLevel.OnDeviceDegraded,
            hint = TaskHint.Summarization,
            estimatedInputTokens = 300,
            maxTokens = 900,
            latencyBudgetMs = 30_000,
        )
        assertEquals(decide(input), decide(input))
    }

    @Test
    fun `default taxonomy matches the documented table`() {
        assertEquals(RouteMode.OnDevice, defaultTaxonomy.getValue(TaskHint.Classification))
        assertEquals(RouteMode.OnDevice, defaultTaxonomy.getValue(TaskHint.Extraction))
        assertEquals(RouteMode.OnDevice, defaultTaxonomy.getValue(TaskHint.Summarization))
        assertEquals(RouteMode.Cloud, defaultTaxonomy.getValue(TaskHint.Chat))
        assertEquals(RouteMode.Cloud, defaultTaxonomy.getValue(TaskHint.Reasoning))
        assertEquals(RouteMode.Cloud, defaultTaxonomy.getValue(TaskHint.Creative))
    }
}
