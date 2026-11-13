package kz.crdrive.phone

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HeartPoint(val bpm: Int, val timestamp: Long)
data class EcgReading(val hr: Int, val quality: Int, val wave: List<Float>, val measuredAt: Long)
data class PpgPoint(val timestamp: Long, val green: Int?, val red: Int?, val infrared: Int?)

data class ContextHealth(
    val sleepHours: Double? = null,
    val sleepEndedAt: Long? = null,
    val oxygenPercent: Double? = null,
    val oxygenMeasuredAt: Long? = null,
    val systolicMmHg: Double? = null,
    val diastolicMmHg: Double? = null,
    val pressureMeasuredAt: Long? = null,
    val source: String = "Нет данных"
)

data class SensorReadings(
    val onBody: Boolean? = null,
    val tripSteps: Int? = null,
    val acceleration: Double? = null,
    val rotation: Double? = null,
    val pressure: Double? = null,
    val updatedAt: Long? = null
)

data class RiskAssessment(
    val score: Int = 0,
    val level: RiskLevel = RiskLevel.UNKNOWN,
    val title: String = "Ожидание данных",
    val explanation: String = "Запустите измерение на часах CR Drive",
    val confidence: Int = 0,
    val baseline: Int? = null
)

data class TelemetryState(
    val bpm: Int? = null,
    val ppgSamples: List<PpgPoint> = emptyList(),
    val battery: Int? = null,
    val receivedAt: Long? = null,
    val source: String = "WAITING",
    val lowPulseAlert: Boolean = false,
    val motionAlert: Boolean = false,
    val sensors: SensorReadings = SensorReadings(),
    val samples: List<HeartPoint> = emptyList(),
    val assessment: RiskAssessment = RiskAssessment(),
    val events: List<String> = emptyList(),
    val history: HistorySummary = HistorySummary(0, 0, null, null, null, null),
    val personalBaseline: Int? = null,
    val personalModel: PersonalPulseModel? = null,
    val sleepinessReport: Int? = null,
    val sleepinessReportedAt: Long? = null,
    val contextHealth: ContextHealth = ContextHealth(),
    val watchLocation: WatchLocation? = null,
    val ecg: EcgReading? = null
)

enum class RiskLevel { UNKNOWN, NORMAL, ATTENTION, HIGH }

fun TelemetryState.withFreshness(now: Long): TelemetryState {
    val activeReport = sleepinessReportedAt?.let { now - it in 0..43_200_000 } == true
    val oxygenFresh = contextHealth.oxygenMeasuredAt?.let { now - it in 0..600_000 } == true
    val sensorsFresh = sensors.updatedAt?.let { now - it in 0..15_000 } == true
    val recentPpg = ppgSamples.filter { now - it.timestamp in 0..120_000 }
    var current = copy(
        sleepinessReport = sleepinessReport.takeIf { activeReport },
        ppgSamples = recentPpg,
        contextHealth = if (oxygenFresh) contextHealth else contextHealth.copy(
            oxygenPercent = null, oxygenMeasuredAt = null,
            source = if (contextHealth.source.contains("час")) "Ожидание свежего SpO₂" else contextHealth.source),
        sensors = if (sensorsFresh) sensors else SensorReadings(
            tripSteps = sensors.tripSteps, updatedAt = sensors.updatedAt)
    )
    val measured = receivedAt ?: return current
    if (now - measured <= 30_000 && measured <= now + 5_000) return current
    return current.copy(bpm = null, source = "НЕТ СВЕЖИХ ДАННЫХ", lowPulseAlert = false,
        assessment = RiskAssessment(title = "Ожидание нового измерения",
            explanation = "Последнее измерение: ${((now - measured).coerceAtLeast(0) / 1000)} сек назад. Оценка приостановлена."))
}

/** Transparent adaptive analytics used until CR Drive has validated training data. */
class RiskEngine {
    private val window = ArrayDeque<HeartPoint>()

    fun analyze(point: HeartPoint): RiskAssessment {
        window.addLast(point)
        while (window.size > 60) window.removeFirst()

        val values = window.map { it.bpm }.sorted()
        val baseline = values.getOrNull(values.size / 2) ?: point.bpm
        val delta = point.bpm - baseline
        val previous = window.dropLast(1).lastOrNull()
        val change = previous?.let { point.bpm - it.bpm } ?: 0
        val sustained = window.takeLast(3).count { it.bpm >= 105 }

        var score = 8
        score += (delta.coerceAtLeast(0) * 1.4).toInt()
        if (point.bpm >= 100) score += 18
        if (point.bpm >= 120) score += 25
        if (point.bpm >= 145) score += 20
        if (change >= 18) score += 16
        if (sustained >= 3) score += 12
        score = score.coerceIn(0, 100)

        val level = when {
            score >= 72 -> RiskLevel.HIGH
            score >= 42 -> RiskLevel.ATTENTION
            else -> RiskLevel.NORMAL
        }
        val reason = when {
            point.bpm >= 145 -> "Очень высокий пульс: ${point.bpm} bpm"
            sustained >= 3 -> "Пульс повышен в трёх измерениях подряд"
            change >= 18 -> "Резкий рост на $change bpm"
            delta >= 20 -> "На $delta bpm выше личной базы"
            else -> "Отклонений от личной базы не найдено"
        }
        val title = when (level) {
            RiskLevel.HIGH -> "Остановитесь безопасно"
            RiskLevel.ATTENTION -> "Требуется внимание"
            RiskLevel.NORMAL -> "Состояние стабильно"
            RiskLevel.UNKNOWN -> "Ожидание данных"
        }

        return RiskAssessment(
            score = score,
            level = level,
            title = title,
            explanation = reason,
            confidence = (window.size * 8).coerceAtMost(100),
            baseline = baseline
        )
    }

    fun reset() = window.clear()
}

object TelemetryRepository {
    private val engine = RiskEngine()
    private var historyStore: HistoryStore? = null
    private var historyRefreshedAt = 0L
    private val mutableState = MutableStateFlow(TelemetryState())
    val state = mutableState.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        if (historyStore != null) return
        val store = HistoryStore(context.applicationContext)
        historyStore = store
        val points = store.recentPoints()
        val report = store.lastSleepiness()
        mutableState.update { it.copy(history = store.summary(), personalBaseline = personalBaseline(points),
            personalModel = fitPersonalPulseModel(points),
            sleepinessReport = report?.first, sleepinessReportedAt = report?.second,
            samples = points.take(40).reversed()) }
    }

    @Synchronized fun reportSleepiness(level: Int) {
        if (level !in 0..2) return
        val now = System.currentTimeMillis()
        historyStore?.saveSleepiness(level, now)
        mutableState.update { it.copy(sleepinessReport = level, sleepinessReportedAt = now) }
    }

    fun updateContextHealth(data: ContextHealth) {
        mutableState.update { it.copy(contextHealth = data) }
    }

    fun updateEcg(reading: EcgReading) {
        mutableState.update { current ->
            val ev = "ЭКГ записана · пульс ${reading.hr} · контакт ${reading.quality}%"
            current.copy(ecg = reading, events = (listOf(ev) + current.events).take(12))
        }
    }

    fun updateWatchOxygen(percent: Double, measuredAt: Long) {
        if (percent !in 70.0..100.0) return
        val now = System.currentTimeMillis()
        if (measuredAt > now + 5_000 || now - measuredAt > 120_000) return
        mutableState.update { current ->
            current.copy(contextHealth = current.contextHealth.copy(
                oxygenPercent = percent,
                oxygenMeasuredAt = measuredAt,
                source = "Samsung Health Sensor SDK · часы"
            ))
        }
    }

    fun updateWatchLocation(location: WatchLocation) {
        if (!location.isFresh(System.currentTimeMillis())) return
        mutableState.update { current ->
            if (location.measuredAt <= (current.watchLocation?.measuredAt ?: Long.MIN_VALUE)) current
            else current.copy(watchLocation = location)
        }
    }

    fun updatePpg(samples: List<PpgPoint>) {
        if (samples.isEmpty()) return
        val now = System.currentTimeMillis()
        val valid = samples.filter { it.timestamp <= now + 5_000 && now - it.timestamp in 0..120_000 }
        if (valid.isEmpty()) return
        mutableState.update { current ->
            val lastTimestamp = current.ppgSamples.lastOrNull()?.timestamp ?: Long.MIN_VALUE
            val newer = valid.filter { it.timestamp > lastTimestamp }
            // Keep up to two minutes at the SDK's nominal 25 Hz so a research
            // capture can inspect a useful window; this remains in-memory only.
            if (newer.isEmpty()) current else current.copy(ppgSamples = (current.ppgSamples + newer).takeLast(3_000))
        }
    }

    fun receive(bpm: Int, battery: Int?, timestamp: Long, source: String = "LIVE", lowPulseAlert: Boolean = false,
                motionAlert: Boolean = false,
                sensors: SensorReadings? = null): Boolean {
        if (source != "LIVE") return false
        if (bpm !in 35..220) return false
        val now = System.currentTimeMillis()
        if (timestamp > now + 5_000 || now - timestamp > 120_000) return false
        val previous = mutableState.value
        if (source == previous.source && timestamp <= (previous.receivedAt ?: Long.MIN_VALUE)) return false
        if (sensors?.onBody == false) {
            updateSensors(sensors)
            return false
        }
        if (source != previous.source || timestamp - (previous.receivedAt ?: timestamp) > 30_000) engine.reset()
        val point = HeartPoint(bpm, timestamp)
        val recentLowPulseAlert = lowPulseAlert && now - timestamp <= 30_000
        val assessment = if (recentLowPulseAlert) {
            engine.analyze(point)
            RiskAssessment(level = RiskLevel.HIGH, title = "Низкий пульс — остановитесь безопасно",
                explanation = "Часы подтвердили несколько низких измерений подряд: $bpm bpm. Проверьте самочувствие.")
        } else engine.analyze(point)
        val store = historyStore
        store?.save(point, recentLowPulseAlert)
        val refreshHistory = store != null && (now - historyRefreshedAt >= 60_000 || recentLowPulseAlert)
        val summary = if (refreshHistory) store?.summary() else null
        val trainedModel = if (refreshHistory) store?.recentPoints()?.let(::fitPersonalPulseModel) else null
        val baseline = trainedModel?.median
        if (refreshHistory) historyRefreshedAt = now
        mutableState.update { current ->
            val newEvent = "${assessment.title}: ${assessment.explanation}"
            val events = if (current.events.firstOrNull() == newEvent) current.events
            else (listOf(newEvent) + current.events).take(6)
            current.copy(
                bpm = bpm,
                battery = battery,
                receivedAt = timestamp,
                source = source,
                lowPulseAlert = recentLowPulseAlert,
                motionAlert = motionAlert,
                sensors = sensors ?: current.sensors,
                samples = (current.samples + point).takeLast(40),
                assessment = assessment,
                events = events,
                history = summary ?: current.history,
                personalBaseline = baseline ?: current.personalBaseline,
                personalModel = trainedModel ?: current.personalModel
            )
        }
        return recentLowPulseAlert && !previous.lowPulseAlert
    }

    fun updateSensors(readings: SensorReadings) {
        mutableState.update { current ->
            if (readings.onBody == false) current.copy(
                bpm = null, lowPulseAlert = false, sensors = readings, source = "ЧАСЫ СНЯТЫ",
                assessment = RiskAssessment(title = "Часы сняты с руки",
                    explanation = "Измерение пульса приостановлено"))
            else current.copy(sensors = readings)
        }
    }

    fun reset() {
        engine.reset()
        mutableState.value = TelemetryState()
    }
}
