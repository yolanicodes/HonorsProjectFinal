package com.example.honoursproject.dataservices

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.honoursproject.R
import com.example.honoursproject.tile.WatchfaceTileService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enhanced DataStreamService with proper lifecycle management, pause/resume handling,
 * and coordinated simulation management
 */
class DataStreamService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "DataStreamChannel"
        private const val STARTUP_DELAY_MS = 1000L

        // Service actions
        const val ACTION_START = "com.example.honoursproject.START_SIMULATION"
        const val ACTION_PAUSE = "com.example.honoursproject.PAUSE_SIMULATION"
        const val ACTION_RESUME = "com.example.honoursproject.RESUME_SIMULATION"
        const val ACTION_STOP = "com.example.honoursproject.STOP_SIMULATION"
        const val ACTION_RESET = "com.example.honoursproject.RESET_SIMULATION"
    }
    private val taskQueue = Channel<suspend () -> Unit>(capacity = Channel.UNLIMITED)
    private var workerJob: Job? = null

    private val isServiceRunning = AtomicBoolean(false)
    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(supervisorJob + Dispatchers.IO)
    private var startupJob: Job? = null
    private var monitoringJob: Job? = null

    // Service state tracking
    private var isSimulationPaused = false
    private var lastNotificationUpdate = 0L

    private fun startTaskWorker() {
        workerJob = serviceScope.launch {
            for (task in taskQueue) {
                try {
                    task()
                } catch (e: Exception) {
                    Log.e("DataStreamService", "Task execution failed", e)
                }
            }
        }
    }

    private fun enqueueTask(task: suspend () -> Unit) {
        taskQueue.trySend(task)
    }
    override fun onCreate() {
        super.onCreate()
        Log.i("DataStreamService", "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("DataStreamService", "Service start command received with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_PAUSE -> {
                pauseSimulation()
                return START_STICKY
            }
            ACTION_RESUME -> {
                resumeSimulation()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopSimulation()
                return START_NOT_STICKY
            }
            ACTION_RESET -> {
                resetSimulation()
                return START_STICKY
            }
            ACTION_START, null -> {
                // Default start behavior
            }
        }

        if (isServiceRunning.get()) {
            Log.i("DataStreamService", "Service already running")
            return START_STICKY
        }

        // Create and show persistent notification
        val notification = createNotification("Starting simulation...")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // Start services with proper sequencing and error handling
            startupJob = serviceScope.launch {
                startServicesSequentially()
            }

            // Start monitoring job for dynamic notifications
            startMonitoringJob()

            isServiceRunning.set(true)
            Log.i("DataStreamService", "Foreground service started successfully")

        } catch (e: Exception) {
            Log.e("DataStreamService", "Error starting foreground service", e)
            stopSelfSafely()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i("DataStreamService", "Service destroy called")

        isServiceRunning.set(false)

        // Cancel all jobs
        startupJob?.cancel()
        monitoringJob?.cancel()
        workerJob?.cancel() // ADD THIS

        // Close task queue
        taskQueue.close() // ADD THIS

        // Stop all simulation services
        stopAllServices()

        // Cancel service scope
        serviceScope.cancel()

        Log.i("DataStreamService", "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Start simulation monitoring job for dynamic notifications and state tracking
     */
    private fun startMonitoringJob() {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            // Monitor simulation state changes
            SimulationManager.simulationState.collectLatest { state ->
                updateNotification(state)
            }
        }
    }

    /**
     * Update notification based on simulation state
     */
    private fun updateNotification(state: SimulationState) {
        // Throttle notification updates to avoid spam
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotificationUpdate < 5000) return // Max one update per 5 seconds

        val notificationText = when {
            state.isPaused -> "Simulation paused at ${state.progressPercentage.toInt()}%"
            !state.isRunning -> "Simulation stopped"
            state.progressPercentage > 0 -> "Running - ${state.progressPercentage.toInt()}% complete"
            else -> "Starting simulation..."
        }

        try {
            val notification = createNotification(notificationText)
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
            lastNotificationUpdate = currentTime
        } catch (e: Exception) {
            Log.e("DataStreamService", "Error updating notification", e)
        }
    }

    /**
     * Start simulation services in proper sequence with error handling
     */
    private suspend fun startServicesSequentially() {
        startTaskWorker()

        try {
            Log.i("DataStreamService", "Starting simulation services")

            // Start health data simulation first
            enqueueTask {
                try {
                    SimulationManager.startStreaming(this@DataStreamService, "P102-Health_Data_Processed.csv")
                    Log.i("DataStreamService", "Health data simulation started successfully")
                } catch (e: Exception) {
                    Log.e("DataStreamService", "Failed to start health data simulation", e)
                    throw e
                }
            }

            // Wait a moment for health simulation to stabilize
            delay(STARTUP_DELAY_MS)

            // Check if service is still running
            if (!isServiceRunning.get()) {
                Log.i("DataStreamService", "Service stopped during startup, aborting")
                return
            }

            // Start workout monitoring
            enqueueTask {
                try {
                    WorkoutSimulationManager.startMonitoring(this@DataStreamService, "P102-Workout_Data.csv")
                    Log.i("DataStreamService", "Workout monitoring started successfully")
                } catch (e: Exception) {
                    Log.e("DataStreamService", "Failed to start workout monitoring", e)
                }
            }


            // Request tile update
            try {
                WatchfaceTileService.requestTileUpdate(this@DataStreamService)
                Log.i("DataStreamService", "Tile update requested")
            } catch (e: Exception) {
                Log.e("DataStreamService", "Failed to request tile update", e)
                // Non-critical error, continue
            }

            Log.i("DataStreamService", "All simulation services started successfully")

        } catch (e: Exception) {
            Log.e("DataStreamService", "Critical error starting simulation services", e)
            stopSelfSafely()
        }
    }

    /**
     * Pause simulation while keeping service running
     */
    private fun pauseSimulation() {
        if (!isSimulationPaused) {
            Log.i("DataStreamService", "Pausing simulation")
            isSimulationPaused = true

            serviceScope.launch {
                try {
                    SimulationManager.pauseSimulation(this@DataStreamService)
                    updateNotification(SimulationManager.simulationState.value)
                } catch (e: Exception) {
                    Log.e("DataStreamService", "Error pausing simulation", e)
                }
            }
        }
    }

    /**
     * Resume simulation from paused state
     */
    private fun resumeSimulation() {
        if (isSimulationPaused) {
            Log.i("DataStreamService", "Resuming simulation")
            isSimulationPaused = false

            serviceScope.launch {
                try {
                    SimulationManager.resumeSimulation(this@DataStreamService)
                    updateNotification(SimulationManager.simulationState.value)
                } catch (e: Exception) {
                    Log.e("DataStreamService", "Error resuming simulation", e)
                }
            }
        }
    }

    /**
     * Stop simulation completely
     */
    private fun stopSimulation() {
        Log.i("DataStreamService", "Stopping simulation")
        stopAllServices()
        stopSelfSafely()
    }

    /**
     * Reset simulation to initial state
     */
    private fun resetSimulation() {
        Log.i("DataStreamService", "Resetting simulation")
        serviceScope.launch {
            try {
                SimulationManager.resetSimulation(this@DataStreamService)
                WorkoutSimulationManager.stopMonitoring()

                // Restart services after reset
                delay(1000)
                startServicesSequentially()
            } catch (e: Exception) {
                Log.e("DataStreamService", "Error resetting simulation", e)
            }
        }
    }

    /**
     * Stop all simulation services safely
     */
    private fun stopAllServices() {
        serviceScope.launch {
            try {
                Log.i("DataStreamService", "Stopping all simulation services")

                // Stop workout monitoring first (faster to stop)
                try {
                    WorkoutSimulationManager.stopMonitoring()
                    Log.i("DataStreamService", "Workout monitoring stopped")
                } catch (e: Exception) {
                    Log.e("DataStreamService", "Error stopping workout monitoring", e)
                }

                // Stop health data simulation
                try {
                    SimulationManager.stopStreaming()
                    Log.i("DataStreamService", "Health data simulation stopped")
                } catch (e: Exception) {
                    Log.e("DataStreamService", "Error stopping health simulation", e)
                }

                Log.i("DataStreamService", "All simulation services stopped")

            } catch (e: Exception) {
                Log.e("DataStreamService", "Error during service shutdown", e)
            }
        }
    }

    /**
     * Stop service safely with proper cleanup
     */
    private fun stopSelfSafely() {
        try {
            isServiceRunning.set(false)
            isSimulationPaused = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.e("DataStreamService", "Error stopping service safely", e)
        }
    }

    /**
     * Create persistent notification for foreground service
     */
    private fun createNotification(contentText: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Health Data Simulation")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.heart_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .addAction(
                R.drawable.pause_icon
                ,
                if (isSimulationPaused) "Resume" else "Pause",
                createActionPendingIntent(if (isSimulationPaused) ACTION_RESUME else ACTION_PAUSE)
            )
            .addAction(
                R.drawable.stop_icon,
                "Stop",
                createActionPendingIntent(ACTION_STOP)
            )
            .build()
    }

    /**
     * Create pending intent for notification actions
     */
    private fun createActionPendingIntent(action: String): android.app.PendingIntent {
        val intent = Intent(this, DataStreamService::class.java).apply {
            this.action = action
        }
        return android.app.PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        val name = "Health Data Simulation"
        val descriptionText = "Notifies that the health data simulation is active."
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        val notificationManager: NotificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        Log.i("DataStreamService", "Notification channel created")
    }

    /**
     * Handle service restart scenarios
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("DataStreamService", "Task removed - keeping service running in background")
        // Don't stop the service when task is removed - let it continue running
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Handle low memory scenarios
     */
    override fun onLowMemory() {
        Log.w("DataStreamService", "Low memory warning received - pausing simulation")
        pauseSimulation()
        super.onLowMemory()
    }

    /**
     * Handle memory trimming
     */
    override fun onTrimMemory(level: Int) {
        Log.w("DataStreamService", "Memory trim requested, level: $level")
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE,
            TRIM_MEMORY_MODERATE -> {
                Log.w("DataStreamService", "Critical memory pressure - pausing simulation")
                pauseSimulation()
            }
        }
        super.onTrimMemory(level)
    }
}