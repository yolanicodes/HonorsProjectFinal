package com.example.honoursproject.dataservices

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Repository interface for health data access
 * SOLID Principle: Dependency Inversion - UI depends on abstraction, not concrete implementation
 */
interface HealthDataRepository {
    suspend fun insertHealthRecord(record: HealthRecordEntity)
    suspend fun insertBatch(records: List<HealthRecordEntity>)
    suspend fun getRecordsBetween(start: Long, end: Long): List<HealthRecordEntity>
    suspend fun getDailyAggregates(start: LocalDate, end: LocalDate): List<DailyAggregateResult>
    suspend fun deleteOlderThan(timestamp: Long)
    suspend fun getLatestRecord(): HealthRecordEntity?
}

/**
 * Data class for daily aggregate results
 */
data class DailyAggregateResult(
    val date: LocalDate,
    val avgHeartRate: Double,
    val maxHeartRate: Double,
    val totalSteps: Int,
    val totalEnergy: Double
)

/**
 * Implementation of HealthDataRepository
 * SOLID Principle: Single Responsibility - Only handles database operations
 * Thread-safe with mutex protection
 */
class HealthDataRepositoryImpl(
    private val healthDao: HealthRecordDao,
    private val archiveDao: ArchiveRecordDao
) : HealthDataRepository {

    private val TAG = "HealthDataRepository"

    // Mutex to prevent concurrent database writes (race condition protection)
    private val insertMutex = Mutex()

    /**
     * Insert a single health record
     * Writes to both health_records (recent) and archive_records (permanent)
     */
    override suspend fun insertHealthRecord(record: HealthRecordEntity):Unit = insertMutex.withLock {
        try {
            healthDao.insert(record)
            archiveDao.insert(record.toArchiveRecord())
            Log.v(TAG, "Inserted record: timestamp=${record.timestamp}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert record", e)
            throw e
        }
    }

    /**
     * Insert multiple health records in batch (more efficient)
     * Used during historical data loading
     */
    override suspend fun insertBatch(records: List<HealthRecordEntity>): Unit = insertMutex.withLock {
        try {
            healthDao.insertBatch(records)
            archiveDao.insertBatch(records.map { it.toArchiveRecord() })
            Log.v(TAG, "Inserted batch: ${records.size} records")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert batch", e)
            throw e
        }
    }

    /**
     * Get health records between two timestamps
     * Used for hourly views (2H, 6H, 12H)
     */
    override suspend fun getRecordsBetween(start: Long, end: Long): List<HealthRecordEntity> {
        return try {
            healthDao.getRecordsBetween(start, end)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get records between $start and $end", e)
            emptyList()
        }
    }

    /**
     * Get daily aggregates for a date range
     * Used for 7-day view
     *
     * Returns:
     * - avgHeartRate: AVERAGE heart rate for each day
     * - totalSteps: TOTAL (max cumulative) steps for each day
     * - totalEnergy: TOTAL energy burned for each day
     */
    override suspend fun getDailyAggregates(
        start: LocalDate,
        end: LocalDate
    ): List<DailyAggregateResult> {
        return try {
            val startTimestamp = start.atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            val endTimestamp = end.atTime(23, 59, 59)
                .atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli()

            val records = archiveDao.getRecordsBetween(startTimestamp, endTimestamp)

            // Group by date and calculate aggregates
            records
                .groupBy { record ->
                    Instant.ofEpochMilli(record.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                }
                .map { (date, dailyRecords) ->
                    DailyAggregateResult(
                        date = date,
                        avgHeartRate = dailyRecords.map { it.heartRate }.average(),
                        maxHeartRate = dailyRecords.maxOf { it.heartRate },
                        totalSteps = dailyRecords.maxOf { it.stepCount },  // Max cumulative = total
                        totalEnergy = dailyRecords.maxOf { it.activeEnergyBurned }
                    )
                }
                .sortedBy { it.date }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get daily aggregates", e)
            emptyList()
        }
    }

    /**
     * Delete old records (cleanup)
     */
    override suspend fun deleteOlderThan(timestamp: Long) {
        try {
            healthDao.deleteOlderThan(timestamp)
            Log.i(TAG, "Deleted records older than $timestamp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete old records", e)
        }
    }

    /**
     * Get the most recent health record
     */
    override suspend fun getLatestRecord(): HealthRecordEntity? {
        return try {
            val records = healthDao.getRecordsBetween(
                System.currentTimeMillis() - 24 * 60 * 60 * 1000,  // Last 24 hours
                System.currentTimeMillis()
            )
            records.maxByOrNull { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get latest record", e)
            null
        }
    }
}

/**
 * Extension function to convert HealthRecordEntity to ArchiveRecordEntity
 */
private fun HealthRecordEntity.toArchiveRecord() = ArchiveRecordEntity(
    id = 0,  // Auto-generate
    timestamp = timestamp,
    heartRate = heartRate,
    stepCount = stepCount,
    activeEnergyBurned = activeEnergyBurned,
    basalEnergyBurned = basalEnergyBurned,
    cyclePhase = cyclePhase,
    cycleDay = cycleDay,
    vo2Max = vo2Max,
    restingHeartRate = restingHeartRate,
    basalBodyTemperature = basalBodyTemperature
)
