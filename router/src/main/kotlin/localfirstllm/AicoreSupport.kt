/*
 * Android factory wiring the pure [BuiltinCapabilityProvider] mapping to the
 * real ML Kit availability check. Kept separate from the pure provider so the
 * mapping stays unit-testable on the JVM without ML Kit on the classpath.
 */
package localfirstllm

import localfirstllm.backends.AicoreClient
import localfirstllm.backends.AicoreModelPreference
import localfirstllm.backends.AicoreModelStage

/**
 * The recommended way to obtain the built-in capability provider: it checks ML
 * Kit's on-device model availability for the given [stage]/[preference] and maps
 * `AVAILABLE` → [CapabilityLevel.OnDeviceFull], everything else →
 * [CapabilityLevel.NotViable].
 *
 * Remember this is a **floor** (see [BuiltinCapabilityProvider]): it never
 * reports [CapabilityLevel.OnDeviceDegraded] and knows nothing about measured
 * speed or quota headroom. For a measured signal use
 * `AICoreRadarCapabilityProvider` from the `router-aicoreradar` module.
 */
public fun BuiltinCapabilityProvider.Companion.aicore(
    stage: AicoreModelStage = AicoreModelStage.Stable,
    preference: AicoreModelPreference = AicoreModelPreference.Full,
): BuiltinCapabilityProvider = BuiltinCapabilityProvider {
    AicoreClient.create(stage, preference).use { it.checkStatus() }
}
