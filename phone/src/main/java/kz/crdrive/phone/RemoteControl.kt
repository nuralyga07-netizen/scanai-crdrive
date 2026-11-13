package kz.crdrive.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Data-collection helper: lets the camera measurement be started and the glucometer value saved from adb. */
object RemoteControl {
    const val ACTION_START = "kz.crdrive.action.CAMERA_START"
    const val ACTION_SAVE = "kz.crdrive.action.PAIR_SAVE"
    var startTick by mutableStateOf(0)
    var startConsumed = 0
    var saveTick by mutableStateOf(0)
    var pendingGlucose = 0.0
    var pendingMeta = PairMeta("P1", MEAL_FASTING, null, "камера")
}
