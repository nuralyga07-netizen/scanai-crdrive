package kz.crdrive.phone

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Optional, user-consented historical context; it does not replace live watch telemetry. */
object HealthConnectBridge {
    val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class)
    )
    fun available(context: Context) = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun read(context: Context): ContextHealth {
        if (!available(context)) return ContextHealth(source = "Health Connect недоступен")
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val now = Instant.now()
        val sleep = if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) client.readRecords(ReadRecordsRequest<SleepSessionRecord>(
            timeRangeFilter = TimeRangeFilter.between(now.minus(7, ChronoUnit.DAYS), now),
            ascendingOrder = false, pageSize = 1)).records.firstOrNull() else null
        val oxygen = if (HealthPermission.getReadPermission(OxygenSaturationRecord::class) in granted) client.readRecords(ReadRecordsRequest<OxygenSaturationRecord>(
            timeRangeFilter = TimeRangeFilter.between(now.minus(7, ChronoUnit.DAYS), now),
            ascendingOrder = false, pageSize = 1)).records.firstOrNull() else null
        val pressure = if (HealthPermission.getReadPermission(BloodPressureRecord::class) in granted) client.readRecords(ReadRecordsRequest<BloodPressureRecord>(
            timeRangeFilter = TimeRangeFilter.between(now.minus(7, ChronoUnit.DAYS), now),
            ascendingOrder = false, pageSize = 1)).records.firstOrNull() else null
        return ContextHealth(
            sleepHours = sleep?.let { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 3_600_000.0 },
            sleepEndedAt = sleep?.endTime?.toEpochMilli(),
            oxygenPercent = oxygen?.percentage?.value,
            oxygenMeasuredAt = oxygen?.time?.toEpochMilli(),
            systolicMmHg = pressure?.systolic?.inMillimetersOfMercury,
            diastolicMmHg = pressure?.diastolic?.inMillimetersOfMercury,
            pressureMeasuredAt = pressure?.time?.toEpochMilli(),
            source = if (granted.isEmpty()) "Нужны разрешения Health Connect" else "Health Connect"
        )
    }
}
