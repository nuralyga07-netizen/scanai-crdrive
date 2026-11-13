package kz.crdrive.phone

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/** Durably queues real readings before upload. */
object RemoteTelemetryUploader {
    private const val UNIQUE_WORK = "cr-drive-latest-telemetry"
    private val executor = Executors.newSingleThreadExecutor()
    private val uploading = AtomicBoolean(false)
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var queueStore: TelemetryQueueStore? = null

    private fun queue(context: Context): TelemetryQueueStore = queueStore ?: synchronized(this) {
        queueStore ?: TelemetryQueueStore(context.applicationContext).also { queueStore = it }
    }

    fun enqueue(context: Context, state: TelemetryState) {
        if (BuildConfig.TELEMETRY_URL.isBlank() || DriverActivationStore(context).deviceToken().isBlank()) return
        val app = context.applicationContext
        sessionPreferences = app.getSharedPreferences("telemetry_session", Context.MODE_PRIVATE)
        val body = payload(app, state) ?: return
        queue(app).enqueue(body)
        schedule(app)
        triggerImmediateUpload(app)
    }

    fun enqueuePpg(context: Context, samples: List<PpgPoint>) {
        if (BuildConfig.TELEMETRY_URL.isBlank() || DriverActivationStore(context).deviceToken().isBlank() || samples.isEmpty()) return
        val body = JSONObject().apply {
            put("kind", "ppg")
            put("vehicleNumber", VehicleAssignmentStore(context).current())
            put("sessionId", sessionId(context, samples.first().timestamp))
            put("capturedAt", samples.first().timestamp)
            put("timestamps", org.json.JSONArray(samples.map { it.timestamp }))
            put("green", org.json.JSONArray(samples.map { it.green ?: JSONObject.NULL }))
            put("red", org.json.JSONArray(samples.map { it.red ?: JSONObject.NULL }))
            put("infrared", org.json.JSONArray(samples.map { it.infrared ?: JSONObject.NULL }))
        }.toString()
        val app = context.applicationContext
        queue(app).enqueue(body)
        schedule(app)
        triggerImmediateUpload(app)
    }

    fun enqueueAlert(context: Context, type: String, title: String, occurredAt: Long) {
        if (BuildConfig.TELEMETRY_URL.isBlank() || DriverActivationStore(context).deviceToken().isBlank()) return
        val app = context.applicationContext
        sessionPreferences = app.getSharedPreferences("telemetry_session", Context.MODE_PRIVATE)
        val body = JSONObject().apply {
            put("kind", "alert")
            put("alertType", type)
            put("alertTitle", title)
            put("vehicleNumber", VehicleAssignmentStore(context).current())
            put("sessionId", sessionId(context, occurredAt))
            put("capturedAt", occurredAt)
        }.toString()
        queue(app).enqueue(body)
        schedule(app)
        triggerImmediateUpload(app)
    }

    /** Flushes the durable queue without waiting for WorkManager's retry window. */
    fun flush(context: Context) {
        if (BuildConfig.TELEMETRY_URL.isBlank() || DriverActivationStore(context).deviceToken().isBlank()) return
        val app = context.applicationContext
        schedule(app)
        triggerImmediateUpload(app)
    }

    private fun triggerImmediateUpload(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < 2_000 || !uploading.compareAndSet(false, true)) return
        lastAttemptAt = now
        executor.execute {
            try { uploadPending(context) }
            catch (error: Exception) { Log.w("CRDriveRemote", "Immediate upload deferred", error) }
            finally { uploading.set(false) }
        }
    }

    @Synchronized
    internal fun uploadPending(context: Context): Boolean {
        val queue = queue(context)
        return try {
            var sent = 0
            while (sent < MAX_UPLOAD_BATCH) {
                // A long offline backlog must never hold up the current reading.
                // Send the newest packet first when it is still fresh, then drain history.
                val latest = queue.peekLatest()
                val latestAt = latest?.let { runCatching { JSONObject(it.body).optLong("capturedAt") }.getOrNull() }
                val fresh = latestAt != null && latestAt <= System.currentTimeMillis() + 5_000 &&
                    System.currentTimeMillis() - latestAt <= 30_000
                val pending = (if (fresh) latest else queue.peek()) ?: return true
                when (upload(context, pending.body)) {
                    UploadOutcome.RETRY -> return false
                    UploadOutcome.ACCEPTED, UploadOutcome.INVALID_PACKET -> queue.remove(pending.id)
                }
                sent++
            }
            queue.count() == 0
        }
        catch (error: Exception) { Log.w("CRDriveRemote", "Queued upload failed", error); false }
    }

    private fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<TelemetryUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private fun payload(context: Context, state: TelemetryState): String? {
        val capturedAt = state.receivedAt ?: return null
        val location = state.watchLocation?.takeIf { it.isFresh(System.currentTimeMillis()) }
        return JSONObject().apply {
            put("kind", "telemetry")
            put("vehicleNumber", VehicleAssignmentStore(context).current())
            put("sessionId", sessionIdForTimestamp(capturedAt))
            state.bpm?.let { put("bpm", it) }
            state.contextHealth.oxygenPercent?.let { put("oxygenPercent", it) }
            state.battery?.let { put("batteryPercent", it) }
            location?.let { put("latitude", it.latitude); put("longitude", it.longitude); put("accuracyM", it.accuracyMeters) }
            put("lowPulseAlert", state.lowPulseAlert)
            put("motionAlert", state.motionAlert)
            put("pilotRiskScore", state.assessment.score)
            state.sensors.onBody?.let { put("onBody", it) }
            state.sensors.tripSteps?.let { put("tripSteps", it) }
            state.sensors.acceleration?.let { put("acceleration", it) }
            state.sensors.rotation?.let { put("rotation", it) }
            state.sensors.pressure?.let { put("airPressureHpa", it) }
            put("capturedAt", capturedAt)
        }.toString()
    }

    @Synchronized
    private fun sessionId(context: Context, capturedAt: Long): String {
        sessionPreferences = context.applicationContext.getSharedPreferences("telemetry_session", Context.MODE_PRIVATE)
        return sessionIdForTimestamp(capturedAt)
    }

    @Synchronized
    private fun sessionIdForTimestamp(capturedAt: Long): String {
        val preferences = sessionPreferences
            ?: throw IllegalStateException("Telemetry session is not initialized")
        val previousAt = preferences.getLong("last_captured_at", 0L)
        var id = preferences.getString("session_id", null)
        if (id == null || previousAt <= 0L || capturedAt + SESSION_GAP_MS < previousAt || capturedAt - previousAt > SESSION_GAP_MS) {
            id = UUID.randomUUID().toString()
        }
        preferences.edit().putString("session_id", id).putLong("last_captured_at", capturedAt).apply()
        return id
    }

    private enum class UploadOutcome { ACCEPTED, INVALID_PACKET, RETRY }

    private fun upload(context: Context, body: String): UploadOutcome {
        val connection = (URL(BuildConfig.TELEMETRY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8_000; readTimeout = 8_000; doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-device-token", DriverActivationStore(context).deviceToken())
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val response = connection.responseCode
        connection.disconnect()
        if (response in 200..299) {
            Log.d("CRDriveRemote", "Upload accepted: HTTP $response")
            return UploadOutcome.ACCEPTED
        }
        if (response == 400 || response == 422) {
            // A malformed or expired packet cannot become valid on retry. Keeping it at
            // the head of the FIFO would prevent every later live reading from uploading.
            Log.w("CRDriveRemote", "Dropping invalid queued packet: HTTP $response")
            return UploadOutcome.INVALID_PACKET
        }
        // Authentication and temporary server errors need intervention or a later retry.
        Log.w("CRDriveRemote", "Upload deferred: HTTP $response")
        return UploadOutcome.RETRY
    }

    private const val MAX_UPLOAD_BATCH = 250
    private const val SESSION_GAP_MS = 5 * 60_000L
    @Volatile private var sessionPreferences: android.content.SharedPreferences? = null
}

class TelemetryUploadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = if (RemoteTelemetryUploader.uploadPending(applicationContext)) Result.success() else Result.retry()
}
