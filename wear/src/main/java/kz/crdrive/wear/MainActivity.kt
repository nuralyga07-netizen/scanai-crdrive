package kz.crdrive.wear

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { delay(4_200); showSplash = false }
                if (showSplash) WatchSplash() else WatchScreen()
            }
        }
    }
}

@Composable
private fun WatchSplash() {
    var started by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(if (started) 1f else 0f, tween(3_600), label = "brand-intro")
    LaunchedEffect(Unit) { started = true }
    Box(Modifier.fillMaxSize().background(Color(0xFF03070D)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.scale(0.72f + progress * 0.28f).alpha(0.35f + progress * 0.65f)) {
            Image(painterResource(R.drawable.cr_drive_logo), "CR DRIVE",
                Modifier.size(112.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Spacer(Modifier.height(10.dp))
            Text("CR DRIVE", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp)
            Text("🚛  ❤️  🛡️", color = Color.White, fontSize = 18.sp)
            Text("SAFE DRIVER · SMART FLEET", color = Color(0xFF52E2BD), fontSize = 8.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun WatchScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        VehicleAssignmentState.number.value = context.getSharedPreferences(
            VehicleConfigListenerService.PREFERENCES, 0
        ).getString(VehicleConfigListenerService.KEY, "") ?: ""
    }
    val monitoringState by WatchMonitoringRepository.state.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var serviceRunning by remember { mutableStateOf(isMonitoringServiceRunning(context)) }
    var selectedPage by remember { mutableStateOf(0) }
    var showSensors by remember { mutableStateOf(false) }
    val ppgStatus by SamsungPpgState.status
    val ppgReceivedAt by SamsungPpgState.lastReceivedAt
    val oxygenStatus by SamsungOxygenState.status
    val oxygenValue by SamsungOxygenState.value
    val oxygenMeasuredAt by SamsungOxygenState.measuredAt
    val ecgStatus by SamsungEcgState.status
    val ecgMeasuring by SamsungEcgState.measuring
    val ecgSamples by SamsungEcgState.sampleCount
    var ecgBaselineRecorded by remember { mutableStateOf(context.getSharedPreferences("ecg_baseline", 0)
        .getLong("completed_at", 0L) > 0L) }
    var preTripEcgRequestedAt by remember { mutableStateOf<Long?>(null) }
    val ppgPermission = "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
    val oxygenPermission = "android.permission.health.READ_OXYGEN_SATURATION"
    val languagePreferences = remember { context.getSharedPreferences("watch_language", 0) }
    var language by remember { mutableStateOf(runCatching {
        WatchLanguage.valueOf(languagePreferences.getString("language", "RU") ?: "RU")
    }.getOrDefault(WatchLanguage.RU)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            serviceRunning = isMonitoringServiceRunning(context)
            delay(5_000)
        }
    }
    val sessionActive = serviceRunning && monitoringState.active
    var pendingGlucose by remember { mutableStateOf(false) }
    LaunchedEffect(pendingGlucose, sessionActive) {
        if (!pendingGlucose) return@LaunchedEffect
        if (sessionActive) {
            delay(4_000)
            ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_MEASURE_GLUCOSE
            })
            pendingGlucose = false
        } else {
            delay(25_000)
            if (pendingGlucose) { pendingGlucose = false; SamsungPpgState.status.value = "Не удалось запустить датчики" }
        }
    }
    val preTripEcgPending = preTripEcgRequestedAt != null &&
        !ecgStatus.startsWith("ЭКГ записана")
    LaunchedEffect(preTripEcgRequestedAt) {
        val requestedAt = preTripEcgRequestedAt ?: return@LaunchedEffect
        repeat(30) {
            if (!isMonitoringServiceRunning(context)) delay(500)
        }
        if (preTripEcgRequestedAt == requestedAt) {
            ContextCompat.startForegroundService(context,
                Intent(context, MonitoringService::class.java).apply {
                    action = MonitoringService.ACTION_MEASURE_ECG
                })
            delay(15_000)
            if (preTripEcgRequestedAt == requestedAt && ecgSamples < 1 &&
                !ecgStatus.startsWith("ЭКГ записана")) {
                SamsungEcgState.status.value = "Перед поездкой измерьте ЭКГ"
                WatchHaptics.playMotionWarning(context)
            }
        }
    }
    LaunchedEffect(ecgStatus) {
        if (ecgStatus.startsWith("ЭКГ записана")) {
            preTripEcgRequestedAt = null
            ecgBaselineRecorded = true
        }
    }
    val freshBpm = monitoringState.bpm.takeIf {
        sessionActive && monitoringState.measuredAt?.let { time ->
            time <= now + 5_000 && now - time <= 30_000
        } == true
    }
    val heartRatePermission = if (Build.VERSION.SDK_INT >= 36) {
        "android.permission.health.READ_HEART_RATE"
    } else {
        Manifest.permission.BODY_SENSORS
    }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, heartRatePermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val backgroundPermission = if (Build.VERSION.SDK_INT >= 36) {
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    } else if (Build.VERSION.SDK_INT >= 33) {
        "android.permission.BODY_SENSORS_BACKGROUND"
    } else null
    val stepsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ContextCompat.startForegroundService(
            context, Intent(context, MonitoringService::class.java))
    }
    val startMonitoring = {
        ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED) {
            stepsLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startMonitoring() } // GPS is optional; heart monitoring still starts if denied.
    val startWithLocation = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) startMonitoring()
        else locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val oxygenPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startWithLocation() }
    val startWithOxygen = {
        if (Build.VERSION.SDK_INT < 36 || ContextCompat.checkSelfPermission(context, oxygenPermission) ==
            PackageManager.PERMISSION_GRANTED) startWithLocation()
        else oxygenPermissionLauncher.launch(oxygenPermission)
    }
    val ppgPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) SamsungPpgState.status.value = "PPG: доступ не разрешён · остальные датчики работают"
        startWithOxygen()
    }
    val startWithPpg = {
        if (ContextCompat.checkSelfPermission(context, ppgPermission) ==
            PackageManager.PERMISSION_GRANTED) startWithOxygen()
        else ppgPermissionLauncher.launch(ppgPermission)
    }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startWithPpg()
        else WatchMonitoringRepository.update(WatchMonitoringState(status = "Нужен доступ к датчикам «Всегда»"))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            if (backgroundPermission != null &&
                ContextCompat.checkSelfPermission(context, backgroundPermission) != PackageManager.PERMISSION_GRANTED) {
                backgroundLauncher.launch(backgroundPermission)
            } else startWithPpg()
        }
    }

    WatchTabbedContent(
        language = language,
        selectedPage = selectedPage,
        onPageSelected = { selectedPage = it },
        serviceRunning = serviceRunning,
        sessionActive = sessionActive,
        pendingGlucose = pendingGlucose,
        freshBpm = freshBpm,
        monitoringState = monitoringState,
        ppgStatus = ppgStatus,
        ppgReceivedAt = ppgReceivedAt,
        oxygenStatus = oxygenStatus,
        oxygenValue = oxygenValue,
        oxygenMeasuredAt = oxygenMeasuredAt,
        ecgStatus = ecgStatus,
        ecgMeasuring = ecgMeasuring,
        preTripEcgPending = preTripEcgPending,
        now = now,
        onToggleMonitoring = {
            if (serviceRunning) {
                preTripEcgRequestedAt = null
                context.stopService(Intent(context, MonitoringService::class.java))
                serviceRunning = false
                WatchMonitoringRepository.update(WatchMonitoringState())
            } else {
                preTripEcgRequestedAt = if (ecgBaselineRecorded) null else System.currentTimeMillis()
                selectedPage = if (ecgBaselineRecorded) 0 else 1
                if (!ecgBaselineRecorded) SamsungEcgState.status.value =
                    "Положите палец на верхнюю кнопку · ЭКГ перед поездкой"
                if (!permissionGranted) permissionLauncher.launch(heartRatePermission)
                else if (backgroundPermission != null && ContextCompat.checkSelfPermission(
                    context, backgroundPermission) != PackageManager.PERMISSION_GRANTED) {
                    backgroundLauncher.launch(backgroundPermission)
                } else startWithPpg()
            }
        },
        onMeasureEcg = {
            if (sessionActive && !ecgMeasuring) ContextCompat.startForegroundService(
                context, Intent(context, MonitoringService::class.java).apply {
                    action = MonitoringService.ACTION_MEASURE_ECG
                })
            else if (!sessionActive) SamsungEcgState.status.value = "Сначала запустите поездку"
        },
        onGlucoseScan = {
            if (sessionActive) ContextCompat.startForegroundService(
                context, Intent(context, MonitoringService::class.java).apply {
                    action = MonitoringService.ACTION_MEASURE_GLUCOSE
                })
            else {
                // No trip yet: start the sensors first, the measurement follows automatically.
                pendingGlucose = true
                SamsungPpgState.status.value = "Запуск датчиков…"
                if (!permissionGranted) permissionLauncher.launch(heartRatePermission) else startWithPpg()
            }
        },
        onLanguage = { next ->
            language = next
            languagePreferences.edit().putString("language", next.name).apply()
        },
        onVibration = { WatchHaptics.playAlert(context) }
    )
    return

    val blue = Color(0xFF29A8FF)
    val muted = Color(0xFFB7C3D4)
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF050709))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.cr_drive_logo),
                contentDescription = "Логотип CR Drive",
                modifier = Modifier.size(20.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Text("CR DRIVE", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp), letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(if (sessionActive) watchText(language, "МОНИТОРИНГ", "БАҚЫЛАУ", "MONITORING")
            else watchText(language, "ГОТОВ К ЗАПУСКУ", "ІСКЕ ҚОСУҒА ДАЙЫН", "READY TO START"),
            color = if (sessionActive) Color(0xFF52E2BD) else muted,
            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.size(56.dp).background(Color(0xFF10203A), CircleShape)
                .border(2.dp, if (freshBpm != null) blue else Color(0xFF34465F), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(freshBpm?.toString() ?: "—", color = Color.White,
                    fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("BPM", color = muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (!sessionActive) watchStatusText(language, "Готов к запуску")
            else if (freshBpm == null) watchStatusText(language, "Ожидаем свежий пульс")
            else watchStatusText(language, monitoringState.status),
            color = if (monitoringState.status.startsWith("НИЗКИЙ")) Color(0xFFFF858E) else muted,
            fontSize = 11.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        WatchAction(
            if (serviceRunning) watchText(language, "Остановить", "Тоқтату", "Stop")
            else watchText(language, "Запустить анализ", "Талдауды бастау", "Start Scan"),
            filled = true
        ) {
            if (serviceRunning) {
                context.stopService(Intent(context, MonitoringService::class.java))
                serviceRunning = false
                WatchMonitoringRepository.update(WatchMonitoringState())
            } else if (!permissionGranted) {
                permissionLauncher.launch(heartRatePermission)
            } else if (backgroundPermission != null &&
                ContextCompat.checkSelfPermission(context, backgroundPermission) != PackageManager.PERMISSION_GRANTED) {
                backgroundLauncher.launch(backgroundPermission)
            } else {
                startWithPpg()
            }
        }
        Spacer(Modifier.height(6.dp))
        WatchAction(
            if (ecgMeasuring) watchText(language, "ЭКГ измеряется…", "ЭКГ өлшенуде…", "ECG measuring…")
            else watchText(language, "Измерить ЭКГ", "ЭКГ өлшеу", "Measure ECG"),
            filled = false
        ) {
            if (sessionActive && !ecgMeasuring) {
                ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java).apply {
                    action = MonitoringService.ACTION_MEASURE_ECG
                })
            } else if (!sessionActive) {
                SamsungEcgState.status.value = "Сначала запустите мониторинг"
            }
        }
        Text(ecgStatus, color = if (ecgStatus.startsWith("ЭКГ записана")) Color(0xFF52E2BD) else muted,
            fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        // The Watch4 cannot stream raw PPG and continuous BPM at the same time.
        // A completed real PPG capture remains the input for this trip's research scan.
        val ppgCaptured = sessionActive && ppgReceivedAt != null
        Text(when {
            !sessionActive -> watchText(language, "Все датчики · одна кнопка", "Датчиктер · бір батырма", "Sensors · one button")
            ppgCaptured -> "PPG · " + watchText(language, "Сигнал сохранён", "Сигнал сақталды", "Signal captured")
            else -> "PPG · " + watchText(language, "Ожидание сигнала", "Сигнал күтілуде", "Waiting for signal")
        }, color = if (ppgCaptured) Color(0xFF52E2BD) else muted, fontSize = 12.sp)
        if (ppgCaptured && freshBpm != null) {
            Text("GLUCOSE AI", color = Color(0xFFFFC36B),
                fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("PPG сохранён · добавьте глюкометр",
                color = Color.White, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        WatchAction(if (showSensors) watchText(language, "Скрыть информацию", "Ақпаратты жасыру", "Hide details")
            else watchText(language, "Показатели", "Көрсеткіштер", "Readings"), filled = false) {
            showSensors = !showSensors
        }
        Spacer(Modifier.height(6.dp))
        val gpsRecent = monitoringState.gpsMeasuredAt?.let { it <= now + 5_000 && now - it <= 60_000 } == true
        Text(when {
            !sessionActive -> watchText(language, "GPS включится с поездкой", "GPS сапармен қосылады", "GPS starts with trip")
            !monitoringState.gpsEnabled -> watchText(language, "GPS недоступен", "GPS қолжетімсіз", "GPS unavailable")
            gpsRecent && monitoringState.locationProvider == "gps" -> watchText(language, "GPS · точка получена", "GPS · орын анықталды", "GPS · fix received")
            gpsRecent -> watchText(language, "Сеть часов · точка", "Сағат желісі · орын", "Watch network · fix")
            else -> watchText(language, "GPS · поиск сигнала", "GPS · сигнал іздеуде", "GPS · searching")
        }, color = muted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row {
            WatchLanguage.entries.forEach { option ->
                Text(option.name, color = if (language == option) blue else muted,
                    fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp)
                        .clickable {
                            language = option
                            languagePreferences.edit().putString("language", option.name).apply()
                        })
            }
        }
        if (showSensors) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().background(Color(0xFF14243D), RoundedCornerShape(14.dp)).padding(10.dp)) {
                Text("РЕАЛЬНЫЕ ДАТЧИКИ", color = Color(0xFF52E2BD), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold)
                Text(oxygenStatus, color = Color.White, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(ppgStatus, color = muted, fontSize = 12.sp)
            val sensors = monitoringState.sensors
            Text("${watchText(language, "На руке", "Қолда", "On wrist")}: ${when (sensors.onBody) {
                true -> watchText(language, "да", "иә", "yes")
                false -> watchText(language, "нет", "жоқ", "no")
                null -> watchText(language, "нет данных", "дерек жоқ", "no data") }}",
                color = muted, fontSize = 12.sp)
            Text("${watchText(language, "Шаги", "Қадамдар", "Steps")}: ${sensors.tripSteps?.toString() ?: "—"}", color = muted, fontSize = 12.sp)
            Text("${watchText(language, "Ускорение", "Үдеу", "Acceleration")}: ${sensors.acceleration?.let { "%.1f".format(it) } ?: "—"} м/с²",
                color = muted, fontSize = 12.sp)
            Text("${watchText(language, "Вращение", "Айналу", "Rotation")}: ${sensors.rotation?.let { "%.1f".format(it) } ?: "—"} рад/с",
                color = muted, fontSize = 12.sp)
            Text("${watchText(language, "Барометр", "Барометр", "Barometer")}: ${sensors.pressure?.let { "%.0f".format(it) } ?: "—"} hPa",
                color = muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        WatchAction(watchText(language, "Вибрация", "Діріл", "Vibration"),
            filled = false) { WatchHaptics.playAlert(context) }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun WatchTabbedContent(
    language: WatchLanguage,
    selectedPage: Int,
    onPageSelected: (Int) -> Unit,
    serviceRunning: Boolean,
    sessionActive: Boolean,
    freshBpm: Int?,
    monitoringState: WatchMonitoringState,
    ppgStatus: String,
    ppgReceivedAt: Long?,
    oxygenStatus: String,
    oxygenValue: Int?,
    oxygenMeasuredAt: Long?,
    ecgStatus: String,
    ecgMeasuring: Boolean,
    preTripEcgPending: Boolean,
    pendingGlucose: Boolean,
    now: Long,
    onToggleMonitoring: () -> Unit,
    onMeasureEcg: () -> Unit,
    onGlucoseScan: () -> Unit,
    onLanguage: (WatchLanguage) -> Unit,
    onVibration: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vehicleNumber by VehicleAssignmentState.number
    var vehicleDraft by remember(vehicleNumber) { mutableStateOf(vehicleNumber) }
    var editingVehicle by remember { mutableStateOf(false) }
    val blue = Color(0xFF29A8FF)
    val mint = Color(0xFF52E2BD)
    val panel = Color(0xFF101B2B)
    val muted = Color(0xFFA9B7C8)
    val pageLabels = listOf(
        watchText(language, "Поездка", "Сапар", "Trip"),
        watchText(language, "Датчики", "Датчик", "Sensors"),
        "AI",
        watchText(language, "Состояние", "Күй", "State")
    )
    val gpsRecent = monitoringState.gpsMeasuredAt?.let { it <= now + 5_000 && now - it <= 60_000 } == true
    val ppgCaptured = sessionActive && ppgReceivedAt?.let { now - it in 0..120_000 } == true
    val contactWarning = SensorContactState.warning.value

    Column(
        Modifier.fillMaxSize().background(Color(0xFF05080D))
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.cr_drive_logo), "CR DRIVE",
                Modifier.size(24.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Text("CR DRIVE", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 7.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            pageLabels.forEachIndexed { index, label ->
                Box(Modifier.padding(horizontal = 1.dp).size(44.dp, 34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selectedPage == index) blue else panel)
                    .clickable { onPageSelected(index) }, contentAlignment = Alignment.Center) {
                    Text(label, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (contactWarning != null) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF7A251F), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("⚠ $contactWarning", color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(watchText(language, "Не двигайтесь — измерение повторится автоматически",
                    "Қозғалмаңыз — өлшеу автоматты қайталанады",
                    "Keep still — measurement will retry automatically"),
                    color = Color.White, fontSize = 9.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (monitoringState.motionAlertActive) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF8A3A12), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(watchText(language, "⚠ НЕТ ДВИЖЕНИЯ 15 СЕК", "⚠ 15 СЕК ҚОЗҒАЛЫС ЖОҚ",
                    "⚠ NO MOVEMENT FOR 15 SEC"), color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(watchText(language, "Подвигайте рукой · проверьте бодрость",
                    "Қолыңызды қозғаңыз · сергектікті тексеріңіз",
                    "Move your hand · check alertness"), color = Color.White, fontSize = 10.sp,
                    textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (preTripEcgPending) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF8A3A12), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(watchText(language, "ПЕРЕД ПОЕЗДКОЙ ИЗМЕРЬТЕ ЭКГ",
                    "САПАР АЛДЫНДА ЭКГ ӨЛШЕҢІЗ", "MEASURE ECG BEFORE TRIP"),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Text(watchText(language, "Положите палец на верхнюю кнопку",
                    "Саусақты жоғарғы түймеге қойыңыз", "Place a finger on the top button"),
                    color = Color.White, fontSize = 9.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
        }
        when (selectedPage) {
            0 -> {
                MetricLine(watchText(language, "Транспорт", "Көлік", "Vehicle"),
                    vehicleNumber.ifBlank { watchText(language, "Не назначен", "Тағайындалмаған", "Not assigned") },
                    vehicleNumber.isNotBlank(), panel, muted, mint)
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp))
                    .padding(10.dp)) {
                    Text(watchText(language, "НОМЕР ТРАНСПОРТА", "КӨЛІК НӨМІРІ", "VEHICLE NUMBER"),
                        color = muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    if (!editingVehicle) {
                        Text(vehicleNumber.ifBlank { "—" }, color = Color.White, fontSize = 22.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                        Text(if (vehicleNumber.isBlank()) watchText(language, "Ввести номер", "Нөмірді енгізу", "Enter number")
                            else watchText(language, "Изменить номер", "Нөмірді өзгерту", "Change number"),
                            color = blue, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { vehicleDraft = vehicleNumber; editingVehicle = true }
                                .padding(vertical = 6.dp))
                    } else {
                        Text(vehicleDraft.ifBlank { "—" }, color = Color.White, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                        ("0123456789" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ").toList().chunked(5).forEach { rowKeys ->
                            Row(Modifier.padding(vertical = 2.dp)) {
                                rowKeys.forEach { key ->
                                    Box(Modifier.padding(horizontal = 2.dp).size(36.dp, 34.dp)
                                        .clip(RoundedCornerShape(9.dp)).background(Color(0xFF1B2A40))
                                        .clickable { vehicleDraft = (vehicleDraft + key).take(16) },
                                        contentAlignment = Alignment.Center) {
                                        Text(key.toString(), color = Color.White, fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Text("⌫", color = muted, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { vehicleDraft = vehicleDraft.dropLast(1) }
                                .padding(vertical = 6.dp, horizontal = 4.dp))
                        Text(watchText(language, "Сохранить номер", "Нөмірді сақтау", "Save number"),
                            color = blue, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                VehicleAssignmentState.saveFromWatch(context, vehicleDraft)
                                editingVehicle = false
                            }.padding(vertical = 6.dp))
                        Text(watchText(language, "Убрать номер", "Нөмірді өшіру", "Clear number"),
                            color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                vehicleDraft = ""
                                VehicleAssignmentState.saveFromWatch(context, "")
                                editingVehicle = false
                            }.padding(vertical = 6.dp))
                        Text(watchText(language, "Отмена", "Бас тарту", "Cancel"),
                            color = muted, fontSize = 11.sp,
                            modifier = Modifier.clickable { editingVehicle = false }.padding(vertical = 6.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(if (sessionActive) watchText(language, "ПОЕЗДКА АКТИВНА", "САПАР БЕЛСЕНДІ", "TRIP ACTIVE")
                    else watchText(language, "ГОТОВО К ПОЕЗДКЕ", "САПАРҒА ДАЙЫН", "READY FOR TRIP"),
                    color = if (sessionActive) mint else muted, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.size(104.dp).background(Color(0xFF0D2035), CircleShape)
                    .border(3.dp, if (freshBpm != null) blue else Color(0xFF33455C), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(freshBpm?.toString() ?: "—", color = Color.White, fontSize = 42.sp,
                            fontWeight = FontWeight.Bold)
                        Text("BPM", color = muted, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(if (!sessionActive) watchText(language, "Нажмите один раз — запустятся все датчики", "Бір рет басыңыз — барлық датчик қосылады", "One tap starts all sensors")
                    else watchStatusText(language, monitoringState.status), color = muted, fontSize = 11.sp,
                    maxLines = 2)
                Spacer(Modifier.height(10.dp))
                WatchAction(if (serviceRunning) watchText(language, "Завершить поездку", "Сапарды аяқтау", "End trip")
                    else watchText(language, "НАЧАТЬ ПОЕЗДКУ", "САПАРДЫ БАСТАУ", "START TRIP"), true,
                    onToggleMonitoring)
                Spacer(Modifier.height(8.dp))
                MetricLine("GPS", when {
                    !sessionActive -> watchText(language, "Запустится автоматически", "Автоматты қосылады", "Starts automatically")
                    gpsRecent -> watchText(language, "Местоположение передаётся", "Орын жіберілуде", "Location is live")
                    monitoringState.gpsEnabled -> watchText(language, "Ищем спутники", "Спутник іздеуде", "Searching")
                    else -> watchText(language, "Нет разрешения", "Рұқсат жоқ", "No permission")
                }, gpsRecent, panel, muted, mint)
            }
            1 -> {
                Text(watchText(language, "ЖИВЫЕ ПОКАЗАТЕЛИ", "НАҚТЫ КӨРСЕТКІШТЕР", "LIVE READINGS"),
                    color = mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                MetricLine(watchText(language, "Пульс", "Пульс", "Heart rate"),
                    freshBpm?.let { "$it bpm" } ?: "—", freshBpm != null, panel, muted, mint)
                MetricLine("SpO₂", oxygenStatus, oxygenStatus.any(Char::isDigit), panel, muted, mint)
                MetricLine("PPG", if (ppgCaptured) watchText(language, "Сигнал сохранён", "Сигнал сақталды", "Signal captured") else ppgStatus,
                    ppgCaptured, panel, muted, mint)
                MetricLine(watchText(language, "Шаги", "Қадам", "Steps"), monitoringState.sensors.tripSteps?.toString() ?: "—",
                    monitoringState.sensors.tripSteps != null, panel, muted, mint)
                MetricLine(watchText(language, "Движение", "Қозғалыс", "Motion"),
                    monitoringState.sensors.acceleration?.let { "%.1f м/с²".format(it) } ?: "—",
                    monitoringState.sensors.acceleration != null, panel, muted, mint)
                Spacer(Modifier.height(6.dp))
                WatchAction(if (ecgMeasuring) watchText(language, "ЭКГ измеряется…", "ЭКГ өлшенуде…", "ECG measuring…")
                    else watchText(language, "Измерить ЭКГ", "ЭКГ өлшеу", "Measure ECG"), false, onMeasureEcg)
                Text(ecgStatus, color = muted, fontSize = 10.sp, maxLines = 2)
            }
            2 -> {
                Text("GLUCOSE AI", color = blue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(watchText(language, "Часы плотно, рука неподвижно", "Сағат тығыз, қол қозғалмасын", "Watch snug, arm still"),
                    color = muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                val liveSamples by SamsungPpgState.validSamples
                val glucose by SamsungPpgState.glucose
                val capturing by SamsungPpgState.capturing
                val sensorRetries by SamsungPpgState.retries
                val meterMmol by GlucoseManualState.mmol
                val meterAt by GlucoseManualState.measuredAt
                val glucoseAt by SamsungPpgState.glucoseAt
                val measuringNow = capturing || pendingGlucose
                val view = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.DisposableEffect(measuringNow) {
                    view.keepScreenOn = measuringNow
                    onDispose { view.keepScreenOn = false }
                }
                var wasCapturing by remember { mutableStateOf(false) }
                LaunchedEffect(capturing) {
                    if (wasCapturing && !capturing && SamsungPpgState.glucose.value != null) WatchHaptics.playAlert(context)
                    wasCapturing = capturing
                }
                val shownMmol = if (measuringNow) null else glucose?.mmol ?: meterMmol
                WatchGlucoseGauge(language, shownMmol, minOf(1f, liveSamples / 1050f), measuringNow)
                Spacer(Modifier.height(6.dp))
                Text(when {
                    measuringNow && liveSamples == 0 && !capturing -> "Запуск датчиков…"
                    glucose != null -> watchText(language,
                        "Результат замера · ${((now - (glucoseAt ?: now)) / 60_000).coerceAtLeast(0)} мин назад",
                        "Өлшем нәтижесі", "Measured · ${((now - (glucoseAt ?: now)) / 60_000).coerceAtLeast(0)} min ago")
                    glucose == null && meterMmol != null -> watchText(language,
                        "По глюкометру · ${((now - meterAt) / 60_000).coerceAtLeast(0)} мин назад",
                        "Глюкометр бойынша · ${((now - meterAt) / 60_000).coerceAtLeast(0)} мин бұрын",
                        "Glucometer · ${((now - meterAt) / 60_000).coerceAtLeast(0)} min ago")
                    capturing && sensorRetries > 0 && liveSamples == 0 -> watchText(language,
                        "Затяните ремешок плотнее", "Белдікті тығызырақ тартыңыз", "Tighten the strap")
                    capturing -> watchText(language, "Не двигайте рукой", "Қолды қозғамаңыз", "Keep your arm still")
                    !sessionActive -> watchText(language, "Сначала начните поездку", "Алдымен сапарды бастаңыз", "Start a trip first")
                    else -> watchText(language, "Нажмите «Измерить»", "«Өлшеу» басыңыз", "Tap Measure")
                }, color = muted, fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                WatchAction(watchText(language, "ИЗМЕРИТЬ", "ӨЛШЕУ", "MEASURE"),
                    true, onGlucoseScan)
                Spacer(Modifier.height(12.dp))
                Row { WatchLanguage.entries.forEach { option ->
                    Box(Modifier.padding(3.dp).size(42.dp, 32.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (language == option) blue else panel).clickable { onLanguage(option) },
                        contentAlignment = Alignment.Center) {
                        Text(option.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }}
            }
            else -> {
                val oxygenFresh = oxygenMeasuredAt?.let { now - it in 0..300_000 } == true
                val assessment = DriverConditionAnalyzer.analyze(
                    freshBpm, oxygenValue, oxygenFresh, monitoringState.sensors.onBody,
                    monitoringState.motionAlertActive, ppgCaptured)
                val score = assessment.score
                val riskColor = when {
                    score == null -> muted
                    score >= 60 -> Color(0xFFFF646E)
                    score >= 30 -> Color(0xFFFFC36B)
                    else -> mint
                }
                Text(watchText(language, "СОСТОЯНИЕ ВОДИТЕЛЯ", "ЖҮРГІЗУШІ КҮЙІ", "DRIVER STATE"),
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(score?.toString() ?: "—", color = riskColor, fontSize = 44.sp,
                    fontWeight = FontWeight.Bold)
                Text("/ 100", color = muted, fontSize = 11.sp)
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(10.dp).background(Color(0xFF26364A), CircleShape)) {
                    if (score != null) Box(Modifier.fillMaxWidth(score / 100f).height(10.dp)
                        .background(riskColor, CircleShape))
                }
                Spacer(Modifier.height(8.dp))
                Text(assessment.level, color = riskColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(assessment.reasons.firstOrNull() ?: "—", color = Color.White, fontSize = 11.sp,
                    textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(7.dp))
                Text(watchText(language, "Качество анализа", "Талдау сапасы", "Analysis quality") +
                    ": ${assessment.confidence}%", color = muted, fontSize = 10.sp)
                Text(watchText(language, "Пилотный анализ сигналов", "Сигналдардың сынақ талдауы",
                    "Pilot signal analysis"), color = muted, fontSize = 9.sp, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MetricLine(label: String, value: String, active: Boolean, panel: Color, muted: Color, mint: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(panel, RoundedCornerShape(14.dp)).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (active) mint else Color(0xFF43536A), CircleShape))
        Column(Modifier.padding(start = 9.dp)) {
            Text(label, color = muted, fontSize = 10.sp)
            Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        }
    }
}

@Suppress("DEPRECATION") // Android still exposes an app's own services for service-control UIs.
private fun isMonitoringServiceRunning(context: android.content.Context): Boolean =
    runCatching {
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(32)
            .any { it.service.className == MonitoringService::class.java.name }
    }.getOrDefault(false)

@Composable
private fun WatchAction(label: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier.fillMaxWidth().height(44.dp)
            .clip(shape)
            .background(if (filled) Color(0xFF279EFF) else Color(0xFF14243D))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
