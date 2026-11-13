package kz.crdrive.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class WatchMonitoringState(
    val active: Boolean = false,
    val bpm: Int? = null,
    val status: String = "Готов к запуску",
    val measuredAt: Long? = null,
    val sensors: SensorSnapshot = SensorSnapshot(),
    val gpsEnabled: Boolean = false,
    val gpsMeasuredAt: Long? = null,
    val gpsAccuracyMeters: Float? = null,
    val locationProvider: String? = null,
    val motionAlertActive: Boolean = false
)

object WatchMonitoringRepository {
    private val mutableState = MutableStateFlow(WatchMonitoringState())
    val state = mutableState.asStateFlow()
    fun update(value: WatchMonitoringState) { mutableState.value = value }
}

class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitoringJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pulseReminderJob: Job? = null
    private var supplementalSensors: SupplementalSensors? = null
    private var locationListener: LocationListener? = null
    private var networkLocationListener: LocationListener? = null
    private var samsungPpg: SamsungSensorProbe? = null
    private val lowPulseDetector = LowPulseDetector()
    private var lastHeartSentAt = 0L
    private var lastMovementAt = 0L
    private var lastAcceleration: Double? = null
    private var lastRotation: Double? = null
    private var motionAlerted = false
    @Volatile private var pulseReminderUntil = 0L
    private val sender by lazy { WatchTelemetrySender(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MEASURE_ECG) {
            samsungPpg?.startEcg() ?: run {
                SamsungEcgState.status.value = "Сначала запустите мониторинг"
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_MEASURE_GLUCOSE) {
            if (samsungPpg == null) {
                SamsungPpgState.status.value = "Сначала запустите поездку"
                return START_STICKY
            }
            // The app's own Health Services / wake-up HR listeners keep the green LED in
            // heart-rate mode, which flattens raw PPG. Pause them for the capture window.
            monitoringJob?.cancel()
            monitoringJob = null
            samsungPpg?.restartPpgCapture()
            scope.launch {
                var waited = 0L
                while (waited < GLUCOSE_CAPTURE_WINDOW_MS && (waited < 3_000L || SamsungPpgState.capturing.value)) {
                    delay(1_000L)
                    waited += 1_000L
                }
                if (monitoringJob?.isActive != true) launchHeartJob()
            }
            return START_STICKY
        }

        val locationManager = getSystemService(LocationManager::class.java)
        val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val gpsAvailable = hasLocationPermission && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkAvailable = hasLocationPermission && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val locationAllowed = gpsAvailable || networkAvailable
        val locationType = if (locationAllowed) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val serviceType = if (Build.VERSION.SDK_INT >= 34)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or locationType else locationType
        startForeground(NOTIFICATION_ID, notification("Подключение датчика…"), serviceType)
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CRDrive:Monitoring")
                .also { it.setReferenceCounted(false); it.acquire() }
        }
        if (monitoringJob?.isActive == true) {
            supplementalSensors?.refreshSteps()
            return START_STICKY
        }
        WatchMonitoringRepository.update(WatchMonitoringState(active = true,
            status = "Фоновый режим · ожидание", gpsEnabled = locationAllowed))
        lastMovementAt = System.currentTimeMillis()
        pulseReminderJob = scope.launch {
            while (true) {
                delay(PULSE_REMINDER_MS)
                if (getSharedPreferences("ecg_baseline", MODE_PRIVATE)
                        .getLong("completed_at", 0L) > 0L) {
                    pulseReminderUntil = System.currentTimeMillis() + 15_000L
                    WatchHaptics.playContactWarning(this@MonitoringService)
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, notification("Проверьте пульс на экране"))
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this,
                "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA") ==
            PackageManager.PERMISSION_GRANTED) {
            samsungPpg = SamsungSensorProbe(
                applicationContext,
                onPpgBatch = { samples -> scope.launch { runCatching { sender.sendPpg(samples) } } },
                onHeartRate = { sample -> handleHeartSample(sample) },
                onOxygen = { value, measuredAt ->
                    scope.launch { runCatching { sender.sendOxygen(value, measuredAt) } }
                },
                onEcg = { hr, quality, wave, at ->
                    scope.launch { runCatching { sender.sendEcg(hr, quality, wave, at) } }
                }
            ).also { it.connect() }
        } else SamsungPpgState.status.value = "PPG: нет разрешения · остальные датчики работают"
        if (locationAllowed) {
            fun listenerFor(provider: String) = LocationListener { location: Location ->
                val limit = if (provider == LocationManager.GPS_PROVIDER) 100f else 200f
                if (location.hasAccuracy() && location.accuracy in 0f..limit &&
                    System.currentTimeMillis() - location.time in 0..60_000) {
                    WatchMonitoringRepository.update(WatchMonitoringRepository.state.value.copy(
                        gpsMeasuredAt = location.time, gpsAccuracyMeters = location.accuracy,
                        locationProvider = provider))
                    scope.launch { runCatching { sender.sendLocation(location) } }
                }
            }
            try {
                if (gpsAvailable) {
                    val listener = listenerFor(LocationManager.GPS_PROVIDER)
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_UPDATE_INTERVAL_MS,
                        0f, listener, Looper.getMainLooper())
                    locationListener = listener
                }
                if (networkAvailable) {
                    val listener = listenerFor(LocationManager.NETWORK_PROVIDER)
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, NETWORK_UPDATE_INTERVAL_MS,
                        0f, listener, Looper.getMainLooper())
                    networkLocationListener = listener
                }
            } catch (_: SecurityException) { /* Health monitoring remains available without location. */ }
        }
        var lastOnBody: Boolean? = null
        supplementalSensors = SupplementalSensors(applicationContext) { snapshot ->
            val now = System.currentTimeMillis()
            val accelerationMoved = snapshot.acceleration?.let { current ->
                lastAcceleration?.let { kotlin.math.abs(current - it) >= ACCELERATION_MOVEMENT_DELTA }
            } == true
            val rotationMoved = snapshot.rotation?.let { current ->
                lastRotation?.let { kotlin.math.abs(current - it) >= ROTATION_MOVEMENT_DELTA }
            } == true
            snapshot.acceleration?.let { lastAcceleration = it }
            snapshot.rotation?.let { lastRotation = it }
            if (snapshot.onBody == true && (accelerationMoved || rotationMoved)) {
                lastMovementAt = now
                motionAlerted = false
            }
            val inactiveTooLong = snapshot.onBody == true &&
                now - lastMovementAt >= NO_MOVEMENT_ALERT_MS
            if (inactiveTooLong && !motionAlerted) {
                motionAlerted = true
                WatchHaptics.playMotionWarning(applicationContext)
                scope.launch { runCatching { sender.sendAlert(
                    "NO_MOVEMENT", "Нет движения 15 секунд · риск усталости", now) } }
            }
            val current = WatchMonitoringRepository.state.value
            val next = when {
                snapshot.onBody == false -> current.copy(
                    bpm = null, measuredAt = null, status = "Часы сняты с руки", sensors = snapshot)
                current.sensors.onBody == false && snapshot.onBody == true -> current.copy(
                    status = "Ожидаем свежий пульс", sensors = snapshot)
                inactiveTooLong -> current.copy(
                    status = "НЕТ ДВИЖЕНИЯ 15 СЕК · проверьте бодрость",
                    sensors = snapshot, motionAlertActive = true)
                else -> current.copy(sensors = snapshot, motionAlertActive = false)
            }
            WatchMonitoringRepository.update(next)
            when (snapshot.onBody) {
                false -> SensorContactState.warn(applicationContext)
                true -> SensorContactState.clear()
                null -> Unit
            }
            if (snapshot.onBody != lastOnBody) {
                if (snapshot.onBody == false) scope.launch { runCatching { sender.sendAlert(
                    "WATCH_OFF_WRIST", "Часы сняты или нет контакта с кожей", now) } }
                lastOnBody = snapshot.onBody
                scope.launch { runCatching { sender.sendSensors(snapshot) } }
            }
        }.also { it.start() }

        launchHeartJob()
        return START_STICKY
    }

    private fun launchHeartJob() {
        monitoringJob = scope.launch {
            val health = HealthServicesManager(applicationContext)
            while (true) {
                try {
                    health.heartRateFlow().collect { sample -> handleHeartSample(sample) }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    WatchMonitoringRepository.update(WatchMonitoringRepository.state.value.copy(
                        active = true, bpm = null, measuredAt = null,
                        status = "Переподключаем датчик пульса…"))
                    runCatching { sender.sendAlert("SENSOR_RECONNECT",
                        "Датчик пульса временно потерян · автоматическое восстановление") }
                    delay(SENSOR_RECONNECT_MS)
                }
            }
        }
    }

    @Synchronized
    private fun handleHeartSample(sample: HeartSample) {
        val sensors = supplementalSensors?.snapshot ?: SensorSnapshot()
        if (sensors.onBody == false) {
            lowPulseDetector.reset()
            return
        }
        val bpm = sample.bpm.toInt()
        if (bpm !in 35..220) return
        val now = System.currentTimeMillis()
        val alert = lowPulseDetector.accept(bpm, sample.measuredAt, now)
        val freshAlert = alert.active && now - sample.measuredAt <= 15_000
        if (alert.newlyTriggered) {
            WatchHaptics.playAlert(this)
            scope.launch { runCatching { sender.sendAlert(
                "LOW_PULSE", "Подтверждён низкий пульс · $bpm bpm", sample.measuredAt) } }
        }
        val motionAlert = WatchMonitoringRepository.state.value.motionAlertActive
        val status = when {
            freshAlert -> "НИЗКИЙ ПУЛЬС · остановитесь"
            motionAlert -> "НЕТ ДВИЖЕНИЯ 15 СЕК · проверьте бодрость"
            now < pulseReminderUntil -> "Проверьте пульс на экране"
            else -> "Пульс передаётся · $bpm bpm"
        }
        WatchMonitoringRepository.update(WatchMonitoringRepository.state.value.copy(
            active = true, bpm = bpm, status = status, measuredAt = sample.measuredAt, sensors = sensors))
        // Never block sensor collection on Bluetooth/Wi-Fi delivery, and avoid flooding
        // the phone when Health Services and Samsung SDK report the same beat.
        if (alert.newlyTriggered || now - lastHeartSentAt >= HEART_SEND_INTERVAL_MS) {
            lastHeartSentAt = now
            scope.launch { runCatching { sender.send(sample, freshAlert, motionAlert, sensors) } }
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(status))
    }

    override fun onDestroy() {
        pulseReminderJob?.cancel()
        samsungPpg?.disconnect()
        samsungPpg = null
        locationListener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        locationListener = null
        networkLocationListener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        networkLocationListener = null
        supplementalSensors?.stop()
        supplementalSensors = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        scope.cancel()
        WatchMonitoringRepository.update(WatchMonitoringState(false, null, "Мониторинг остановлен"))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CR Drive monitoring",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setContentTitle("CR Drive защищает поездку")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    companion object {
        const val ACTION_STOP = "kz.crdrive.STOP_MONITORING"
        private const val CHANNEL_ID = "cr_drive_monitoring"
        private const val NOTIFICATION_ID = 41
        // Keep transport latency low without manufacturing intermediate sensor values.
        // Health Services may still deliver samples more slowly or in batches.
        private const val HEART_SEND_INTERVAL_MS = 1_000L
        private const val PULSE_REMINDER_MS = 60 * 60_000L
        private const val GPS_UPDATE_INTERVAL_MS = 5_000L
        private const val NETWORK_UPDATE_INTERVAL_MS = 10_000L
        private const val NO_MOVEMENT_ALERT_MS = 15_000L
        private const val ACCELERATION_MOVEMENT_DELTA = 0.35
        private const val ROTATION_MOVEMENT_DELTA = 0.18
        private const val SENSOR_RECONNECT_MS = 5_000L
        private const val GLUCOSE_CAPTURE_WINDOW_MS = 150_000L
        const val ACTION_MEASURE_ECG = "kz.crdrive.wear.action.MEASURE_ECG"
        const val ACTION_MEASURE_GLUCOSE = "kz.crdrive.wear.action.MEASURE_GLUCOSE"
    }
}
