package kz.crdrive.wear

data class DriverConditionAssessment(
    val score: Int?,
    val level: String,
    val reasons: List<String>,
    val confidence: Int
)

/**
 * Transparent pilot risk index. It combines available signals but is not a
 * trained drowsiness classifier or a medical diagnosis.
 */
object DriverConditionAnalyzer {
    fun analyze(
        bpm: Int?,
        oxygenPercent: Int?,
        oxygenFresh: Boolean,
        onBody: Boolean?,
        motionAlert: Boolean,
        ppgReady: Boolean
    ): DriverConditionAssessment {
        if (onBody == false) return DriverConditionAssessment(
            null, "Наденьте часы", listOf("Нет контакта с рукой"), 0)
        if (bpm == null) return DriverConditionAssessment(
            null, "Ожидание", listOf("Нужен свежий пульс"), if (onBody == true) 20 else 0)

        var score = 0
        val reasons = mutableListOf<String>()
        if (motionAlert) {
            score += 45
            reasons += "Нет движения 15 секунд"
        }
        when {
            bpm < 45 -> { score += 45; reasons += "Очень низкий пульс" }
            bpm < 55 -> { score += 25; reasons += "Пульс ниже обычного диапазона" }
            bpm > 130 -> { score += 35; reasons += "Очень высокий пульс" }
            bpm > 110 -> { score += 15; reasons += "Повышенный пульс" }
        }
        if (oxygenFresh && oxygenPercent != null) when {
            oxygenPercent < 90 -> { score += 45; reasons += "Низкий SpO₂" }
            oxygenPercent < 94 -> { score += 25; reasons += "SpO₂ требует внимания" }
            oxygenPercent < 96 -> { score += 10; reasons += "SpO₂ немного снижен" }
        }
        score = score.coerceIn(0, 100)
        if (reasons.isEmpty()) reasons += "Сигналы без заметных отклонений"

        var confidence = 35 // fresh heart rate
        if (onBody == true) confidence += 20
        if (oxygenFresh && oxygenPercent != null) confidence += 25
        if (ppgReady) confidence += 20
        val level = when {
            score >= 60 -> "Высокий риск"
            score >= 30 -> "Внимание"
            else -> "Норма"
        }
        return DriverConditionAssessment(score, level, reasons, confidence.coerceAtMost(100))
    }
}
