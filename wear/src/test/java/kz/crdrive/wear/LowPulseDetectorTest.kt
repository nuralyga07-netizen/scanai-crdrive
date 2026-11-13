package kz.crdrive.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowPulseDetectorTest {
    @Test fun oneLowReadingDoesNotAlert() {
        val detector = LowPulseDetector()
        assertFalse(detector.accept(48, 1_000, 1_000).active)
        assertFalse(detector.accept(49, 6_000, 6_000).active)
    }

    @Test fun sustainedLowPulseAlertsOnceAndRecovers() {
        val detector = LowPulseDetector()
        detector.accept(48, 1_000, 1_000)
        detector.accept(49, 6_000, 6_000)
        assertTrue(detector.accept(47, 11_000, 11_000).newlyTriggered)
        assertFalse(detector.accept(48, 12_000, 12_000).newlyTriggered)
        assertTrue(detector.accept(56, 13_000, 13_000).active)
        assertFalse(detector.accept(57, 14_000, 14_000).active)
    }

    @Test fun staleAndRepeatedSamplesDoNotTrigger() {
        val detector = LowPulseDetector()
        detector.accept(48, 1_000, 1_000)
        detector.accept(48, 1_000, 1_000)
        detector.accept(48, 6_000, 30_000)
        assertFalse(detector.accept(48, 11_000, 11_000).active)
    }

    @Test fun removingWatchResetsLowPulseEpisode() {
        val detector = LowPulseDetector()
        detector.accept(48, 1_000, 1_000)
        detector.accept(48, 6_000, 6_000)
        detector.reset()
        assertFalse(detector.accept(48, 11_000, 11_000).active)
    }
}
