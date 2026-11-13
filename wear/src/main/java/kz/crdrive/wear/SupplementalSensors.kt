package kz.crdrive.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

data class SensorSnapshot(
    val onBody: Boolean? = null,
    val tripSteps: Int? = null,
    val acceleration: Double? = null,
    val rotation: Double? = null,
    val pressure: Double? = null
)

/** Lightweight summaries only; raw motion streams are not stored or uploaded. */
class SupplementalSensors(
    context: Context,
    private val onUpdate: (SensorSnapshot) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val appContext = context.applicationContext
    private var initialSteps: Int? = null
    private var stepsRegistered = false
    @Volatile var snapshot = SensorSnapshot()
        private set

    fun start() {
        register(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        register(Sensor.TYPE_ACCELEROMETER)
        register(Sensor.TYPE_GYROSCOPE)
        register(Sensor.TYPE_PRESSURE)
        refreshSteps()
    }

    fun refreshSteps() {
        if (stepsRegistered || ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        stepsRegistered = register(Sensor.TYPE_STEP_COUNTER)
    }

    private fun register(type: Int): Boolean {
        val sensor = manager.getDefaultSensor(type) ?: return false
        return manager.registerListener(this, sensor, 1_000_000, 0)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        val next = when (event.sensor.type) {
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT ->
                snapshot.copy(onBody = values.firstOrNull()?.let { it >= 0.5f })
            Sensor.TYPE_STEP_COUNTER -> {
                val total = values.firstOrNull()?.toInt() ?: return
                if (initialSteps == null) initialSteps = total
                snapshot.copy(tripSteps = (total - (initialSteps ?: total)).coerceAtLeast(0))
            }
            Sensor.TYPE_ACCELEROMETER -> snapshot.copy(acceleration = magnitude(values))
            Sensor.TYPE_GYROSCOPE -> snapshot.copy(rotation = magnitude(values))
            Sensor.TYPE_PRESSURE -> snapshot.copy(pressure = values.firstOrNull()?.toDouble())
            else -> return
        }
        snapshot = next
        onUpdate(next)
    }

    private fun magnitude(values: FloatArray): Double? {
        if (values.size < 3) return null
        val result = sqrt(values.take(3).sumOf { it.toDouble() * it.toDouble() })
        return result.takeIf { it.isFinite() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun stop() { manager.unregisterListener(this) }
}
