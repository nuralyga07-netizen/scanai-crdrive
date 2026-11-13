package kz.crdrive.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.wearable.Wearable

class TelemetryListenerService : WearableListenerService() {
    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.data.size > 100_000) return
        try {
            TelemetryRepository.initialize(this)
            process(event.path, DataMap.fromByteArray(event.data))
            android.util.Log.d("CRDriveTransport", "Message processed path=${event.path}")
        } catch (error: Exception) {
            android.util.Log.w("CRDriveTransport", "Invalid message", error)
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        TelemetryRepository.initialize(this)
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val path = event.dataItem.uri.path
            if (!acceptedPath(path)) return@forEach

            process(path, DataMapItem.fromDataItem(event.dataItem).dataMap)
            if (path?.startsWith("$OFFLINE_PATH/") == true ||
                path?.startsWith("$ALERT_PATH/") == true || path?.startsWith("$PPG_PATH/") == true) {
                // Delivery is acknowledged by removing the durable watch-side item.
                // The phone has already copied it into its SQLite upload queue.
                Wearable.getDataClient(this).deleteDataItems(event.dataItem.uri)
            }
        }
    }

    private fun process(path: String?, data: DataMap) {
            if (!acceptedPath(path)) return
            if (path == VEHICLE_PATH) {
                val number = VehicleAssignmentStore.normalize(data.getString(VEHICLE_KEY).orEmpty())
                getSharedPreferences(VehicleAssignmentStore.PREFERENCES, MODE_PRIVATE)
                    .edit().putString(VehicleAssignmentStore.KEY, number).apply()
                RemoteTelemetryUploader.enqueue(this, TelemetryRepository.state.value)
                return
            }
            if (path == ECG_PATH) {
                val hr = data.getInt(KEY_ECG_HR, -1)
                val at = data.getLong(KEY_TIMESTAMP, 0L)
                if (hr > 0 && at > 0L) {
                    TelemetryRepository.updateEcg(EcgReading(hr, data.getInt(KEY_ECG_QUALITY, 0),
                        data.getFloatArray(KEY_ECG_WAVE)?.toList() ?: emptyList(), at))
                }
                return
            }
            if (path == OXYGEN_PATH) {
                val percent = data.getInt(KEY_OXYGEN_PERCENT, -1)
                val measuredAt = data.getLong(KEY_TIMESTAMP, 0L)
                if (percent in 70..100 && measuredAt > 0L) {
                    TelemetryRepository.updateWatchOxygen(percent.toDouble(), measuredAt)
                    RemoteTelemetryUploader.enqueue(this, TelemetryRepository.state.value)
                }
                return
            }
            if (path?.startsWith("$ALERT_PATH/") == true) {
                val type = data.getString(KEY_ALERT_TYPE).orEmpty()
                val title = data.getString(KEY_ALERT_TITLE).orEmpty()
                val occurredAt = data.getLong(KEY_TIMESTAMP, 0L)
                if (type.matches(Regex("[A-Z_]{3,40}")) && title.isNotBlank() && occurredAt > 0L) {
                    RemoteTelemetryUploader.enqueueAlert(this, type, title.take(120), occurredAt)
                }
                return
            }
            if (path == LOCATION_PATH) {
                TelemetryRepository.updateWatchLocation(WatchLocation(
                    data.getDouble(KEY_LATITUDE), data.getDouble(KEY_LONGITUDE),
                    data.getFloat(KEY_ACCURACY), data.getLong(KEY_TIMESTAMP),
                    data.getString(KEY_PROVIDER) ?: "unknown"))
                RemoteTelemetryUploader.enqueue(this, TelemetryRepository.state.value)
                return
            }
            if (path?.startsWith("$PPG_PATH/") == true || path == PPG_PATH) {
                val timestamps = data.getLongArray(KEY_PPG_TIMESTAMPS) ?: return
                val green = data.getIntegerArrayList(KEY_PPG_GREEN) ?: return
                val red = data.getIntegerArrayList(KEY_PPG_RED) ?: return
                val infrared = data.getIntegerArrayList(KEY_PPG_IR) ?: return
                val count = minOf(timestamps.size, green.size, red.size, infrared.size)
                val samples = (0 until count).map { index -> PpgPoint(
                    timestamp = timestamps[index],
                    green = green[index].takeUnless { it == MISSING_PPG },
                    red = red[index].takeUnless { it == MISSING_PPG },
                    infrared = infrared[index].takeUnless { it == MISSING_PPG }
                ) }
                RawPpgArchiveStore(this).append(samples)
                TelemetryRepository.updatePpg(samples)
                RemoteTelemetryUploader.enqueuePpg(this, samples)
                return
            }
            val sensors = data.sensorReadings()
            if (path == SENSORS_PATH) {
                TelemetryRepository.updateSensors(sensors)
                // Contact, motion, pressure and steps must reach the dispatcher even
                // when no new heart-rate packet arrives immediately afterwards.
                RemoteTelemetryUploader.enqueue(this, TelemetryRepository.state.value)
                return
            }
            val bpm = data.getDouble(KEY_BPM, 0.0).toInt()
            val newAlert = TelemetryRepository.receive(
                bpm = bpm,
                battery = data.getInt(KEY_BATTERY, -1).takeIf { it >= 0 },
                timestamp = data.getLong(KEY_TIMESTAMP, System.currentTimeMillis()),
                lowPulseAlert = data.getBoolean(KEY_LOW_PULSE_ALERT, false),
                motionAlert = data.getBoolean(KEY_MOTION_ALERT, false),
                sensors = sensors
            )
            if (newAlert) notifyLowPulse(bpm)
            else if (needsAttentionReminder(TelemetryRepository.state.value, System.currentTimeMillis()))
                notifyAttentionReminder()
            RemoteTelemetryUploader.enqueue(this, TelemetryRepository.state.value)
    }

    private fun acceptedPath(path: String?): Boolean = path in setOf(
        TELEMETRY_PATH, SENSORS_PATH, LOCATION_PATH, PPG_PATH, OXYGEN_PATH, VEHICLE_PATH, ECG_PATH
    ) || path?.startsWith("$PPG_PATH/") == true || path?.startsWith("$ALERT_PATH/") == true ||
        path?.startsWith("$OFFLINE_PATH/") == true

    private fun DataMap.sensorReadings() = SensorReadings(
        onBody = if (containsKey(KEY_ON_BODY)) getBoolean(KEY_ON_BODY) else null,
        tripSteps = if (containsKey(KEY_TRIP_STEPS)) getInt(KEY_TRIP_STEPS) else null,
        acceleration = if (containsKey(KEY_ACCELERATION)) getDouble(KEY_ACCELERATION) else null,
        rotation = if (containsKey(KEY_ROTATION)) getDouble(KEY_ROTATION) else null,
        pressure = if (containsKey(KEY_PRESSURE)) getDouble(KEY_PRESSURE) else null,
        updatedAt = getLong(KEY_TIMESTAMP, System.currentTimeMillis())
    )

    private fun notifyLowPulse(bpm: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            ALERT_CHANNEL, "CR Drive · звуковые тревоги", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Звук только при подтверждённом низком пульсе"
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
        channel.enableVibration(true)
        manager.createNotificationChannel(channel)
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(LOW_PULSE_ID, NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CR Drive · низкий пульс")
            .setContentText("$bpm bpm · остановитесь безопасно и проверьте самочувствие")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build())
    }

    private fun notifyAttentionReminder() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        val preferences = getSharedPreferences("attention_alerts", MODE_PRIVATE)
        if (now - preferences.getLong("last_notification", 0L) < 30 * 60_000) return
        preferences.edit().putLong("last_notification", now).apply()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            ATTENTION_CHANNEL, "CR Drive · сигналы внимания", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(ATTENTION_ID, NotificationCompat.Builder(this, ATTENTION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CR Drive · проверьте самочувствие")
            .setContentText("Несколько сигналов требуют внимания. При сонливости остановитесь безопасно.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build())
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
        const val VEHICLE_PATH = "/config/vehicle"
        const val VEHICLE_KEY = "vehicle_number"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_PROVIDER = "provider"
        const val KEY_BPM = "bpm"
        const val KEY_BATTERY = "battery"
        const val KEY_TIMESTAMP = "timestamp"
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
        // A new ID is needed because Android does not allow changing an existing channel's sound.
        private const val ALERT_CHANNEL = "cr_drive_low_pulse_sound_v2"
        private const val LOW_PULSE_ID = 51
        private const val ATTENTION_CHANNEL = "cr_drive_attention_v1"
        private const val ATTENTION_ID = 52
    }
}
