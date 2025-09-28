package com.example.honoursproject.presentation.workoutsimulation
import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.compose.runtime.Composable

import androidx.core.app.NotificationCompat

import com.example.honoursproject.R

// Notification constants
const val WORKOUT_NOTIFICATION_CHANNEL_ID = "workout_channel"
const val WORKOUT_NOTIFICATION_ID = 1001

// --- Workout Service for Background Operation ---
class WorkoutService : Service() {
    private val binder = WorkoutBinder()
    private var workoutViewModel: WorkoutViewModel? = null

    inner class WorkoutBinder : Binder() {
        fun getService(): WorkoutService = this@WorkoutService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun setWorkoutViewModel(viewModel: WorkoutViewModel) {
        this.workoutViewModel = viewModel
        startForegroundService()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            WORKOUT_NOTIFICATION_CHANNEL_ID,
            "Workout Notifications",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notifications for active workouts"
            setShowBadge(false)
            setSound(null, null)
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundService() {
        val workoutIntent = Intent(this, WorkoutActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, workoutIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, WORKOUT_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Workout in Progress")
            .setContentText("Tap to return to your workout")
            .setSmallIcon(R.drawable.heart_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()

        startForeground(WORKOUT_NOTIFICATION_ID, notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }


    fun UpdateNotification(elapsedTime: Long, heartRate: Int, workoutType: String) {
        val timeString = formatElapsedTime(elapsedTime)
        val workoutIntent = Intent(this, WorkoutActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, workoutIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, WORKOUT_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("$workoutType • $timeString")
            .setContentText("❤️ $heartRate BPM • Tap to return")
            .setSmallIcon(R.drawable.heart_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(WORKOUT_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
    }
}