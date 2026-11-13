package kz.scanai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared state so a scan keeps its result while the user switches tabs, and so adb can drive data collection. */
object ScanState {
    var running by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var finger by mutableStateOf(false)
    var wave by mutableStateOf<List<Double>>(emptyList())
    var message by mutableStateOf("")
    var result by mutableStateOf<GlucoseAiModel.Result?>(null)
    var resultAt by mutableStateOf(0L)
    var captureMs by mutableStateOf(0L)
    var usedCapture by mutableStateOf(0L)
    var startTick by mutableStateOf(0)
    var startConsumed = 0
    var saveTick by mutableStateOf(0)
    var pendingGlucose = 0.0
    var pendingParticipant = "P1"
    var pendingMeal = "натощак"
}

const val ACTION_START = "kz.scanai.action.START"
const val ACTION_SAVE = "kz.scanai.action.SAVE"

class MainActivity : ComponentActivity() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START -> ScanState.startTick += 1
                ACTION_SAVE -> {
                    ScanState.pendingGlucose = intent.getStringExtra("glucose")?.replace(',', '.')?.toDoubleOrNull() ?: return
                    ScanState.pendingParticipant = intent.getStringExtra("participant") ?: "P1"
                    ScanState.pendingMeal = intent.getStringExtra("meal") ?: "натощак"
                    ScanState.saveTick += 1
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(ACTION_START); addAction(ACTION_SAVE)
        }, ContextCompat.RECEIVER_EXPORTED)
        setContent { ScanApp() }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }
}

private fun ago(now: Long, at: Long): String {
    val m = ((now - at) / 60_000).coerceAtLeast(0)
    return when { m < 1 -> "только что"; m < 60 -> "$m мин назад"; m < 1440 -> "${m / 60} ч назад"; else -> "${m / 1440} дн назад" }
}

private fun buzz(context: Context, pattern: LongArray) {
    try { context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1)) } catch (_: Exception) {}
}

@Composable
fun ScanApp() {
    val context = LocalContext.current
    val history = remember { HistoryStore(context) }
    var readings by remember { mutableStateOf(history.all()) }
    var tab by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var splash by remember { mutableStateOf(true) }
    var profile by remember { mutableStateOf(ProfileStore.load(context)) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1900); splash = false }

    Cosmos {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.logo_icon), null, Modifier.size(40.dp), contentScale = ContentScale.Fit)
                    Spacer(Modifier.width(10.dp))
                    Text("Scan", color = White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text("AI", color = Blue, fontSize = 26.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(18.dp))
                AnimatedContent(tab, transitionSpec = { (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 14 }) togetherWith fadeOut(tween(200)) },
                    label = "tab") { t ->
                    Column(Modifier.fillMaxWidth()) {
                        when (t) {
                            0 -> MeasureScreen(context, now,
                                onScan = { r -> history.add(r.mmol, HistoryStore.SCAN, r.heartRate); readings = history.all() },
                                onMeter = { v -> history.add(v, HistoryStore.METER); readings = history.all() })
                            1 -> HistoryScreen(readings)
                            2 -> AiScreen(context, readings, profile)
                            else -> ProfileScreen(readings, profile) { ProfileStore.save(context, it); profile = it }
                        }
                    }
                }
            }
            NavBar(tab, enabled = !ScanState.running) { tab = it }
        }
        if (!splash && profile.name.isBlank()) Onboarding { profile = it; ProfileStore.save(context, it) }
        val splashAlpha by animateFloatAsState(if (splash) 1f else 0f, tween(600), label = "splash")
        if (splashAlpha > 0.01f) {
            Box(Modifier.fillMaxSize().alpha(splashAlpha).background(Navy), contentAlignment = Alignment.Center) {
                val s = remember { Animatable(0.82f) }
                LaunchedEffect(Unit) { s.animateTo(1f, tween(1200)) }
                Image(painterResource(R.drawable.logo_full), null, Modifier.fillMaxWidth(0.8f).scale(s.value), contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun NavBar(tab: Int, enabled: Boolean, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xCC040B33)).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("ЗАМЕР", "ИСТОРИЯ", "ИИ-СКАН", "ПРОФИЛЬ").forEachIndexed { i, label ->
            val on = tab == i
            val a by animateFloatAsState(if (on) 1f else 0f, tween(300), label = "nav$i")
            Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp))
                .background(Brush.horizontalGradient(listOf(Cyan.copy(alpha = 0.22f * a), Blue.copy(alpha = 0.22f * a))))
                .clickable(enabled = enabled) { onTab(i) }, contentAlignment = Alignment.Center) {
                Text(label, color = if (on) White else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MeasureScreen(context: Context, now: Long, onScan: (GlucoseAiModel.Result) -> Unit, onMeter: (Double) -> Unit) {
    val capture = remember { CameraPpgCapture(context) }
    DisposableEffect(Unit) { onDispose { capture.stop(); ScanState.running = false } }
    var attempt by remember { mutableStateOf(0) }
    var lastWarn by remember { mutableStateOf(0L) }

    fun begin() {
        attempt++
        ScanState.running = true; ScanState.progress = 0f; ScanState.finger = false
        ScanState.message = "Закройте камеру и вспышку пальцем"
        capture.start(object : CameraPpgCapture.Listener {
            override fun onProgress(progress: Float, fingerOk: Boolean, wave: List<Double>) {
                ScanState.progress = progress; ScanState.finger = fingerOk; ScanState.wave = wave
                ScanState.message = if (fingerOk) "Не двигайтесь" else "Закройте камеру и вспышку пальцем"
                if (!fingerOk && progress > 0.02f) {
                    val t = System.currentTimeMillis()
                    if (t - lastWarn > 2_500) { lastWarn = t; buzz(context, longArrayOf(0, 150, 100, 150)) }
                }
            }
            override fun onDone(result: GlucoseAiModel.Result?, reason: String?) {
                ScanState.running = false
                if (result != null) {
                    attempt = 0
                    ScanState.result = result; ScanState.resultAt = System.currentTimeMillis()
                    ScanState.captureMs = CameraPpgCapture.lastCaptureAtMs
                    CaptureLog.append(context, ScanState.captureMs, result)
                    ScanState.message = "Готово"
                    buzz(context, longArrayOf(0, 400))
                    onScan(result)
                } else if (attempt < 3) {
                    buzz(context, longArrayOf(0, 300, 120, 300, 120, 300))
                    ScanState.message = "Слабый сигнал. Прижмите палец. Повтор…"
                    begin()
                } else {
                    attempt = 0
                    buzz(context, longArrayOf(0, 300, 120, 300, 120, 300))
                    ScanState.message = reason ?: "Не удалось измерить"
                }
            }
        })
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else ScanState.message = "Нужен доступ к камере"
    }
    fun requestScan() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) begin()
        else launcher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(ScanState.startTick) {
        if (ScanState.startTick > ScanState.startConsumed && !ScanState.running &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            ScanState.startConsumed = ScanState.startTick; begin()
        }
    }
    LaunchedEffect(ScanState.saveTick) {
        if (ScanState.saveTick > 0) {
            val ai = ScanState.result
            val ok = ai != null && ScanState.captureMs != ScanState.usedCapture
            if (ok) {
                PairLog.append(context, ScanState.pendingGlucose, ScanState.captureMs, ai!!, ScanState.pendingParticipant, ScanState.pendingMeal, null)
                ScanState.usedCapture = ScanState.captureMs
            }
            onMeter(ScanState.pendingGlucose)
            android.util.Log.i("ScanAiRemote", "save ${ScanState.pendingGlucose} paired=$ok")
        }
    }

    val value = ScanState.result?.mmol
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ScanGauge(ScanState.running, ScanState.progress, value, ScanState.resultAt)
        Spacer(Modifier.height(6.dp))
        if (!ScanState.running && value != null) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(zoneColor(value).copy(alpha = 0.18f)).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(zoneWord(value), color = zoneColor(value), fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            when {
                ScanState.running -> ScanState.message
                ScanState.result != null -> "${ago(now, ScanState.resultAt)} · пульс ${ScanState.result!!.heartRate.toInt()}"
                else -> ScanState.message.ifBlank { "Приложите палец к камере и вспышке" }
            }, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        GlucoseStick(if (ScanState.running) null else value)
        Spacer(Modifier.height(10.dp))
        PulseLine(ScanState.wave, ScanState.running, if (ScanState.running && !ScanState.finger) ZoneWarn else Cyan)
        Spacer(Modifier.height(18.dp))
        if (ScanState.running) {
            GhostButton("СТОП", Modifier.fillMaxWidth()) { capture.stop(); ScanState.running = false; attempt = 0; ScanState.message = "Остановлено" }
        } else {
            GradientButton("СКАНИРОВАТЬ", Modifier.fillMaxWidth()) { requestScan() }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun HistoryScreen(items: List<Reading>) {
    Text("ИСТОРИЯ", color = White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
    Spacer(Modifier.height(16.dp))
    if (items.isEmpty()) {
        Text("Пока нет замеров. Сделайте первый скан.", color = Muted, fontSize = 15.sp)
        return
    }
    val last = items.takeLast(24)
    val draw = remember { Animatable(0f) }
    LaunchedEffect(last.size) { draw.snapTo(0f); draw.animateTo(1f, tween(1400)) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(PanelC).padding(14.dp)) {
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            val lo = 2.0; val hi = 12.0
            fun y(v: Double) = size.height - ((v.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat() * size.height
            drawRect(ZoneOk.copy(alpha = 0.08f), Offset(0f, y(7.8)), Size(size.width, y(3.9) - y(7.8)))
            clipRect(0f, 0f, size.width * draw.value, size.height) {
                if (last.size > 1) {
                    val path = Path()
                    last.forEachIndexed { i, e ->
                        val x = size.width * i / (last.size - 1f)
                        if (i == 0) path.moveTo(x, y(e.mmol)) else path.lineTo(x, y(e.mmol))
                    }
                    val area = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                    drawPath(area, Brush.verticalGradient(listOf(Blue.copy(alpha = 0.30f), Color.Transparent)))
                    drawPath(path, Brush.horizontalGradient(listOf(Cyan, Blue)), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
                last.forEachIndexed { i, e ->
                    val x = if (last.size > 1) size.width * i / (last.size - 1f) else size.width / 2
                    drawCircle(zoneColor(e.mmol).copy(alpha = 0.3f), 9.dp.toPx(), Offset(x, y(e.mmol)))
                    drawCircle(zoneColor(e.mmol), 4.5.dp.toPx(), Offset(x, y(e.mmol)))
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    val fmt = SimpleDateFormat("d MMM · HH:mm", Locale("ru"))
    items.takeLast(40).reversed().forEachIndexed { idx, e ->
        val a = remember { Animatable(0f) }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay((idx * 40L).coerceAtMost(600)); a.animateTo(1f, tween(400)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).alpha(a.value).clip(RoundedCornerShape(18.dp)).background(PanelC).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(zoneColor(e.mmol), CircleShape))
            Spacer(Modifier.width(14.dp))
            Text("%.1f".format(e.mmol), color = White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text("ммоль/л", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(e.source.uppercase(), color = if (e.source == HistoryStore.METER) Cyan else Muted, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(fmt.format(Date(e.at)), color = Muted, fontSize = 12.sp)
            }
        }
    }
}
