package fr.omronbridge.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class HealthConnectWriter(context: Context) {

    private val appContext = context.applicationContext

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(appContext)

    fun clientOrNull(): HealthConnectClient? =
        if (sdkStatus() == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(appContext)
        } else {
            null
        }

    suspend fun insertMeasurement(
        systolic: Int,
        diastolic: Int,
        pulse: Int,
        time: Instant
    ) {
        val client = clientOrNull()
            ?: error("Santé Connect n'est pas disponible sur cet appareil.")

        val zoneOffset = ZoneId.systemDefault().rules.getOffset(time)
        val groupId = UUID.randomUUID().toString()

        val bloodPressure = BloodPressureRecord(
            time = time,
            zoneOffset = zoneOffset,
            metadata = Metadata.manualEntry(
                clientRecordId = "omron-bp-$groupId",
                clientRecordVersion = 1
            ),
            systolic = Pressure.millimetersOfMercury(systolic.toDouble()),
            diastolic = Pressure.millimetersOfMercury(diastolic.toDouble()),
            bodyPosition = BloodPressureRecord.BODY_POSITION_UNKNOWN,
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN
        )

        // HeartRateRecord represents a series. A one-second interval with one
        // sample is used to represent the pulse displayed with this BP reading.
        val heartRate = HeartRateRecord(
            startTime = time,
            startZoneOffset = zoneOffset,
            endTime = time.plusSeconds(1),
            endZoneOffset = zoneOffset,
            samples = listOf(
                HeartRateRecord.Sample(
                    time = time,
                    beatsPerMinute = pulse.toLong()
                )
            ),
            metadata = Metadata.manualEntry(
                clientRecordId = "omron-hr-$groupId",
                clientRecordVersion = 1
            )
        )

        client.insertRecords(listOf(bloodPressure, heartRate))
    }
}
