package com.example.honoursproject.dataservices

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.honoursproject.R
import com.example.honoursproject.presentation.workoutsimulation.WorkoutActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

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
data class WorkoutSchedule(
    val upcomingWorkouts: List<WorkoutDetails> = emptyList()
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
    private val _workoutSchedule = MutableStateFlow(WorkoutSchedule())
    val workoutSchedule = _workoutSchedule.asStateFlow()

    // NEW: A set to track workouts for which an activity has been launched
    private val launchedWorkouts = ConcurrentHashMap.newKeySet<LocalDateTime>()

    fun startMonitoring(context: Context, workoutCsvFileName: String) {
        if (monitoringJob?.isActive == true) return
        Log.i("WorkoutSimManager", "Starting workout monitoring.")

        monitoringJob = scope.launch {
            if (EvaluationState.isEvaluationMode.value) {
                Log.i("WorkoutSimManager", "EVALUATION MODE: Triggering workout sequence in 5 minutes.")

                // 1. Find the first workout for the evaluation date
                val evaluationWorkouts = findWorkoutsForDate(context, workoutCsvFileName, EvaluationState.EVALUATION_DATE)
                _workoutSchedule.value = WorkoutSchedule(evaluationWorkouts)
                val nextWorkout = evaluationWorkouts.firstOrNull()

                if (nextWorkout != null) {
                    // 2. Wait 5 minutes from the start of evaluation mode
                    delay(Duration.ofMinutes(5))

                    // 3. Trigger notification and launch activity
                    sendWorkoutNotification(context, nextWorkout)
                    if (!launchedWorkouts.contains(nextWorkout.startTime)) {
                        launchWorkoutActivity(context, nextWorkout)
                        _workoutState.value = WorkoutState.InProgress(nextWorkout.workoutType)
                    }

                    // 4. Schedule workout end based on its duration
                    val durationMillis = (nextWorkout.duration * 60 * 1000).toLong()
                    delay(durationMillis)

                    val healthData = SimulationManager.healthDataStream.value
                    val summary = WorkoutSummary(nextWorkout, healthData.cyclePhase, healthData.cycleDay, healthData.cycleDay, emptyMap())
                    _workoutState.value = WorkoutState.Finished(summary)
                    Log.i("WorkoutSimManager", "Evaluation workout finished.")
                } else {
                    Log.w("WorkoutSimManager", "No workouts found for evaluation date: ${EvaluationState.EVALUATION_DATE}")
                }
                // End the monitoring job after the evaluation sequence
                return@launch
            }
            while (isActive) {
                // Find all workouts for today that haven't ended yet
                val upcomingWorkouts = findUpcomingWorkoutsForToday(context, workoutCsvFileName)
                _workoutSchedule.value = WorkoutSchedule(upcomingWorkouts)

                // The rest of the logic should focus on the NEXT workout in the list
                val nextWorkout = upcomingWorkouts.minByOrNull { it.startTime }

                if (nextWorkout == null) {
                    Log.i("WorkoutSimManager", "No more workouts scheduled for today. Checking again in 5 minutes.")
                    delay(java.time.Duration.ofMinutes(5))
                    continue // Restart the loop
                }

                Log.i("WorkoutSimManager", "Next workout found: ${nextWorkout.workoutType} at ${nextWorkout.startTime}")

                val now = LocalDateTime.now()
                val notificationTime = nextWorkout.startTime.minusMinutes(5)
                val launchTime = nextWorkout.startTime.minusSeconds(10)

                // Schedule Notification
                if (now.isBefore(notificationTime)) {
                    val delayMillis = ChronoUnit.MILLIS.between(now, notificationTime)
                    delay(delayMillis)
                    sendWorkoutNotification(context, nextWorkout)
                }

                // Schedule Automatic Activity Launch
                if (now.isBefore(launchTime)) {
                    val delayMillis = ChronoUnit.MILLIS.between(LocalDateTime.now(), launchTime)
                    delay(delayMillis)
                    // CRITICAL: Only launch if the user hasn't already started it
                    if (!launchedWorkouts.contains(nextWorkout.startTime)) {
                        launchWorkoutActivity(context, nextWorkout)
                        _workoutState.value = WorkoutState.InProgress(nextWorkout.workoutType)
                    }
                }

                // Schedule Workout End
                if (now.isBefore(nextWorkout.endTime)) {
                    val delayMillis = ChronoUnit.MILLIS.between(LocalDateTime.now(), nextWorkout.endTime)
                    delay(delayMillis)

                    val healthData = SimulationManager.healthDataStream.value
                    val summary = WorkoutSummary(
                        nextWorkout, healthData.cyclePhase, healthData.cycleDay,
                        dayOfCycle = healthData.cycleDay, // Assuming this is correct
                        heartZones = emptyMap() // Summary logic might need adjustment
                    )
                    _workoutState.value = WorkoutState.Finished(summary)
                    Log.i("WorkoutSimManager", "Workout finished. Summary generated.")
                } else {
                    _workoutState.value = WorkoutState.Idle
                }

                // Once a workout is finished, remove it from the launched set
                launchedWorkouts.remove(nextWorkout.startTime)
                // Short delay before checking for the next workout
                delay(1000)
            }
        }
    }

    // NEW: Public method for the Activity to call to prevent auto-launch
    fun userInitiatedWorkout(startTime: LocalDateTime) {
        Log.i("WorkoutSimManager", "User initiated workout for $startTime. Preventing auto-launch.")
        launchedWorkouts.add(startTime)
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        launchedWorkouts.clear()
        _workoutState.value = WorkoutState.Idle
        Log.i("WorkoutSimManager", "Workout monitoring stopped.")
    }

    private suspend fun findWorkoutsForDate(context: Context, fileName: String, date: LocalDate): List<WorkoutDetails> {
        return withContext(Dispatchers.IO) {
            try {
                val lines = context.assets.open(fileName).bufferedReader().readLines()
                if (lines.size <= 1) return@withContext emptyList()

                val header =
                    lines.first().split(',').mapIndexed { index, name -> name.trim() to index }
                        .toMap()
                val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                lines.drop(1).mapNotNull { line ->
                    try {
                        val values = line.split(',')
                        val startTime =
                            LocalDateTime.parse(values[header.getValue("startDate")], formatter)
                        if (startTime.toLocalDate() == date) {
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
                                maxHeartRate = values[header.getValue("AverageHeartRate")].toDouble() + 20, // Placeholder
                                totalEnergyBurned = values[header.getValue("activeEnergyBurned")].toDouble() + values[header.getValue(
                                    "basalEnergyBurned"
                                )].toDouble() // Placeholder
                            )
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e("WorkoutSimManager", "Error reading workout CSV", e)
                emptyList()
            }
        }
    }
    private suspend fun findUpcomingWorkoutsForToday(context: Context, fileName: String): List<WorkoutDetails> {
        val today = LocalDateTime.now()
        val allWorkoutsForToday = findWorkoutsForDate(context, fileName, today.toLocalDate())
        // Also filter for workouts that haven't ended yet
        return allWorkoutsForToday.filter { it.endTime.isAfter(today) }
    }

    private fun sendWorkoutNotification(context: Context, workout: WorkoutDetails) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Workout Alerts",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

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

    private fun launchWorkoutActivity(context: Context, workout: WorkoutDetails) {
        // Also add this workout to the launched set
        launchedWorkouts.add(workout.startTime)

        val intent = Intent(context, WorkoutActivity::class.java).apply {
            putExtra("workout_type", workout.workoutType)
            // MODIFIED: Pass start time as a robust ISO string
            putExtra("workout_start_time", workout.startTime.toString())
            putExtra("workout_duration", workout.duration.toInt())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.i("WorkoutSimManager", "Launching WorkoutActivity automatically for workout at ${workout.startTime}")
    }
}