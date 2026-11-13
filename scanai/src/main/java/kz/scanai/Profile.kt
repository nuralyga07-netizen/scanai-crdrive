package kz.scanai

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val DIAGNOSES = listOf("Нет диагноза", "Преддиабет", "Диабет 1 типа", "Диабет 2 типа", "Другое")

@Composable
private fun inputColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Cyan, unfocusedBorderColor = TrackC,
    cursorColor = Cyan, focusedLabelColor = Cyan, unfocusedLabelColor = Muted)

@Composable
private fun ProfileForm(initial: Profile, button: String, onSave: (Profile) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var age by remember { mutableStateOf(initial.age) }
    var dx by remember { mutableStateOf(initial.diagnosis.ifBlank { DIAGNOSES[0] }) }
    OutlinedTextField(name, { name = it.take(24) }, label = { Text("Имя") }, singleLine = true, colors = inputColors(),
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(age, { age = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("Возраст") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = inputColors(),
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
    Spacer(Modifier.height(12.dp))
    Text("ДИАГНОЗ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    DIAGNOSES.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { d ->
                val on = dx == d
                Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp))
                    .background(if (on) Brush.horizontalGradient(listOf(Cyan.copy(alpha = 0.25f), Blue.copy(alpha = 0.25f))) else Brush.horizontalGradient(listOf(PanelC, PanelC)))
                    .border(1.dp, if (on) Cyan else TrackC, RoundedCornerShape(23.dp)).clickable { dx = d },
                    contentAlignment = Alignment.Center) { Text(d, color = if (on) White else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(10.dp))
    GradientButton(button, Modifier.fillMaxWidth(), pulse = name.isNotBlank()) {
        if (name.isNotBlank()) onSave(Profile(name.trim(), age, dx))
    }
}

/** First launch: registration; the name is then used to greet the person in the AI tab. */
@Composable
fun Onboarding(onDone: (Profile) -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(800)) }
    Box(Modifier.fillMaxSize().background(Navy)) {
        Cosmos {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).alpha(a.value),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(30.dp))
                Text("Добро пожаловать", color = White, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                Text("в ScanAI", color = Blue, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text("Расскажите о себе, чтобы советы были точнее", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(26.dp))
                ProfileForm(Profile("", "", ""), "НАЧАТЬ", onDone)
            }
        }
    }
}

private fun dayKey(ms: Long): Int {
    val c = Calendar.getInstance(); c.timeInMillis = ms
    return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
}

@Composable
fun ProfileScreen(readings: List<Reading>, profile: Profile, onSave: (Profile) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val high = readings.filter { it.mmol >= 7.8 }
    val highDays = high.map { dayKey(it.at) }.toSet().size

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(68.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Cyan, Blue, Color(0xFF6A5BFF)))),
            contentAlignment = Alignment.Center) {
            Text(profile.name.take(1).uppercase().ifBlank { "?" }, color = White, fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name.ifBlank { "Без имени" }, color = White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text(listOfNotNull(profile.age.takeIf { it.isNotBlank() }?.let { "$it лет" }, profile.diagnosis.takeIf { it.isNotBlank() })
                .joinToString(" · ").ifBlank { "Профиль не заполнен" }, color = Muted, fontSize = 13.sp)
        }
        Text(if (editing) "ЗАКРЫТЬ" else "ИЗМЕНИТЬ", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            modifier = Modifier.clickable { editing = !editing }.padding(8.dp))
    }
    Spacer(Modifier.height(18.dp))
    if (editing) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(PanelC).padding(16.dp)) {
            ProfileForm(profile, "СОХРАНИТЬ") { onSave(it); editing = false }
        }
        Spacer(Modifier.height(18.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("ЗАМЕРОВ", readings.size.toString(), Cyan, Modifier.weight(1f))
        StatCard("СРЕДНИЙ", if (readings.isEmpty()) "—" else "%.1f".format(readings.map { it.mmol }.average()), ZoneOk, Modifier.weight(1f))
        StatCard("ДНЕЙ С ВЫСОКИМ", highDays.toString(), if (highDays > 0) ZoneWarn else Muted, Modifier.weight(1f))
    }
    Spacer(Modifier.height(18.dp))
    CalendarCard(readings)
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(PanelC).padding(14.dp)) {
        Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Text(label, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
    }
}

/** Month calendar: each day coloured by that day's highest reading, so high-sugar days are visible at a glance. */
@Composable
private fun CalendarCard(readings: List<Reading>) {
    var offset by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<Int?>(null) }
    val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, offset) }
    val year = cal.get(Calendar.YEAR); val month = cal.get(Calendar.MONTH)
    val daysIn = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstCol = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7   // Monday first
    val byDay = readings.groupBy { dayKey(it.at) }
    val todayKey = dayKey(System.currentTimeMillis())
    val title = SimpleDateFormat("LLLL yyyy", Locale("ru")).format(cal.time).replaceFirstChar { it.uppercase() }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(PanelC).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = White, fontSize = 28.sp, modifier = Modifier.clickable { offset--; picked = null }.padding(horizontal = 10.dp))
            Text(title, color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("›", color = White, fontSize = 28.sp, modifier = Modifier.clickable { if (offset < 0) { offset++; picked = null } }.padding(horizontal = 10.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
                Text(it, color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        val rows = (firstCol + daysIn + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val day = r * 7 + c - firstCol + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                        if (day in 1..daysIn) {
                            val key = year * 10000 + (month + 1) * 100 + day
                            val max = byDay[key]?.maxOf { it.mmol }
                            val col = max?.let { zoneColor(it) }
                            val a = remember(key) { Animatable(0f) }
                            LaunchedEffect(key) { kotlinx.coroutines.delay((day * 14L)); a.animateTo(1f, tween(350)) }
                            Box(Modifier.fillMaxSize().alpha(a.value).clip(CircleShape)
                                .background(if (col != null) col.copy(alpha = if (max!! >= 7.8) 0.9f else 0.35f) else Color.Transparent)
                                .border(if (key == todayKey) 2.dp else 0.dp, if (key == todayKey) White else Color.Transparent, CircleShape)
                                .clickable(enabled = max != null) { picked = if (picked == key) null else key },
                                contentAlignment = Alignment.Center) {
                                Text("$day", color = if (col != null && max!! >= 7.8) Navy else if (col != null) White else Muted,
                                    fontSize = 13.sp, fontWeight = if (col != null) FontWeight.Black else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(ZoneOk, "норма"); LegendDot(ZoneWarn, "повышен"); LegendDot(ZoneHigh, "высокий"); LegendDot(ZoneLow, "низкий")
        }
        val sel = picked?.let { byDay[it] }
        if (sel != null) {
            Spacer(Modifier.height(12.dp))
            val fmt = SimpleDateFormat("HH:mm", Locale("ru"))
            sel.sortedBy { it.at }.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(zoneColor(e.mmol), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text("%.1f ммоль/л".format(e.mmol), color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${e.source} · ${fmt.format(Date(e.at))}", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Muted, fontSize = 11.sp)
    }
}
