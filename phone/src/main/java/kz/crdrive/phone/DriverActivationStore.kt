package kz.crdrive.phone

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

data class DriverActivation(val active: Boolean, val driverName: String, val vehicleNumber: String)

class DriverActivationStore(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("driver_activation", Context.MODE_PRIVATE)

    fun current() = DriverActivation(
        active = deviceToken().isNotBlank(),
        driverName = preferences.getString("driver_name", "") ?: "",
        vehicleNumber = preferences.getString("vehicle_number", "") ?: ""
    )

    fun deviceToken(): String = preferences.getString("device_token", "") ?: BuildConfig.DEVICE_TOKEN

    fun activate(code: String, callback: (Result<DriverActivation>) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            val result = runCatching {
                require(BuildConfig.ACTIVATION_URL.isNotBlank()) { "Сервер активации ещё не подключён" }
                val normalized = code.uppercase().replace(Regex("[^A-Z0-9]"), "")
                require(normalized.length >= 10) { "Проверьте код водителя" }
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val token = bytes.joinToString("") { "%02x".format(it) }
                val connection = (URL(BuildConfig.ACTIVATION_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 8_000; readTimeout = 8_000; doOutput = true
                    setRequestProperty("content-type", "application/json")
                }
                val body = JSONObject().put("code", normalized).put("deviceToken", token).toString()
                connection.outputStream.use { it.write(body.toByteArray()) }
                val responseBody = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                    .bufferedReader().use { it.readText() }
                val response = JSONObject(responseBody)
                if (connection.responseCode !in 200..299) error(when (response.optString("error")) {
                    "invalid_or_expired_code" -> "Код неверный, использован или истёк"
                    else -> "Не удалось подключить водителя"
                })
                val activation = DriverActivation(true, response.optString("driverName", "Водитель"), response.optString("vehicleNumber"))
                preferences.edit().putString("device_token", token).putString("driver_name", activation.driverName)
                    .putString("vehicle_number", activation.vehicleNumber).apply()
                if (activation.vehicleNumber.isNotBlank()) VehicleAssignmentStore(app).save(activation.vehicleNumber)
                activation
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(result) }
        }
    }
}
