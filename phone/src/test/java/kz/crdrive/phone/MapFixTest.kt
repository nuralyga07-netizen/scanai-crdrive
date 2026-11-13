package kz.crdrive.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFixTest {
    @Test fun staleOrImpreciseFixIsNotLive() {
        val fix = MapFix(50.2839, 57.1670, 25f, 100_000L, "Телефон")
        assertTrue(fix.isFresh(120_000L))
        assertFalse(fix.isFresh(161_000L))
        assertFalse(fix.copy(accuracyMeters = 300f).isFresh(120_000L))
    }
}
