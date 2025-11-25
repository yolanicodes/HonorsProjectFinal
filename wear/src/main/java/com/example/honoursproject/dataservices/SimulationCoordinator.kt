package com.example.honoursproject.dataservices

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Utility class to coordinate health and workout simulations
 * Provides high-level control and synchronization between services
 *
 * SOLID Principle: Single Responsibility - Only coordinates simulation services
 */
object SimulationCoordinator {

    private const val TAG = "SimulationCoordinator"

    /**
     * Combined state flow that provides overall simulation status
     * Combines health simulation state and workout state
     */
    val combinedSimulationState = combine(
        SimulationManager.simulationState,
        WorkoutSimulationManager.workoutState,
        WorkoutSimulationManager.workoutTimingState,
        HealthDataStreamManager.dataStream
    ) { healthState, workoutState, workoutTimingState, healthSnapshot ->
        CombinedSimulationState(
            healthSimulation = healthState,
            workoutState = workoutState,
            workoutTimingState = workoutTimingState,
            isHealthRunning = healthState.isRunning,
            isHealthPaused = healthState.isPaused,
            isHistoricalDataLoaded = healthSnapshot.isHistoricalDataLoaded,
            isSynchronized = isSystemSynchronized(healthState, workoutTimingState, healthSnapshot),
            overallProgress = (healthState.currentIndex.toFloat() / healthState.totalRecords.coerceAtLeast(1))
                .coerceIn(0f, 1f)
        )
    }.distinctUntilChanged()

    /**
     * Start all simulation services through the DataStreamService
     */
    fun startSimulation(context: Context) {
        Log.i(TAG, "Starting coordinated simulation")
        val intent = Intent(context, DataStreamService::class.java).apply {
            action = DataStreamService.ACTION_START_STREAMING
        }
        context.startForegroundService(intent)
    }

    /**
     * Pause all simulation services
     */
    fun pauseSimulation(context: Context) {
        Log.i(TAG, "Pausing coordinated simulation")
        val intent = Intent(context, DataStreamService::class.java).apply {
            action = DataStreamService.ACTION_PAUSE_STREAMING
        }
        context.startService(intent)

        SimulationManager.pauseSimulation()
        WorkoutSimulationManager.pauseSimulation()
    }

    /**
     * Resume all simulation services
     */
    fun resumeSimulation(context: Context) {
        Log.i(TAG, "Resuming coordinated simulation")
        val intent = Intent(context, DataStreamService::class.java).apply {
            action = DataStreamService.ACTION_RESUME_STREAMING
        }
        context.startService(intent)

        SimulationManager.resumeSimulation()
        WorkoutSimulationManager.resumeSimulation()
    }

    /**
     * Stop all simulation services
     */
    fun stopSimulation(context: Context) {
        Log.i(TAG, "Stopping coordinated simulation")
        val intent = Intent(context, DataStreamService::class.java).apply {
            action = DataStreamService.ACTION_STOP_STREAMING
        }
        context.startService(intent)
    }

    /**
     * Reset all simulation services and data stream
     */
    suspend fun resetSimulation(context: Context) {
        Log.i(TAG, "Resetting coordinated simulation")

        SimulationManager.stopStreaming()
        WorkoutSimulationManager.stopSimulation()
        HealthDataStreamManager.reset()

        Log.i(TAG, "✓ Simulation reset complete")
    }

    /**
     * Check if the overall system is synchronized
     * System is synchronized when:
     * 1. Health data is running
     * 2. Workout timing is synchronized with health data
     * 3. No pause/resume conflicts
     */
    private fun isSystemSynchronized(
        healthState: SimulationState,
        workoutTimingState: WorkoutTimingState,
        healthSnapshot: HealthDataSnapshot
    ): Boolean {
        return when {
            !healthState.isRunning -> false
            healthState.isPaused -> false
            !healthSnapshot.isHistoricalDataLoaded -> false
            workoutTimingState is WorkoutTimingState.SynchronizationNeeded -> false
            else -> {
                // Check if workout timing has sync flag set
                when (workoutTimingState) {
                    is WorkoutTimingState.NotificationScheduled -> workoutTimingState.healthDataSynced
                    is WorkoutTimingState.UserStartedEarly -> workoutTimingState.healthDataSynced
                    is WorkoutTimingState.AutoStartCountdown -> workoutTimingState.healthDataSynced
                    is WorkoutTimingState.InProgress -> workoutTimingState.healthDataSynced
                    is WorkoutTimingState.Finished -> workoutTimingState.healthDataSynced
                    WorkoutTimingState.Idle -> true
                    is WorkoutTimingState.SynchronizationNeeded -> false
                }
            }
        }
    }

    /**
     * Get current synchronization status message
     */
    fun getSynchronizationStatusMessage(): String {
        val healthState = SimulationManager.simulationState.value
        val healthSnapshot = HealthDataStreamManager.getCurrentSnapshot()
        val workoutSynced = WorkoutSimulationManager.isHealthDataSynchronized()

        return when {
            !healthState.isRunning -> "Health simulation stopped"
            healthState.isPaused -> "Health simulation paused"
            !healthSnapshot.isHistoricalDataLoaded -> "Loading historical data..."
            !workoutSynced -> "Workout timing not synchronized"
            else -> "✓ All systems synchronized"
        }
    }

    /**
     * Get detailed synchronization status
     */
    fun getDetailedSyncStatus(): SynchronizationStatus {
        val healthState = SimulationManager.simulationState.value
        val healthSnapshot = HealthDataStreamManager.getCurrentSnapshot()
        val workoutState = WorkoutSimulationManager.workoutState.value
        val workoutTiming = WorkoutSimulationManager.workoutTimingState.value

        return SynchronizationStatus(
            healthSimulationRunning = healthState.isRunning,
            healthSimulationPaused = healthState.isPaused,
            historicalDataLoaded = healthSnapshot.isHistoricalDataLoaded,
            workoutDataSynced = WorkoutSimulationManager.isHealthDataSynchronized(),
            workoutState = workoutState.toString(),
            workoutTiming = workoutTiming::class.simpleName ?: "Unknown",
            overallSyncStatus = getSynchronizationStatusMessage()
        )
    }

    /**
     * Check if simulation can be paused
     */
    fun canPause(): Boolean {
        return SimulationManager.simulationState.value.isRunning &&
                !SimulationManager.simulationState.value.isPaused
    }

    /**
     * Check if simulation can be resumed
     */
    fun canResume(): Boolean {
        return SimulationManager.simulationState.value.isRunning &&
                SimulationManager.simulationState.value.isPaused
    }

    /**
     * Check if simulation is completely stopped
     */
    fun isStopped(): Boolean {
        return !SimulationManager.simulationState.value.isRunning
    }

    /**
     * Check if historical data is loaded
     */
    fun isHistoricalDataLoaded(): Boolean {
        return HealthDataStreamManager.getCurrentSnapshot().isHistoricalDataLoaded
    }

    /**
     * Get historical load progress (0.0 to 1.0)
     */
    fun getHistoricalLoadProgress(): Float {
        return SimulationManager.historicalLoadingProgress.value
    }

    /**
     * Check if system is ready for workouts
     * (historical data loaded and health sim running)
     */
    fun isSystemReadyForWorkouts(): Boolean {
        return isHistoricalDataLoaded() &&
                !isStopped() &&
                !canPause() // canPause() means not paused
    }

    /**
     * Log current system status for debugging
     */
    fun logSystemStatus() {
        val status = getDetailedSyncStatus()
        Log.i(TAG, """
            ========== SIMULATION STATUS ==========
            Health Simulation Running: ${status.healthSimulationRunning}
            Health Simulation Paused: ${status.healthSimulationPaused}
            Historical Data Loaded: ${status.historicalDataLoaded}
            Workout Data Synced: ${status.workoutDataSynced}
            Workout State: ${status.workoutState}
            Workout Timing: ${status.workoutTiming}
            Overall Status: ${status.overallSyncStatus}
            =======================================
        """.trimIndent())
    }
}

/**
 * Combined state representing the overall simulation status
 */
data class CombinedSimulationState(
    val healthSimulation: SimulationState,
    val workoutState: WorkoutState,
    val workoutTimingState: WorkoutTimingState,
    val isHealthRunning: Boolean,
    val isHealthPaused: Boolean,
    val isHistoricalDataLoaded: Boolean,
    val isSynchronized: Boolean,
    val overallProgress: Float
)

/**
 * Detailed synchronization status for debugging
 */
data class SynchronizationStatus(
    val healthSimulationRunning: Boolean,
    val healthSimulationPaused: Boolean,
    val historicalDataLoaded: Boolean,
    val workoutDataSynced: Boolean,
    val workoutState: String,
    val workoutTiming: String,
    val overallSyncStatus: String
)
