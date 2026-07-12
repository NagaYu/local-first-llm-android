/*
 * HybridRouter — the orchestration layer that ties a capability provider, the
 * pure policy engine ([decide]), and the configured backends together behind a
 * single suspend `complete()`.
 *
 * Responsibilities (and nothing more), mirroring local-first-llm's `router.ts`:
 *   1. Probe device capability (cached for the router's lifetime by default).
 *   2. Derive a [PolicyInput] from the request and run [decide].
 *   3. Dispatch to the chosen backend; on failure, retry ONCE against the other
 *      mode when policy allows it (never cloud for sensitive requests).
 *   4. Attach [RoutedResponse.routedTo] / [RoutedResponse.routingReason] to
 *      every result so callers can observe what actually happened.
 */
package localfirstllm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import localfirstllm.backends.Backend
import kotlin.math.ceil

/**
 * Rough token estimation feeding the policy heuristic: total message characters
 * (plus tool-call arguments/names) / 4, a common rule of thumb for English.
 * Precision is not needed — the heuristic only cares about order of magnitude.
 */
public const val CHARS_PER_TOKEN_ESTIMATE: Int = 4

/** Machine-readable causes for a [RoutingBlockedException]. */
public enum class RoutingBlockedCode {
    /** Sensitive request, but the device cannot run inference locally. */
    PrivacySensitiveOnDeviceNotViable,

    /** Sensitive request routed on-device, but no on-device backend is configured. */
    PrivacySensitiveOnDeviceBackendMissing,
}

/**
 * Thrown when a request cannot be served without violating the privacy rule.
 * This is deliberately an exception, not a silent cloud fallback: sensitive
 * requests never reach the cloud backend, full stop.
 */
public class RoutingBlockedException(
    public val code: RoutingBlockedCode,
    public val reason: String,
) : Exception("routing blocked ($code): $reason")

/**
 * A provider-agnostic hybrid on-device/cloud router.
 *
 * ```kotlin
 * val router = HybridRouter(
 *     capability = capabilityProvider,
 *     onDevice = AICoreBackend(),
 *     cloud = OpenAICompatibleBackend(baseUrl = "...", apiKey = "...", model = "..."),
 * )
 * val result = router.complete(
 *     ChatRequest(
 *         messages = listOf(Message(Role.User, "...")),
 *         hint = TaskHint.Classification,
 *         privacy = PrivacyLevel.Sensitive,
 *     ),
 * )
 * // result.routedTo, result.routingReason, result.message
 * ```
 *
 * At least one of [onDevice] / [cloud] must be provided.
 *
 * @property capability Capability provider; probed lazily and (by default) cached.
 * @property onDevice Backend for on-device inference, or null if unconfigured.
 * @property cloud Backend for cloud inference, or null if unconfigured.
 * @property policy Policy overrides (taxonomy, thresholds, estimates, budgets).
 * @property fallback When true (default), retry once against the other mode if
 *   the chosen backend throws — bounded to a single retry, and never routing a
 *   sensitive request to cloud.
 * @property cacheCapability Cache the capability probe for the router's lifetime
 *   (default true). Set false to re-probe per request if capability genuinely
 *   fluctuates in your environment.
 */
public class HybridRouter(
    private val capability: CapabilityProvider,
    private val onDevice: Backend? = null,
    private val cloud: Backend? = null,
    private val policy: PolicyConfig = PolicyConfig(),
    private val fallback: Boolean = true,
    private val cacheCapability: Boolean = true,
) {
    init {
        require(onDevice != null || cloud != null) {
            "HybridRouter: configure at least one backend (onDevice and/or cloud)"
        }
    }

    // Cached probe. A suspend probe cached across coroutines: guard with a mutex
    // so a concurrent first burst probes once, not N times.
    private val probeMutex = Mutex()

    @Volatile
    private var cachedCapability: CapabilityLevel? = null

    private suspend fun getCapability(): CapabilityLevel {
        if (!cacheCapability) return capability.probe()
        cachedCapability?.let { return it }
        return probeMutex.withLock {
            cachedCapability ?: capability.probe().also { cachedCapability = it }
        }
    }

    /** Run one completion, applying the routing policy and bounded fallback. */
    public suspend fun complete(request: ChatRequest): RoutedResponse {
        val plan = plan(request)
        return try {
            val response = plan.backend.complete(request)
            RoutedResponse.of(response, plan.mode, plan.reasons.joinToString("; "))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val retry = plan.retry ?: throw error
            val response = retry.backend.complete(request)
            val note = "fallback: ${plan.backend.name} (${plan.mode}) failed " +
                "(${error.message ?: error::class.simpleName}), retried on " +
                "${retry.backend.name} (${retry.mode})"
            RoutedResponse.of(response, retry.mode, (plan.reasons + note).joinToString("; "))
        }
    }

    private data class RetryTarget(val mode: RouteMode, val backend: Backend)
    private data class Plan(
        val mode: RouteMode,
        val backend: Backend,
        val reasons: List<String>,
        val retry: RetryTarget?,
    )

    private suspend fun plan(request: ChatRequest): Plan {
        val capabilityLevel = getCapability()

        val input = PolicyInput(
            capability = capabilityLevel,
            hint = request.hint,
            privacy = request.privacy,
            estimatedInputTokens = estimateInputTokens(request.messages),
            maxTokens = request.maxTokens,
            hasTools = request.tools.isNotEmpty(),
            wantsStructuredOutput = request.responseFormat == ResponseFormat.JsonObject,
            costBudgetUsd = request.costBudgetUsd,
            latencyBudgetMs = request.latencyBudgetMs,
        )

        val decision = decide(input, policy)
        val reasons = mutableListOf(decision.reason)

        var mode: RouteMode = when (decision) {
            is RoutingDecision.Blocked -> throw RoutingBlockedException(
                RoutingBlockedCode.PrivacySensitiveOnDeviceNotViable,
                decision.reason,
            )
            is RoutingDecision.OnDevice -> RouteMode.OnDevice
            is RoutingDecision.Cloud -> RouteMode.Cloud
        }
        var backend = if (mode == RouteMode.OnDevice) onDevice else cloud

        // The policy engine reasons about modes, not about which backends the
        // caller wired up. If the decided mode has no backend, serve from the
        // other one when policy allows — with the same hard privacy rule:
        // sensitive traffic never falls over to cloud.
        if (backend == null) {
            if (mode == RouteMode.OnDevice) {
                if (request.privacy == PrivacyLevel.Sensitive) {
                    throw RoutingBlockedException(
                        RoutingBlockedCode.PrivacySensitiveOnDeviceBackendMissing,
                        "${decision.reason} — but no onDevice backend is configured, and " +
                            "sensitive requests are never sent to the cloud",
                    )
                }
                val cloudBackend = cloud
                    ?: error("HybridRouter: no backend available for this request")
                mode = RouteMode.Cloud
                backend = cloudBackend
                reasons += "no onDevice backend configured, serving from cloud"
            } else {
                val onDeviceBackend = onDevice
                if (onDeviceBackend == null || capabilityLevel == CapabilityLevel.NotViable) {
                    error(
                        "HybridRouter: policy chose cloud but no cloud backend is configured " +
                            "(and on-device is not available)",
                    )
                }
                mode = RouteMode.OnDevice
                backend = onDeviceBackend
                reasons += "no cloud backend configured, serving on-device"
            }
        }

        // Single bounded retry target, resolved up front so the privacy and
        // viability rules live in one place.
        val retry: RetryTarget? = when {
            !fallback -> null
            mode == RouteMode.OnDevice && cloud != null && request.privacy != PrivacyLevel.Sensitive ->
                RetryTarget(RouteMode.Cloud, cloud)
            mode == RouteMode.Cloud && onDevice != null && capabilityLevel != CapabilityLevel.NotViable ->
                RetryTarget(RouteMode.OnDevice, onDevice)
            else -> null
        }

        return Plan(mode, backend, reasons, retry)
    }

    internal companion object {
        /** Message characters (plus tool-call arguments/names) / 4, rounded up. */
        internal fun estimateInputTokens(messages: List<Message>): Int {
            var chars = 0
            for (message in messages) {
                chars += message.content?.length ?: 0
                for (call in message.toolCalls) {
                    chars += call.arguments.length + call.name.length
                }
            }
            return ceil(chars.toDouble() / CHARS_PER_TOKEN_ESTIMATE).toInt()
        }
    }
}
