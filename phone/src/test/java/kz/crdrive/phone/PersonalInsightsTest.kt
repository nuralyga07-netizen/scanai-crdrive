package kz.crdrive.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalInsightsTest {
    @Test fun baselineNeedsThreeDays() {
        val points = (0 until 150).map { HeartPoint(70, it * 1000L) }
        assertEquals(null, personalBaseline(points))
        val spread = (0 until 120).map { HeartPoint(70 + it % 3, (it % 3) * HistoryStore.DAY + it * 1000L) }
        assertEquals(71, personalBaseline(spread))
    }

    @Test fun stalePulseCannotProduceInsight() {
        assertTrue(personalInsight(TelemetryState(personalBaseline = 70)).contains("приостановлена"))
    }

    @Test fun selfReportedSleepinessTakesPrecedence() {
        assertTrue(personalInsight(TelemetryState(bpm = 70, personalBaseline = 70, sleepinessReport = 2)).contains("отдохните"))
    }

    @Test fun oldSleepinessReportExpires() {
        val state = TelemetryState(sleepinessReport = 2, sleepinessReportedAt = 1_000L)
        assertEquals(null, state.withFreshness(1_000L + 43_200_001L).sleepinessReport)
    }

    @Test fun personalDeviationRequiresThreeRecentMeasurements() {
        val one = TelemetryState(bpm = 110, personalBaseline = 70,
            samples = listOf(HeartPoint(110, 100_000)))
        assertTrue(personalInsight(one).contains("Ждём три"))
        val three = one.copy(samples = listOf(HeartPoint(108, 90_000), HeartPoint(110, 95_000), HeartPoint(112, 100_000)))
        assertTrue(personalInsight(three).contains("выше медианы"))
    }
}
