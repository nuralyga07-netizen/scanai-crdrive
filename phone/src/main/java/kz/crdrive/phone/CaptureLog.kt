package kz.crdrive.phone

import android.content.Context
import java.io.File
import java.util.Locale

/** Every successful camera capture with its signal features; glucometer values are attached later by capture number. */
object CaptureLog {
    private val FEATURES = listOf("amp", "amp_cv", "apg_ba", "apg_da", "area_ratio", "fall", "hr", "p_05_1", "p_1_2",
        "p_2_4", "p_4_8", "rise", "rmssd", "sdnn", "sqi", "vpg_peak", "w50")

    private fun file(context: Context) = File(context.applicationContext.filesDir, "captures_v3.csv")

    @Synchronized
    fun append(context: Context, captureMs: Long, r: GlucoseAiModel.Result) {
        val f = file(context)
        if (!f.exists()) f.writeText((listOf("idx", "capture_ms", "ai_mmol") + FEATURES.map { "ppg_$it" }).joinToString(",") + "\n")
        val idx = f.readLines().size
        fun num(v: Double?) = v?.let { String.format(Locale.US, "%.6g", it) } ?: ""
        f.appendText((listOf(idx.toString(), captureMs.toString(), num(r.mmol)) + FEATURES.map { num(r.features[it]) }).joinToString(",") + "\n")
    }
}
