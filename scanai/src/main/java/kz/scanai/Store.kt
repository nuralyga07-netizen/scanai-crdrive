package kz.scanai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class Reading(val at: Long, val mmol: Double, val source: String, val hr: Double)

/** Scan and glucometer history (stays on the phone). */
class HistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scanai_history", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Reading> = try {
        val arr = JSONArray(prefs.getString("items", "[]") ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Reading(o.getLong("at"), o.getDouble("v"), o.getString("s"), o.optDouble("hr", 0.0))
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun add(mmol: Double, source: String, hr: Double = 0.0) {
        val list = (all() + Reading(System.currentTimeMillis(), mmol, source, hr)).takeLast(300)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("at", it.at).put("v", it.mmol).put("s", it.source).put("hr", it.hr)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    companion object { const val SCAN = "скан"; const val METER = "глюкометр" }
}

private val FEATURES = listOf("amp", "amp_cv", "apg_ba", "apg_da", "area_ratio", "fall", "hr", "p_05_1", "p_1_2",
    "p_2_4", "p_4_8", "rise", "rmssd", "sdnn", "sqi", "vpg_peak", "w50")

private fun num(v: Double?) = v?.let { String.format(Locale.US, "%.6g", it) } ?: ""

/** Every successful scan with its signal features (training data). */
object CaptureLog {
    private fun file(c: Context) = File(c.applicationContext.filesDir, "captures_v3.csv")

    @Synchronized
    fun append(c: Context, captureMs: Long, r: GlucoseAiModel.Result) {
        val f = file(c)
        if (!f.exists()) f.writeText((listOf("idx", "capture_ms", "ai_mmol") + FEATURES.map { "ppg_$it" }).joinToString(",") + "\n")
        val idx = f.readLines().size
        f.appendText((listOf(idx.toString(), captureMs.toString(), num(r.mmol)) + FEATURES.map { num(r.features[it]) }).joinToString(",") + "\n")
    }
}

/** One scan paired with one glucometer value; same columns as the CR DRIVE pair log so the training scripts read it. */
object PairLog {
    private val HEADER = listOf("time_ms", "participant", "source", "meal_state", "minutes_after_meal", "glucose_mmol",
        "camera_capture_ms", "ai_estimate_mmol", "watch_bpm", "spo2_percent", "acceleration", "on_body")
        .plus(FEATURES.map { "ppg_$it" })

    private fun file(c: Context) = File(c.applicationContext.filesDir, "glucose_pairs_v2.csv")

    fun count(c: Context): Int { val f = file(c); return if (f.exists()) (f.readLines().size - 1).coerceAtLeast(0) else 0 }

    @Synchronized
    fun append(c: Context, glucose: Double, captureMs: Long, ai: GlucoseAiModel.Result, participant: String, meal: String, minutes: Int?) {
        val f = file(c)
        if (!f.exists()) f.writeText(HEADER.joinToString(",") + "\n")
        fun txt(s: String) = s.replace(",", " ").replace("\n", " ")
        val row = listOf(System.currentTimeMillis().toString(), txt(participant), "камера", txt(meal), minutes?.toString() ?: "",
            num(glucose), captureMs.toString(), num(ai.mmol), "", "", "", "") + FEATURES.map { num(ai.features[it]) }
        f.appendText(row.joinToString(",") + "\n")
    }
}

data class Profile(val name: String, val age: String, val diagnosis: String)

object ProfileStore {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("scanai_profile", Context.MODE_PRIVATE)
    fun load(c: Context) = p(c).let { Profile(it.getString("name", "") ?: "", it.getString("age", "") ?: "", it.getString("dx", "") ?: "") }
    fun save(c: Context, pr: Profile) { p(c).edit().putString("name", pr.name.trim()).putString("age", pr.age.trim()).putString("dx", pr.diagnosis).apply() }
}
