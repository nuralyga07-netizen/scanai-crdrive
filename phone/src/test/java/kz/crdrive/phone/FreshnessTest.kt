package kz.crdrive.phone
import org.junit.Assert.*
import org.junit.Test
class FreshnessTest {
    @Test fun staleDataHidesPulseAndAssessment() {
        val state = TelemetryState(bpm = 78, receivedAt = 1_000, source = "LIVE")
        val stale = state.withFreshness(32_000)
        assertNull(stale.bpm)
        assertEquals(RiskLevel.UNKNOWN, stale.assessment.level)
        assertNotEquals("LIVE", stale.source)
        assertEquals(78, state.withFreshness(2_000).bpm)
    }
    @Test fun outOfOrderPacketDoesNotReplaceLatest() {
        TelemetryRepository.reset()
        val now = System.currentTimeMillis()
        TelemetryRepository.receive(80, 90, now)
        TelemetryRepository.receive(100, 90, now - 1_000)
        assertEquals(80, TelemetryRepository.state.value.bpm)
    }
    @Test fun simulatedPacketCannotReplaceWatchReading() {
        TelemetryRepository.reset()
        TelemetryRepository.receive(80, 90, System.currentTimeMillis())
        TelemetryRepository.receive(150, 90, System.currentTimeMillis() + 1_000, "DEMO")
        assertEquals(80, TelemetryRepository.state.value.bpm)
        assertEquals("LIVE", TelemetryRepository.state.value.source)
    }
    @Test fun staleAuxiliarySensorsAreNotShownAsLive() {
        val state = TelemetryState(
            sensors = SensorReadings(onBody = true, acceleration = 9.8, rotation = 0.5,
                tripSteps = 12, updatedAt = 1_000),
            contextHealth = ContextHealth(oxygenPercent = 97.0, oxygenMeasuredAt = 1_000,
                source = "Samsung Health Sensor SDK · часы"),
            ppgSamples = listOf(PpgPoint(1_000, 1, 2, 3))
        )
        val stale = state.withFreshness(700_001)
        assertNull(stale.sensors.onBody)
        assertNull(stale.sensors.acceleration)
        assertEquals(12, stale.sensors.tripSteps)
        assertNull(stale.contextHealth.oxygenPercent)
        assertTrue(stale.ppgSamples.isEmpty())
    }
}
