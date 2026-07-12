/*
 * The recommended capability provider: maps aicore-radar's *measured* signals
 * (availability, quota pressure, measured decode speed, measured context window)
 * down to the router's three-value [CapabilityLevel].
 *
 * This is the optional-integration counterpart to router's built-in floor
 * (`BuiltinCapabilityProvider`), which only knows "AVAILABLE or not". The extra
 * fidelity here is the whole reason the `router-aicoreradar` module exists — and
 * the whole reason it is a *separate* module: the core `router` never depends on
 * `aicore-radar`.
 *
 * Design notes:
 * - The reduction is intentionally lossy and conservative: many rich signals →
 *   three coarse buckets, erring toward reporting *less* capability, never more.
 * - The mapping ([classify]) is a pure function so it is fully unit-testable
 *   with hand-constructed aicore-radar signals — no device required.
 */
package localfirstllm.aicoreradar

import aicoreradar.AicoreRadar
import aicoreradar.BenchmarkMode
import aicoreradar.ModelSelection
import aicoreradar.QuotaPressure
import aicoreradar.RadarReport
import android.content.Context
import localfirstllm.CapabilityLevel
import localfirstllm.CapabilityProvider

/**
 * Tunable thresholds for the signal → [CapabilityLevel] reduction. Defaults are
 * documented, order-of-magnitude heuristics — override them with numbers that
 * match your app's UX bar.
 *
 * @property degradedBelowTokensPerSecond Measured decode speed at or above this
 *   (tokens/second) is treated as full-speed; below it, on-device is reported
 *   [CapabilityLevel.OnDeviceDegraded]. Default 8.0 sits between the policy
 *   engine's own full/degraded decode estimates (15 / 5 tok/s).
 * @property degradedBelowContextTokens Measured total context window at or above
 *   this (tokens) is treated as ample; below it, on-device is reported
 *   [CapabilityLevel.OnDeviceDegraded] because large prompts won't fit. Default
 *   2048.
 * @property batteryQuotaIsNotViable When a battery-budget quota error was
 *   observed recently, aicore-radar's own guidance is "calls will likely keep
 *   failing"; by default that is mapped to [CapabilityLevel.NotViable] rather
 *   than merely degraded. Set false to treat it as degraded instead.
 */
public data class RadarMappingConfig(
    val degradedBelowTokensPerSecond: Double = 8.0,
    val degradedBelowContextTokens: Int = 2048,
    val batteryQuotaIsNotViable: Boolean = true,
)

/**
 * A [CapabilityProvider] backed by aicore-radar's measured report.
 *
 * ```kotlin
 * val router = HybridRouter(
 *     capability = AICoreRadarCapabilityProvider(context),
 *     onDevice = AICoreBackend(),
 *     cloud = OpenAICompatibleBackend(baseUrl = "...", apiKey = "...", model = "..."),
 * )
 * ```
 *
 * The router probes once and caches for its lifetime by default, which matters
 * here: [BenchmarkMode.QUICK] (the default) runs a real on-device inference to
 * measure decode speed, consuming a little battery and quota. Pass
 * [BenchmarkMode.NONE] to skip benchmarking and map from availability + context
 * only (then measured decode speed is unknown and never triggers a downgrade).
 *
 * @property reportProducer Produces one [RadarReport]. The [Context] constructor
 *   supplies the real `AicoreRadar(context).generateReport(...)`; tests inject a
 *   fake producer.
 * @property config Thresholds for the reduction.
 */
public class AICoreRadarCapabilityProvider(
    private val reportProducer: suspend () -> RadarReport,
    private val config: RadarMappingConfig = RadarMappingConfig(),
) : CapabilityProvider {

    /**
     * @param context Any context; the application context is used internally.
     * @param selection Which on-device model configuration to measure.
     * @param benchmark How much benchmarking to run per probe. Defaults to
     *   [BenchmarkMode.QUICK] so measured decode speed is available.
     * @param config Reduction thresholds.
     */
    public constructor(
        context: Context,
        selection: ModelSelection = ModelSelection(),
        benchmark: BenchmarkMode = BenchmarkMode.QUICK,
        config: RadarMappingConfig = RadarMappingConfig(),
    ) : this({ AicoreRadar(context, selection).generateReport(benchmark) }, config)

    /** Probes the device and reduces the report to a [CapabilityLevel]. Never throws. */
    override suspend fun probe(): CapabilityLevel =
        runCatching { classify(reportProducer(), config) }
            .getOrDefault(CapabilityLevel.NotViable)

    public companion object {
        /**
         * The pure reduction from one [RadarReport] to a [CapabilityLevel].
         * Extracts the four signals this mapping uses and delegates to the
         * granular [classify] overload.
         */
        public fun classify(report: RadarReport, config: RadarMappingConfig = RadarMappingConfig()): CapabilityLevel =
            classify(
                availabilityUsable = report.availability.isUsableNow,
                quotaPressure = report.usage?.pressure ?: QuotaPressure.NO_SIGNAL,
                measuredTokensPerSecond = report.benchmark?.approxOutputTokensPerSecond,
                measuredContextTokenLimit = report.contextWindow?.reportedTokenLimit,
                config = config,
            )

        /**
         * The core reduction, expressed over the individual signals so it is
         * trivially testable. Order matters:
         *
         * 1. **Not usable now → [CapabilityLevel.NotViable].** If aicore-radar
         *    says the model isn't AVAILABLE for this configuration, on-device is
         *    off the table regardless of any other signal.
         * 2. **Battery quota hit recently → [CapabilityLevel.NotViable]** (by
         *    default; see [RadarMappingConfig.batteryQuotaIsNotViable]). The
         *    long-duration budget is spent and won't replenish soon.
         * 3. Otherwise start from [CapabilityLevel.OnDeviceFull] and **downgrade
         *    to [CapabilityLevel.OnDeviceDegraded]** if any single signal is
         *    weak: a recent short-window rate-limit, measured decode speed below
         *    [RadarMappingConfig.degradedBelowTokensPerSecond], or a measured
         *    context window below [RadarMappingConfig.degradedBelowContextTokens].
         *    A `null` measured signal (e.g. benchmarking was skipped) never
         *    downgrades on its own.
         *
         * @param availabilityUsable `report.availability.isUsableNow`.
         * @param quotaPressure `report.usage?.pressure`, or NO_SIGNAL if absent.
         * @param measuredTokensPerSecond `report.benchmark?.approxOutputTokensPerSecond`.
         * @param measuredContextTokenLimit `report.contextWindow?.reportedTokenLimit`.
         */
        public fun classify(
            availabilityUsable: Boolean,
            quotaPressure: QuotaPressure,
            measuredTokensPerSecond: Double?,
            measuredContextTokenLimit: Int?,
            config: RadarMappingConfig = RadarMappingConfig(),
        ): CapabilityLevel {
            if (!availabilityUsable) return CapabilityLevel.NotViable
            if (quotaPressure == QuotaPressure.BATTERY_BUDGET_HIT_RECENTLY && config.batteryQuotaIsNotViable) {
                return CapabilityLevel.NotViable
            }

            val degraded =
                quotaPressure == QuotaPressure.RATE_LIMITED_RECENTLY ||
                    (quotaPressure == QuotaPressure.BATTERY_BUDGET_HIT_RECENTLY) ||
                    (measuredTokensPerSecond != null && measuredTokensPerSecond < config.degradedBelowTokensPerSecond) ||
                    (measuredContextTokenLimit != null && measuredContextTokenLimit < config.degradedBelowContextTokens)

            return if (degraded) CapabilityLevel.OnDeviceDegraded else CapabilityLevel.OnDeviceFull
        }
    }
}
