package kz.crdrive.wear
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import java.time.Instant
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HeartSample(val bpm: Double, val measuredAt: Long)
class HealthServicesManager(context: Context) {
    private val client = HealthServices.getClient(context).passiveMonitoringClient
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    fun heartRateFlow(): Flow<HeartSample> = callbackFlow {
        val future = client.getCapabilitiesAsync()
        val capabilities = suspendCancellableCoroutine<androidx.health.services.client.data.PassiveMonitoringCapabilities> { continuation ->
            future.addListener({
                try { continuation.resume(future.get()) }
                catch (error: Exception) { continuation.resumeWithException(error) }
            }, java.util.concurrent.Executor { it.run() })
            continuation.invokeOnCancellation { future.cancel(true) }
        }
        check(DataType.HEART_RATE_BPM in capabilities.supportedDataTypesPassiveMonitoring)
        val callback = object : PassiveListenerCallback {
            override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
                dataPoints.getData(DataType.HEART_RATE_BPM)
                    .sortedBy { it.getTimeInstant(boot) }
                    .forEach { point ->
                        trySend(HeartSample(point.value, point.getTimeInstant(boot).toEpochMilli()))
                    }
            }
            override fun onPermissionLost() { close(SecurityException("Нужен доступ к датчикам всегда")) }
            override fun onRegistrationFailed(throwable: Throwable) { close(throwable) }
        }
        client.setPassiveListenerCallback(
            PassiveListenerConfig.builder().setDataTypes(setOf(DataType.HEART_RATE_BPM)).build(), callback)

        // A wake-up heart-rate sensor can deliver readings while the display is off.
        // Keep Health Services passive monitoring as a fallback if direct delivery stops.
        val wakeUpHeartSensor = sensorManager.getSensorList(Sensor.TYPE_HEART_RATE)
            .firstOrNull { it.isWakeUpSensor() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
                val bpm = event.values.firstOrNull()?.toDouble() ?: return
                if (!bpm.isFinite() || bpm !in 35.0..220.0) return
                val measuredAt = System.currentTimeMillis() - SystemClock.elapsedRealtime() +
                    event.timestamp / 1_000_000
                trySend(HeartSample(bpm, measuredAt))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (wakeUpHeartSensor != null) {
            sensorManager.registerListener(listener, wakeUpHeartSensor, SensorManager.SENSOR_DELAY_NORMAL, 0)
        }
        awaitClose {
            sensorManager.unregisterListener(listener)
            client.clearPassiveListenerCallbackAsync()
        }
    }
}
