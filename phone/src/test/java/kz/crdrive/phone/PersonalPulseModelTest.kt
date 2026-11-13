package kz.crdrive.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalPulseModelTest {
    @Test fun requiresThreeDaysAndEnoughReadings() {
        assertNull(fitPersonalPulseModel((0 until 120).map { HeartPoint(70, it * 1000L) }))
    }

    @Test fun fitsRobustMedianAndIgnoresSingleSpike() {
        val points = (0 until 120).map { HeartPoint(if (it == 0) 180 else 70 + it % 3,
            (it % 3) * HistoryStore.DAY + it * 1000L) }
        val model = fitPersonalPulseModel(points)
        assertNotNull(model)
        assertEquals(71, model!!.median)
        assertEquals(3, model.days)
        assertFalse(model.isSustainedOutlier(listOf(HeartPoint(180, 1000L))))
        assertTrue(model.isSustainedOutlier(listOf(HeartPoint(110, 1000L),
            HeartPoint(112, 2000L), HeartPoint(111, 3000L))))
    }
}
