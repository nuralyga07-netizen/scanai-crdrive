package kz.crdrive.phone

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class VehicleAssignmentStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun current(): String = preferences.getString(KEY, "") ?: ""

    fun save(raw: String, onResult: (Boolean) -> Unit = {}) {
        val number = normalize(raw)
        preferences.edit().putString(KEY, number).apply()
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString(KEY, number)
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    companion object {
        const val PREFERENCES = "vehicle_assignment"
        const val KEY = "vehicle_number"
        const val PATH = "/config/vehicle"
        fun normalize(value: String): String = value.uppercase().trim()
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(16)
    }
}
