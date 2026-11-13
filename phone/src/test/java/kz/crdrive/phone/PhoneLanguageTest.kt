package kz.crdrive.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneLanguageTest {
    @Test fun navigationAndSafetyTextTranslate() {
        assertEquals("Home", localizePhone("Главное", PhoneLanguage.EN))
        assertEquals("Басты бет", localizePhone("Главное", PhoneLanguage.KZ))
        assertEquals("Главное", localizePhone("Главное", PhoneLanguage.RU))
        assertEquals("No fresh GPS fix", localizePhone("Нет свежей GPS-точки", PhoneLanguage.EN))
    }

    @Test fun measurementAgeKeepsRealAge() {
        assertEquals("Measured 12 sec ago", localizePhone("Измерено 12 сек назад", PhoneLanguage.EN))
    }
}
