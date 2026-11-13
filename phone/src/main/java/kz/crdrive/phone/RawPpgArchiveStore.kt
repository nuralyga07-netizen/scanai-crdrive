package kz.crdrive.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Local, append-only archive of every accepted raw PPG batch. */
class RawPpgArchiveStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "ppg_archive")

    @Synchronized
    fun append(samples: List<PpgPoint>) {
        if (samples.isEmpty()) return
        val first = samples.first().timestamp
        // The Wear listener can deliver the same data item twice; keep one copy.
        val key = "$first:${samples.last().timestamp}:${samples.size}"
        synchronized(Companion) {
            if (key == lastKey) return
            lastKey = key
        }
        directory.mkdirs()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(first))
        val target = File(directory, "ppg-$day.ndjson")
        val record = JSONObject().apply {
            put("schema", 1)
            put("captured_at_ms", first)
            put("received_at_ms", System.currentTimeMillis())
            put("timestamps_ms", JSONArray(samples.map { it.timestamp }))
            put("green", JSONArray(samples.map { it.green ?: JSONObject.NULL }))
            put("red", JSONArray(samples.map { it.red ?: JSONObject.NULL }))
            put("infrared", JSONArray(samples.map { it.infrared ?: JSONObject.NULL }))
        }
        target.appendText(record.toString() + "\n", Charsets.UTF_8)
    }

    private companion object {
        private var lastKey: String? = null
    }
}
