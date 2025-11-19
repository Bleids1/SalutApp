package com.example.salutapp

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class HealthConnectManager(private val context: Context) {

    private val healthConnectClient: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    val sdkStatus: Int
        get() = HealthConnectClient.getSdkStatus(context)

    val permissionLauncher: ActivityResultContract<Set<String>, Set<String>> by lazy {
        PermissionController.createRequestPermissionResultContract()
    }

    suspend fun hasAllPermissions(): Boolean {
        return healthConnectClient?.permissionController?.getGrantedPermissions()?.containsAll(PERMISSIONS) ?: false
    }

    fun requestPermissions(): Set<String> {
        return PERMISSIONS
    }

    fun readHealthData(): Flow<WearableData> = flow {
        val client = healthConnectClient
        if (client == null) {
            emit(WearableData(0, 0))
            return@flow
        }

        try {
            val now = Instant.now()
            val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()

            // Read sleep sessions from the last 24 hours
            val sleepRequest = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(now.minus(1, ChronoUnit.DAYS), now)
            )
            val sleepSessions = client.readRecords(sleepRequest).records
            val totalSleepMinutes = sleepSessions.sumOf {
                Duration.between(it.startTime, it.endTime).toMinutes()
            }

            // Aggregate total steps for today
            val stepsResponse = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                )
            )
            val totalSteps = stepsResponse[StepsRecord.COUNT_TOTAL] ?: 0L

            emit(WearableData(totalSleepMinutes.toInt(), totalSteps.toInt()))
        } catch (e: Exception) {
            // Emit a default value in case of error, you might want to handle this more gracefully
            emit(WearableData(0, 0))
        }
    }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )
    }
}