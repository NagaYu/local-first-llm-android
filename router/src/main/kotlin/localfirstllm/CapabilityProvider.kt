/*
 * The capability abstraction and the zero-dependency default implementation.
 *
 * This mirrors local-first-llm's `capability/types.ts` + `capability/builtin.ts`:
 * a one-method interface the router probes, plus a minimal built-in check that
 * is honest about being a floor, not a good signal. The recommended, measured
 * provider lives in the separate `router-aicoreradar` module so the core never
 * depends on `aicore-radar`.
 */
package localfirstllm

/**
 * Answers one question: how capable is *this* device of running on-device
 * inference, right now?
 *
 * [HybridRouter] calls [probe] lazily on the first request and (by default)
 * caches the result for the router's lifetime, because a probe can be
 * expensive — the recommended `AICoreRadarCapabilityProvider` runs real
 * micro-benchmarks. Implementations should therefore not assume [probe] is
 * called per request.
 *
 * Bring your own signal by implementing this interface: a device allowlist for
 * a managed fleet, a cached server-driven flag, etc. The three-value
 * [CapabilityLevel] is deliberately the same coarse shape richer signals get
 * reduced to — see [CapabilityLevel] for what each value means to the policy
 * engine.
 */
public interface CapabilityProvider {
    public suspend fun probe(): CapabilityLevel
}

/**
 * A [CapabilityProvider] that always reports a fixed [CapabilityLevel]. Useful
 * in tests, in previews, and for callers who already know the answer (e.g. a
 * server-driven flag) and just want to hand it to the router.
 */
public class FixedCapabilityProvider(private val level: CapabilityLevel) : CapabilityProvider {
    override suspend fun probe(): CapabilityLevel = level
}

/**
 * The zero-dependency default capability check: is AICore (ML Kit's GenAI
 * on-device model) *available* on this device for the configured model?
 *
 * **Honest limits — this is a floor, not a good capability signal:**
 * - It reduces the platform's availability status to a boolean: `AVAILABLE`
 *   maps to [CapabilityLevel.OnDeviceFull], everything else (downloadable,
 *   downloading, unavailable, error) maps to [CapabilityLevel.NotViable].
 * - It **never** reports [CapabilityLevel.OnDeviceDegraded], because a bare
 *   availability check has no way to measure "usable but slow / under quota
 *   pressure". A `DOWNLOADABLE` device that could run well after a one-time
 *   model download is reported [CapabilityLevel.NotViable] here, because it
 *   cannot serve a request *right now*.
 * - It knows nothing about measured latency, context size, per-app quota
 *   headroom, or thermal state.
 *
 * For a real, measured signal — quota headroom, measured latency, measured
 * context window, all mapped down to the three-value [CapabilityLevel] — use
 * `AICoreRadarCapabilityProvider` from the `router-aicoreradar` module instead.
 *
 * This class is constructed with an [availabilityCheck] lambda rather than
 * touching ML Kit directly, so the mapping is unit-testable on the JVM without
 * a device. The default lambda (via the [create] factory) calls ML Kit's
 * `GenerativeModel.checkStatus()`.
 */
public class BuiltinCapabilityProvider internal constructor(
    private val availabilityCheck: suspend () -> AicoreAvailabilityStatus,
) : CapabilityProvider {

    override suspend fun probe(): CapabilityLevel =
        when (runCatching { availabilityCheck() }.getOrDefault(AicoreAvailabilityStatus.Error)) {
            AicoreAvailabilityStatus.Available -> CapabilityLevel.OnDeviceFull
            // Downloadable / Downloading / Unavailable / Error all mean "cannot
            // serve on-device right now" — and, being a floor, this provider
            // never claims Degraded.
            AicoreAvailabilityStatus.Downloadable,
            AicoreAvailabilityStatus.Downloading,
            AicoreAvailabilityStatus.Unavailable,
            AicoreAvailabilityStatus.Error,
            -> CapabilityLevel.NotViable
        }

    public companion object
}

/**
 * The minimal availability signal the [BuiltinCapabilityProvider] reduces to a
 * [CapabilityLevel]. This is intentionally a small, provider-agnostic enum: the
 * platform's real status type (ML Kit's `FeatureStatus` IntDef) is only touched
 * by the Android-only factory that produces one of these, keeping the mapping
 * itself pure and testable.
 */
public enum class AicoreAvailabilityStatus {
    /** The model is downloaded and ready for inference now. */
    Available,

    /** The device is eligible but the model is not downloaded yet. */
    Downloadable,

    /** A model download is currently in progress. */
    Downloading,

    /** The platform reports the feature as unavailable on this device/config. */
    Unavailable,

    /** The availability check itself failed. */
    Error,
}
