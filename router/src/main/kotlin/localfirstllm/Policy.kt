/*
 * The policy engine: a pure, synchronous function from (request facts, config)
 * to a routing decision. No I/O, no globals, no backend knowledge — this is
 * the whole value proposition of the library, kept independently testable.
 *
 * This is a faithful Kotlin port of local-first-llm's `src/policy/decide.ts`
 * and `src/policy/taxonomy.ts` (https://github.com/NagaYu/local-first-llm):
 * identical decision-tree structure, identical thresholds and estimation
 * formulas, identical step ordering.
 */
package localfirstllm

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Default task-type → preferred-route table.
 *
 * Rationale per row:
 * - [TaskHint.Classification] → on-device: short input/output, well-tolerated
 *   by small quantized models.
 * - [TaskHint.Extraction] → on-device: structured, bounded-length output.
 * - [TaskHint.Summarization] → on-device: generally fine; very long source
 *   documents are a known reason to override toward cloud (see README).
 * - [TaskHint.Chat] → cloud: the open-ended quality bar is high, and
 *   inconsistency is more noticeable to users.
 * - [TaskHint.Reasoning] → cloud: small on-device models show clear quality
 *   degradation on multi-step reasoning.
 * - [TaskHint.Creative] → cloud: long-form fluency and coherence benefit from
 *   larger models.
 *
 * Override any row via [PolicyConfig.taxonomyOverrides], e.g.
 * `mapOf(TaskHint.Chat to RouteMode.OnDevice)`.
 */
public val defaultTaxonomy: Map<TaskHint, RouteMode> = mapOf(
    TaskHint.Classification to RouteMode.OnDevice,
    TaskHint.Extraction to RouteMode.OnDevice,
    TaskHint.Summarization to RouteMode.OnDevice,
    TaskHint.Chat to RouteMode.Cloud,
    TaskHint.Reasoning to RouteMode.Cloud,
    TaskHint.Creative to RouteMode.Cloud,
)

private data class ModeEstimate(val latencyMs: Double, val costUsd: Double)

/** Estimation model — see [RoutingEstimates] for the documented formula. */
private fun estimateMode(
    mode: RouteMode,
    capability: CapabilityLevel,
    inputTokens: Int,
    outputTokens: Int,
    est: RoutingEstimates,
): ModeEstimate {
    if (mode == RouteMode.OnDevice) {
        val tps = if (capability == CapabilityLevel.OnDeviceDegraded) {
            est.onDeviceTokensPerSecondDegraded
        } else {
            est.onDeviceTokensPerSecondFull
        }
        return ModeEstimate(
            latencyMs = est.onDeviceOverheadMs + (outputTokens / tps) * 1000,
            costUsd = 0.0,
        )
    }
    return ModeEstimate(
        latencyMs = est.cloudOverheadMs + (outputTokens / est.cloudTokensPerSecond) * 1000,
        costUsd = (inputTokens / 1e6) * est.cloudUsdPerMillionInputTokens +
            (outputTokens / 1e6) * est.cloudUsdPerMillionOutputTokens,
    )
}

private fun withinBudgets(
    estimate: ModeEstimate,
    costBudgetUsd: Double?,
    latencyBudgetMs: Long?,
): Boolean {
    return (costBudgetUsd == null || estimate.costUsd <= costBudgetUsd) &&
        (latencyBudgetMs == null || estimate.latencyMs <= latencyBudgetMs)
}

private fun ModeEstimate.describe(): String =
    "~${latencyMs.roundToLong()}ms/$${String.format(Locale.ROOT, "%.4f", costUsd)}"

private fun RouteMode.decision(reason: String): RoutingDecision = when (this) {
    RouteMode.OnDevice -> RoutingDecision.OnDevice(reason)
    RouteMode.Cloud -> RoutingDecision.Cloud(reason)
}

/**
 * Decide where one request should run.
 *
 * Decision steps, in order (each documented inline):
 * 1. Privacy gate — [PrivacyLevel.Sensitive] either forces on-device or
 *    blocks. Never cloud.
 * 2. Preferred mode from the taxonomy table ([PolicyInput.hint]) or, without
 *    a hint, the request-shape heuristic.
 * 3. Capability cross-check — on-device preference falls back to cloud only
 *    when the device is [CapabilityLevel.NotViable];
 *    [CapabilityLevel.OnDeviceDegraded] still routes on-device.
 * 4. Budget check — if the selected mode is estimated to violate a set budget
 *    and the other (viable) mode would satisfy all set budgets, switch.
 * 5. Return a [RoutingDecision] with a log-friendly, human-readable reason.
 */
public fun decide(input: PolicyInput, config: PolicyConfig = PolicyConfig()): RoutingDecision {
    val capability = input.capability
    val privacy = input.privacy

    // ── Step 1: privacy gate. A hard rule, not a preference: it overrides the
    // taxonomy, the heuristic, and the budget check. A sensitive request is
    // never sent to the cloud — if the device can't serve it, it is blocked.
    if (privacy == PrivacyLevel.Sensitive) {
        if (capability != CapabilityLevel.NotViable) {
            return RoutingDecision.OnDevice(
                "privacy=Sensitive, capability=$capability — " +
                    "sensitive requests never leave the device",
            )
        }
        return RoutingDecision.Blocked(
            reason = "privacy=Sensitive, capability=NotViable — sensitive requests are " +
                "never sent to the cloud and this device cannot run inference locally",
            code = BlockedCode.PrivacySensitiveOnDeviceNotViable,
        )
    }

    val taxonomy = defaultTaxonomy + config.taxonomyOverrides
    val thresholds = config.heuristic
    // An uncapped request is treated as potentially long-output; see
    // HeuristicThresholds.assumedMaxTokens.
    val outputTokens = input.maxTokens ?: thresholds.assumedMaxTokens

    // ── Step 2: preferred mode. Explicit hint reads the taxonomy table
    // directly; otherwise the request-shape heuristic classifies the request
    // as classification-like or chat-like and resolves through the same table
    // (so taxonomy overrides also steer the heuristic).
    val preferred: RouteMode
    val notes = mutableListOf<String>()
    if (input.hint != null) {
        preferred = taxonomy.getValue(input.hint)
        notes += "hint=${input.hint}"
    } else {
        val hasTools = input.hasTools
        val classificationLike =
            input.estimatedInputTokens <= thresholds.maxInputTokens &&
                !hasTools &&
                outputTokens <= thresholds.maxOutputTokens
        if (classificationLike) {
            preferred = taxonomy.getValue(TaskHint.Classification)
            notes += "no hint, heuristic=classification-like " +
                "(inputTokens≈${input.estimatedInputTokens}≤${thresholds.maxInputTokens}, " +
                "no tools, maxTokens=$outputTokens≤${thresholds.maxOutputTokens})"
        } else {
            preferred = taxonomy.getValue(TaskHint.Chat)
            notes += "no hint, heuristic=chat-like " +
                "(inputTokens≈${input.estimatedInputTokens}, tools=$hasTools, " +
                "maxTokens=$outputTokens)"
        }
    }
    notes += "capability=$capability"

    // ── Step 3: capability cross-check. Only NotViable overturns an
    // on-device preference. Degraded still routes on-device — degraded is
    // usable, just slower; whether "slower" is acceptable belongs to the
    // budget check in step 4, not here.
    var mode = preferred
    if (preferred == RouteMode.OnDevice && capability == CapabilityLevel.NotViable) {
        mode = RouteMode.Cloud
        notes += "on-device not viable, falling back to cloud"
    }

    // ── Step 4: budget check. Estimate whether the selected mode violates a
    // set budget; if the *other* mode is viable and satisfies every set
    // budget where the current one doesn't, switch. If neither satisfies,
    // keep the policy choice and say so — an unsatisfiable budget is not a
    // reason to block.
    val costBudgetUsd = input.costBudgetUsd ?: config.costBudgetUsd
    val latencyBudgetMs = input.latencyBudgetMs ?: config.latencyBudgetMs
    if (costBudgetUsd != null || latencyBudgetMs != null) {
        val estimates = config.estimates
        val inputTokens = input.estimatedInputTokens
        val current = estimateMode(mode, capability, inputTokens, outputTokens, estimates)
        if (!withinBudgets(current, costBudgetUsd, latencyBudgetMs)) {
            val other = if (mode == RouteMode.OnDevice) RouteMode.Cloud else RouteMode.OnDevice
            val otherViable = other == RouteMode.Cloud || capability != CapabilityLevel.NotViable
            val otherEstimate = if (otherViable) {
                estimateMode(other, capability, inputTokens, outputTokens, estimates)
            } else {
                null
            }
            if (otherEstimate != null && withinBudgets(otherEstimate, costBudgetUsd, latencyBudgetMs)) {
                notes += "budget: $mode estimated ${current.describe()} violates budget, " +
                    "$other estimated ${otherEstimate.describe()} fits — switched to $other"
                mode = other
            } else {
                notes += "budget: $mode estimated ${current.describe()} violates budget " +
                    "but no viable alternative satisfies it — keeping $mode"
            }
        }
    }

    // ── Step 5: final decision with a log-friendly reason.
    return mode.decision(notes.joinToString(", "))
}
