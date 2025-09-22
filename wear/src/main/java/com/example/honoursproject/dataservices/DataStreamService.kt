package com.example.honoursproject.dataservices

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service

import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.honoursproject.R
import com.example.honoursproject.tile.WatchfaceTileService

class DataStreamService : Service() {
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "DataStreamChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Health Data Simulation")
            .setContentText("Your health data stream is running.")
            .setSmallIcon(R.drawable.heart_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            // Start simulation with proper error handling
            SimulationManager.startStreaming(this, "P102-Health_Data_Processed.csv")
            Log.i("DataStreamService", "Health data simulation started successfully")

            // Slight delay before starting workout monitoring
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                WorkoutSimulationManager.startMonitoring(this, "P102-Workout_Data.csv")
                Log.i("DataStreamService", "Workout monitoring started successfully")
            }, 1000)

            // Request tile update
            WatchfaceTileService.requestTileUpdate(this)

        } catch (e: Exception) {
            Log.e("DataStreamService", "Error starting simulation services", e)
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            SimulationManager.stopStreaming()
            WorkoutSimulationManager.stopMonitoring()
            Log.i("DataStreamService", "All simulation services stopped")
        } catch (e: Exception) {
            Log.e("DataStreamService", "Error stopping services", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val name = "Health Data Simulation"
        val descriptionText = "Notifies that the health data simulation is active."
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}