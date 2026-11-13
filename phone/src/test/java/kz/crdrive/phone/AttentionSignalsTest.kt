package kz.crdrive.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionSignalsTest {
    private val now = 1_000_000L
    private fun live() = TelemetryState(bpm = 72, receivedAt = now,
        sensors = SensorReadings(onBody = true, updatedAt = now))

    @Test fun noPulseHasNoIndex() {
        assertEquals(null, attentionSignals(TelemetryState(), now).value)
    }

    @Test fun noWristContactHasNoIndex() {
        assertEquals(null, attentionSignals(live().copy(sensors = SensorReadings(onBody = false, updatedAt = now)), now).value)
    }

    @Test fun selfReportAndSleepIncreaseIndex() {
        val state = live().copy(sleepinessReport = 1, sleepinessReportedAt = now,
            contextHealth = ContextHealth(sleepHours = 5.0, sleepEndedAt = now))
        assertEquals(40, attentionSignals(state, now).value)
    }

    @Test fun staleReadingsStopIndex() {
        assertEquals(null, attentionSignals(live(), now + 31_000).value)
    }

    @Test fun oxygenAloneNeverChangesIndex() {
        val oxygen = ContextHealth(oxygenPercent = 91.0, oxygenMeasuredAt = now)
        assertEquals(attentionSignals(live(), now).value,
            attentionSignals(live().copy(contextHealth = oxygen), now).value)
        assertTrue(attentionSignals(live().copy(contextHealth = oxygen), now).details.contains("SpO₂ 91%"))
    }

    @Test fun deviceHealthIsSeparateFromAttention() {
        val state = live().copy(battery = 10, sensors = SensorReadings(onBody = true,
            acceleration = 1.0, pressure = 1001.0, updatedAt = now))
        assertEquals(0, attentionSignals(state, now).value)
        assertEquals("Низкий заряд часов: 10%", signalCoverage(state, now).deviceWarning)
    }

    @Test fun combinedSignalsTriggerReminderButNotOxygenAlone() {
        val combined = live().copy(sleepinessReport = 2, sleepinessReportedAt = now,
            contextHealth = ContextHealth(sleepHours = 5.0, sleepEndedAt = now))
        assertEquals(true, needsAttentionReminder(combined, now))
        assertEquals(false, needsAttentionReminder(live().copy(
            contextHealth = ContextHealth(oxygenPercent = 89.0, oxygenMeasuredAt = now)), now))
    }
}
