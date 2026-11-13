package kz.crdrive.phone

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/** Last glucometer reading (mmol/L) entered by the driver; synced to the watch like the vehicle number. */
class GlucoseManualStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    data class Reading(val mmol: Double, val measuredAt: Long)

    fun current(): Reading? {
        val at = preferences.getLong(KEY_AT, 0L)
        val v = preferences.getFloat(KEY_VALUE, 0f).toDouble()
        return if (at > 0L && v > 0.0) Reading(v, at) else null
    }

    /** Returns false when the value is outside a plausible 1.5–30 mmol/L range. */
    fun save(mmol: Double, onResult: (Boolean) -> Unit = {}): Boolean {
        if (mmol.isNaN() || mmol < 1.5 || mmol > 30.0) return false
        val now = System.currentTimeMillis()
        preferences.edit().putFloat(KEY_VALUE, mmol.toFloat()).putLong(KEY_AT, now).apply()
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putDouble(KEY_VALUE, mmol)
            dataMap.putLong(KEY_AT, now)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
        return true
    }

    companion object {
        const val PREFERENCES = "glucose_manual"
        const val KEY_VALUE = "glucose_mmol"
        const val KEY_AT = "measured_at"
        const val PATH = "/config/glucose"
    }
}
