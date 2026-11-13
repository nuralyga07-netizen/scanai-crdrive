package kz.crdrive.phone

data class DriverState(val title: String, val explanation: String)

/** Multi-source wellness context; no medical/fatigue diagnosis or trained classifier. */
fun assessDriverState(state: TelemetryState, now: Long): DriverState {
    val fresh = state.withFreshness(now)
    val onBodyFresh = fresh.sensors.updatedAt?.let { now - it in 0..30_000 } == true
    if (fresh.sensors.onBody == false && onBodyFresh) return DriverState("Нет оценки", "Часы сняты с руки")
    if (fresh.bpm == null) return DriverState("Нет оценки", "Нужен свежий пульс с часов")
    if (fresh.lowPulseAlert) return DriverState("Остановитесь безопасно", "Подтверждённая тревога о низком пульсе с часов")
    if (!onBodyFresh || fresh.sensors.onBody != true)
        return DriverState("Нет полной оценки", "Не подтверждён свежий контакт часов с рукой")
    if (fresh.sleepinessReport == 2) return DriverState("Нужен отдых", "Водитель отметил сонливость; остановитесь в безопасном месте")

    val factors = mutableListOf<String>()
    if (fresh.assessment.level == RiskLevel.HIGH || fresh.assessment.level == RiskLevel.ATTENTION)
        factors += "отклонение пульса"
    val sleep = fresh.contextHealth.sleepHours
    val sleepAge = fresh.contextHealth.sleepEndedAt?.let { now - it }
    if (sleep != null && sleepAge != null && sleepAge in 0..36 * 3_600_000 && sleep < 6.0)
        factors += "короткий сон (${String.format("%.1f", sleep)} ч)"
    if (fresh.sleepinessReport == 1) factors += "водитель отметил усталость"
    if (factors.isNotEmpty()) return DriverState("Проверьте самочувствие", factors.joinToString(" · "))

    val sources = mutableListOf("пульс")
    sources += "часы на руке"
    if (sleep != null && sleepAge != null && sleepAge in 0..36 * 3_600_000) sources += "сон"
    return DriverState("Нет выявленных сигналов", "Учтено: ${sources.joinToString()}. Это не подтверждает безопасность вождения.")
}
