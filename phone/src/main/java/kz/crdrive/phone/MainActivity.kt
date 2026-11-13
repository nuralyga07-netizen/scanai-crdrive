package kz.crdrive.phone

import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import com.google.android.gms.wearable.Wearable

class MainActivity : ComponentActivity() {
    private var watchConnectionStatus by mutableStateOf("Связь с часами не проверена")
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { }
    private val healthPermission = registerForActivityResult(HealthConnectBridge.permissionContract()) {
        refreshHealth()
    }

    private val remoteReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                RemoteControl.ACTION_START -> RemoteControl.startTick += 1
                RemoteControl.ACTION_SAVE -> {
                    val g = intent.getStringExtra("glucose")?.replace(',', '.')?.toDoubleOrNull() ?: return
                    RemoteControl.pendingGlucose = g
                    RemoteControl.pendingMeta = PairMeta(intent.getStringExtra("participant") ?: "P1",
                        intent.getStringExtra("meal") ?: MEAL_FASTING, intent.getStringExtra("minutes")?.toIntOrNull(), "камера")
                    RemoteControl.saveTick += 1
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(remoteReceiver) }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ContextCompat.registerReceiver(this, remoteReceiver, android.content.IntentFilter().apply {
            addAction(RemoteControl.ACTION_START); addAction(RemoteControl.ACTION_SAVE)
        }, ContextCompat.RECEIVER_EXPORTED)
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = "CRDrive/0.1 (kz.crdrive)"
        Configuration.getInstance().expirationExtendedDuration = 7L * 24 * 60 * 60 * 1000
        TelemetryRepository.initialize(this)
        RemoteTelemetryUploader.flush(this)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { CRDriveApp(onConnectHealth = ::connectHealth,
            onOpenWatchLocation = ::openWatchLocation,
            initialTab = if (intent.getBooleanExtra("show_map", false)) 3 else 0,
            watchConnectionStatus = watchConnectionStatus,
            onCheckWatchConnection = ::checkWatchConnection) }
        refreshHealth()
        checkWatchConnection()
    }

    private fun checkWatchConnection() {
        watchConnectionStatus = "Проверяем связь с часами…"
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                watchConnectionStatus = if (nodes.isNotEmpty())
                    "Сопряжённые часы доступны · ${nodes.size} устройство(а)"
                else "Часы недоступны: проверьте сопряжение и Bluetooth/Wi‑Fi"
            }
            .addOnFailureListener { watchConnectionStatus = "Не удалось проверить связь с часами" }
    }

    private fun connectHealth() {
        if (!HealthConnectBridge.available(this)) {
            TelemetryRepository.updateContextHealth(ContextHealth(source = "Health Connect недоступен на телефоне"))
            return
        }
        lifecycleScope.launch {
            try {
                val granted = HealthConnectClient.getOrCreate(this@MainActivity)
                    .permissionController.getGrantedPermissions()
                if (granted.containsAll(HealthConnectBridge.permissions)) refreshHealth()
                else healthPermission.launch(HealthConnectBridge.permissions)
            } catch (_: Exception) {
                TelemetryRepository.updateContextHealth(ContextHealth(source = "Не удалось открыть доступ к данным"))
            }
        }
    }

    private fun refreshHealth() {
        lifecycleScope.launch {
            val data = try { HealthConnectBridge.read(this@MainActivity) }
                catch (_: Exception) { ContextHealth(source = "Ошибка чтения Health Connect") }
            TelemetryRepository.updateContextHealth(data)
        }
    }

    private fun openWatchLocation(location: WatchLocation) {
        if (!location.isFresh(System.currentTimeMillis())) return
        val query = Uri.encode("${location.latitude},${location.longitude}")
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$query")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

}

private val Ink = Color(0xFF08111C)
private val Panel = Color(0xFF101D2B)
private val Blue = Color(0xFF35BDF2)
private val Mint = Color(0xFFB9FF62)
private val Muted = Color(0xFFA6B4C2)

@Composable
fun CRDriveApp(onConnectHealth: () -> Unit = {},
    onOpenWatchLocation: (WatchLocation) -> Unit = {},
    initialTab: Int = 0,
    watchConnectionStatus: String = "Связь с часами не проверена",
    onCheckWatchConnection: () -> Unit = {}) {
    val rawTelemetry by TelemetryRepository.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val languagePreferences = remember { context.getSharedPreferences("phone_language", android.content.Context.MODE_PRIVATE) }
    var language by remember { mutableStateOf(runCatching {
        PhoneLanguage.valueOf(languagePreferences.getString("language", "RU") ?: "RU")
    }.getOrDefault(PhoneLanguage.RU)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var selectedTab by remember { mutableStateOf(initialTab) }
    val vehicleStore = remember { VehicleAssignmentStore(context) }
    val activationStore = remember { DriverActivationStore(context) }
    var activation by remember { mutableStateOf(activationStore.current()) }
    var activationCode by remember { mutableStateOf("") }
    var activationStatus by remember { mutableStateOf("") }
    var activationBusy by remember { mutableStateOf(false) }
    var vehicleNumber by remember { mutableStateOf(vehicleStore.current()) }
    var vehicleDraft by remember { mutableStateOf(vehicleStore.current()) }
    var vehicleStatus by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) {
        now = System.currentTimeMillis()
        vehicleNumber = vehicleStore.current()
        delay(300)
    } }
    val telemetry = rawTelemetry.withFreshness(now)
    LaunchedEffect(RemoteControl.startTick) { if (RemoteControl.startTick > 0) selectedTab = 5 }

    CompositionLocalProvider(LocalPhoneLanguage provides language) {
    MaterialTheme {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                if (selectedTab != 3) {
                    Header(telemetry.source)
                    Spacer(Modifier.height(18.dp))
                }
                if (selectedTab == 1 || selectedTab == 5 || selectedTab == 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("ПОКАЗАТЕЛИ" to 1, "ГЛЮКОЗА ИИ" to 5, "СОБЫТИЯ" to 2).forEach { (label, target) ->
                            val on = selectedTab == target
                            OutlinedButton(onClick = { selectedTab = target }, modifier = Modifier.weight(1f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Mint else Color(0xFF263248)),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (on) Mint.copy(alpha = 0.14f) else Color.Transparent),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 10.dp),
                                shape = RoundedCornerShape(14.dp)) {
                                Text(label, color = if (on) Mint else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (selectedTab == 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("ТРАНСПОРТ", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                        Text(vehicleNumber.ifBlank { "—" }, color = Mint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(14.dp))
                    val level = if (telemetry.bpm == null) null else telemetry.assessment.score
                    StateGauge(level, when {
                        level == null -> "НЕТ ДАННЫХ"
                        level < 42 -> "НОРМА"
                        level < 72 -> "ВНИМАНИЕ"
                        else -> "ОПАСНО"
                    })
                    Spacer(Modifier.height(14.dp))
                    HomeTiles(telemetry.bpm, telemetry.battery, when {
                        level == null -> Muted
                        level < 42 -> CrGreen
                        level < 72 -> CrOrange
                        else -> CrRed
                    })
                }
                if (selectedTab == 1) {
                    VitalsTab(telemetry, now)
                }
                if (selectedTab == 2) {
                    EventsTab(telemetry)
                }
                if (selectedTab == 3) {
                    val location = telemetry.watchLocation?.takeIf { it.isFresh(now) }
                    val activeFix = location?.let { MapFix(it.latitude, it.longitude,
                        it.accuracyMeters, it.measuredAt,
                        if (it.provider == "gps") "GPS часов" else "Сеть часов") }
                    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                    LiveMap(activeFix, Modifier.fillMaxWidth().height((screenH - 140).dp).clip(RoundedCornerShape(28.dp)))
                }
                if (selectedTab == 5) {
                    val glucoseStore = remember { GlucoseManualStore(context) }
                    var glucoseManual by remember { mutableStateOf(glucoseStore.current()) }
                    var pairCount by remember { mutableStateOf(GlucosePairLog.count(context)) }
                    GlucoseAiTab(telemetry.ppgSamples, now, glucoseManual, pairCount) { mmol, ai, meta ->
                        val ok = glucoseStore.save(mmol)
                        if (ok) {
                            glucoseManual = glucoseStore.current()
                            if (ai != null) GlucosePairLog.append(context, mmol, telemetry, ai, meta)
                            pairCount = GlucosePairLog.count(context)
                        }
                        ok
                    }
                }
                if (selectedTab == 4) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("ПОДКЛЮЧЕНИЕ ВОДИТЕЛЯ", color = Mint, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(8.dp))
                            if (activation.active) {
                                Text(activation.driverName.ifBlank { "Водитель подключён" }, color = Color.White,
                                    fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                Text("Код принят · устройство защищённо привязано к кабинету",
                                    color = Muted, fontSize = 13.sp)
                            } else {
                                Text("Введите код, который выдал диспетчер Казпочты.", color = Muted, fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = activationCode,
                                    onValueChange = { activationCode = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(20) },
                                    label = { MaterialText("Код водителя") }, placeholder = { MaterialText("ABCD1234EF") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                Button(enabled = !activationBusy, onClick = {
                                    activationBusy = true; activationStatus = "Проверяем код…"
                                    activationStore.activate(activationCode) { result ->
                                        activationBusy = false
                                        result.onSuccess { connected ->
                                            activation = connected; activationStatus = "Водитель подключён"
                                            if (connected.vehicleNumber.isNotBlank()) {
                                                vehicleDraft = connected.vehicleNumber; vehicleNumber = connected.vehicleNumber
                                            }
                                            RemoteTelemetryUploader.flush(context)
                                        }.onFailure { activationStatus = it.message ?: "Не удалось подключиться" }
                                    }
                                }, modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Mint)) {
                                    Text("Подключить к кабинету", color = Ink, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (activationStatus.isNotBlank()) Text(activationStatus, color = Muted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("ТРАНСПОРТ ВОДИТЕЛЯ", color = Mint, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = vehicleDraft,
                                onValueChange = { vehicleDraft = VehicleAssignmentStore.normalize(it) },
                                label = { MaterialText("Госномер / бортовой номер") },
                                placeholder = { MaterialText("618 DFG") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                val normalized = VehicleAssignmentStore.normalize(vehicleDraft)
                                vehicleNumber = normalized
                                vehicleStore.save(normalized) { synced ->
                                    vehicleStatus = if (synced) "Сохранено и отправлено на часы"
                                    else "Сохранено на телефоне · часы получат номер после подключения"
                                }
                                RemoteTelemetryUploader.flush(context)
                            }, modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Mint)) {
                                Text("Назначить транспорт", color = Ink, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = {
                                vehicleDraft = ""
                                vehicleNumber = ""
                                vehicleStore.save("") { synced ->
                                    vehicleStatus = if (synced) "Номер удалён и с часов"
                                    else "Номер удалён на телефоне · часы обновятся после подключения"
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("Убрать номер транспорта", color = Color.White)
                            }
                            if (vehicleStatus.isNotBlank()) Text(vehicleStatus, color = Muted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhoneLanguage.entries.forEach { option ->
                            OutlinedButton(onClick = {
                                language = option
                                languagePreferences.edit().putString("language", option.name).apply()
                            }, border = androidx.compose.foundation.BorderStroke(1.dp,
                                if (language == option) Mint else Muted)) {
                                Text(option.name, color = if (language == option) Mint else Color.White)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(watchConnectionStatus, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCheckWatchConnection) {
                        Text("Проверить связь с часами", color = Mint)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Главная" to 0, "Датчики" to 1, "Маршрут" to 3, "Профиль" to 4).forEach { (label, target) ->
                    val active = selectedTab == target || (target == 1 && (selectedTab == 2 || selectedTab == 5))
                    OutlinedButton(onClick = { selectedTab = target }, modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) Mint else Color.Transparent),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(14.dp)) {
                        Text(label, color = if (active) Mint else Muted, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            }
        }
    }
    }
}

private val LocalPhoneLanguage = compositionLocalOf { PhoneLanguage.RU }

@Composable
private fun Text(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified, letterSpacing: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE) {
    MaterialText(localizePhone(text, LocalPhoneLanguage.current), modifier = modifier, color = color,
        fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight,
        letterSpacing = letterSpacing, maxLines = maxLines)
}

@Composable
private fun AchievementCard(number: String, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Mint, fontSize = 25.sp, fontWeight = FontWeight.Black)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Header(source: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.cr_drive_logo),
            contentDescription = "Логотип CR Drive",
            modifier = Modifier.size(44.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("CR DRIVE", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            Text("Безопасность водителя", color = Muted, fontSize = 12.sp)
        }
        Text(if (source == "LIVE") "В СЕТИ" else "ОЖИДАНИЕ", color = if (source == "LIVE") Mint else Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeartCard(state: TelemetryState, now: Long) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "pulse-scale"
    )
    val accent = when (state.assessment.level) {
        RiskLevel.HIGH -> Color(0xFFFF5668)
        RiskLevel.ATTENTION -> Color(0xFFFFB84D)
        RiskLevel.NORMAL -> Mint
        RiskLevel.UNKNOWN -> Blue
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF1B2535), RoundedCornerShape(28.dp))
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(accent.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Favorite, null, tint = accent, modifier = Modifier.size(28.dp).scale(if (state.bpm != null) pulse else 1f))
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text("ПУЛЬС С ЧАСОВ", color = Muted, fontSize = 11.sp, letterSpacing = 1.3.sp)
                    AnimatedContent(state.bpm, label = "bpm") { bpm ->
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(bpm?.toString() ?: "—", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Black)
                            Text(" уд/мин", color = Muted, modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, tint = accent, modifier = Modifier.size(18.dp))
                Text(
                    state.assessment.title,
                    color = accent,
                    modifier = Modifier.padding(start = 8.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(state.assessment.explanation, color = Muted, fontSize = 12.sp)
            state.receivedAt?.let { measuredAt ->
                val age = ((now - measuredAt).coerceAtLeast(0) / 1000)
                Text("Измерено $age сек назад", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ScenarioButton(
    label: String,
    id: String,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    OutlinedButton(
        onClick = { onSelect(id) },
        modifier = modifier,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected == id) Blue else Color(0xFF263248)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selected == id) Color.White else Muted),
        shape = RoundedCornerShape(14.dp)
    ) { Text(label, fontSize = 11.sp) }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(label, color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MiniVital(label: String, value: String, status: String) {
    Card(Modifier.width(142.dp), colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp, maxLines = 1)
            Spacer(Modifier.height(11.dp))
            Text(value, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                maxLines = 1)
            Spacer(Modifier.height(5.dp))
            Text(status, color = if (value == "—") Muted else Mint, fontSize = 11.sp,
                maxLines = 1)
        }
    }
}

@Composable
private fun EventRow(title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(Blue, CircleShape))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
    }
}
