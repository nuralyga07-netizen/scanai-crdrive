package kz.crdrive.wear

import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey

internal object SamsungPpgState {
    val status = mutableStateOf("PPG не запущен")
    val lastReceivedAt = mutableStateOf<Long?>(null)
    val validSamples = mutableStateOf(0)
    val glucose = mutableStateOf<GlucoseAiModel.Result?>(null)
    val capturing = mutableStateOf(false)
    val glucoseAt = mutableStateOf<Long?>(null)
    val retries = mutableStateOf(0)
    val recentGreen = ArrayList<Double>()
}

internal object SamsungOxygenState {
    val status = mutableStateOf("SpO₂ не запущен")
    val value = mutableStateOf<Int?>(null)
    val measuredAt = mutableStateOf<Long?>(null)
}

internal object SamsungEcgState {
    val status = mutableStateOf("ЭКГ готова к измерению")
    val measuring = mutableStateOf(false)
    val sampleCount = mutableStateOf(0)
}

/** Owns the Samsung PPG tracker while the trip's foreground service is running. */
internal class SamsungSensorProbe(
    context: Context,
    private val onPpgBatch: (List<PpgSample>) -> Unit,
    private val onHeartRate: (HeartSample) -> Unit,
    private val onOxygen: (Int, Long) -> Unit,
    private val onEcg: (Int, Int, FloatArray, Long) -> Unit = { _, _, _, _ -> }
) {
    private val appContext = context.applicationContext
    private var ppgTracker: HealthTracker? = null
    private var heartRateTracker: HealthTracker? = null
    private var oxygenTracker: HealthTracker? = null
    private var ecgTracker: HealthTracker? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sampleCount = 0L
    // Points with a live (non-placeholder) green signal. The sensor returns constant
    // placeholders for the first seconds after start, so only these count for a capture.
    private var validSampleCount = 0L
    private var ppgSupported = false
    private var heartRateSupported = false
    private var oxygenSupported = false
    private var ecgSupported = false
    private var ecgContactSamples = 0
    private var ecgTotalSamples = 0
    private val pendingSamples = mutableListOf<PpgSample>()
    private var lastBatchSentAt = 0L
    private var ppgCaptureComplete = false
    // While true the optical sensor is reserved for the raw PPG capture: SpO2 and
    // continuous heart-rate trackers share the same LEDs and flatten the signal.
    private var glucoseCaptureActive = false
    private var oxygenRetryCount = 0
    private var disconnected = false
    private var ecgInProgress = false
    private val oxygenRetry = Runnable {
        if (!disconnected && !ecgInProgress && oxygenSupported && oxygenTracker == null) startOxygen()
    }
    private val ppgRetry = Runnable {
        if (!disconnected && !ecgInProgress && ppgSupported && !ppgCaptureComplete && ppgTracker == null) startPpg()
    }
    private val ppgListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: List<DataPoint>) {
            if (points.isEmpty()) return
            sampleCount += points.size
            validSampleCount += points.count { p ->
                ((runCatching { p.getValue(ValueKey.PpgGreenSet.PPG_GREEN) }.getOrNull()
                    ?: runCatching { p.getValue(ValueKey.PpgSet.PPG_GREEN) }.getOrNull()) ?: 0) != 0
            }
            SamsungPpgState.validSamples.value = validSampleCount.toInt()
            val receivedAt = System.currentTimeMillis()
            val latestSensorTimestamp = points.last().timestamp
            val batch = points.map { point ->
                PpgSample(
                    // Samsung documents milliseconds but not a Unix epoch. Convert the
                    // sensor clock to wall time at receipt while retaining batch spacing.
                    timestamp = wallClockTimestamp(receivedAt, latestSensorTimestamp, point.timestamp),
                    green = runCatching { point.getValue(ValueKey.PpgGreenSet.PPG_GREEN) }.getOrNull()
                        ?: runCatching { point.getValue(ValueKey.PpgSet.PPG_GREEN) }.getOrNull(),
                    red = runCatching { point.getValue(ValueKey.PpgRedSet.PPG_RED) }.getOrNull()
                        ?: runCatching { point.getValue(ValueKey.PpgSet.PPG_RED) }.getOrNull(),
                    infrared = runCatching { point.getValue(ValueKey.PpgIrSet.PPG_IR) }.getOrNull()
                        ?: runCatching { point.getValue(ValueKey.PpgSet.PPG_IR) }.getOrNull()
                )
            }
            synchronized(SamsungPpgState.recentGreen) {
                batch.forEach { p ->
                    val g = p.green
                    if (g != null && g != 0) SamsungPpgState.recentGreen.add(g.toDouble())
                }
                while (SamsungPpgState.recentGreen.size > 1600) SamsungPpgState.recentGreen.removeAt(0)
            }
            if (validSampleCount >= 1000) computeGlucoseAsync()
            if (glucoseCaptureActive && validSampleCount == 0L && sampleCount >= 400 &&
                SamsungPpgState.retries.value < MAX_SENSOR_RETRIES) {
                // The sensor decides at start whether skin contact is good; a flat signal
                // stays flat until the tracker is restarted.
                SamsungPpgState.retries.value = SamsungPpgState.retries.value + 1
                runCatching { ppgTracker?.unsetEventListener() }
                ppgTracker = null
                SamsungPpgState.validSamples.value = 0
                SamsungPpgState.status.value = "Датчик не видит кожу · перезапуск"
                handler.postDelayed({ if (glucoseCaptureActive && ppgTracker == null) startPpg() }, 1500L)
                return
            }
            val latest = batch.last()
            val p0 = points.first()
            Log.i(TAG, "PPG status: green=${runCatching { p0.getValue(ValueKey.PpgSet.GREEN_STATUS) }.getOrNull()} " +
                "red=${runCatching { p0.getValue(ValueKey.PpgSet.RED_STATUS) }.getOrNull()} " +
                "ir=${runCatching { p0.getValue(ValueKey.PpgSet.IR_STATUS) }.getOrNull()} " +
                "ts=${p0.timestamp} tsLast=${points.last().timestamp}")
            Log.i(TAG, "PPG raw sample: first=${batch.first().green}/${batch.first().red}/${batch.first().infrared} " +
                "mid=${batch[batch.size / 2].green}/${batch[batch.size / 2].red}/${batch[batch.size / 2].infrared} " +
                "last=${latest.green}/${latest.red}/${latest.infrared} " +
                "distinctGreen=${batch.map { it.green }.distinct().size} distinctIr=${batch.map { it.infrared }.distinct().size}")
            SamsungPpgState.lastReceivedAt.value = System.currentTimeMillis()
            SamsungPpgState.status.value = "PPG: $sampleCount отсчётов · G ${latest.green ?: "—"} · R ${latest.red ?: "—"} · IR ${latest.infrared ?: "—"}"
            pendingSamples += batch
            val now = System.currentTimeMillis()
            if (now - lastBatchSentAt >= PPG_SEND_INTERVAL_MS) {
                val toSend = pendingSamples.takeLast(MAX_PENDING_SAMPLES)
                pendingSamples.clear()
                lastBatchSentAt = now
                onPpgBatch(toSend)
                // Galaxy Watch4 uses the same optical sensor for raw PPG and the
                // continuous HR tracker. Keep the real PPG capture, then release
                // the sensor so live BPM can continue for the rest of the trip.
                if (!ppgCaptureComplete && validSampleCount >= MIN_GLUCOSE_PPG_SAMPLES) {
                    ppgCaptureComplete = true
                    handler.post { finishPpgCapture() }
                }
            }
            Log.i(TAG, "Received ${points.size} real PPG points; total=$sampleCount")
        }

        override fun onFlushCompleted() = Unit

        override fun onError(error: HealthTracker.TrackerError) {
            SamsungPpgState.status.value = "Ошибка PPG: $error"
            Log.e(TAG, "PPG tracker error: $error")
            runCatching { ppgTracker?.unsetEventListener() }
            ppgTracker = null
            SensorContactState.warn(appContext)
            handler.removeCallbacks(ppgRetry)
            handler.postDelayed(ppgRetry, SENSOR_RETRY_MS)
        }
    }

    private val heartRateListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: List<DataPoint>) {
            points.forEach { point ->
                val bpm = runCatching { point.getValue(ValueKey.HeartRateSet.HEART_RATE) }.getOrNull()
                if (bpm != null && bpm in 35..220) onHeartRate(HeartSample(bpm.toDouble(), System.currentTimeMillis()))
            }
        }
        override fun onFlushCompleted() = Unit
        override fun onError(error: HealthTracker.TrackerError) {
            Log.w(TAG, "Samsung heart-rate tracker error: $error")
        }
    }

    private val oxygenTimeout = Runnable {
        stopOxygen()
        oxygenRetryCount++
        SamsungOxygenState.status.value = "SpO₂: прижмите часы · повтор ${oxygenRetryCount + 1}"
        SensorContactState.warn(appContext)
        startContinuousTrackers()
        scheduleOxygenRetry()
    }

    private val oxygenListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: List<DataPoint>) {
            points.forEach { point ->
                val value = runCatching { point.getValue(ValueKey.SpO2Set.SPO2) }.getOrNull()
                if (value != null && value in 70..100) {
                    val measuredAt = System.currentTimeMillis()
                    SamsungOxygenState.value.value = value
                    SamsungOxygenState.measuredAt.value = measuredAt
                    SamsungOxygenState.status.value = "SpO₂ $value% · измерено"
                    oxygenRetryCount = 0
                    SensorContactState.clear()
                    onOxygen(value, measuredAt)
                    stopOxygen()
                    startContinuousTrackers()
                    return
                }
            }
        }
        override fun onFlushCompleted() = Unit
        override fun onError(error: HealthTracker.TrackerError) {
            oxygenRetryCount++
            SamsungOxygenState.status.value = "SpO₂: поправьте часы · повторяем"
            Log.e(TAG, "SpO2 tracker error: $error")
            stopOxygen()
            SensorContactState.warn(appContext)
            startContinuousTrackers()
            scheduleOxygenRetry()
        }
    }

    private val ecgTimeout = Runnable { finishEcgMeasurement() }
    private val ecgBuffer = ArrayList<Float>()

    /** Heart rate (R-peaks) and a 4 s waveform from the contact part of the ECG; null if no clean beats. */
    private fun analyzeEcg(): Pair<Int, FloatArray>? {
        val x = synchronized(ecgBuffer) { ArrayList(ecgBuffer) }
        if (x.size < 2500) return null
        val fs = 500
        val k = fs / 2
        val c = FloatArray(x.size) { i -> val a = maxOf(0, i - k); val b = minOf(x.size - 1, i + k); var s = 0f; for (j in a..b) s += x[j]; x[i] - s / (b - a + 1) }
        val sorted = c.map { kotlin.math.abs(it) }.sorted()
        val thr = sorted[(sorted.size * 0.985).toInt().coerceAtMost(sorted.size - 1)] * 0.6f
        val peaks = ArrayList<Int>()
        var i = 1
        while (i < c.size - 1) {
            if (c[i] > thr && c[i] >= c[i - 1] && c[i] >= c[i + 1] && (peaks.isEmpty() || i - peaks.last() > (fs * 0.3).toInt())) peaks.add(i)
            i++
        }
        if (peaks.size < 5) return null
        val rr = peaks.zipWithNext { a, b -> (b - a) * 1000.0 / fs }.sorted()
        val med = rr[rr.size / 2]
        val hr = (60_000.0 / med).toInt()
        if (hr !in 35..200) return null
        val tail = c.takeLast(2000)
        val wave = FloatArray(500) { tail[it * 4] }
        return hr to wave
    }

    private val ecgListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: List<DataPoint>) {
            if (points.isEmpty()) return
            val leadOff = runCatching {
                points.first().getValue(ValueKey.EcgSet.LEAD_OFF)
            }.getOrNull()
            val values = points.mapNotNull { point ->
                runCatching { point.getValue(ValueKey.EcgSet.ECG_MV) }.getOrNull()
            }
            ecgTotalSamples += values.size
            if (leadOff == 0) { ecgContactSamples += values.size; synchronized(ecgBuffer) { ecgBuffer.addAll(values); while (ecgBuffer.size > 16_000) ecgBuffer.removeAt(0) } }
            SamsungEcgState.sampleCount.value = ecgContactSamples
            SamsungEcgState.status.value = if (leadOff == 0) {
                "ЭКГ измеряется · ${ecgContactSamples} отсчётов"
            } else {
                "Коснитесь верхней кнопки пальцем"
            }
        }

        override fun onFlushCompleted() = Unit

        override fun onError(error: HealthTracker.TrackerError) {
            SamsungEcgState.status.value = "Ошибка ЭКГ: $error"
            Log.e(TAG, "ECG tracker error: $error")
            stopEcgTracker()
            startContinuousTrackers()
        }
    }

    private val listener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            runCatching {
                service.trackingCapability.supportHealthTrackerTypes
            }.onSuccess { types ->
                ppgSupported = types.contains(HealthTrackerType.PPG_CONTINUOUS)
                heartRateSupported = types.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)
                oxygenSupported = types.contains(HealthTrackerType.SPO2_ON_DEMAND)
                ecgSupported = types.contains(HealthTrackerType.ECG_ON_DEMAND)
                SamsungEcgState.status.value = if (ecgSupported) {
                    "ЭКГ готова к измерению"
                } else {
                    "ЭКГ не поддерживается"
                }
                if (oxygenSupported) startOxygen() else {
                    SamsungOxygenState.status.value = "SpO₂ не поддерживается"
                    startContinuousTrackers()
                }
                Log.i(TAG, "Connected; supported tracker types: $types")
            }.onFailure { error ->
                SamsungPpgState.status.value = "Не удалось проверить датчики"
                Log.e(TAG, "Capability check failed", error)
            }
        }

        override fun onConnectionEnded() {
            SamsungPpgState.status.value = "Связь со службой Samsung потеряна"
            Log.i(TAG, "Health Sensor Service connection ended")
        }

        override fun onConnectionFailed(error: HealthTrackerException) {
            SamsungPpgState.status.value = "Ошибка Samsung SDK: ${error.errorCode}"
            Log.e(TAG, "Health Sensor Service connection failed: ${error.errorCode}", error)
        }
    }

    private val service: HealthTrackingService by lazy {
        HealthTrackingService(listener, context.applicationContext)
    }

    fun connect() {
        disconnected = false
        SamsungPpgState.lastReceivedAt.value = null
        SamsungPpgState.status.value = "Подключение PPG…"
        SamsungOxygenState.value.value = null
        SamsungOxygenState.measuredAt.value = null
        SamsungOxygenState.status.value = "Подключение SpO₂…"
        service.connectService()
    }

    private fun startContinuousTrackers() {
        // Raw PPG runs only for a requested glucose capture: it blocks the pulse sensor.
        if (ppgSupported && glucoseCaptureActive && !ppgCaptureComplete) startPpg()
        else if (!ppgSupported) SamsungPpgState.status.value = "PPG не поддерживается"
        if (glucoseCaptureActive) return
        if (heartRateSupported && heartRateTracker == null) runCatching {
            service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS).also {
                it.setEventListener(heartRateListener)
                heartRateTracker = it
            }
        }.onFailure { Log.w(TAG, "Failed to start Samsung heart rate", it) }
    }

    private fun startOxygen() {
        if (oxygenTracker != null || disconnected || ecgInProgress || glucoseCaptureActive) return
        handler.removeCallbacks(oxygenRetry)
        SamsungOxygenState.status.value = "SpO₂ · не двигайтесь"
        runCatching {
            service.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND).also {
                oxygenTracker = it
                it.setEventListener(oxygenListener)
                handler.postDelayed(oxygenTimeout, OXYGEN_TIMEOUT_MS)
            }
        }.onFailure {
            SamsungOxygenState.status.value = "Не удалось запустить SpO₂"
            Log.e(TAG, "Failed to start SpO2", it)
            startContinuousTrackers()
        }
    }

    private fun stopOxygen() {
        handler.removeCallbacks(oxygenTimeout)
        runCatching { oxygenTracker?.unsetEventListener() }
        oxygenTracker = null
    }

    private fun scheduleOxygenRetry() {
        handler.removeCallbacks(oxygenRetry)
        if (!disconnected && !ecgInProgress) {
            handler.postDelayed(oxygenRetry, OXYGEN_RETRY_MS)
        }
    }

    fun startEcg() {
        if (SamsungEcgState.measuring.value) return
        if (!ecgSupported) {
            SamsungEcgState.status.value = "ЭКГ недоступна · проверьте Samsung SDK"
            return
        }
        ecgInProgress = true
        handler.removeCallbacks(oxygenRetry)
        stopOxygen()
        runCatching { ppgTracker?.unsetEventListener() }
        ppgTracker = null
        runCatching { heartRateTracker?.unsetEventListener() }
        heartRateTracker = null
        ecgContactSamples = 0
        ecgTotalSamples = 0
        synchronized(ecgBuffer) { ecgBuffer.clear() }
        SamsungEcgState.sampleCount.value = 0
        SamsungEcgState.measuring.value = true
        SamsungEcgState.status.value = "Положите палец на верхнюю кнопку · 30 сек"
        runCatching {
            service.getHealthTracker(HealthTrackerType.ECG_ON_DEMAND).also {
                ecgTracker = it
                it.setEventListener(ecgListener)
                handler.postDelayed(ecgTimeout, ECG_MEASUREMENT_MS)
            }
        }.onFailure {
            SamsungEcgState.status.value = "Не удалось запустить ЭКГ"
            Log.e(TAG, "Failed to start ECG", it)
            stopEcgTracker()
            startContinuousTrackers()
        }
    }

    private fun finishEcgMeasurement() {
        val quality = if (ecgTotalSamples > 0) ecgContactSamples * 100 / ecgTotalSamples else 0
        stopEcgTracker()
        SamsungEcgState.status.value = if (ecgContactSamples >= MIN_ECG_CONTACT_SAMPLES) {
            appContext.getSharedPreferences("ecg_baseline", Context.MODE_PRIVATE)
                .edit().putLong("completed_at", System.currentTimeMillis()).apply()
            analyzeEcg()?.let { (hr, wave) -> onEcg(hr, quality, wave, System.currentTimeMillis()) }
            "ЭКГ записана · качество контакта $quality%"
        } else {
            "ЭКГ не записана · держите палец на кнопке"
        }
        startContinuousTrackers()
        ecgInProgress = false
        scheduleOxygenRetry()
    }

    private fun stopEcgTracker() {
        handler.removeCallbacks(ecgTimeout)
        runCatching { ecgTracker?.unsetEventListener() }
        ecgTracker = null
        SamsungEcgState.measuring.value = false
    }

    fun startPpg() {
        if (!ppgSupported) {
            SamsungPpgState.status.value = "PPG не готов: проверьте подключение"
            return
        }
        if (ppgTracker != null) return
        runCatching {
            sampleCount = 0
            validSampleCount = 0
            SamsungPpgState.validSamples.value = 0
            ppgCaptureComplete = false
            pendingSamples.clear()
            lastBatchSentAt = 0L
            SamsungPpgState.status.value = "PPG запущен · ожидаем отсчёты"
            // Samsung SDK requires the requested wavelength set explicitly.
            // Dedicated green-PPG tracker first (the combined PPG_CONTINUOUS one returns placeholders
            // on this watch); fall back to the combined tracker if it is not available.
            val dedicated = runCatching { service.getHealthTracker(HealthTrackerType.PPG_GREEN) }.getOrNull()
            Log.i(TAG, "PPG tracker: " + if (dedicated != null) "PPG_GREEN" else "PPG_CONTINUOUS")
            (dedicated ?: service.getHealthTracker(HealthTrackerType.PPG_CONTINUOUS,
                setOf(PpgType.GREEN, PpgType.RED, PpgType.IR))).also { tracker ->
                tracker.setEventListener(ppgListener)
                ppgTracker = tracker
            }
        }.onFailure { error ->
            SamsungPpgState.status.value = "Не удалось запустить PPG: ${error.javaClass.simpleName}"
            Log.e(TAG, "Failed to start PPG", error)
        }
    }

    fun restartPpgCapture() {
        if (!ppgSupported || disconnected || ecgInProgress) {
            SamsungPpgState.status.value = "PPG сейчас недоступен"
            return
        }
        runCatching { ppgTracker?.unsetEventListener() }
        ppgTracker = null
        // Reserve the optical sensor: stop SpO2 + continuous HR, block their retries.
        glucoseCaptureActive = true
        SamsungPpgState.capturing.value = true
        SamsungPpgState.glucose.value = null
        SamsungPpgState.glucoseAt.value = null
        SamsungPpgState.retries.value = 0
        synchronized(SamsungPpgState.recentGreen) { SamsungPpgState.recentGreen.clear() }
        handler.removeCallbacks(captureTimeout)
        handler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)
        handler.removeCallbacks(oxygenRetry)
        handler.removeCallbacks(oxygenTimeout)
        stopOxygen()
        runCatching { heartRateTracker?.unsetEventListener() }
        heartRateTracker = null
        SamsungPpgState.lastReceivedAt.value = null
        ppgCaptureComplete = false
        sampleCount = 0
        validSampleCount = 0
        SamsungPpgState.validSamples.value = 0
        pendingSamples.clear()
        SamsungPpgState.status.value = "Glucose AI · сбор PPG 30 секунд"
        // Give the sensor service a moment to release the LEDs before PPG starts.
        handler.postDelayed({ if (glucoseCaptureActive) startPpg() }, 800L)
    }

    fun stopPpg() {
        glucoseCaptureActive = false
        SamsungPpgState.lastReceivedAt.value = null
        runCatching { ppgTracker?.unsetEventListener() }
        ppgTracker = null
        SamsungPpgState.status.value = "PPG остановлен · всего $sampleCount отсчётов"
    }

    private var computing = false
    private val captureTimeout = Runnable {
        if (glucoseCaptureActive && !ppgCaptureComplete) {
            ppgCaptureComplete = true
            finishPpgCapture()
            SamsungPpgState.status.value = "Чистый сигнал не получен · повторите измерение"
        }
    }

    private fun computeGlucoseAsync() {
        if (computing) return
        computing = true
        val data = synchronized(SamsungPpgState.recentGreen) { SamsungPpgState.recentGreen.takeLast(1500).toDoubleArray() }
        Thread {
            val r = GlucoseAiModel.estimate(data, 25.0)
            handler.post {
                if (r != null) { SamsungPpgState.glucose.value = r; SamsungPpgState.glucoseAt.value = System.currentTimeMillis() }
                computing = false
            }
        }.start()
    }

    private fun finishPpgCapture() {
        handler.removeCallbacks(captureTimeout)
        SamsungPpgState.capturing.value = false
        if (validSampleCount >= 1000) computeGlucoseAsync()
        runCatching { ppgTracker?.unsetEventListener() }
        ppgTracker = null
        glucoseCaptureActive = false
        SamsungPpgState.status.value = "PPG: $sampleCount реальных отсчётов · анализ готов"
        startContinuousTrackers()
        scheduleOxygenRetry()
    }

    fun disconnect() {
        disconnected = true
        handler.removeCallbacks(oxygenRetry)
        handler.removeCallbacks(ppgRetry)
        stopEcgTracker()
        stopOxygen()
        runCatching { heartRateTracker?.unsetEventListener() }
        heartRateTracker = null
        stopPpg()
        service.disconnectService()
    }

    companion object {
        private const val TAG = "CRDriveSamsungSensor"
        private const val PPG_SEND_INTERVAL_MS = 5_000L
        private const val MAX_PENDING_SAMPLES = 500
        // About 30 seconds at 25 Hz: long enough for useful feature extraction,
        // but captured only once per trip to avoid draining the optical sensor.
        private const val MIN_GLUCOSE_PPG_SAMPLES = 1050
        private const val CAPTURE_TIMEOUT_MS = 140_000L
        private const val MAX_SENSOR_RETRIES = 5
        private const val OXYGEN_TIMEOUT_MS = 30_000L
        private const val OXYGEN_RETRY_MS = 12_000L
        private const val SENSOR_RETRY_MS = 5_000L
        private const val ECG_MEASUREMENT_MS = 30_000L
        private const val MIN_ECG_CONTACT_SAMPLES = 5_000

        internal fun wallClockTimestamp(receivedAt: Long, latestSensorTimestamp: Long,
                                        sensorTimestamp: Long): Long {
            val age = (latestSensorTimestamp - sensorTimestamp).coerceIn(0L, 120_000L)
            return receivedAt - age
        }
    }
}
