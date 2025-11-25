// Enhanced HealthDataStreamManager.kt with safety checks

package com.example.honoursproject.dataservices

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object HealthDataStreamManager {

    private val TAG = "HealthDataStreamManager"

    private val _dataStream = MutableSharedFlow<HealthDataSnapshot>(
        replay = 1,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val dataStream: SharedFlow<HealthDataSnapshot> = _dataStream.asSharedFlow()

    @Volatile
    private var currentSnapshot = HealthDataSnapshot()

    private val updateMutex = Mutex()

    // Track if manager is initialized
    @Volatile
    private var isInitialized = false

    /**
     * Initialize the manager (call from Application onCreate or DataStreamService)
     */
    fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }

        isInitialized = true
        Log.i(TAG, "HealthDataStreamManager initialized")
    }

    /**
     * Check if manager is ready to use
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Emit new data with safety checks
     */
    suspend fun emitData(snapshot: HealthDataSnapshot) = updateMutex.withLock {
        if (!isInitialized) {
            Log.w(TAG, "Attempting to emit data before initialization - auto-initializing")
            isInitialized = true
        }

        currentSnapshot = snapshot

        try {
            _dataStream.emit(snapshot)

            Log.v(TAG,
                "Emitted: HR=${snapshot.heartRate.toInt()}, " +
                        "Steps=${snapshot.cumulativeSteps}, " +
                        "Energy=${snapshot.cumulativeEnergyBurned.toInt()}, " +
                        "Source=${snapshot.dataSource}, " +
                        "HistoricalLoaded=${snapshot.isHistoricalDataLoaded}")
        } catch (e: Exception) {
            Log.e(TAG, "Error emitting data", e)
            throw e
        }
    }

    /**
     * Get current snapshot with safety check
     */
    fun getCurrentSnapshot(): HealthDataSnapshot {
        if (!isInitialized) {
            Log.w(TAG, "getCurrentSnapshot called before initialization - returning default")
        }
        return currentSnapshot
    }

    /**
     * Safe update for aggregates
     */
    suspend fun updateAggregates(steps: Int, energy: Double) = updateMutex.withLock {
        if (!isInitialized) {
            Log.w(TAG, "updateAggregates called before initialization - auto-initializing")
            isInitialized = true
        }

        val updated = currentSnapshot.copy(
            cumulativeSteps = steps,
            cumulativeEnergyBurned = energy,
            timestamp = System.currentTimeMillis()
        )
        currentSnapshot = updated

        try {
            _dataStream.emit(updated)
            Log.v(TAG, "Updated aggregates: Steps=$steps, Energy=${energy.toInt()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating aggregates", e)
            throw e
        }
    }

    /**
     * Reset with safety check
     */
    suspend fun reset() = updateMutex.withLock {
        currentSnapshot = HealthDataSnapshot()

        try {
            _dataStream.emit(currentSnapshot)
            Log.i(TAG, "Stream reset to initial state")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting stream", e)
        }
    }

    /**
     * Check if historical data has been loaded
     */
    fun isHistoricalDataLoaded(): Boolean {
        return isInitialized && currentSnapshot.isHistoricalDataLoaded
    }

    /**
     * Get data source for debugging
     */
    fun getCurrentDataSource(): DataSource = currentSnapshot.dataSource

    /**
     * Get last update timestamp
     */
    fun getLastUpdateTimestamp(): Long = currentSnapshot.timestamp
}