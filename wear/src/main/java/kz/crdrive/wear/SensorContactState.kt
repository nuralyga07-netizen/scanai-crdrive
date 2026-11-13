package kz.crdrive.wear

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Shared warning shown by the watch UI when optical sensors lose skin contact. */
internal object SensorContactState {
    val warning = mutableStateOf<String?>(null)
    private var lastVibrationAt = 0L

    fun warn(context: Context, message: String = "Прижмите часы к руке") {
        warning.value = message
        val now = System.currentTimeMillis()
        if (now - lastVibrationAt >= VIBRATION_COOLDOWN_MS) {
            lastVibrationAt = now
            WatchHaptics.playContactWarning(context)
        }
    }

    fun clear() { warning.value = null }

    private const val VIBRATION_COOLDOWN_MS = 20_000L
}
