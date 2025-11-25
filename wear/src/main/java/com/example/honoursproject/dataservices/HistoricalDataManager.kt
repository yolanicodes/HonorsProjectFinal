package com.example.honoursproject.dataservices

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime

val Context.historicalDataStore by preferencesDataStore(name = "historical_data_prefs")

/**
 * Manages historical data loading state and persistence
 * Ensures data is loaded only once on first install
 *
 * This class tracks whether historical CSV data has been loaded into the database,
 * preventing redundant loads on subsequent app launches.
 */
object HistoricalDataManager {
    private const val TAG = "HistoricalDataManager"

    // DataStore keys
    private val KEY_HISTORICAL_LOADED = booleanPreferencesKey("historical_data_loaded")
    private val KEY_LOAD_TIMESTAMP = longPreferencesKey("historical_load_timestamp")
    private val KEY_EVALUATION_MODE = booleanPreferencesKey("was_evaluation_mode")
    private val KEY_CUTOFF_TIME = stringPreferencesKey("historical_cutoff_time")
    private val KEY_RECORD_COUNT = longPreferencesKey("historical_record_count")

    /**
     * Check if historical data has already been loaded
     * Returns true if data was previously loaded from CSV
     */
    suspend fun isHistoricalDataLoaded(context: Context): Boolean {
        return context.historicalDataStore.data
            .map { prefs -> prefs[KEY_HISTORICAL_LOADED] ?: false }
            .first()
    }

    /**
     * Get detailed information about the historical data load
     * Returns null if data hasn't been loaded yet
     */
    suspend fun getHistoricalDataInfo(context: Context): HistoricalDataInfo? {
        val prefs = context.historicalDataStore.data.first()

        return if (prefs[KEY_HISTORICAL_LOADED] == true) {
            HistoricalDataInfo(
                isLoaded = true,
                loadTimestamp = prefs[KEY_LOAD_TIMESTAMP] ?: 0L,
                wasEvaluationMode = prefs[KEY_EVALUATION_MODE] ?: false,
                cutoffTime = prefs[KEY_CUTOFF_TIME] ?: "",
                recordCount = prefs[KEY_RECORD_COUNT] ?: 0L
            )
        } else {
            null
        }
    }

    /**
     * Mark historical data as loaded and save metadata
     * Call this after successfully loading data from CSV
     *
     * @param context Application context
     * @param cutoffTime The time up to which data was loaded
     * @param recordCount Number of records loaded
     * @param isEvaluationMode Whether evaluation mode was active during load
     */
    suspend fun markHistoricalDataLoaded(
        context: Context,
        cutoffTime: LocalDateTime,
        recordCount: Long,
        isEvaluationMode: Boolean
    ) {
        context.historicalDataStore.edit { prefs ->
            prefs[KEY_HISTORICAL_LOADED] = true
            prefs[KEY_LOAD_TIMESTAMP] = System.currentTimeMillis()
            prefs[KEY_EVALUATION_MODE] = isEvaluationMode
            prefs[KEY_CUTOFF_TIME] = cutoffTime.toString()
            prefs[KEY_RECORD_COUNT] = recordCount
        }

        Log.i(TAG, "✓ Historical data marked as loaded:")
        Log.i(TAG, "  Records: $recordCount")
        Log.i(TAG, "  Cutoff: $cutoffTime")
        Log.i(TAG, "  Evaluation mode: $isEvaluationMode")
    }

    /**
     * Reset historical data flag
     * Use this for testing or when data needs to be reloaded
     * WARNING: This will cause a full reload on next start
     */
    suspend fun resetHistoricalDataFlag(context: Context) {
        context.historicalDataStore.edit { prefs ->
            prefs.clear()
        }
        Log.i(TAG, "⚠ Historical data flag reset - will reload on next start")
    }

    /**
     * Verify that the database actually has data
     * This is a safety check to ensure the flag matches reality
     *
     * @return true if database contains records, false otherwise
     */
    suspend fun verifyDatabaseHasData(context: Context): Boolean {
        return try {
            val dao = ArchiveDatabase.getInstance(context).archiveRecordDao()
            val records = dao.getAll()
            val count = records.size

            Log.i(TAG, "Database verification: $count records found")

            if (count > 0) {
                // Additional verification: check if we have recent data
                val latestRecord = records.maxByOrNull { it.timestamp }
                Log.i(TAG, "  Latest record: ${java.util.Date(latestRecord?.timestamp ?: 0)}")
            }

            count > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying database", e)
            false
        }
    }

    /**
     * Get a summary of the database state
     * Useful for debugging and status displays
     */
    suspend fun getDatabaseSummary(context: Context): DatabaseSummary {
        return try {
            val archiveDao = ArchiveDatabase.getInstance(context).archiveRecordDao()
            val healthDao = HealthDatabase.getInstance(context).healthRecordDao()

            val archiveRecords = archiveDao.getAll()
            val archiveCount = archiveRecords.size

            val earliestArchive = archiveRecords.minByOrNull { it.timestamp }
            val latestArchive = archiveRecords.maxByOrNull { it.timestamp }

            val healthLatest = healthDao.getLatestRecord()

            DatabaseSummary(
                archiveRecordCount = archiveCount,
                earliestTimestamp = earliestArchive?.timestamp ?: 0L,
                latestTimestamp = latestArchive?.timestamp ?: 0L,
                hasHealthRecords = healthLatest != null,
                healthRecordTimestamp = healthLatest?.timestamp ?: 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting database summary", e)
            DatabaseSummary(0, 0, 0, false, 0)
        }
    }

    /**
     * Check if a reload is needed
     * This can be expanded to include version checks, data corruption checks, etc.
     */
    suspend fun shouldReloadData(context: Context): Boolean {
        val isLoaded = isHistoricalDataLoaded(context)
        val hasData = verifyDatabaseHasData(context)

        // Reload if flag says loaded but database is empty (data corruption)
        if (isLoaded && !hasData) {
            Log.w(TAG, "⚠ Data corruption detected - flag set but no database records")
            resetHistoricalDataFlag(context)
            return true
        }

        // Reload if flag says not loaded
        if (!isLoaded) {
            Log.i(TAG, "No historical data loaded yet")
            return true
        }

        return false
    }
}

/**
 * Information about the historical data load
 */
data class HistoricalDataInfo(
    val isLoaded: Boolean,
    val loadTimestamp: Long,
    val wasEvaluationMode: Boolean,
    val cutoffTime: String,
    val recordCount: Long
)

/**
 * Summary of database state
 */
data class DatabaseSummary(
    val archiveRecordCount: Int,
    val earliestTimestamp: Long,
    val latestTimestamp: Long,
    val hasHealthRecords: Boolean,
    val healthRecordTimestamp: Long
)