package com.example.honoursproject.dataservices

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.honoursproject.R
import com.example.honoursproject.presentation.workoutsimulation.WorkoutActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.time.delay
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

    // A set to track workouts for which an activity has been launched
    private val launchedWorkouts = ConcurrentHashMap.newKeySet<LocalDateTime>()

    // UPDATED: Track evaluation mode start time
    private var evaluationStartTime: LocalDateTime? = null

    fun startMonitoring(context: Context, workoutCsvFileName: String) {
        if (monitoringJob?.isActive == true) return
        Log.i("WorkoutSimManager", "Starting workout monitoring.")

        monitoringJob = scope.launch {
            if (EvaluationState.isEvaluationMode.value) {
                // UPDATED: Record evaluation start time and implement new timing logic
                evaluationStartTime = LocalDateTime.now()
                Log.i("WorkoutSimManager", "EVALUATION MODE: Started at $evaluationStartTime")

                // Find workouts for the evaluation date (2023-08-19)
                val evaluationWorkouts = findWorkoutsForDate(context, workoutCsvFileName, EvaluationState.EVALUATION_DATE)
                _workoutSchedule.value = WorkoutSchedule(evaluationWorkouts)

                if (evaluationWorkouts.isEmpty()) {
                    Log.w("WorkoutSimManager", "No workouts found for evaluation date: ${EvaluationState.EVALUATION_DATE}")
                    return@launch
                }

                Log.i("WorkoutSimManager", "Found ${evaluationWorkouts.size} workouts for evaluation date")

                // Process each workout with evaluation timing
                evaluationWorkouts.forEach { workout ->
                    processEvaluationWorkout(context, workout)
                }

                return@launch
            }

            // Normal mode logic (unchanged)
            while (isActive) {
                val upcomingWorkouts = findUpcomingWorkoutsForToday(context, workoutCsvFileName)
                _workoutSchedule.value = WorkoutSchedule(upcomingWorkouts)

                val nextWorkout = upcomingWorkouts.minByOrNull { it.startTime }

                if (nextWorkout == null) {
                    Log.i("WorkoutSimManager", "No more workouts scheduled for today. Checking again in 5 minutes.")
                    delay(Duration.ofMinutes(5))
                    continue
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
                        dayOfCycle = healthData.cycleDay,
                        heartZones = emptyMap()
                    )
                    _workoutState.value = WorkoutState.Finished(summary)
                    Log.i("WorkoutSimManager", "Workout finished. Summary generated.")
                } else {
                    _workoutState.value = WorkoutState.Idle
                }

                launchedWorkouts.remove(nextWorkout.startTime)
                delay(1000)
            }
        }
    }

    // NEW: Process workout in evaluation mode with specific timing
    private suspend fun processEvaluationWorkout(context: Context, workout: WorkoutDetails) {
        val evaluationStart = evaluationStartTime ?: return

        Log.i("WorkoutSimManager", "Processing evaluation workout: ${workout.workoutType} originally scheduled for ${workout.startTime}")

        try {
            // UPDATED: Wait 5 minutes from evaluation start, then send notification
            val notificationTime = evaluationStart.plusMinutes(5)
            val workoutStartTime = evaluationStart.plusMinutes(8)

            Log.i("WorkoutSimManager", "Notification scheduled for: $notificationTime")
            Log.i("WorkoutSimManager", "Workout start scheduled for: $workoutStartTime")

            // Wait for notification time
            val notificationDelay = ChronoUnit.MILLIS.between(LocalDateTime.now(), notificationTime)
            if (notificationDelay > 0) {
                Log.i("WorkoutSimManager", "Waiting ${notificationDelay}ms for notification time")
                delay(notificationDelay)
            }

            // Send notification
            sendWorkoutNotification(context, workout)
            Log.i("WorkoutSimManager", "Notification sent at ${LocalDateTime.now()}")

            // Wait for workout start time
            val workoutStartDelay = ChronoUnit.MILLIS.between(LocalDateTime.now(), workoutStartTime)
            if (workoutStartDelay > 0) {
                Log.i("WorkoutSimManager", "Waiting ${workoutStartDelay}ms for workout start")
                delay(workoutStartDelay)
            }

            // Launch workout activity
            if (!launchedWorkouts.contains(workout.startTime)) {
                // UPDATED: Create workout with evaluation timing
                val evaluationWorkout = workout.copy(
                    startTime = workoutStartTime,
                    endTime = workoutStartTime.plusMinutes(workout.duration.toLong())
                )

                launchWorkoutActivity(context, evaluationWorkout)
                _workoutState.value = WorkoutState.InProgress(workout.workoutType)
                Log.i("WorkoutSimManager", "Evaluation workout started at ${LocalDateTime.now()}")

                // Schedule workout end
                val workoutEndTime = workoutStartTime.plusMinutes(workout.duration.toLong())
                val endDelay = ChronoUnit.MILLIS.between(LocalDateTime.now(), workoutEndTime)

                if (endDelay > 0) {
                    Log.i("WorkoutSimManager", "Workout will end in ${endDelay}ms at $workoutEndTime")
                    delay(endDelay)
                }

                // Finish workout
                val healthData = SimulationManager.healthDataStream.value
                val summary = WorkoutSummary(
                    evaluationWorkout,
                    healthData.cyclePhase,
                    healthData.cycleDay,
                    healthData.cycleDay,
                    emptyMap()
                )
                _workoutState.value = WorkoutState.Finished(summary)
                Log.i("WorkoutSimManager", "Evaluation workout finished at ${LocalDateTime.now()}")
            }

        } catch (e: Exception) {
            Log.e("WorkoutSimManager", "Error processing evaluation workout", e)
        }
    }

    // Public method for the Activity to call to prevent auto-launch
    fun userInitiatedWorkout(startTime: LocalDateTime) {
        Log.i("WorkoutSimManager", "User initiated workout for $startTime. Preventing auto-launch.")
        launchedWorkouts.add(startTime)
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        launchedWorkouts.clear()
        evaluationStartTime = null
        _workoutState.value = WorkoutState.Idle
        Log.i("WorkoutSimManager", "Workout monitoring stopped.")
    }

    // UPDATED: Enhanced workout finding for evaluation mode
    private suspend fun findWorkoutsForDate(context: Context, fileName: String, date: LocalDate): List<WorkoutDetails> {
        return withContext(Dispatchers.IO) {
            try {
                val lines = context.assets.open(fileName).bufferedReader().readLines()
                if (lines.size <= 1) return@withContext emptyList()

                val header = lines.first().split(',').mapIndexed { index, name -> name.trim() to index }.toMap()
                val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

                val workouts = lines.drop(1).mapNotNull { line ->
                    try {
                        val values = line.split(',')
                        val startTime = LocalDateTime.parse(values[header.getValue("startDate")], formatter)

                        // UPDATED: Check for exact date match
                        if (startTime.toLocalDate().isEqual(date)) {
                            Log.i("WorkoutSimManager", "Found workout for $date: ${values[header.getValue("workoutActivityType")]} at $startTime")

                            WorkoutDetails(
                                workoutType = values[header.getValue("workoutActivityType")],
                                startTime = startTime,
                                endTime = LocalDateTime.parse(values[header.getValue("endDate")], formatter),
                                duration = values[header.getValue("duration")].toDouble(),
                                distance = values[header.getValue("distance")].toDouble(),
                                avgHeartRate = values[header.getValue("AverageHeartRate")].toDouble(),
                                activeEnergy = values[header.getValue("activeEnergyBurned")].toDouble(),
                                basalEnergy = values[header.getValue("basalEnergyBurned")].toDouble(),
                                maxHeartRate = values[header.getValue("AverageHeartRate")].toDouble() + 20,
                                totalEnergyBurned = values[header.getValue("activeEnergyBurned")].toDouble() + values[header.getValue("basalEnergyBurned")].toDouble()
                            )
                        } else null
                    } catch (e: Exception) {
                        Log.e("WorkoutSimManager", "Error parsing workout data: ${e.message}")
                        null
                    }
                }

                Log.i("WorkoutSimManager", "Found ${workouts.size} workouts for date $date")
                workouts

            } catch (e: Exception) {
                Log.e("WorkoutSimManager", "Error reading workout CSV", e)
                emptyList()
            }
        }
    }

    private suspend fun findUpcomingWorkoutsForToday(context: Context, fileName: String): List<WorkoutDetails> {
        val today = LocalDateTime.now()
        val allWorkoutsForToday = findWorkoutsForDate(context, fileName, today.toLocalDate())
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
        val notificationText = if (EvaluationState.isEvaluationMode.value) {
            "Evaluation workout: ${workout.workoutType} starting soon!"
        } else {
            "${workout.workoutType} workout scheduled for ${workout.startTime.format(timeFormatter)}."
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.run_icon)
            .setContentTitle("Workout Approaching")
            .setContentText(notificationText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)
        Log.i("WorkoutSimManager", "Notification sent: $notificationText")
    }

    private fun launchWorkoutActivity(context: Context, workout: WorkoutDetails) {
        launchedWorkouts.add(workout.startTime)

        val intent = Intent(context, WorkoutActivity::class.java).apply {
            putExtra("workout_type", workout.workoutType)
            putExtra("workout_start_time", workout.startTime.toString())
            putExtra("workout_duration", workout.duration.toInt())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.i("WorkoutSimManager", "Launching WorkoutActivity for workout at ${workout.startTime}")
    }
}