package kz.crdrive.phone

data class AttentionSignals(val value: Int?, val details: String)

data class SignalCoverage(val summary: String, val deviceWarning: String?)

/** Availability and device health are separate from the pilot attention index. */
fun signalCoverage(state: TelemetryState, now: Long): SignalCoverage {
    val fresh = state.withFreshness(now)
    val wristFresh = fresh.sensors.updatedAt?.let { now - it in 0..30_000 } == true
    val sources = mutableListOf<String>()
    if (fresh.bpm != null) sources += "пульс"
    if (wristFresh && fresh.sensors.onBody != null) sources += "контакт с рукой"
    if (wristFresh && (fresh.sensors.acceleration != null || fresh.sensors.rotation != null)) sources += "движение"
    if (wristFresh && fresh.sensors.pressure != null) sources += "давление воздуха"
    if (fresh.contextHealth.sleepEndedAt?.let { now - it in 0..36 * 3_600_000 } == true) sources += "сон"
    if (fresh.contextHealth.oxygenMeasuredAt?.let { now - it in 0..24 * 3_600_000 } == true) sources += "последний SpO₂"
    if (fresh.sleepinessReport != null) sources += "самооценка"
    val deviceWarning = when {
        fresh.sensors.onBody == false && wristFresh -> "Часы сняты: оценка приостановлена"
        fresh.bpm == null -> "Нет свежего пульса: оценка приостановлена"
        fresh.battery != null && fresh.battery <= 15 -> "Низкий заряд часов: ${fresh.battery}%"
        else -> null
    }
    return SignalCoverage(sources.joinToString().ifBlank { "Нет свежих источников" }, deviceWarning)
}

/** Pilot signal index, not a fatigue measurement or a trained medical model. */
fun attentionSignals(state: TelemetryState, now: Long): AttentionSignals {
    val fresh = state.withFreshness(now)
    val contactIsFresh = fresh.sensors.updatedAt?.let { now - it in 0..30_000 } == true
    if (fresh.bpm == null || !contactIsFresh || fresh.sensors.onBody != true) {
        return AttentionSignals(null, "Нужны свежий пульс и подтверждение, что часы на руке")
    }

    val factors = mutableListOf<String>()
    var value = 0
    if (fresh.lowPulseAlert) {
        value += 70
        factors += "подтверждённое предупреждение о низком пульсе"
    } else when (fresh.assessment.level) {
        RiskLevel.HIGH -> { value += 35; factors += "выраженное отклонение пульса" }
        RiskLevel.ATTENTION -> { value += 20; factors += "отклонение пульса" }
        else -> Unit
    }
    if (fresh.personalModel?.isSustainedOutlier(fresh.samples) == true) {
        value += 15
        factors += "отклонение от личного пульса"
    }
    val sleepAge = fresh.contextHealth.sleepEndedAt?.let { now - it }
    if (fresh.contextHealth.sleepHours?.let { it < 6.0 } == true &&
        sleepAge != null && sleepAge in 0..36 * 3_600_000) {
        value += 20
        factors += "сон менее 6 часов"
    }
    when (fresh.sleepinessReport) {
        1 -> { value += 20; factors += "водитель отметил усталость" }
        2 -> { value += 45; factors += "водитель отметил сонливость" }
    }
    val recentOxygen = fresh.contextHealth.oxygenPercent?.takeIf { oxygen ->
        oxygen in 0.0..100.0 && fresh.contextHealth.oxygenMeasuredAt?.let { now - it in 0..24 * 3_600_000 } == true
    }
    val explanation = factors.joinToString(" · ").ifBlank { "Дополнительных сигналов не выявлено" }
    val oxygenContext = recentOxygen?.let { " · SpO₂ ${it.toInt()}%: разовое измерение, в индекс не входит" } ?: ""
    return AttentionSignals(value.coerceIn(0, 100), explanation + oxygenContext)
}

/** A local reminder only; not an emergency or dispatcher alert. */
fun needsAttentionReminder(state: TelemetryState, now: Long): Boolean {
    val fresh = state.withFreshness(now)
    if (fresh.lowPulseAlert) return false // Already has a dedicated watch + phone alert.
    return (attentionSignals(fresh, now).value ?: 0) >= 50
}
