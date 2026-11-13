package kz.crdrive.phone

enum class PhoneLanguage { RU, KZ, EN }

private fun pair(kz: String, en: String) = kz to en

private val translations = mapOf(
    "ПОЕЗДКА ПОД КОНТРОЛЕМ" to pair("САПАР БАҚЫЛАУДА", "TRIP MONITORING"),
    "Часы передают данные" to pair("Сағат деректерді жіберіп жатыр", "Watch is sending data"),
    "Ожидаем запуск на часах" to pair("Сағатта іске қосуды күтеміз", "Waiting for watch to start"),
    "Карта GPS" to pair("GPS картасы", "GPS map"),
    "ЗДОРОВЬЕ И СИГНАЛЫ" to pair("ДЕНСАУЛЫҚ ЖӘНЕ СИГНАЛДАР", "HEALTH AND SIGNALS"),
    "ТЕМПЕРАТУРА" to pair("ТЕМПЕРАТУРА", "TEMPERATURE"),
    "ДАВЛЕНИЕ" to pair("ҚАН ҚЫСЫМЫ", "BLOOD PRESSURE"),
    "ЭКГ" to pair("ЭКГ", "ECG"),
    "САХАР" to pair("ҚАНТ", "GLUCOSE"),
    "Нет источника" to pair("Дерек көзі жоқ", "No data source"),
    "Нет измерения" to pair("Өлшем жоқ", "No reading"),
    "Не подключено" to pair("Қосылмаған", "Not connected"),
    "Сохранено" to pair("Сақталған", "Saved reading"),
    "Оценка по доступным сигналам" to pair("Қолжетімді сигналдар бойынша бағалау", "Assessment from available signals"),
    "Оценка строится по доступным сигналам и самочувствию водителя; это не диагноз." to pair(
        "Баға қолжетімді сигналдар мен жүргізушінің хал-жағдайына негізделеді; бұл диагноз емес.",
        "Assessment uses available signals and the driver's self-report; it is not a diagnosis."),
    "Добавьте самооценку к сигналам часов — так картина поездки будет точнее." to pair(
        "Сағат сигналдарына өз жағдайыңызды қосыңыз — сапар туралы мәлімет толық болады.",
        "Add how you feel to the watch signals for a fuller picture of the trip."),
    "Числа показываются только из подключённых измерений. Давление — последняя запись, не поток с часов." to pair(
        "Сандар тек қосылған өлшемдерден көрсетіледі. Қан қысымы — сағаттан тікелей ағын емес, соңғы жазба.",
        "Numbers appear only from connected measurements. Blood pressure is the last saved record, not a live watch stream."),
    "Заглушки означают, что измерение ещё не подключено. Давление появится из сохранённой записи Health Connect." to pair(
        "Белгілер өлшем әлі қосылмағанын білдіреді. Қан қысымы Health Connect жүйесіндегі сақталған жазбадан көрсетіледі.",
        "Placeholders mean the measurement is not connected yet. Blood pressure appears from a saved Health Connect record."),
    "Главное" to pair("Басты бет", "Home"),
    "Показатели" to pair("Көрсеткіштер", "Vitals"),
    "Данные" to pair("Деректер", "Data"),
    "События" to pair("Оқиғалар", "Events"),
    "GPS часов" to pair("Сағат GPS-і", "Watch GPS"),
    "GPS" to pair("GPS", "GPS"),
    "О проекте" to pair("Жоба туралы", "About"),
    "О нас" to pair("Біз туралы", "About"),
    "Безопасность водителя" to pair("Жүргізуші қауіпсіздігі", "Driver safety"),
    "ПУЛЬС С ЧАСОВ" to pair("САҒАТТАҒЫ ПУЛЬС", "WATCH HEART RATE"),
    " уд/мин" to pair(" соғ/мин", " bpm"),
    "ОБЩАЯ ОЦЕНКА · НЕ ДИАГНОЗ" to pair("ЖАЛПЫ БАҒА · ДИАГНОЗ ЕМЕС", "OVERALL ASSESSMENT · NOT A DIAGNOSIS"),
    "ИНДЕКС СИГНАЛОВ ВНИМАНИЯ" to pair("НАЗАР АУДАРУ СИГНАЛДАРЫНЫҢ ИНДЕКСІ", "ATTENTION SIGNAL INDEX"),
    "Нет оценки" to pair("Баға жоқ", "No assessment"),
    "Пилотная шкала сигналов, не датчик усталости и не обученный ИИ. Движение и SpO₂ в балл не входят." to pair(
        "Сынақ шкаласы: шаршау датчигі де, оқытылған ЖИ де емес. Қозғалыс пен SpO₂ есепке кірмейді.",
        "Pilot signal scale, not a fatigue sensor or trained AI. Motion and SpO₂ are not scored."),
    "ЧАСЫ CR DRIVE" to pair("CR DRIVE САҒАТЫ", "CR DRIVE WATCH"),
    "Нет свежего сигнала" to pair("Жаңа сигнал жоқ", "No fresh signal"),
    "Сигнал получен" to pair("Сигнал алынды", "Signal received"),
    "ЗАРЯД ЧАСОВ" to pair("САҒАТ ЗАРЯДЫ", "WATCH BATTERY"),
    "ИНДЕКС ОТКЛОНЕНИЙ" to pair("АУЫТҚУ ИНДЕКСІ", "DEVIATION INDEX"),
    "ОБЫЧНЫЙ ПУЛЬС" to pair("ӘДЕТТЕГІ ПУЛЬС", "USUAL HEART RATE"),
    "GPS ЧАСОВ" to pair("САҒАТ GPS-І", "WATCH GPS"),
    "Поиск GPS-сигнала" to pair("GPS сигналы ізделуде", "Searching for GPS"),
    "Запустите поездку на часах" to pair("Сағатта сапарды бастаңыз", "Start a trip on the watch"),
    "Часы измеряют пульс; ожидаем точный GPS-сигнал. На улице сигнал обычно лучше." to pair(
        "Сағат пульсті өлшеп жатыр; дәл GPS сигналы күтілуде. Сыртта сигнал әдетте жақсырақ.",
        "The watch is measuring heart rate; waiting for an accurate GPS fix. Signal is usually better outdoors."),
    "ЛИЧНЫЙ ОРИЕНТИР · 7 ДНЕЙ" to pair("ЖЕКЕ БАҒДАР · 7 КҮН", "PERSONAL BASELINE · 7 DAYS"),
    "Личный ориентир появится после 100 сохранённых отсчётов за 3 разных дня" to pair(
        "Жеке бағдар 3 күнде сақталған 100 өлшемнен кейін пайда болады",
        "Personal baseline appears after 100 saved readings across 3 days"),
    "Персональная модель: данных для обучения пока недостаточно" to pair(
        "Жеке модельге әзірге дерек жеткіліксіз", "Not enough data for the personal model yet"),
    "Данные хранятся только на телефоне 7 дней. Разные условия поездки влияют на пульс." to pair(
        "Деректер телефонда ғана 7 күн сақталады. Жол жағдайы пульске әсер етеді.",
        "Data stays on this phone for 7 days. Driving conditions affect heart rate."),
    "СОНЛИВОСТЬ" to pair("ҰЙҚЫШЫЛДЫҚ", "SLEEPINESS"),
    "Отметьте самочувствие. Часы не измеряют сонливость напрямую." to pair(
        "Өзіңізді қалай сезінетініңізді белгілеңіз. Сағат ұйқышылдықты тікелей өлшемейді.",
        "Report how you feel. The watch does not measure sleepiness directly."),
    "Бодр" to pair("Сергек", "Alert"),
    "Устал" to pair("Шаршадым", "Tired"),
    "Клонит в сон" to pair("Ұйқым келді", "Sleepy"),
    "Если клонит в сон, остановитесь в безопасном месте и отдохните." to pair(
        "Ұйқыңыз келсе, қауіпсіз жерде тоқтап, демалыңыз.",
        "If sleepy, stop in a safe place and rest."),
    "СОН И КИСЛОРОД" to pair("ҰЙҚЫ ЖӘНЕ ОТТЕГІ", "SLEEP AND OXYGEN"),
    "ПОСЛЕДНИЙ СОН" to pair("СОҢҒЫ ҰЙҚЫ", "LATEST SLEEP"),
    "ПОСЛЕДНИЙ SpO₂" to pair("СОҢҒЫ SpO₂", "LATEST SpO₂"),
    "Нет данных" to pair("Дерек жоқ", "No data"),
    "Нет свежих данных" to pair("Жаңа дерек жоқ", "No recent data"),
    "Подключить данные здоровья" to pair("Денсаулық деректерін қосу", "Connect health data"),
    "Сон учитывается как контекст. SpO₂ показывается справочно; одиночное измерение не определяет состояние водителя." to pair(
        "Ұйқы тек қосымша мәлімет. SpO₂ анықтама үшін көрсетіледі; бір өлшем жүргізуші күйін анықтамайды.",
        "Sleep is context only. SpO₂ is informational; one reading cannot determine driver condition."),
    "ДАТЧИКИ ЧАСОВ" to pair("САҒАТ ДАТЧИКТЕРІ", "WATCH SENSORS"),
    "КОНТАКТ С РУКОЙ" to pair("ҚОЛМЕН ЖАНАСУ", "WRIST CONTACT"),
    "На руке" to pair("Қолда", "On wrist"),
    "Часы сняты" to pair("Сағат шешілді", "Watch removed"),
    "ШАГИ ЗА ПОЕЗДКУ" to pair("САПАРДАҒЫ ҚАДАМДАР", "TRIP STEPS"),
    "УСКОРЕНИЕ" to pair("ҮДЕУ", "ACCELERATION"),
    "ВРАЩЕНИЕ" to pair("АЙНАЛУ", "ROTATION"),
    "ДАВЛЕНИЕ ВОЗДУХА" to pair("АУА ҚЫСЫМЫ", "AIR PRESSURE"),
    "Движение и давление показаны как данные датчиков, а не как оценка усталости." to pair(
        "Қозғалыс пен қысым — датчик деректері, шаршау бағасы емес.",
        "Motion and pressure are sensor readings, not a fatigue assessment."),
    "Показания поступают только с часов. Последний сигнал обновляется с учётом времени измерения." to pair(
        "Көрсеткіштер тек сағаттан келеді. Соңғы сигнал өлшенген уақытпен көрсетіледі.",
        "Readings come only from the watch. The latest signal uses its measurement time."),
    "УВЕДОМЛЕНИЯ" to pair("ХАБАРЛАМАЛАР", "ALERTS"),
    "Событий пока нет" to pair("Әзірге оқиға жоқ", "No events yet"),
    "Запустите измерение на часах CR Drive" to pair("CR Drive сағатында өлшеуді бастаңыз", "Start monitoring on the CR Drive watch"),
    "только что" to pair("жаңа ғана", "just now"),
    "ранее" to pair("бұрын", "earlier"),
    "⚠ Низкий пульс — остановитесь безопасно" to pair("⚠ Пульс төмен — қауіпсіз тоқтаңыз", "⚠ Low pulse — stop safely"),
    "Проверьте самочувствие; это не диагноз" to pair("Күйіңізді тексеріңіз; бұл диагноз емес", "Check how you feel; this is not a diagnosis"),
    "CR Drive оценивает риск, но не ставит диагноз. Для реального продукта тревоги должны подтверждаться медицинскими испытаниями." to pair(
        "CR Drive тәуекелді бағалайды, бірақ диагноз қоймайды. Нақты өнімдегі ескертулер медициналық сынақтан өтуі тиіс.",
        "CR Drive assesses risk, not diagnoses. Alerts require clinical validation before real-world safety use."),
    "Последняя GPS-точка" to pair("Соңғы GPS нүктесі", "Last GPS fix"),
    "Нет свежей GPS-точки" to pair("Жаңа GPS нүктесі жоқ", "No fresh GPS fix"),
    "Запустите поездку на часах и выйдите туда, где доступен GPS-сигнал." to pair(
        "Сағатта сапарды бастаңыз және GPS сигналы бар ашық жерге шығыңыз.",
        "Start a trip on the watch and move somewhere with GPS reception."),
    "© OpenStreetMap contributors · карта требует интернет" to pair(
        "© OpenStreetMap үлес қосушылары · картаға интернет керек",
        "© OpenStreetMap contributors · map requires internet"),
    "Открыть точку часов в Google Maps" to pair("Сағат нүктесін Google Maps-та ашу", "Open watch fix in Google Maps"),
    "ТЕЛЕФОН ВОДИТЕЛЯ" to pair("ЖҮРГІЗУШІ ТЕЛЕФОНЫ", "DRIVER'S PHONE"),
    "Проверить связь с часами" to pair("Сағат байланысын тексеру", "Check watch connection"),
    "Безопасность водителя — в центре внимания." to pair(
        "Жүргізуші қауіпсіздігі — басты назарда.", "Driver safety comes first."),
    "НАШИ ДОСТИЖЕНИЯ" to pair("БІЗДІҢ ЖЕТІСТІКТЕР", "OUR ACHIEVEMENTS"),
    "Финалисты Pizza Pitch" to pair("Pizza Pitch финалистері", "Pizza Pitch finalists"),
    "2 место на Business Challenge" to pair("Business Challenge: 2-орын", "2nd place at Business Challenge"),
    "3 место на National Founder Cup" to pair("National Founder Cup: 3-орын", "3rd place at National Founder Cup"),
    "Пульс и другие сигналы не являются медицинским диагнозом или прямым измерением усталости." to pair(
        "Пульс және басқа сигналдар медициналық диагноз да, шаршауды тікелей өлшеу де емес.",
        "Heart rate and other signals are not a diagnosis or a direct fatigue measurement."),
    "Анализ по правилам · не измерение усталости" to pair(
        "Ережеге негізделген талдау · шаршауды өлшемейді", "Rule-based analysis · not a fatigue measurement"),
    "Состояние стабильно" to pair("Жағдай тұрақты", "Condition stable"),
    "Нет выявленных сигналов" to pair("Сигналдар анықталмады", "No signals detected"),
    "Ожидание данных" to pair("Деректер күтілуде", "Waiting for data")
    ,"Нет полной оценки" to pair("Толық баға жоқ", "No full assessment")
    ,"Нужен свежий пульс с часов" to pair("Сағаттан жаңа пульс қажет", "Fresh watch heart rate needed")
    ,"Не подтверждён свежий контакт часов с рукой" to pair("Сағаттың қолмен жанасуы расталмады", "Fresh wrist contact not confirmed")
    ,"Остановитесь безопасно" to pair("Қауіпсіз тоқтаңыз", "Stop safely")
    ,"Нужен отдых" to pair("Демалу қажет", "Rest needed")
    ,"Проверьте самочувствие" to pair("Күйіңізді тексеріңіз", "Check how you feel")
    ,"Требуется внимание" to pair("Назар аудару қажет", "Attention needed")
    ,"Подтверждённая тревога о низком пульсе с часов" to pair(
        "Сағат төмен пульс туралы дабылды растады", "Watch confirmed a low-heart-rate alert")
    ,"Водитель отметил сонливость; остановитесь в безопасном месте" to pair(
        "Жүргізуші ұйқышылдықты белгіледі; қауіпсіз жерде тоқтаңыз",
        "Driver reported sleepiness; stop in a safe place")
    ,"Отклонений от личной базы не найдено" to pair(
        "Жеке деңгейден ауытқу табылмады", "No deviation from personal baseline")
    ,"Дополнительных сигналов не выявлено" to pair(
        "Қосымша сигналдар анықталмады", "No additional signals detected")
    ,"Нужны свежий пульс и подтверждение, что часы на руке" to pair(
        "Жаңа пульс және сағаттың қолда екені қажет",
        "Fresh heart rate and confirmed wrist contact required")
    ,"Нет свежих источников" to pair("Жаңа дерек көздері жоқ", "No fresh sources")
    ,"Часы сняты: оценка приостановлена" to pair("Сағат шешілді: бағалау тоқтады", "Watch removed: assessment paused")
    ,"Нет свежего пульса: оценка приостановлена" to pair(
        "Жаңа пульс жоқ: бағалау тоқтады", "No fresh heart rate: assessment paused")
    ,"пульс" to pair("пульс", "heart rate")
    ,"контакт с рукой" to pair("қолмен жанасу", "wrist contact")
    ,"движение" to pair("қозғалыс", "motion")
    ,"давление воздуха" to pair("ауа қысымы", "air pressure")
    ,"сон" to pair("ұйқы", "sleep")
    ,"самооценка" to pair("өзіндік баға", "self-report")
    ,"Часы" to pair("Сағат", "Watch")
    ,"Телефон" to pair("Телефон", "Phone")
    ,"НЕТ ДАННЫХ" to pair("ДЕРЕК ЖОҚ", "NO DATA")
    ,"Геопозиция телефона выключена" to pair("Телефон геопозициясы өшірулі", "Phone location off")
    ,"Точка телефона обновляется" to pair("Телефон орны жаңартылуда", "Phone fix updating")
    ,"Поиск GPS-сигнала" to pair("GPS сигналы ізделуде", "Searching for GPS")
    ,"Сопряжённые часы доступны" to pair("Жұпталған сағат қолжетімді", "Paired watch reachable")
)

fun localizePhone(text: String, language: PhoneLanguage): String {
    if (language == PhoneLanguage.RU) return text
    translations[text]?.let { return if (language == PhoneLanguage.KZ) it.first else it.second }
    val age = Regex("^(Измерено|Получено) (\\d+) сек назад$").matchEntire(text)
    if (age != null) return if (language == PhoneLanguage.KZ)
        "${age.groupValues[2]} сек бұрын өлшенді" else "Measured ${age.groupValues[2]} sec ago"
    val replacements = if (language == PhoneLanguage.KZ) listOf(
        "ГЕОПОЗИЦИЯ" to "ОРНАЛАСҚАН ЖЕРІ", "ИСТОЧНИКИ:" to "КӨЗДЕР:",
        "Учтено:" to "Ескерілді:", "Это не подтверждает безопасность вождения." to "Бұл жүргізудің қауіпсіздігін растамайды.",
        "Отклонений от личной базы не найдено" to "Жеке деңгейден ауытқу табылмады",
        "Нет свежих источников" to "Жаңа дерек көздері жоқ",
        "пульс" to "пульс", "часы на руке" to "сағат қолда",
        "контакт с рукой" to "қолмен жанасу", "движение" to "қозғалыс",
        "давление воздуха" to "ауа қысымы", "сон" to "ұйқы"
    ) else listOf(
        "ГЕОПОЗИЦИЯ" to "LOCATION", "ИСТОЧНИКИ:" to "SOURCES:",
        "Учтено:" to "Included:", "Это не подтверждает безопасность вождения." to "This does not prove driving is safe.",
        "Отклонений от личной базы не найдено" to "No deviation from personal baseline",
        "Нет свежих источников" to "No fresh sources",
        "часы на руке" to "watch on wrist", "контакт с рукой" to "wrist contact",
        "давление воздуха" to "air pressure", "движение" to "motion",
        "пульс" to "heart rate", "сон" to "sleep"
    )
    var result = text
    replacements.forEach { (from, to) -> result = result.replace(from, to) }
    if (result != text) return result
    return text
}
