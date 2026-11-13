package kz.crdrive.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local history of glucose readings (mmol/L): camera estimates and glucometer entries. Stays on the phone. */
class GlucoseHistoryStore(context: Context) {
    data class Entry(val at: Long, val mmol: Double, val source: String)

    private val prefs = context.applicationContext.getSharedPreferences("glucose_history", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Entry> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Entry(o.getLong("at"), o.getDouble("v"), o.getString("s"))
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun add(mmol: Double, source: String) {
        val list = (all() + Entry(System.currentTimeMillis(), mmol, source)).takeLast(200)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("at", it.at).put("v", it.mmol).put("s", it.source)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    companion object {
        const val CAMERA = "камера"
        const val WATCH = "часы"
        const val METER = "глюкометр"
    }
}
