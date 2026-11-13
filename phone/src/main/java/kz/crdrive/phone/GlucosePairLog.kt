package kz.crdrive.phone

import android.content.Context
import java.io.File
import java.util.Locale

const val MEAL_FASTING = "натощак"
const val MEAL_AFTER = "после еды"
const val MEAL_OTHER = "другое"

/** Who was measured and in which state; needed to train and to test on people the model has not seen. */
data class PairMeta(val participant: String, val meal: String, val minutesAfterMeal: Int?, val source: String = "")

/**
 * Training data: one row per glucometer reading that follows a fresh signal measurement (camera or
 * watch). Stored in files/glucose_pairs_v2.csv; the raw camera signals are in files/camera_ppg (one csv per measurement).
 * Pull with: adb exec-out run-as kz.crdrive cat files/glucose_pairs_v2.csv
 */
object GlucosePairLog {
    private val FEATURES = listOf("amp", "amp_cv", "apg_ba", "apg_da", "area_ratio", "fall", "hr", "p_05_1", "p_1_2",
        "p_2_4", "p_4_8", "rise", "rmssd", "sdnn", "sqi", "vpg_peak", "w50")
    private val HEADER = listOf("time_ms", "participant", "source", "meal_state", "minutes_after_meal", "glucose_mmol",
        "camera_capture_ms", "ai_estimate_mmol", "watch_bpm", "spo2_percent", "acceleration", "on_body")
        .plus(FEATURES.map { "ppg_$it" })

    private fun file(context: Context) = File(context.applicationContext.filesDir, "glucose_pairs_v2.csv")

    fun count(context: Context): Int {
        val f = file(context)
        return if (f.exists()) (f.readLines().size - 1).coerceAtLeast(0) else 0
    }

    @Synchronized
    fun append(context: Context, glucoseMmol: Double, telemetry: TelemetryState, ai: GlucoseAiModel.Result, meta: PairMeta) {
        val f = file(context)
        if (!f.exists()) f.writeText(HEADER.joinToString(",") + "\n")
        fun num(v: Number?) = v?.let { String.format(Locale.US, "%.6g", it.toDouble()) } ?: ""
        fun txt(s: String) = s.replace(",", " ").replace("\n", " ")
        val row = listOf(
            System.currentTimeMillis().toString(), txt(meta.participant), txt(meta.source), txt(meta.meal), num(meta.minutesAfterMeal),
            num(glucoseMmol), CameraPpgCapture.lastCaptureAtMs.takeIf { it > 0 }?.toString() ?: "", num(ai.mmol),
            num(telemetry.bpm), num(telemetry.contextHealth.oxygenPercent), num(telemetry.sensors.acceleration),
            telemetry.sensors.onBody?.let { if (it) "1" else "0" } ?: ""
        ) + FEATURES.map { num(ai.features[it]) }
        f.appendText(row.joinToString(",") + "\n")
    }
}
