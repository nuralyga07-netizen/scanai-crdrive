package kz.crdrive.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLocationTest {
    @Test fun onlyRecentAccurateWatchFixIsShown() {
        val fix = WatchLocation(50.2839, 57.1670, 20f, 100_000L)
        assertTrue(fix.isFresh(120_000L))
        assertFalse(fix.isFresh(161_000L))
        assertFalse(fix.copy(accuracyMeters = 150f).isFresh(120_000L))
        assertTrue(fix.copy(accuracyMeters = 150f, provider = "network").isFresh(120_000L))
        assertFalse(fix.copy(latitude = 200.0).isFresh(120_000L))
    }
}
