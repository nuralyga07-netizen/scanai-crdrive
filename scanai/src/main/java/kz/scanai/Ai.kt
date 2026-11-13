package kz.scanai

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MODEL = "claude-sonnet-5-5"

private const val SYSTEM_PROMPT = """Ты — ИИ-ассистент приложения ScanAI. Помогаешь человеку понимать уровень глюкозы в крови (ммоль/л) и что делать дальше.
Ты видишь последние замеры пользователя: значения со сканера камеры телефона (ориентировочные, это исследовательский прототип, а не медицинский прибор) и, если есть, значения глюкометра. Если они расходятся, доверяй глюкометру и скажи об этом.
Отвечай по-русски, дружелюбно, коротко и конкретно (до 8 строк), опираясь на замеры человека.
Что можно советовать: что означает значение (норма натощак 3,9–5,5, после еды до 7,8); при повышенном сахаре — вода, лёгкая прогулка 10–15 минут, избегать быстрых сахаров, повторить замер через 1–2 часа; примеры блюд с низким гликемическим индексом; режим сна, питьё; при низком сахаре (ниже 3,9) — 15 г быстрых углеводов (сок, глюкоза, сахар) и повторный замер через 15 минут.
Лекарства: не назначай препараты и дозы, не советуй менять или отменять назначенное врачом лечение и не называй конкретные дозировки. Можно общими словами объяснить, что лекарства от диабета подбирает только врач, и напомнить принимать то, что назначено, по схеме.
Срочно вызвать скорую (103 / 112), если: сахар выше 13,9 с жаждой, тошнотой, рвотой, запахом ацетона, сильной слабостью; сахар ниже 3,0 или человек не может выпить сам; спутанность сознания, обморок, боль в груди, сильная одышка.
Не ставь диагнозов и не пугай. Если данных мало — скажи, какой замер сделать. В конце при необходимости напоминай про врача."""

data class ChatMsg(val fromUser: Boolean, val text: String)

fun embeddedOrSavedKey(c: Context): String =
    BuildConfig.API_KEY.ifBlank { c.getSharedPreferences("scanai_ai", Context.MODE_PRIVATE).getString("key", "") ?: "" }

object AiChat {
    val messages = mutableStateListOf<ChatMsg>()
    var busy by mutableStateOf(false)
    private val main = Handler(Looper.getMainLooper())

    private fun context(readings: List<Reading>): String {
        if (readings.isEmpty()) return "Замеров пока нет."
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale("ru"))
        val last = readings.takeLast(12)
        val sb = StringBuilder("Последние замеры (от старых к новым):\n")
        last.forEach { sb.append("- ${fmt.format(Date(it.at))}: %.1f ммоль/л (${it.source})".format(it.mmol)).append('\n') }
        val l = last.last()
        sb.append("Текущее значение: %.1f ммоль/л — ${zoneWord(l.mmol).lowercase()}.".format(l.mmol))
        return sb.toString()
    }

    fun send(c: Context, text: String, readings: List<Reading>, name: String = "", diagnosis: String = "") {
        val key = embeddedOrSavedKey(c)
        messages.add(ChatMsg(true, text))
        busy = true
        if (key.isBlank()) {
            // offline mini-assistant: no network, answers about glucose and health from the user's readings
            val answer = LocalBot.reply(text, readings, name, diagnosis)
            main.postDelayed({ messages.add(ChatMsg(false, answer)); busy = false }, 900)
            return
        }
        val history = messages.toList()
        Thread {
            val answer = try {
                val arr = JSONArray()
                history.takeLast(12).forEachIndexed { i, m ->
                    // the glucose context rides with the latest user message so the model always sees current readings
                    val content = if (i == history.takeLast(12).lastIndex && m.fromUser) "${context(readings)}\n\nВопрос: ${m.text}" else m.text
                    arr.put(JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", content))
                }
                val body = JSONObject().put("model", MODEL).put("max_tokens", 700).put("system", SYSTEM_PROMPT).put("messages", arr)
                val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 60000; conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
                if (code in 200..299) {
                    val content = JSONObject(resp).getJSONArray("content")
                    (0 until content.length()).mapNotNull { content.getJSONObject(it).optString("text").ifBlank { null } }.joinToString("\n")
                        .ifBlank { "Пустой ответ, попробуйте ещё раз." }
                } else when (code) {
                    401 -> "Ключ не принят (401). Проверьте ключ Anthropic API."
                    429 -> "Слишком много запросов (429). Подождите минуту."
                    else -> "Ошибка сервиса ($code). Попробуйте позже."
                }
            } catch (e: Exception) { "Нет связи с сервером. Проверьте интернет." }
            main.post { messages.add(ChatMsg(false, answer)); busy = false }
        }.start()
    }
}

@Composable
private fun aiFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Cyan, unfocusedBorderColor = TrackC,
    cursorColor = Cyan, focusedLabelColor = Cyan, unfocusedLabelColor = Muted)

@Composable
fun AiScreen(context: Context, readings: List<Reading>, profile: Profile) {
    val prefs = remember { context.getSharedPreferences("scanai_ai", Context.MODE_PRIVATE) }
    var hasKey by remember { mutableStateOf(true) }
    var keyDraft by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    val last = readings.lastOrNull()

    Column(Modifier.fillMaxWidth()) {
        val greet = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(profile.name) { greet.snapTo(0f); greet.animateTo(1f, tween(900)) }
        if (profile.name.isNotBlank()) {
            Text("Привет, ${profile.name}", color = White, fontSize = 30.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.padding(bottom = 6.dp).graphicsLayer { alpha = greet.value; translationY = (1f - greet.value) * 40f })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (hasKey) ZoneOk else ZoneWarn, CircleShape))
            Spacer(Modifier.size(10.dp))
            Text("ИИ-СКАН", color = White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        }
        Text(if (last != null) "Вижу ваш замер: %.1f ммоль/л · ${zoneWord(last.mmol).lowercase()}".format(last.mmol)
            else "Сделайте скан, и я дам советы по вашему результату", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))

        if (false) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(PanelC).padding(16.dp)) {
                Text("ПОДКЛЮЧЕНИЕ ИИ", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Вставьте ключ Anthropic API. Он хранится только на этом телефоне.", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
                OutlinedTextField(keyDraft, { keyDraft = it.trim() }, label = { Text("Ключ API") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), colors = aiFieldColors(),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(10.dp))
                GradientButton("ПОДКЛЮЧИТЬ", Modifier.fillMaxWidth(), pulse = false) {
                    if (keyDraft.length > 20) { prefs.edit().putString("key", keyDraft).apply(); hasKey = true; keyDraft = "" }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        if (AiChat.messages.isEmpty() && hasKey) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Оцени мой замер", "Что делать при высоком сахаре?", "Низкий сахар, что делать?", "Что выпить и съесть?", "Какие нормы сахара?", "Когда идти к врачу?").forEach { q ->
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(PanelC).clickable(enabled = !AiChat.busy) {
                        AiChat.send(context, q, readings, profile.name, profile.diagnosis)
                    }.padding(horizontal = 14.dp, vertical = 10.dp)) { Text(q, color = White, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        AiChat.messages.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                Box(Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(20.dp))
                    .background(if (m.fromUser) Brush.horizontalGradient(listOf(Blue, Color(0xFF3A5BFF))) else Brush.horizontalGradient(listOf(PanelC, PanelC)))
                    .padding(14.dp)) { Text(m.text, color = White, fontSize = 14.sp, lineHeight = 20.sp) }
            }
        }
        if (AiChat.busy) TypingDots()
        Spacer(Modifier.height(12.dp))
        if (hasKey) {
            OutlinedTextField(input, { input = it }, placeholder = { Text("Спросите про сахар…", color = Muted) },
                colors = aiFieldColors(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), maxLines = 3)
            Spacer(Modifier.height(10.dp))
            GradientButton("ОТПРАВИТЬ", Modifier.fillMaxWidth(), pulse = false) {
                if (input.isNotBlank() && !AiChat.busy) { AiChat.send(context, input.trim(), readings, profile.name, profile.diagnosis); input = "" }
            }
            Text("Справочные советы. Это не диагноз и не замена врача.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(9.dp).background(Cyan.copy(alpha = a), CircleShape))
        }
    }
}
