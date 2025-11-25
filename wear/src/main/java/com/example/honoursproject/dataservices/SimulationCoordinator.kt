package com.example.honoursproject.dataservices

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Utility class to coordinate health and workout simulations
 * Provides high-level control and synchronization between services
 */
object SimulationCoordinator {

    /**
     * Combined state flow that provides overall simulation status
     */
    val combinedSimulationState = combine(
        SimulationManager.simulationState,
        WorkoutSimulationManager.workoutState,
        WorkoutSimulationManager.workoutTimingState
    ) { healthState, workoutState, workoutTimingState ->
        CombinedSimulationState(
            healthSimulation = healthState,
            workoutState = workoutState,
            workoutTimingState = workoutTimingState,
            isHealthRunning = healthState.isRunning,
            isHealthPaused = healthState.isPaused,
            isSynchronized = isSystemSynchronized(healthState, workoutTimingState),
            overallProgress = healthState.progressPercentage
        )
    }.distinctUntilChanged()

    /**
     * Start all simulation services through the DataStreamService
     */
    fun startSimulation(context: Context) {
        Log.i("SimulationCoordinator", "Starting coordinated simulation")
        val intent = Intent(context, DataStreamService::class.java).apply {
            action = DataStreamService.ACTION_START
        }
        context.startForegroundService(intent)
    }

    /**
     * Pause all simulation services
     */
    fun pauseSimulation(context: Context) {
        Log.i("SimulationCoordinator", "Pausing coordinated simulation")
        val intent = Intent(context, DataStreamService::class.java).apply {
            action = DataStreamService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    /**
     * Resume all simulation services
     */
    fun resumeSimulation(context: Context) {
        Log.i("SimulationCoordinator", "Resuming coordinated simulation")
        val intent = Intent(context, DataStreamService::class.java).apply {
            action = DataStreamService.ACTION_RESUME
        }
        context.startService(intent)
    }

    /**
     * Stop all simulation services
     */
    fun stopSimulation(context: Context) {
        Log.i("SimulationCoordinator", "Stopping coordinated simulation")
        val intent = Intent(context, DataStreamService::class.java).apply {
            action = DataStreamService.ACTION_STOP
        }
        context.startService(intent)
    }

    /**
     * Reset all simulation services
     */
    fun resetSimulation(context: Context) {
        Log.i("SimulationCoordinator", "Resetting coordinated simulation")
        val intent = Intent(context, DataStreamService::class.java).apply {
            action = DataStreamService.ACTION_RESET
        }
        context.startService(intent)
    }

    /**
     * Check if the overall system is synchronized
     */
    private fun isSystemSynchronized(
        healthState: SimulationState,
        workoutTimingState: WorkoutTimingState
    ): Boolean {
        return when (workoutTimingState) {
            is WorkoutTimingState.NotificationScheduled -> workoutTimingState.healthDataSynced
            is WorkoutTimingState.UserStartedEarly -> workoutTimingState.healthDataSynced
            is WorkoutTimingState.AutoStartCountdown -> workoutTimingState.healthDataSynced
            is WorkoutTimingState.InProgress -> workoutTimingState.healthDataSynced
            is WorkoutTimingState.Finished -> workoutTimingState.healthDataSynced
            is WorkoutTimingState.SynchronizationNeeded -> false
            WorkoutTimingState.Idle -> healthState.isRunning && !healthState.isPaused
        }
    }

    /**
     * Get current synchronization status message
     */
    fun getSynchronizationStatusMessage(): String {
        val healthRunning = SimulationManager.isRunning()
        val healthPaused = SimulationManager.isPaused()
        val workoutSynced = WorkoutSimulationManager.isHealthDataSynchronized()

        return when {
            !healthRunning -> "Health simulation stopped"
            healthPaused -> "Health simulation paused"
            !workoutSynced -> "Workout timing not synchronized"
            else -> "All systems synchronized"
        }
    }

    /**
     * Check if simulation can be paused
     */
    fun canPause(): Boolean {
        return SimulationManager.isRunning() && !SimulationManager.isPaused()
    }

    /**
     * Check if simulation can be resumed
     */
    fun canResume(): Boolean {
        return SimulationManager.isRunning() && SimulationManager.isPaused()
    }

    /**
     * Check if simulation is completely stopped
     */
    fun isStopped(): Boolean {
        return !SimulationManager.isRunning()
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
    val isSynchronized: Boolean,
    val overallProgress: Float
)