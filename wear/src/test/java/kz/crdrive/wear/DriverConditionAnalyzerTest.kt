package kz.crdrive.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverConditionAnalyzerTest {
    @Test fun normalSignalsStayLowRisk() {
        val result = DriverConditionAnalyzer.analyze(72, 98, true, true, false, true)
        assertEquals(0, result.score)
        assertEquals("Норма", result.level)
        assertEquals(100, result.confidence)
    }

    @Test fun noMovementAndLowPulseRaiseRisk() {
        val result = DriverConditionAnalyzer.analyze(50, 97, true, true, true, true)
        assertTrue((result.score ?: 0) >= 60)
        assertEquals("Высокий риск", result.level)
    }

    @Test fun removedWatchHasNoScore() {
        val result = DriverConditionAnalyzer.analyze(70, 98, true, false, false, true)
        assertEquals(null, result.score)
        assertEquals(0, result.confidence)
    }
}
