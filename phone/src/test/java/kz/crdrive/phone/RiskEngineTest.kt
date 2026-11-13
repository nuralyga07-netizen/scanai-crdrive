package kz.crdrive.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RiskEngineTest {
    @Test
    fun stablePulseRemainsNormal() {
        val engine = RiskEngine()
        repeat(12) { index ->
            val result = engine.analyze(HeartPoint(76 + index % 3, index * 1_000L))
            assertEquals(RiskLevel.NORMAL, result.level)
        }
    }

    @Test
    fun suddenHighPulseCreatesHighRisk() {
        val engine = RiskEngine()
        repeat(10) { engine.analyze(HeartPoint(76, it * 1_000L)) }
        val result = engine.analyze(HeartPoint(148, 11_000L))
        assertEquals(RiskLevel.HIGH, result.level)
        assertTrue(result.score >= 72)
    }

    @Test
    fun invalidTelemetryIsIgnoredByRepository() {
        TelemetryRepository.reset()
        TelemetryRepository.receive(280, 80, 1L)
        assertEquals(null, TelemetryRepository.state.value.bpm)
    }

    @Test
    fun confirmedLowPulseAlertIsShownOnceAndClearsWhenStale() {
        TelemetryRepository.reset()
        val now = System.currentTimeMillis()
        assertTrue(TelemetryRepository.receive(47, 80, now, lowPulseAlert = true))
        assertFalse(TelemetryRepository.receive(47, 80, now, lowPulseAlert = true))
        assertTrue(TelemetryRepository.state.value.lowPulseAlert)
        assertFalse(TelemetryRepository.state.value.withFreshness(now + 31_000).lowPulseAlert)
    }

    @Test
    fun removingWatchHidesPulseAndAlert() {
        TelemetryRepository.reset()
        val now = System.currentTimeMillis()
        TelemetryRepository.receive(48, 80, now, lowPulseAlert = true)
        TelemetryRepository.updateSensors(SensorReadings(onBody = false, updatedAt = now))
        assertEquals(null, TelemetryRepository.state.value.bpm)
        assertFalse(TelemetryRepository.state.value.lowPulseAlert)
        assertEquals("ЧАСЫ СНЯТЫ", TelemetryRepository.state.value.source)
    }
}
