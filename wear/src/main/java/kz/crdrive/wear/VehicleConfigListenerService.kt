package kz.crdrive.wear

import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object GlucoseManualState {
    val mmol = mutableStateOf<Double?>(null)
    val measuredAt = mutableStateOf(0L)
    const val PATH = "/config/glucose"
    const val PREFERENCES = "glucose_manual"

    fun load(context: android.content.Context) {
        val p = context.getSharedPreferences(PREFERENCES, 0)
        val at = p.getLong("measured_at", 0L)
        val v = p.getFloat("glucose_mmol", 0f).toDouble()
        if (at > 0L && v > 0.0) { mmol.value = v; measuredAt.value = at }
    }
}

object VehicleAssignmentState {
    val number = mutableStateOf("")

    fun saveFromWatch(context: android.content.Context, raw: String) {
        val normalized = raw.uppercase().trim()
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(16)
        context.getSharedPreferences(VehicleConfigListenerService.PREFERENCES, 0)
            .edit().putString(VehicleConfigListenerService.KEY, normalized).apply()
        number.value = normalized
        val request = PutDataMapRequest.create(VehicleConfigListenerService.PATH).apply {
            dataMap.putString(VehicleConfigListenerService.KEY, normalized)
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }
}

class VehicleConfigListenerService : WearableListenerService() {
    override fun onCreate() {
        super.onCreate()
        VehicleAssignmentState.number.value = preferences().getString(KEY, "") ?: ""
        GlucoseManualState.load(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == GlucoseManualState.PATH }
            .forEach { event ->
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                val v = map.getDouble("glucose_mmol", 0.0)
                val at = map.getLong("measured_at", 0L)
                if (v > 0.0 && at > 0L) {
                    getSharedPreferences(GlucoseManualState.PREFERENCES, MODE_PRIVATE).edit()
                        .putFloat("glucose_mmol", v.toFloat()).putLong("measured_at", at).apply()
                    GlucoseManualState.mmol.value = v
                    GlucoseManualState.measuredAt.value = at
                }
            }
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PATH }
            .forEach { event ->
                val number = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(KEY).orEmpty()
                preferences().edit().putString(KEY, number).apply()
                VehicleAssignmentState.number.value = number
            }
    }

    private fun preferences() = getSharedPreferences(PREFERENCES, MODE_PRIVATE)

    companion object {
        const val PATH = "/config/vehicle"
        const val PREFERENCES = "vehicle_assignment"
        const val KEY = "vehicle_number"
    }
}
