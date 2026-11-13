package kz.crdrive.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverStateTest {
    private val now = 1_000_000L

    @Test fun noFreshPulseMeansNoAssessment() {
        assertEquals("Нет оценки", assessDriverState(TelemetryState(), now).title)
    }

    @Test fun confirmedLowPulseOverridesOtherContext() {
        val state = TelemetryState(bpm = 45, receivedAt = now, lowPulseAlert = true,
            sensors = SensorReadings(onBody = true, updatedAt = now),
            sleepinessReport = 0, sleepinessReportedAt = now)
        assertEquals("Остановитесь безопасно", assessDriverState(state, now).title)
    }

    @Test fun selfReportedSleepinessNeedsRest() {
        val state = TelemetryState(bpm = 72, receivedAt = now,
            sensors = SensorReadings(onBody = true, updatedAt = now),
            sleepinessReport = 2, sleepinessReportedAt = now)
        assertEquals("Нужен отдых", assessDriverState(state, now).title)
    }

    @Test fun recentShortSleepAddsContext() {
        val state = TelemetryState(bpm = 72, receivedAt = now,
            sensors = SensorReadings(onBody = true, updatedAt = now),
            contextHealth = ContextHealth(sleepHours = 4.5, sleepEndedAt = now - 1000))
        assertTrue(assessDriverState(state, now).explanation.contains("короткий сон"))
    }

    @Test fun staleSleepDoesNotTriggerWarning() {
        val state = TelemetryState(bpm = 72, receivedAt = now,
            sensors = SensorReadings(onBody = true, updatedAt = now),
            contextHealth = ContextHealth(sleepHours = 4.5, sleepEndedAt = now - 40 * 3_600_000L))
        assertEquals("Нет выявленных сигналов", assessDriverState(state, now).title)
    }

    @Test fun missingWristContactPreventsCompleteAssessment() {
        val state = TelemetryState(bpm = 72, receivedAt = now)
        assertEquals("Нет полной оценки", assessDriverState(state, now).title)
    }
}
