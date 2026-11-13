package kz.crdrive.phone

/** Explainable pilot insight, not a trained AI model or a diagnosis. */
fun personalInsight(state: TelemetryState): String {
    if (state.bpm == null) return "Нет свежего пульса — персональная оценка приостановлена."
    if (state.sleepinessReport == 2) return "Вы отметили сонливость. Остановитесь в безопасном месте и отдохните."
    val baseline = state.personalBaseline
        ?: return "Пока недостаточно данных для личного ориентира: нужны 3 дня наблюдений."
    val latest = state.samples.lastOrNull()
    val recent = if (latest == null) emptyList() else state.samples.takeLast(3)
        .filter { latest.timestamp - it.timestamp in 0..30_000 }
    if (recent.size < 3) return "Личный ориентир: $baseline bpm. Ждём три свежих измерения для сравнения."
    val average = recent.map { it.bpm }.average().toInt()
    val difference = average - baseline
    return when {
        difference >= 25 -> "Три свежих измерения в среднем на $difference bpm выше медианы за 7 дней. Проверьте самочувствие; причина может быть обычной нагрузкой."
        difference <= -25 -> "Три свежих измерения в среднем на ${-difference} bpm ниже медианы за 7 дней. Проверьте самочувствие."
        else -> "Последние измерения близки к медиане за 7 дней. Это не исключает усталость или другие риски."
    }
}
