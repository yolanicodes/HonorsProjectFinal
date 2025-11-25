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
import kotlinx.coroutines.*

/**
 * Enhanced DataStreamService with proper lifecycle management
 * and coordinated simulation management
 */
class DataStreamService : Service() {

    companion object {
        private const val TAG = "DataStreamService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "DataStreamChannel"
        private const val STARTUP_DELAY_MS = 1000L

        // Service actions
        const val ACTION_START_STREAMING = "com.example.honoursproject.START_STREAMING"
        const val ACTION_STOP_STREAMING = "com.example.honoursproject.STOP_STREAMING"
        const val ACTION_PAUSE_STREAMING = "com.example.honoursproject.PAUSE_STREAMING"
        const val ACTION_RESUME_STREAMING = "com.example.honoursproject.RESUME_STREAMING"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupJob: Job? = null
    private var isServiceRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DataStreamService onCreate")

        createNotificationChannel()

        // Start foreground immediately with proper service type
        val notification = createNotification("Starting simulation...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}, isServiceRunning=$isServiceRunning")

        when (intent?.action) {
            ACTION_START_STREAMING -> {
                if (!isServiceRunning) {
                    startStreaming()
                } else {
                    Log.w(TAG, "Service already running, ignoring start request")
                }
            }
            ACTION_STOP_STREAMING -> {
                stopStreaming()
            }
            ACTION_PAUSE_STREAMING -> {
                SimulationManager.pauseSimulation()
                WorkoutSimulationManager.pauseSimulation()
                updateNotification("Simulation paused")
            }
            ACTION_RESUME_STREAMING -> {
                SimulationManager.resumeSimulation()
                WorkoutSimulationManager.resumeSimulation()
                updateNotification("Simulation running")
            }
            else -> {
                // Default: start streaming if not already running
                if (!isServiceRunning) {
                    startStreaming()
                }
            }
        }
        return START_STICKY
    }

    private fun startStreaming() {
        if (startupJob?.isActive == true) {
            Log.w(TAG, "Startup already in progress")
            return
        }

        isServiceRunning = true // SET FLAG

        startupJob = serviceScope.launch {
            try {
                Log.i(TAG, "Starting simulation services with delay...")

                // Small delay to ensure context is ready
                delay(STARTUP_DELAY_MS)

                // Initialize repository
                val repository = HealthDataRepositoryImpl(
                    HealthDatabase.getInstance(this@DataStreamService).healthRecordDao(),
                    ArchiveDatabase.getInstance(this@DataStreamService).archiveRecordDao()
                )

                // Initialize SimulationManager with repository
                SimulationManager.initialize(repository)

                updateNotification("Loading historical data...")

                // Start health data simulation
                SimulationManager.startStreaming(
                    this@DataStreamService,
                    "P102-Health_Data_Processed.csv"
                )

                updateNotification("Simulation running")

                Log.i(TAG, "✓ All simulation services started successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Critical error starting simulation services", e)
                e.printStackTrace()
                updateNotification("Simulation error")
                isServiceRunning = false // RESET FLAG
                stopSelfSafely()
            }
        }
    }


    private fun stopStreaming() {
        Log.i(TAG, "Stopping simulation services...")

        isServiceRunning = false // RESET FLAG

        SimulationManager.stopStreaming()
        WorkoutSimulationManager.stopSimulation()

        updateNotification("Simulation stopped")

        stopSelfSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "DataStreamService onDestroy")

        isServiceRunning = false // RESET FLAG

        startupJob?.cancel()
        serviceScope.cancel()

        // Clean up simulation managers
        SimulationManager.stopStreaming()
        WorkoutSimulationManager.stopSimulation()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed - service may be killed soon")
        // Service will restart due to START_STICKY
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Data Stream Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Health data simulation service"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Health Monitor")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service", e)
        }
    }
}
