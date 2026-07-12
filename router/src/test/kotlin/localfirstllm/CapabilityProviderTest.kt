package localfirstllm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the pure [BuiltinCapabilityProvider] mapping and the
 * [FixedCapabilityProvider]. The Android factory ([BuiltinCapabilityProvider.aicore])
 * that supplies the real ML Kit availability status is not unit-tested (device
 * only) — here we drive the mapping with a lambda directly.
 */
class CapabilityProviderTest {

    private fun provider(status: AicoreAvailabilityStatus) =
        BuiltinCapabilityProvider { status }

    @Test
    fun `available maps to OnDeviceFull`() = runTest {
        assertEquals(CapabilityLevel.OnDeviceFull, provider(AicoreAvailabilityStatus.Available).probe())
    }

    @Test
    fun `every non-available status maps to NotViable — the builtin never claims Degraded`() = runTest {
        for (status in listOf(
            AicoreAvailabilityStatus.Downloadable,
            AicoreAvailabilityStatus.Downloading,
            AicoreAvailabilityStatus.Unavailable,
            AicoreAvailabilityStatus.Error,
        )) {
            assertEquals(CapabilityLevel.NotViable, provider(status).probe(), "status=$status")
        }
    }

    @Test
    fun `a throwing availability check degrades to NotViable, never crashes the probe`() = runTest {
        val throwing = BuiltinCapabilityProvider { throw RuntimeException("AICore blew up") }
        assertEquals(CapabilityLevel.NotViable, throwing.probe())
    }

    @Test
    fun `FixedCapabilityProvider reports its configured level`() = runTest {
        assertEquals(
            CapabilityLevel.OnDeviceDegraded,
            FixedCapabilityProvider(CapabilityLevel.OnDeviceDegraded).probe(),
        )
    }
}
