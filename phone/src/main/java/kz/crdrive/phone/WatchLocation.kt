package kz.crdrive.phone

data class WatchLocation(val latitude: Double, val longitude: Double,
    val accuracyMeters: Float, val measuredAt: Long, val provider: String = "gps") {
    fun isFresh(now: Long): Boolean = measuredAt <= now + 5_000 && now - measuredAt in 0..60_000 &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        accuracyMeters in 0f..(if (provider == "gps") 100f else 200f)
}
