package kz.crdrive.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchLanguageTest {
    @Test fun startAndAlertTranslate() {
        assertEquals("Start trip", watchText(WatchLanguage.EN,
            "Начать поездку", "Сапарды бастау", "Start trip"))
        assertEquals("ТӨМЕН ПУЛЬС · тоқтаңыз", watchStatusText(WatchLanguage.KZ,
            "НИЗКИЙ ПУЛЬС · остановитесь"))
    }
}
