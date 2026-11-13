package kz.crdrive.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class PpgTimestampTest {
    @Test fun convertsSensorClockToWallClockAndKeepsSpacing() {
        val now = 1_700_000_000_000L
        assertEquals(now, SamsungSensorProbe.wallClockTimestamp(now, 50_000L, 50_000L))
        assertEquals(now - 40L, SamsungSensorProbe.wallClockTimestamp(now, 50_000L, 49_960L))
    }

    @Test fun capsImpossibleBatchAge() {
        val now = 1_700_000_000_000L
        assertEquals(now - 120_000L,
            SamsungSensorProbe.wallClockTimestamp(now, 1_000_000L, 0L))
    }
}
