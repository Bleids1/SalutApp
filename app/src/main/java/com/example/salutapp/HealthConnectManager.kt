package com.example.salutapp

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthConnectManager(private val context: Context) {

    private val healthConnectClient: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    val sdkStatus: Int by lazy {
        HealthConnectClient.getSdkStatus(context)
    }

    val permissionLauncher: ActivityResultContract<Set<String>, Set<String>> by lazy {
        HealthPermission.createRequestPermissionContract()
    }

    suspend fun hasAllPermissions(): Boolean {
        val granted = healthConnectClient.permissionController.getGrantedPermissions(PERMISSIONS)
        return granted.containsAll(PERMISSIONS)
    }

    fun requestPermissions(): Set<String> {
        return PERMISSIONS
    }

    fun readHealthData(): Flow<WearableData> = flow {
        val now = Instant.now()
        val yesterday = now.minus(1, ChronoUnit.DAYS)
        var totalSleepMinutes = 0L
        var totalSteps = 0L

        try {
            // Read sleep sessions
            val sleepRequest = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(yesterday, now)
            )
            val sleepSessions = healthConnectClient.readRecords(sleepRequest).records
            totalSleepMinutes = sleepSessions.sumOf { it.duration?.toMinutes() ?: 0L }

            // Aggregate total steps
            val stepsResponse = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(yesterday, now)
                )
            )
            totalSteps = stepsResponse[StepsRecord.COUNT_TOTAL] ?: 0L

        } finally {
            emit(WearableData(totalSleepMinutes.toInt(), totalSteps.toInt()))
        }
    }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )
    }
}