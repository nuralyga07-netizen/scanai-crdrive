package kz.crdrive.phone

import kotlin.math.abs

/** Robust, locally fitted pulse reference. It has no fatigue or medical labels. */
data class PersonalPulseModel(val median: Int, val mad: Int, val samples: Int, val days: Int) {
    fun deviation(readings: List<HeartPoint>): Int? {
        val latest = readings.lastOrNull() ?: return null
        val recent = readings.takeLast(3).filter { latest.timestamp - it.timestamp in 0..30_000 }
        if (recent.size < 3) return null
        val average = recent.map { it.bpm }.average().toInt()
        return average - median
    }

    fun isSustainedOutlier(readings: List<HeartPoint>): Boolean {
        val difference = deviation(readings) ?: return false
        return abs(difference) >= maxOf(20, 3 * mad)
    }
}

fun fitPersonalPulseModel(points: List<HeartPoint>): PersonalPulseModel? {
    val valid = points.filter { it.bpm in 35..220 }
    val days = valid.map { it.timestamp / HistoryStore.DAY }.distinct().size
    if (valid.size < 100 || days < 3) return null
    val ordered = valid.map { it.bpm }.sorted()
    val median = ordered[ordered.size / 2]
    val deviations = ordered.map { abs(it - median) }.sorted()
    return PersonalPulseModel(median, deviations[deviations.size / 2], valid.size, days)
}
