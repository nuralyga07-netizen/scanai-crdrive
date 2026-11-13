package kz.crdrive.phone

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import java.util.Random

class GlucoseAiModelTest {
    /** Pulse-like wave: sharp systolic peak plus a dicrotic bump, 72 bpm, a little noise. */
    private fun wave(seconds: Double, fs: Double, bpm: Double = 72.0): DoubleArray {
        val rnd = Random(1)
        val n = (seconds * fs).toInt()
        val period = 60.0 / bpm
        return DoubleArray(n) { i ->
            val t = i / fs
            val ph = (t % period) / period
            100 * exp(-((ph - 0.2) * (ph - 0.2)) / 0.004) + 35 * exp(-((ph - 0.5) * (ph - 0.5)) / 0.01) +
                0.5 * sin(2 * PI * 0.1 * t) * 100 + rnd.nextGaussian() * 1.5
        }
    }

    @Test fun cameraRateGivesEstimateInRange() {
        val r = GlucoseAiModel.estimate(wave(46.0, 30.0), 30.0)
        assertNotNull(r)
        assertTrue(r!!.mmol in 2.5..12.0)
        assertTrue(r.heartRate in 65.0..80.0)
    }

    @Test fun watchRateGivesEstimateInRange() {
        val r = GlucoseAiModel.estimate(wave(45.0, 25.0), 25.0)
        assertNotNull(r)
        assertTrue(r!!.mmol in 2.5..12.0)
    }

    @Test fun tooShortOrFlatSignalGivesNothing() {
        assertNull(GlucoseAiModel.estimate(wave(10.0, 30.0), 30.0))
        assertNull(GlucoseAiModel.estimate(DoubleArray(1500) { 5.0 }, 30.0))
    }
}
