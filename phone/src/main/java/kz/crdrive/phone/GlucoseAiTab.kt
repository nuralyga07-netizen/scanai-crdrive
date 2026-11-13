package kz.crdrive.phone

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private val GInk = Color(0xFF08111C)
private val GPanel = Color(0xFF101D2B)
private val GMint = Color(0xFFB9FF62)
private val GMuted = Color(0xFFA6B4C2)
private val GAmber = Color(0xFFFFC36B)

private const val RANGE_MIN = 2.0   // mmol/L at the start of the scale
private const val RANGE_MAX = 12.0  // mmol/L at the end of the scale
private const val PPG_HZ = 25.0

private fun zoneColor(v: Double): Color = when {
    v < 3.9 -> Color(0xFFFF6B6B)
    v < 5.6 -> Color(0xFF52E2BD)
    v < 7.8 -> GMint
    v < 10.0 -> Color(0xFFFFB84D)
    else -> Color(0xFFFF6B6B)
}

private fun zoneLabel(v: Double) = when {
    v < 3.9 -> "НИЖЕ НОРМЫ"
    v < 7.8 -> "В ПРЕДЕЛАХ НОРМЫ"
    else -> "ВЫШЕ НОРМЫ"
}

private fun agoText(now: Long, at: Long): String {
    val min = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 24 * 60 -> "${min / 60} ч назад"
        else -> "${min / (24 * 60)} дн назад"
    }
}

/** Last results survive tab switches: a value changes only when a new measurement finishes. */
private object LastResults {
    var watch by mutableStateOf<Pair<GlucoseAiModel.Result, Long>?>(null)
    var camera by mutableStateOf<Pair<GlucoseAiModel.Result, Long>?>(null)
    /** Capture time already paired with a glucometer value: one signal = one pair. */
    val usedAt = longArrayOf(0L, 0L)
}

@Composable
fun GlucoseAiTab(
    samples: List<PpgPoint>,
    now: Long,
    manual: GlucoseManualStore.Reading?,
    pairCount: Int,
    onSaveManual: (Double, GlucoseAiModel.Result?, PairMeta) -> Boolean
) {
    var sub by rememberSaveable { mutableStateOf(0) }
    var camRunning by remember { mutableStateOf(false) }
    var camProgress by remember { mutableStateOf(0f) }
    val appContext = LocalContext.current
    val historyStore = remember { GlucoseHistoryStore(appContext) }
    var history by remember { mutableStateOf(historyStore.all()) }

    // ---- watch signal: only the latest uninterrupted recording counts
    val recent = samples.filter { (it.green ?: 0) != 0 && now - it.timestamp in -5_000..130_000 }
    var start = 0
    for (i in 1 until recent.size) if (recent[i].timestamp - recent[i - 1].timestamp > 10_000) start = i
    val session = recent.subList(start, recent.size)
    val fresh = session.lastOrNull()?.let { now - it.timestamp <= 9_000 } == true
    val watchProgress = min(1f, session.size / 1050f)
    val watchRunning = fresh && session.size < 1000
    val estimate by produceState<GlucoseAiModel.Result?>(null, session.size / 125) {
        val data = session.map { it.green!!.toDouble() }.toDoubleArray()
        value = withContext(Dispatchers.Default) { GlucoseAiModel.estimate(data, PPG_HZ) }
    }
    val sessionEnd = session.lastOrNull()?.timestamp ?: 0L
    LaunchedEffect(estimate, sessionEnd, fresh) {
        val e = estimate
        val done = session.size >= 1000 || (!fresh && session.size >= 700)
        if (e != null && done && LastResults.watch?.second != sessionEnd) {
            LastResults.watch = e to sessionEnd
            historyStore.add(e.mmol, GlucoseHistoryStore.WATCH)
            history = historyStore.all()
        }
    }
    val watchResult = LastResults.watch
    val cameraResult = LastResults.camera

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("ГЛЮКОЗА ЧАСОВ", "ГЛЮКОЗА КАМЕРЫ").forEachIndexed { i, title ->
            val on = sub == i
            androidx.compose.material3.OutlinedButton(
                onClick = { if (!camRunning) sub = i }, modifier = Modifier.weight(1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (on) GMint else Color(0xFF263248)),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (on) GMint.copy(alpha = 0.14f) else Color.Transparent),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                Text(title, color = if (on) GMint else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
    Spacer(Modifier.height(14.dp))

    LaunchedEffect(RemoteControl.startTick) { if (RemoteControl.startTick > 0) sub = 1 }
    val result = if (sub == 0) watchResult else cameraResult
    val running = if (sub == 0) watchRunning else camRunning
    val prog = if (sub == 0) watchProgress else camProgress
    GlucoseGaugeCard(
        title = if (sub == 0) "CR DRIVE часы модель 1" else "Камера телефона",
        value = result?.first?.mmol, running = running, progress = prog,
        subtitle = result?.let { (r, at) ->
            "Замер ${agoText(now, at)} · пульс %.0f · качество %d%%".format(r.heartRate, (r.quality * 100).toInt())
        } ?: if (sub == 0) "Нажмите «ИЗМЕРИТЬ» на часах, вкладка «Глюкоза ИИ»" else "Нажмите «Начать измерение» ниже",
        meter = manual?.let { "Глюкометр: %.1f ммоль/л · ${agoText(now, it.measuredAt)}".format(it.mmol) })
    Spacer(Modifier.height(14.dp))

    if (sub == 1) {
        CameraMeasureCard(camRunning, camProgress, { r, p -> camRunning = r; camProgress = p }) { res ->
            LastResults.camera = res to System.currentTimeMillis()
            CaptureLog.append(appContext, CameraPpgCapture.lastCaptureAtMs, res)
            historyStore.add(res.mmol, GlucoseHistoryStore.CAMERA)
            history = historyStore.all()
        }
    } else {
        Card(colors = CardDefaults.cardColors(containerColor = GPanel), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("СИГНАЛ С ЧАСОВ", color = GAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                LiveWave(session.takeLast(300).map { it.green!!.toDouble() }, fresh)
                Spacer(Modifier.height(6.dp))
                Text(if (fresh) "Идёт запись · ${(watchProgress * 100).toInt()}%" else "Ждём замер с часов: часы плотно, рука неподвижно",
                    color = GMuted, fontSize = 12.sp)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    val pairAi = result?.takeIf { now - it.second in 0..30 * 60_000 && it.second != LastResults.usedAt[sub] }?.first
    val sourceName = if (sub == 0) "часы" else "камера"
    LaunchedEffect(RemoteControl.saveTick) {
        if (RemoteControl.saveTick > 0) {
            val cam = LastResults.camera
            val ai = cam?.takeIf { now - it.second in 0..30 * 60_000 && it.second != LastResults.usedAt[1] }?.first
            val ok = onSaveManual(RemoteControl.pendingGlucose, ai, RemoteControl.pendingMeta)
            if (ok && ai != null) LastResults.usedAt[1] = cam.second
            if (ok) { historyStore.add(RemoteControl.pendingGlucose, GlucoseHistoryStore.METER); history = historyStore.all() }
            android.util.Log.i("RemoteCtl", "save glucose=${RemoteControl.pendingGlucose} ok=$ok paired=${ai != null} total=${GlucosePairLog.count(appContext)}")
        }
    }
    ManualEntryCard(manual, now, pairCount, pairAi, sourceName) { v, a, meta ->
        val ok = onSaveManual(v, a, meta)
        if (ok && a != null) LastResults.usedAt[sub] = result?.second ?: 0L
        if (ok) { historyStore.add(v, GlucoseHistoryStore.METER); history = historyStore.all() }
        ok
    }
    Spacer(Modifier.height(14.dp))
    HistoryCard(history)
}

@Composable
private fun GlucoseGaugeCard(title: String, value: Double?, running: Boolean, progress: Float, subtitle: String, meter: String?) {
    val animated by animateFloatAsState(
        targetValue = if (!running && value != null) ((value - RANGE_MIN) / (RANGE_MAX - RANGE_MIN)).toFloat().coerceIn(0f, 1f) else 0f,
        animationSpec = tween(900), label = "glucose")
    val pAnim by animateFloatAsState(progress, tween(400), label = "progress")
    Card(colors = CardDefaults.cardColors(containerColor = GPanel), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(if (running) GAmber else GMint, CircleShape))
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(250.dp)) {
                    val stroke = 22.dp.toPx()
                    val inset = stroke / 2 + 6.dp.toPx()
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    val start = 150f; val sweep = 240f; val segments = 60
                    val segSweep = sweep / segments
                    for (i in 0 until segments) {
                        val frac = i / (segments - 1f)
                        val v = RANGE_MIN + frac * (RANGE_MAX - RANGE_MIN)
                        val on = !running && value != null && frac <= animated
                        drawArc(
                            color = if (on) zoneColor(v) else Color(0xFF1B2A3A),
                            startAngle = start + i * segSweep + 0.6f, sweepAngle = segSweep - 1.2f,
                            useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Butt))
                    }
                    if (running) {
                        drawArc(color = GMint, startAngle = start, sweepAngle = sweep * pAnim.coerceIn(0f, 1f),
                            useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        running -> {
                            Text("${(pAnim * 100).toInt().coerceIn(0, 100)}%", color = GMint, fontSize = 60.sp, fontWeight = FontWeight.Bold)
                            Text("идёт замер", color = GMuted, fontSize = 15.sp)
                        }
                        value != null -> {
                            Text("%.1f".format(value), color = zoneColor(value), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                            Text("ммоль/л", color = GMuted, fontSize = 16.sp)
                        }
                        else -> {
                            Text("—", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                            Text("нет замера", color = GMuted, fontSize = 14.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!running && value != null) {
                Box(Modifier.background(zoneColor(value).copy(alpha = 0.16f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Text(zoneLabel(value), color = zoneColor(value), fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(if (running) "Не двигайтесь, идёт запись сигнала" else subtitle, color = GMuted, fontSize = 12.sp)
            if (meter != null) {
                Spacer(Modifier.height(4.dp))
                Text(meter, color = GMint.copy(alpha = 0.85f), fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${RANGE_MIN.toInt()}", color = GMuted, fontSize = 11.sp)
                Text("3.9 · 7.8 ммоль/л — норма", color = GMuted, fontSize = 11.sp)
                Text("${RANGE_MAX.toInt()}", color = GMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ManualEntryCard(
    manual: GlucoseManualStore.Reading?, now: Long, pairCount: Int,
    ai: GlucoseAiModel.Result?, source: String, onSave: (Double, GlucoseAiModel.Result?, PairMeta) -> Boolean
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("pair_meta", android.content.Context.MODE_PRIVATE) }
    var text by remember { mutableStateOf("") }
    var participant by remember { mutableStateOf(prefs.getString("participant", "P001") ?: "P001") }
    var meal by remember { mutableStateOf(MEAL_FASTING) }
    var minutes by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = GPanel), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("ЗАМЕР ГЛЮКОМЕТРОМ", color = GMint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Text(if (manual != null) "Последний: %.1f ммоль/л · ${agoText(now, manual.measuredAt)}".format(manual.mmol)
                else "Пока нет замеров", color = GMuted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = participant, onValueChange = { participant = it.take(12) },
                label = { Text("Кто измерен (код, например P001)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(MEAL_FASTING, MEAL_AFTER, MEAL_OTHER).forEach { m ->
                    androidx.compose.material3.OutlinedButton(onClick = { meal = m }, modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (meal == m) GMint else Color(0xFF263248)),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)) {
                        Text(m, color = if (meal == m) GMint else Color.White, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            if (meal == MEAL_AFTER) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = minutes, onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Сколько минут после еды") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) },
                label = { Text("Глюкометр, ммоль/л, например 5,4") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val v = text.replace(',', '.').toDoubleOrNull()
                val meta = PairMeta(participant.trim().ifBlank { "P001" }, meal, minutes.toIntOrNull(), source)
                if (v != null && onSave(v, ai, meta)) {
                    prefs.edit().putString("participant", meta.participant).apply()
                    status = if (ai != null) "Пара сохранена для обучения" else
                        "Сохранено, но в пару не попало: на каждое значение глюкометра нужен новый замер камерой"
                    text = ""
                } else status = "Введите значение от 1,5 до 30 ммоль/л"
            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = GMint)) {
                Text("Сохранить", color = GInk, fontWeight = FontWeight.Bold)
            }
            if (status.isNotBlank()) Text(status, color = GMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(6.dp))
            Text("Пар для обучения: $pairCount из 50", color = GMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LiveWave(values: List<Double>, fresh: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        if (values.size < 20) return@Canvas
        // remove the slow baseline with a moving average so the pulse wave fills the height
        val k = 25
        val centered = values.mapIndexed { i, v ->
            val a = max(0, i - k / 2); val b = min(values.size - 1, i + k / 2)
            v - values.subList(a, b + 1).average()
        }
        val lo = centered.min(); val hi = centered.max(); val span = max(hi - lo, 1e-9)
        val path = Path()
        centered.forEachIndexed { i, v ->
            val x = size.width * i / (centered.size - 1f)
            val y = size.height - ((v - lo) / span).toFloat() * (size.height * 0.8f) - size.height * 0.1f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = if (fresh) GAmber else GMuted, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun CameraMeasureCard(running: Boolean, progress: Float, onRun: (Boolean, Float) -> Unit, onResult: (GlucoseAiModel.Result) -> Unit) {
    val context = LocalContext.current
    var finger by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var wave by remember { mutableStateOf<List<Double>>(emptyList()) }
    val capture = remember { CameraPpgCapture(context) }
    DisposableEffect(Unit) { onDispose { capture.stop() } }

    var attempt = 0
    var lastWarn = 0L
    fun begin() {
        attempt++
        onRun(true, 0f); finger = false
        message = "Закройте пальцем камеру и вспышку"
        capture.start(object : CameraPpgCapture.Listener {
            override fun onProgress(progress0: Float, fingerOk: Boolean, wave0: List<Double>) {
                onRun(true, progress0); finger = fingerOk; wave = wave0
                if (!fingerOk && progress0 > 0.02f) {
                    val t = System.currentTimeMillis()
                    if (t - lastWarn > 2_500) { lastWarn = t; buzzWarn(context) }
                }
                message = if (fingerOk) "Держите палец неподвижно" else "Закройте пальцем камеру и вспышку"
            }
            override fun onDone(result: GlucoseAiModel.Result?, reason: String?) {
                onRun(false, 1f)
                if (result != null) { attempt = 0; message = "Готово: %.1f ммоль/л".format(result.mmol); onResult(result); buzz(context) }
                else if (attempt < 3) { buzzWarn(context, long = true); message = "Сигнал слабый: прижмите палец к камере и вспышке. Повторяем…"; begin() }
                else { attempt = 0; buzzWarn(context, long = true); message = reason ?: "Не удалось измерить" }
            }
        })
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else message = "Нужно разрешение на камеру"
    }
    LaunchedEffect(RemoteControl.startTick) {
        if (RemoteControl.startTick > RemoteControl.startConsumed && !running &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            RemoteControl.startConsumed = RemoteControl.startTick
            begin()
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = GPanel), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("ИЗМЕРЕНИЕ КАМЕРОЙ", color = GMint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text("Приложите палец к камере и вспышке и не двигайтесь около 35 секунд.", color = GMuted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            if (running) {
                LiveWave(wave, finger)
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
                    color = if (finger) GMint else GAmber)
                Spacer(Modifier.height(8.dp))
                Text(message, color = if (finger) GMint else GAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(onClick = { capture.stop(); onRun(false, 0f); message = "Остановлено" },
                    modifier = Modifier.fillMaxWidth()) { Text("Остановить", color = Color.White) }
            } else {
                Button(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) begin()
                    else launcher.launch(Manifest.permission.CAMERA)
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = GMint)) {
                    Text("Начать измерение", color = GInk, fontWeight = FontWeight.Bold)
                }
                if (message.isNotBlank()) Text(message, color = GMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun HistoryCard(items: List<GlucoseHistoryStore.Entry>) {
    Card(colors = CardDefaults.cardColors(containerColor = GPanel), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("ИСТОРИЯ ЗАМЕРОВ", color = GMint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(10.dp))
            if (items.size < 2) {
                Text(if (items.isEmpty()) "Пока нет замеров" else "Сделайте ещё замер, чтобы увидеть динамику",
                    color = GMuted, fontSize = 13.sp)
            } else {
                Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                    val last = items.takeLast(20)
                    val lo = 2.0; val hi = 12.0
                    fun y(v: Double) = size.height - ((v.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat() * size.height
                    drawRect(Color(0x2252E2BD), topLeft = Offset(0f, y(7.8)), size = Size(size.width, y(3.9) - y(7.8)))
                    val path = Path()
                    last.forEachIndexed { i, e ->
                        val x = size.width * i / (last.size - 1f)
                        if (i == 0) path.moveTo(x, y(e.mmol)) else path.lineTo(x, y(e.mmol))
                    }
                    drawPath(path, GAmber, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                    last.forEachIndexed { i, e ->
                        drawCircle(if (e.source == GlucoseHistoryStore.METER) GMint else GAmber, 4.dp.toPx(),
                            Offset(size.width * i / (last.size - 1f), y(e.mmol)))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Зелёная полоса: 3,9–7,8 ммоль/л · зелёные точки: глюкометр", color = GMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            val fmt = SimpleDateFormat("d MMM HH:mm", Locale("ru"))
            items.takeLast(5).reversed().forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt.format(Date(e.at)), color = GMuted, fontSize = 13.sp)
                    Text("%.1f ммоль/л · %s".format(e.mmol, e.source), color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun buzz(context: android.content.Context) {
    try {
        val v = context.getSystemService(android.os.Vibrator::class.java)
        v?.vibrate(android.os.VibrationEffect.createOneShot(400, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) {}
}

/** Vibration that tells the person the finger is not on the camera / the signal is bad. */
private fun buzzWarn(context: android.content.Context, long: Boolean = false) {
    try {
        val v = context.getSystemService(android.os.Vibrator::class.java)
        val pattern = if (long) longArrayOf(0, 300, 120, 300, 120, 300) else longArrayOf(0, 150, 100, 150)
        v?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
    } catch (_: Exception) {}
}
