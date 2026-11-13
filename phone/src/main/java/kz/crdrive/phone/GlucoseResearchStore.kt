package kz.crdrive.phone

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.UUID

data class GlucoseCaptureResult(val saved: Boolean, val message: String)

/** Stores consented research pairs locally. It never derives or invents glucose labels. */
class GlucoseResearchStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "glucose_research_sessions.csv")
    private val preferences = appContext.getSharedPreferences("glucose_research", Context.MODE_PRIVATE)

    fun sessionCount(): Int = preferences.getInt("session_count", 0)

    @Synchronized
    fun capture(subjectId: String, referenceMmolL: Double, state: TelemetryState, now: Long): GlucoseCaptureResult {
        val safeSubject = subjectId.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_-]"), "-")
        if (safeSubject.length !in 3..32) {
            return GlucoseCaptureResult(false, "Укажите обезличенный ID участника: 3–32 символа")
        }
        if (referenceMmolL !in 1.0..33.0) {
            return GlucoseCaptureResult(false, "Введите реальное показание глюкометра от 1,0 до 33,0 ммоль/л")
        }
        val window = state.ppgSamples.filter { it.timestamp <= now + 5_000 && now - it.timestamp in 0..30_000 }
        if (window.size < 250) {
            return GlucoseCaptureResult(false, "Нужно минимум 10 секунд свежего PPG. Оставьте часы плотно на руке")
        }
        val sessionId = UUID.randomUUID().toString()
        if (!file.exists()) {
            file.writeText("subject_id,session_id,timestamp_ms,ppg_green,ppg_red,ppg_ir,heart_rate_bpm,spo2_percent,systolic_mmhg,diastolic_mmhg,glucose_mmol_l\n")
        }
        val bpm = state.bpm?.toString().orEmpty()
        val health = state.contextHealth
        val oxygen = health.oxygenPercent?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()
        val systolic = health.systolicMmHg?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()
        val diastolic = health.diastolicMmHg?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()
        val reference = String.format(Locale.US, "%.3f", referenceMmolL)
        file.appendText(buildString {
            window.forEach { point ->
                append(safeSubject).append(',').append(sessionId).append(',').append(point.timestamp).append(',')
                append(point.green ?: "").append(',').append(point.red ?: "").append(',')
                append(point.infrared ?: "").append(',').append(bpm).append(',').append(oxygen).append(',')
                append(systolic).append(',').append(diastolic).append(',').append(reference).append('\n')
            }
        })
        val count = sessionCount() + 1
        preferences.edit().putInt("session_count", count).apply()
        return GlucoseCaptureResult(true, "Сессия №$count сохранена: ${window.size} PPG-отсчётов")
    }

    fun exportUri(): android.net.Uri? {
        if (!file.exists() || sessionCount() == 0) return null
        val export = File(appContext.cacheDir, "cr_drive_glucose_research.csv")
        file.copyTo(export, overwrite = true)
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", export)
    }
}
