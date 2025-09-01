package com.example.honoursproject.dataservices

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.honoursproject.R
import com.example.honoursproject.presentation.WorkoutActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Data class to hold parsed workout details
data class WorkoutDetails(
    val workoutType: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val duration: Double,
    val distance: Double,
    val avgHeartRate: Double,
    val activeEnergy: Double,
    val basalEnergy: Double,
    val maxHeartRate: Double,
    val totalEnergyBurned: Double
)

// Data class for the final summary
data class WorkoutSummary(
    val details: WorkoutDetails,
    val cyclePhase: String?,
    val cycleDay: Int?,
    val dayOfCycle: Int,
    val heartZones: Map<Int, Long>
)

// Represents the state of the workout simulation
sealed class WorkoutState {
    object Idle : WorkoutState()
    data class InProgress(val workoutType: String) : WorkoutState()
    data class Finished(val summary: WorkoutSummary) : WorkoutState()
}

object WorkoutSimulationManager {
    private const val NOTIFICATION_CHANNEL_ID = "workout_channel"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null

    private val _workoutState = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val workoutState = _workoutState.asStateFlow()

    fun startMonitoring(context: Context, workoutCsvFileName: String) {
        if (monitoringJob?.isActive == true) return
        Log.i("WorkoutSimManager", "Starting workout monitoring.")

        monitoringJob = scope.launch {
            val workout = findNextWorkoutForToday(context, workoutCsvFileName)
            if (workout == null) {
                Log.i("WorkoutSimManager", "No workout scheduled for today.")
                return@launch
            }

            Log.i("WorkoutSimManager", "Workout found: ${workout.workoutType} at ${workout.startTime}")

            val now = LocalDateTime.now()
            val notificationTime = workout.startTime.minusMinutes(3)
            val launchTime = workout.startTime.minusSeconds(30)

            // Schedule Notification
            if (now.isBefore(notificationTime)) {
                val delayMillis = ChronoUnit.MILLIS.between(now, notificationTime)
                Log.d("WorkoutSimManager", "Delaying for notification: $delayMillis ms")
                delay(delayMillis)
                sendWorkoutNotification(context, workout)
            }

            // Schedule Activity Launch
            if (now.isBefore(launchTime)) {
                val delayMillis = ChronoUnit.MILLIS.between(LocalDateTime.now(), launchTime)
                Log.d("WorkoutSimManager", "Delaying for activity launch: $delayMillis ms")
                delay(delayMillis)
                launchWorkoutActivity(context, workout.workoutType)
                _workoutState.value = WorkoutState.InProgress(workout.workoutType)
            }

            // Schedule Workout End
            if (now.isBefore(workout.endTime)) {
                val delayMillis = ChronoUnit.MILLIS.between(LocalDateTime.now(), workout.endTime)
                Log.d("WorkoutSimManager", "Delaying for workout end: $delayMillis ms")
                delay(delayMillis)

                // Fetch latest health data for the summary
                val healthData = SimulationManager.healthDataStream.value
                val summary = WorkoutSummary(
                    workout, healthData.cyclePhase, healthData.cycleDay,
                    dayOfCycle = TODO(),
                    heartZones = TODO(),
                )
                _workoutState.value = WorkoutState.Finished(summary)
                Log.i("WorkoutSimManager", "Workout finished. Summary generated.")
            } else {
                Log.w("WorkoutSimManager", "Workout end time has already passed.")
                _workoutState.value = WorkoutState.Idle // Reset if missed
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        _workoutState.value = WorkoutState.Idle
        Log.i("WorkoutSimManager", "Workout monitoring stopped.")
    }

    private suspend fun findNextWorkoutForToday(context: Context, fileName: String): WorkoutDetails? {
        return withContext(Dispatchers.IO) {
            try {
                val lines = context.assets.open(fileName).bufferedReader().readLines()
                if (lines.size <= 1) return@withContext null

                val header = lines.first().split(',').mapIndexed { index, name -> name.trim() to index }.toMap()
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
                val today = LocalDateTime.now()

                lines.drop(1).mapNotNull { line ->
                    try {
                        val values = line.split(',')
                        val startTime = LocalDateTime.parse(values[header.getValue("startDate")], formatter)
                        if (startTime.dayOfMonth == today.dayOfMonth && startTime.month == today.month && startTime.isAfter(today)) {
                            WorkoutDetails(
                                workoutType = values[header.getValue("workoutActivityType")],
                                startTime = startTime,
                                endTime = LocalDateTime.parse(
                                    values[header.getValue("endDate")],
                                    formatter
                                ),
                                duration = values[header.getValue("duration")].toDouble(),
                                distance = values[header.getValue("distance")].toDouble(),
                                avgHeartRate = values[header.getValue("AverageHeartRate")].toDouble(),
                                activeEnergy = values[header.getValue("activeEnergyBurned")].toDouble(),
                                basalEnergy = values[header.getValue("basalEnergyBurned")].toDouble(),
                                maxHeartRate = TODO(),
                                totalEnergyBurned = TODO(),
                            )
                        } else null
                    } catch (e: Exception) {
                        null // Ignore malformed lines
                    }
                }.minByOrNull { it.startTime } // Find the earliest workout for today
            } catch (e: Exception) {
                Log.e("WorkoutSimManager", "Error reading workout CSV", e)
                null
            }
        }
    }

    private fun sendWorkoutNotification(context: Context, workout: WorkoutDetails) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Workout Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.run_icon)
            .setContentTitle("Workout Approaching")
            .setContentText("${workout.workoutType} workout scheduled for ${workout.startTime.format(timeFormatter)}.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)
        Log.i("WorkoutSimManager", "Notification sent.")
    }

    private fun launchWorkoutActivity(context: Context, workoutType: String) {
        val intent = Intent(context, WorkoutActivity::class.java).apply {
            putExtra("workout_type", workoutType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.i("WorkoutSimManager", "Launching WorkoutActivity.")
    }
}