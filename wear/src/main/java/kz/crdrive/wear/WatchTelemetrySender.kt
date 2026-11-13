package kz.crdrive.wear

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import android.location.Location
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

data class PpgSample(val timestamp: Long, val green: Int?, val red: Int?, val infrared: Int?)

class WatchTelemetrySender(private val context: Context) {
    private val dataClient = Wearable.getDataClient(context)
    private val sequence = AtomicLong()
    private val lastOfflineSnapshotAt = AtomicLong()

    suspend fun send(sample: HeartSample, lowPulseAlert: Boolean, motionAlert: Boolean, sensors: SensorSnapshot) {
        val now = System.currentTimeMillis()
        val connected = runCatching { withTimeout(2_000) {
            Wearable.getNodeClient(context).connectedNodes.await().isNotEmpty()
        } }.getOrDefault(false)
        val keepOffline = !connected && now - lastOfflineSnapshotAt.get() >= OFFLINE_SNAPSHOT_INTERVAL_MS
        val path = if (keepOffline) {
            lastOfflineSnapshotAt.set(now)
            "$OFFLINE_PATH/${sample.measuredAt}-${sequence.incrementAndGet()}"
        } else TELEMETRY_PATH
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putDouble(KEY_BPM, sample.bpm)
            dataMap.putLong(KEY_TIMESTAMP, sample.measuredAt)
            dataMap.putInt(KEY_BATTERY, readBattery())
            dataMap.putLong(KEY_SEQUENCE, sequence.incrementAndGet())
            dataMap.putBoolean(KEY_LOW_PULSE_ALERT, lowPulseAlert)
            dataMap.putBoolean(KEY_MOTION_ALERT, motionAlert)
            dataMap.putSnapshot(sensors)
        }.asPutDataRequest().setUrgent()

        transmit(request)
    }

    suspend fun sendSensors(sensors: SensorSnapshot) {
        val request = PutDataMapRequest.create(SENSORS_PATH).apply {
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            dataMap.putLong(KEY_SEQUENCE, sequence.incrementAndGet())
            dataMap.putSnapshot(sensors)
        }.asPutDataRequest().setUrgent()
        transmit(request)
    }

    suspend fun sendLocation(location: Location) {
        val request = PutDataMapRequest.create(LOCATION_PATH).apply {
            dataMap.putDouble(KEY_LATITUDE, location.latitude)
            dataMap.putDouble(KEY_LONGITUDE, location.longitude)
            dataMap.putFloat(KEY_ACCURACY, location.accuracy)
            dataMap.putString(KEY_PROVIDER, location.provider ?: "unknown")
            dataMap.putLong(KEY_TIMESTAMP, location.time)
            dataMap.putLong(KEY_SEQUENCE, sequence.incrementAndGet())
        }.asPutDataRequest().setUrgent()
        transmit(request)
    }

    suspend fun sendPpg(samples: List<PpgSample>) {
        if (samples.isEmpty()) return
        val recent = samples.takeLast(MAX_PPG_BATCH)
        // A unique path keeps every research batch in the Data Layer while the
        // phone is disconnected instead of replacing the previous batch.
        val request = PutDataMapRequest.create("$PPG_PATH/${recent.last().timestamp}").apply {
            dataMap.putLong(KEY_TIMESTAMP, recent.last().timestamp)
            dataMap.putLong(KEY_SEQUENCE, sequence.incrementAndGet())
            dataMap.putLongArray(KEY_PPG_TIMESTAMPS, recent.map { it.timestamp }.toLongArray())
            dataMap.putIntegerArrayList(KEY_PPG_GREEN, ArrayList(recent.map { it.green ?: MISSING_PPG }))
            dataMap.putIntegerArrayList(KEY_PPG_RED, ArrayList(recent.map { it.red ?: MISSING_PPG }))
            dataMap.putIntegerArrayList(KEY_PPG_IR, ArrayList(recent.map { it.infrared ?: MISSING_PPG }))
        }.asPutDataRequest().setUrgent()
        transmit(request)
    }

    suspend fun sendOxygen(percent: Int, measuredAt: Long) {
        val request = PutDataMapRequest.create(OXYGEN_PATH).apply {
            dataMap.putInt(KEY_OXYGEN_PERCENT, percent)
            dataMap.putLong(KEY_TIMESTAMP, measuredAt)
            dataMap.putLong(KEY_SEQUENCE, sequence.incrementAndGet())
        }.asPutDataRequest().setUrgent()
        transmit(request)
    }

    suspend fun sendEcg(hr: Int, quality: Int, wave: FloatArray, measuredAt: Long) {
        val request = PutDataMapRequest.create(ECG_PATH).apply {
            dataMap.putInt(KEY_ECG_HR, hr)
            dataMap.putInt(KEY_ECG_QUALITY, quality)
            dataMap.putFloatArray(KEY_ECG_WAVE, wave)
            dataMap.putLong(KEY_TIMESTAMP, measuredAt)
            dataMap.putLong(KEY_SEQUENCE, sequence.incrementAndGet())
        }.asPutDataRequest().setUrgent()
        transmit(request)
    }

    /**
     * Alerts use a unique Data Layer path. Unlike /telemetry/latest, they are not
     * replaced while the phone is offline and are delivered when it reconnects.
     */
    suspend fun sendAlert(type: String, title: String, occurredAt: Long = System.currentTimeMillis()) {
        val request = PutDataMapRequest.create("$ALERT_PATH/$occurredAt-${sequence.incrementAndGet()}").apply {
            dataMap.putString(KEY_ALERT_TYPE, type)
            dataMap.putString(KEY_ALERT_TITLE, title)
            dataMap.putLong(KEY_TIMESTAMP, occurredAt)
            dataMap.putLong(KEY_SEQUENCE, sequence.incrementAndGet())
        }.asPutDataRequest().setUrgent()
        transmit(request)
    }

    private suspend fun transmit(request: com.google.android.gms.wearable.PutDataRequest) {
        // Transport success is not an application acknowledgement. Log both paths.
        try {
            withTimeout(5_000) {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(context).sendMessage(
                        node.id, request.uri.path!!, request.data!!).await()
                }
                Log.d("CRDriveTransport", "Message sent path=${request.uri.path} peers=${nodes.size}")
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            Log.w("CRDriveTransport", "Message path failed: ${request.uri.path}", error)
        }
        try {
            withTimeout(5_000) { dataClient.putDataItem(request).await() }
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            Log.w("CRDriveTransport", "DataItem path failed: ${request.uri.path}", error)
        }
    }

    private fun DataMap.putSnapshot(sensors: SensorSnapshot) {
        sensors.onBody?.let { putBoolean(KEY_ON_BODY, it) }
        sensors.tripSteps?.let { putInt(KEY_TRIP_STEPS, it) }
        sensors.acceleration?.let { putDouble(KEY_ACCELERATION, it) }
        sensors.rotation?.let { putDouble(KEY_ROTATION, it) }
        sensors.pressure?.let { putDouble(KEY_PRESSURE, it) }
    }

    private fun readBattery(): Int {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    companion object {
        const val TELEMETRY_PATH = "/telemetry/latest"
        const val OFFLINE_PATH = "/telemetry/offline"
        const val SENSORS_PATH = "/telemetry/sensors"
        const val LOCATION_PATH = "/telemetry/location"
        const val PPG_PATH = "/telemetry/ppg"
        const val OXYGEN_PATH = "/telemetry/oxygen"
        const val ECG_PATH = "/telemetry/ecg"
        const val KEY_ECG_HR = "ecgHr"
        const val KEY_ECG_QUALITY = "ecgQuality"
        const val KEY_ECG_WAVE = "ecgWave"
        const val ALERT_PATH = "/telemetry/alert"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_PROVIDER = "provider"
        const val KEY_BPM = "bpm"
        const val KEY_BATTERY = "battery"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_SEQUENCE = "sequence"
        const val KEY_LOW_PULSE_ALERT = "lowPulseAlert"
        const val KEY_MOTION_ALERT = "motionAlert"
        const val KEY_ON_BODY = "onBody"
        const val KEY_TRIP_STEPS = "tripSteps"
        const val KEY_ACCELERATION = "acceleration"
        const val KEY_ROTATION = "rotation"
        const val KEY_PRESSURE = "pressure"
        const val KEY_PPG_TIMESTAMPS = "ppgTimestamps"
        const val KEY_PPG_GREEN = "ppgGreen"
        const val KEY_PPG_RED = "ppgRed"
        const val KEY_PPG_IR = "ppgIr"
        const val KEY_OXYGEN_PERCENT = "oxygenPercent"
        const val KEY_ALERT_TYPE = "alertType"
        const val KEY_ALERT_TITLE = "alertTitle"
        const val MISSING_PPG = Int.MIN_VALUE
        private const val MAX_PPG_BATCH = 500
        private const val OFFLINE_SNAPSHOT_INTERVAL_MS = 15_000L
    }
}
