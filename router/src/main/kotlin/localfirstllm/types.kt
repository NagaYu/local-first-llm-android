/*
 * Types for the policy engine. Everything here is I/O-free: the policy engine
 * ([decide]) is a pure, synchronous function over these shapes so that every
 * routing decision is unit-testable without a device, a model, or a network.
 *
 * This is a faithful Kotlin port of local-first-llm's `src/policy/types.ts`
 * (https://github.com/NagaYu/local-first-llm) — same shapes, same defaults,
 * same semantics, translated to Kotlin idioms (enums, sealed classes, data
 * classes with default arguments in place of TypeScript `Partial` merging).
 */
package localfirstllm

/**
 * How capable the current device is of running on-device inference, as
 * reported by the active [CapabilityProvider].
 *
 * - [OnDeviceFull] — the device can run the on-device backend at usable speed.
 * - [OnDeviceDegraded] — the device can run it, but slower / under pressure
 *   (e.g. thermal throttling, low quota headroom, or a smaller measured
 *   context window). Usable; the latency tradeoff is handled by the budget
 *   check, not by refusing to route on-device.
 * - [NotViable] — on-device inference is not realistically possible here
 *   (AICore unavailable, unsupported device, model not downloadable).
 */
public enum class CapabilityLevel {
    OnDeviceFull,
    OnDeviceDegraded,
    NotViable,
}

/**
 * Optional developer-supplied task-type hint. When present it selects the
 * preferred route from the taxonomy table directly; when absent, a documented
 * heuristic over the request shape is used instead. See [defaultTaxonomy].
 */
public enum class TaskHint {
    Classification,
    Extraction,
    Summarization,
    Chat,
    Reasoning,
    Creative,
}

/**
 * Privacy marking for a request. [Sensitive] is a hard rule, not a
 * preference: a sensitive request is either served on-device or blocked — it
 * is never sent to the cloud, regardless of hints, capability quality, or
 * budgets.
 */
public enum class PrivacyLevel {
    Sensitive,
    Normal,
}

/** Where a request can actually be served. */
public enum class RouteMode {
    OnDevice,
    Cloud,
}

/** Machine-readable reason codes for a [RoutingDecision.Blocked] decision. */
public enum class BlockedCode {
    PrivacySensitiveOnDeviceNotViable,
}

/**
 * The output of [decide]. [reason] is always a human-readable summary of the
 * inputs and the decisive rule, suitable for logs and UIs
 * (e.g. `"hint=Classification, capability=OnDeviceFull"`).
 */
public sealed class RoutingDecision {
    /** Human-readable summary of the inputs and the decisive rule. */
    public abstract val reason: String

    /** Serve the request with the on-device backend. */
    public data class OnDevice(override val reason: String) : RoutingDecision()

    /** Serve the request with the cloud backend. */
    public data class Cloud(override val reason: String) : RoutingDecision()

    /**
     * The request cannot be served under the current policy: it is
     * privacy-sensitive and the device cannot run inference locally.
     * [code] is the typed cause, so callers can branch without parsing
     * [reason].
     */
    public data class Blocked(
        override val reason: String,
        val code: BlockedCode,
    ) : RoutingDecision()
}

/**
 * Everything [decide] is allowed to know about one request. The router
 * derives this from the chat request plus the capability probe; advanced
 * users calling [decide] directly supply it themselves.
 *
 * @property capability Result of the active capability provider's probe.
 * @property hint Optional explicit task type; see [TaskHint].
 * @property privacy Defaults to [PrivacyLevel.Normal]. See [PrivacyLevel].
 * @property estimatedInputTokens Rough size of the prompt in tokens.
 *   Precision is not required — the default heuristic and estimates only need
 *   the right order of magnitude. The router estimates this as message
 *   characters / 4.
 * @property maxTokens The request's max output tokens, if set. When null, the
 *   policy assumes [HeuristicThresholds.assumedMaxTokens] (default 1024) for
 *   both the no-hint heuristic and cost/latency estimation, because an
 *   uncapped request must be treated as potentially long-output.
 * @property hasTools Whether the request asks for tool / function calling.
 * @property wantsStructuredOutput Whether the request asks for structured /
 *   JSON output. Collected for observability and custom policies; the default
 *   heuristic does not branch on it (structured output alone is neither
 *   evidence for nor against on-device viability).
 * @property costBudgetUsd Maximum acceptable estimated cost for this request,
 *   in **USD**. On-device inference is modeled as $0 marginal cost. Overrides
 *   any router-level [PolicyConfig.costBudgetUsd].
 * @property latencyBudgetMs Maximum acceptable estimated
 *   time-to-full-completion, in **milliseconds**. Overrides any router-level
 *   [PolicyConfig.latencyBudgetMs].
 */
public data class PolicyInput(
    val capability: CapabilityLevel,
    val hint: TaskHint? = null,
    val privacy: PrivacyLevel = PrivacyLevel.Normal,
    val estimatedInputTokens: Int = 0,
    val maxTokens: Int? = null,
    val hasTools: Boolean = false,
    val wantsStructuredOutput: Boolean = false,
    val costBudgetUsd: Double? = null,
    val latencyBudgetMs: Long? = null,
)

/**
 * Thresholds for the no-hint heuristic (decision-tree step 2 when
 * [PolicyInput.hint] is absent): a request is treated like
 * [TaskHint.Classification] (on-device-preferred) iff **all** of the
 * following hold, otherwise it is treated like [TaskHint.Chat]
 * (cloud-preferred):
 *
 * 1. `estimatedInputTokens <= maxInputTokens` — small quantized models handle
 *    short prompts well; long prompts both slow prefill and degrade quality.
 *    512 tokens ≈ 1.5 pages of text: enough for a label/lookup-style request,
 *    comfortably under small-model context pressure.
 * 2. no tools requested — reliable function-calling is a known weak spot of
 *    small on-device models.
 * 3. effective `maxTokens <= maxOutputTokens` — 256 tokens ≈ a short
 *    paragraph; bounded output is where small models stay consistent. When
 *    the request sets no `maxTokens`, [assumedMaxTokens] (1024) is used,
 *    which intentionally fails this test: an uncapped request is open-ended
 *    by construction and is treated like [TaskHint.Chat].
 *
 * All bounds are inclusive. The default values below are the documented
 * defaults; override individual values via `copy()` on a default instance or
 * by constructing your own in [PolicyConfig.heuristic].
 *
 * @property maxInputTokens Inclusive upper bound on
 *   [PolicyInput.estimatedInputTokens] for a request to be treated as
 *   classification-like (on-device-preferred).
 * @property maxOutputTokens Inclusive upper bound on effective `maxTokens`
 *   for a request to be treated as classification-like (on-device-preferred).
 * @property assumedMaxTokens `maxTokens` assumed when the request does not
 *   set one — used by both the heuristic and cost/latency estimation.
 */
public data class HeuristicThresholds(
    val maxInputTokens: Int = 512,
    val maxOutputTokens: Int = 256,
    val assumedMaxTokens: Int = 1024,
)

/**
 * Constants for the deliberately simple cost/latency estimation used by the
 * budget check (step 4 of the decision tree). These are order-of-magnitude
 * defaults, not measurements — override them with numbers from your own
 * backends when budget routing matters to you.
 *
 * Estimation model (documented contract, see [decide]):
 * - latency(on-device) = [onDeviceOverheadMs] + outputTokens / tokensPerSecond × 1000,
 *   where tokensPerSecond is the full or degraded figure per capability.
 * - latency(cloud)     = [cloudOverheadMs] + outputTokens / [cloudTokensPerSecond] × 1000
 * - cost(on-device)    = $0 (marginal dollar cost of local compute)
 * - cost(cloud)        = inputTokens/1e6 × [cloudUsdPerMillionInputTokens]
 *                      + outputTokens/1e6 × [cloudUsdPerMillionOutputTokens]
 * - outputTokens = the request's `maxTokens`, else
 *   [HeuristicThresholds.assumedMaxTokens].
 *
 * Default rationale:
 * - 15 tok/s full / 5 tok/s degraded: typical range for small quantized
 *   on-device models (Gemini Nano-class) between a healthy accelerator path
 *   and a constrained one. Real devices vary widely — override with your own
 *   measurements (e.g. from aicore-radar's latency benchmark) if budget
 *   routing matters.
 * - 250ms on-device overhead assumes a *warm* engine; first-use model
 *   download is minutes, not milliseconds, and is out of scope here.
 * - 600ms cloud overhead ≈ network round-trip + queueing + time-to-first-token.
 * - 40 tok/s cloud decode: mid-range hosted-API streaming speed.
 * - $1 / $4 per million input/output tokens: a generic mid-tier hosted-model
 *   price point, chosen only as a plausible default. Not tied to any specific
 *   provider — set your actual prices via [PolicyConfig.estimates].
 *
 * @property onDeviceTokensPerSecondFull Decode speed assumed for
 *   [CapabilityLevel.OnDeviceFull], tokens/second.
 * @property onDeviceTokensPerSecondDegraded Decode speed assumed for
 *   [CapabilityLevel.OnDeviceDegraded], tokens/second.
 * @property onDeviceOverheadMs Fixed on-device overhead (prefill, scheduling)
 *   per request, ms. Assumes a warm engine.
 * @property cloudTokensPerSecond Cloud decode speed, tokens/second.
 * @property cloudOverheadMs Fixed cloud overhead (network round-trip +
 *   time-to-first-token), ms.
 * @property cloudUsdPerMillionInputTokens Cloud price per million input
 *   tokens, USD.
 * @property cloudUsdPerMillionOutputTokens Cloud price per million output
 *   tokens, USD.
 */
public data class RoutingEstimates(
    val onDeviceTokensPerSecondFull: Double = 15.0,
    val onDeviceTokensPerSecondDegraded: Double = 5.0,
    val onDeviceOverheadMs: Double = 250.0,
    val cloudTokensPerSecond: Double = 40.0,
    val cloudOverheadMs: Double = 600.0,
    val cloudUsdPerMillionInputTokens: Double = 1.0,
    val cloudUsdPerMillionOutputTokens: Double = 4.0,
)

/**
 * Optional overrides for the policy engine. Anything not supplied falls back
 * to the documented defaults ([defaultTaxonomy], [HeuristicThresholds],
 * [RoutingEstimates]).
 *
 * @property taxonomyOverrides Override preferred routes per task hint, merged
 *   over [defaultTaxonomy] (e.g. `mapOf(TaskHint.Chat to RouteMode.OnDevice)`).
 * @property heuristic The no-hint heuristic thresholds.
 * @property estimates The cost/latency estimation constants.
 * @property costBudgetUsd Router-level default cost budget (USD);
 *   per-request [PolicyInput.costBudgetUsd] wins.
 * @property latencyBudgetMs Router-level default latency budget (ms);
 *   per-request [PolicyInput.latencyBudgetMs] wins.
 */
public data class PolicyConfig(
    val taxonomyOverrides: Map<TaskHint, RouteMode> = emptyMap(),
    val heuristic: HeuristicThresholds = HeuristicThresholds(),
    val estimates: RoutingEstimates = RoutingEstimates(),
    val costBudgetUsd: Double? = null,
    val latencyBudgetMs: Long? = null,
)
