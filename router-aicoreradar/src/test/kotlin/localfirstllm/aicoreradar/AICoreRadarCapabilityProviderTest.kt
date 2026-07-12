package localfirstllm.aicoreradar

import aicoreradar.AvailabilitySummary
import aicoreradar.BenchmarkMode
import aicoreradar.BenchmarkResult
import aicoreradar.ContextWindowMeasurement
import aicoreradar.DeviceIdentity
import aicoreradar.ModelSelection
import aicoreradar.QuotaPressure
import aicoreradar.RadarReport
import aicoreradar.UsageSnapshot
import kotlinx.coroutines.test.runTest
import localfirstllm.CapabilityLevel
import localfirstllm.aicoreradar.AICoreRadarCapabilityProvider.Companion.classify
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the aicore-radar-signals → [CapabilityLevel] reduction, using
 * hand-constructed aicore-radar signals (no device). Covers the granular
 * [classify] truth table, the [RadarReport] extraction, and the provider's
 * probe/never-throw contract.
 */
class AICoreRadarCapabilityProviderTest {

    private val defaults = RadarMappingConfig()

    // ── Availability gate ───────────────────────────────────────────────────

    @Test
    fun `not usable maps to NotViable regardless of other signals`() {
        assertEquals(
            CapabilityLevel.NotViable,
            classify(
                availabilityUsable = false,
                quotaPressure = QuotaPressure.NO_SIGNAL,
                measuredTokensPerSecond = 100.0, // fast, but not available → irrelevant
                measuredContextTokenLimit = 100_000,
            ),
        )
    }

    // ── Quota pressure ──────────────────────────────────────────────────────

    @Test
    fun `battery quota hit recently maps to NotViable by default`() {
        assertEquals(
            CapabilityLevel.NotViable,
            classify(true, QuotaPressure.BATTERY_BUDGET_HIT_RECENTLY, 30.0, 8192),
        )
    }

    @Test
    fun `battery quota maps to Degraded when configured as not-viable=false`() {
        assertEquals(
            CapabilityLevel.OnDeviceDegraded,
            classify(
                true,
                QuotaPressure.BATTERY_BUDGET_HIT_RECENTLY,
                30.0,
                8192,
                RadarMappingConfig(batteryQuotaIsNotViable = false),
            ),
        )
    }

    @Test
    fun `rate limited recently maps to Degraded`() {
        assertEquals(
            CapabilityLevel.OnDeviceDegraded,
            classify(true, QuotaPressure.RATE_LIMITED_RECENTLY, 30.0, 8192),
        )
    }

    // ── Measured speed ──────────────────────────────────────────────────────

    @Test
    fun `decode speed below the threshold maps to Degraded`() {
        assertEquals(
            CapabilityLevel.OnDeviceDegraded,
            classify(true, QuotaPressure.NO_SIGNAL, measuredTokensPerSecond = 5.0, measuredContextTokenLimit = 8192),
        )
    }

    @Test
    fun `decode speed exactly at the threshold is full — bound is inclusive of full`() {
        // 8.0 is NOT below 8.0, so it stays full.
        assertEquals(
            CapabilityLevel.OnDeviceFull,
            classify(true, QuotaPressure.NO_SIGNAL, measuredTokensPerSecond = 8.0, measuredContextTokenLimit = 8192),
        )
    }

    @Test
    fun `unknown decode speed does not downgrade on its own`() {
        assertEquals(
            CapabilityLevel.OnDeviceFull,
            classify(true, QuotaPressure.NO_SIGNAL, measuredTokensPerSecond = null, measuredContextTokenLimit = 8192),
        )
    }

    // ── Measured context ────────────────────────────────────────────────────

    @Test
    fun `context window below the threshold maps to Degraded`() {
        assertEquals(
            CapabilityLevel.OnDeviceDegraded,
            classify(true, QuotaPressure.NO_SIGNAL, measuredTokensPerSecond = 30.0, measuredContextTokenLimit = 1024),
        )
    }

    @Test
    fun `context window exactly at the threshold is full`() {
        assertEquals(
            CapabilityLevel.OnDeviceFull,
            classify(true, QuotaPressure.NO_SIGNAL, measuredTokensPerSecond = 30.0, measuredContextTokenLimit = 2048),
        )
    }

    @Test
    fun `unknown context does not downgrade on its own`() {
        assertEquals(
            CapabilityLevel.OnDeviceFull,
            classify(true, QuotaPressure.NO_SIGNAL, measuredTokensPerSecond = 30.0, measuredContextTokenLimit = null),
        )
    }

    // ── Healthy device ──────────────────────────────────────────────────────

    @Test
    fun `available with no pressure, fast decode, ample context maps to OnDeviceFull`() {
        assertEquals(
            CapabilityLevel.OnDeviceFull,
            classify(true, QuotaPressure.NO_SIGNAL, measuredTokensPerSecond = 20.0, measuredContextTokenLimit = 8192),
        )
    }

    @Test
    fun `custom thresholds are honored`() {
        // Raise the speed bar so 20 tok/s now counts as degraded.
        assertEquals(
            CapabilityLevel.OnDeviceDegraded,
            classify(
                true,
                QuotaPressure.NO_SIGNAL,
                measuredTokensPerSecond = 20.0,
                measuredContextTokenLimit = 8192,
                config = RadarMappingConfig(degradedBelowTokensPerSecond = 25.0),
            ),
        )
    }

    // ── RadarReport extraction ──────────────────────────────────────────────

    private fun report(
        usable: Boolean = true,
        pressure: QuotaPressure = QuotaPressure.NO_SIGNAL,
        tokensPerSecond: Double? = 20.0,
        contextLimit: Int? = 8192,
    ) = RadarReport(
        generatedAtMillis = 0L,
        device = DeviceIdentity("Pixel", "Pixel 9 Pro", "caiman", 35, "15"),
        modelSelection = ModelSelection(),
        availability = AvailabilitySummary(status = if (usable) "AVAILABLE" else "UNAVAILABLE", isUsableNow = usable),
        contextWindow = contextLimit?.let { ContextWindowMeasurement(reportedTokenLimit = it, measuredAtMillis = 0L) },
        benchmark = tokensPerSecond?.let {
            BenchmarkResult(
                mode = BenchmarkMode.QUICK,
                approxOutputTokensPerSecond = it,
                promptText = "",
                measuredAtMillis = 0L,
            )
        },
        usage = UsageSnapshot(atMillis = 0L, callsInWindow = 0, windowMillis = 60_000L, totalRecordedCalls = 0, pressure = pressure),
    )

    @Test
    fun `extracts the four signals from a RadarReport`() {
        assertEquals(CapabilityLevel.OnDeviceFull, classify(report(), defaults))
        assertEquals(CapabilityLevel.NotViable, classify(report(usable = false), defaults))
        assertEquals(
            CapabilityLevel.OnDeviceDegraded,
            classify(report(tokensPerSecond = 3.0), defaults),
        )
        assertEquals(
            CapabilityLevel.OnDeviceDegraded,
            classify(report(pressure = QuotaPressure.RATE_LIMITED_RECENTLY), defaults),
        )
    }

    @Test
    fun `a report with no usage snapshot is treated as no quota signal`() {
        val noUsage = report().copy(usage = null)
        assertEquals(CapabilityLevel.OnDeviceFull, classify(noUsage, defaults))
    }

    // ── Provider contract ───────────────────────────────────────────────────

    @Test
    fun `probe maps the produced report`() = runTest {
        val provider = AICoreRadarCapabilityProvider(reportProducer = { report(tokensPerSecond = 2.0) })
        assertEquals(CapabilityLevel.OnDeviceDegraded, provider.probe())
    }

    @Test
    fun `probe never throws — a failing producer degrades to NotViable`() = runTest {
        val provider = AICoreRadarCapabilityProvider(reportProducer = { throw RuntimeException("radar failed") })
        assertEquals(CapabilityLevel.NotViable, provider.probe())
    }
}
