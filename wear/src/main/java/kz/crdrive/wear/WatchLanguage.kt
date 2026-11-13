package kz.crdrive.wear

enum class WatchLanguage { RU, KZ, EN }

fun watchText(language: WatchLanguage, ru: String, kz: String, en: String) = when (language) {
    WatchLanguage.RU -> ru
    WatchLanguage.KZ -> kz
    WatchLanguage.EN -> en
}

fun watchStatusText(language: WatchLanguage, status: String): String = when (status) {
    "Готов к запуску" -> watchText(language, status, "Іске қосуға дайын", "Ready to start")
    "Фоновый режим · ожидание" -> watchText(language, status, "Фондық режим · күту", "Background · waiting")
    "Ожидаем свежий пульс" -> watchText(language, status, "Жаңа пульсті күтуде", "Waiting for heart rate")
    "Часы сняты с руки" -> watchText(language, status, "Сағат қолдан шешілді", "Watch removed")
    "Сигнал отправляется" -> watchText(language, status, "Сигнал жіберілуде", "Sending signal")
    "Ошибка отправки" -> watchText(language, status, "Жіберу қатесі", "Send error")
    "Датчик недоступен" -> watchText(language, status, "Датчик қолжетімсіз", "Sensor unavailable")
    "Мониторинг остановлен" -> watchText(language, status, "Бақылау тоқтатылды", "Monitoring stopped")
    "НИЗКИЙ ПУЛЬС · остановитесь" -> watchText(language, status,
        "ТӨМЕН ПУЛЬС · тоқтаңыз", "LOW PULSE · stop safely")
    else -> status
}
