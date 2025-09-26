package com.example.honoursproject.dataservices

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.honoursproject.R
import com.example.honoursproject.presentation.SetupPreferences
import com.example.honoursproject.presentation.setupDataStore
import com.example.honoursproject.presentation.workoutsimulation.WorkoutActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
    val upcomingWorkouts: List<WorkoutDetails> = emptyList(),
    val nextWorkout: WorkoutDetails? = null
)

// Heart rate zone calculation
data class HeartRateZone(val zone: Int, val minBpm: Int, val maxBpm: Int, val name: String)

// Enhanced workout summary with heart zones
data class WorkoutSummary(
    val details: WorkoutDetails,
    val cyclePhase: String?,
    val cycleDay: Int?,
    val dayOfCycle: Int,
    val heartZones: Map<Int, Long>, // Zone -> Duration in seconds
    val workoutHealthData: List<HealthDataRecord> = emptyList(),
    val avgHeartRateActual: Double = 0.0,
    val maxHeartRateActual: Double = 0.0,
    val totalActiveEnergyActual: Double = 0.0
)

// Enhanced workout timing states with synchronization info
sealed class WorkoutTimingState {
    object Idle : WorkoutTimingState()
    data class NotificationScheduled(
        val workoutType: String,
        val timeRemaining: Long,
        val healthDataSynced: Boolean = true
    ) : WorkoutTimingState()
    data class UserStartedEarly(
        val workoutType: String,
        val actualStartCountdown: Long,
        val healthDataSynced: Boolean = true
    ) : WorkoutTimingState()
    data class AutoStartCountdown(
        val workoutType: String,
        val countdown: Int,
        val healthDataSynced: Boolean = true
    ) : WorkoutTimingState()
    data class InProgress(
        val workoutType: String,
        val startTime: LocalDateTime,
        val healthDataSynced: Boolean = true
    ) : WorkoutTimingState()
    data class Finished(
        val summary: WorkoutSummary,
        val healthDataSynced: Boolean = true
    ) : WorkoutTimingState()
    data class SynchronizationNeeded(
        val workoutType: String,
        val reason: String
    ) : WorkoutTimingState()
}

// Represents the state of the workout simulation
sealed class WorkoutState {
    object Idle : WorkoutState()
    data class InProgress(val workoutType: String) : WorkoutState()
    data class Finished(val summary: WorkoutSummary) : WorkoutState()
    data class SyncRequired(val reason: String) : WorkoutState()
}

object WorkoutSimulationManager {
    private const val NOTIFICATION_CHANNEL_ID = "workout_channel"
    private const val EVALUATION_NOTIFICATION_DELAY_MINUTES = 5L
    private const val EVALUATION_AUTO_START_COUNTDOWN_SECONDS = 30L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var countdownJob: Job? = null
    private var synchronizationJob: Job? = null

    private val _workoutState = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val workoutState = _workoutState.asStateFlow()

    private val _workoutTimingState = MutableStateFlow<WorkoutTimingState>(WorkoutTimingState.Idle)
    val workoutTimingState = _workoutTimingState.asStateFlow()

    private val _workoutSchedule = MutableStateFlow(WorkoutSchedule())
    val workoutSchedule = _workoutSchedule.asStateFlow()

    private val launchedWorkouts = ConcurrentHashMap.newKeySet<LocalDateTime>()
    private var evaluationStartTime: LocalDateTime? = null
    private var userStartedEarly = false

    // Enhanced synchronization tracking
    private var lastHealthDataSyncCheck: Long = 0L
    private val syncCheckInterval = 30000L // Check every 30 seconds

    fun startMonitoring(context: Context, workoutCsvFileName: String) {
        if (monitoringJob?.isActive == true) return
        Log.i("WorkoutSimManager", "Starting enhanced workout monitoring with health data coordination.")

        monitoringJob = scope.launch {
            // Start synchronization monitoring
            startSynchronizationMonitoring(context)

            if (EvaluationState.isEvaluationMode.value) {
                processEvaluationMode(context, workoutCsvFileName)
            } else {
                processNormalMode(context, workoutCsvFileName)
            }
        }
    }

    /**
     * Monitor health data simulation synchronization
     */
    private fun startSynchronizationMonitoring(context: Context) {
        synchronizationJob = scope.launch {
            while (scope.isActive) {
                try {
                    // Check if health data simulation is running and synchronized
                    val healthSimulationRunning = SimulationManager.isRunning()
                    val healthSimulationPaused = SimulationManager.isPaused()
                    val currentTime = System.currentTimeMillis()

                    if (!healthSimulationRunning) {
                        Log.w("WorkoutSimManager", "Health data simulation not running - workout timing may be affected")
                        updateTimingStateForSync("Health data simulation stopped")
                    } else if (healthSimulationPaused) {
                        Log.w("WorkoutSimManager", "Health data simulation paused - workout timing paused")
                        updateTimingStateForSync("Health data simulation paused")
                    } else {
                        // Health simulation is running normally
                        clearSynchronizationWarnings()
                    }

                    lastHealthDataSyncCheck = currentTime
                    delay(syncCheckInterval)

                } catch (e: Exception) {
                    Log.e("WorkoutSimManager", "Error in synchronization monitoring", e)
                    delay(syncCheckInterval)
                }
            }
        }
    }

    private fun updateTimingStateForSync(reason: String) {
        val currentState = _workoutTimingState.value
        when (currentState) {
            is WorkoutTimingState.NotificationScheduled -> {
                _workoutTimingState.value = currentState.copy(healthDataSynced = false)
            }
            is WorkoutTimingState.UserStartedEarly -> {
                _workoutTimingState.value = currentState.copy(healthDataSynced = false)
            }
            is WorkoutTimingState.AutoStartCountdown -> {
                _workoutTimingState.value = currentState.copy(healthDataSynced = false)
            }
            is WorkoutTimingState.InProgress -> {
                _workoutTimingState.value = currentState.copy(healthDataSynced = false)
            }
            is WorkoutTimingState.Finished -> {
                _workoutTimingState.value = currentState.copy(healthDataSynced = false)
            }
            else -> {
                _workoutTimingState.value = WorkoutTimingState.SynchronizationNeeded("Unknown", reason)
            }
        }
    }

    private fun clearSynchronizationWarnings() {
        val currentState = _workoutTimingState.value
        when (currentState) {
            is WorkoutTimingState.NotificationScheduled -> {
                if (!currentState.healthDataSynced) {
                    _workoutTimingState.value = currentState.copy(healthDataSynced = true)
                }
            }
            is WorkoutTimingState.UserStartedEarly -> {
                if (!currentState.healthDataSynced) {
                    _workoutTimingState.value = currentState.copy(healthDataSynced = true)
                }
            }
            is WorkoutTimingState.AutoStartCountdown -> {
                if (!currentState.healthDataSynced) {
                    _workoutTimingState.value = currentState.copy(healthDataSynced = true)
                }
            }
            is WorkoutTimingState.InProgress -> {
                if (!currentState.healthDataSynced) {
                    _workoutTimingState.value = currentState.copy(healthDataSynced = true)
                }
            }
            is WorkoutTimingState.Finished -> {
                if (!currentState.healthDataSynced) {
                    _workoutTimingState.value = currentState.copy(healthDataSynced = true)
                }
            }
            is WorkoutTimingState.SynchronizationNeeded -> {
                _workoutTimingState.value = WorkoutTimingState.Idle
            }
            is WorkoutTimingState.Idle -> {}
        }
    }

    private suspend fun processEvaluationMode(context: Context, workoutCsvFileName: String) {
        evaluationStartTime = LocalDateTime.now()
        Log.i("WorkoutSimManager", "EVALUATION MODE: Started at $evaluationStartTime")

        // Wait for health data simulation to be ready
        waitForHealthDataSimulation()

        // Use 2023-08-19 for workout data to match simulation data
        val evaluationWorkoutDate = LocalDate.of(2023, 8, 19)
        val evaluationWorkouts = findWorkoutsForDate(context, workoutCsvFileName, evaluationWorkoutDate)

        _workoutSchedule.value = WorkoutSchedule(
            upcomingWorkouts = evaluationWorkouts,
            nextWorkout = evaluationWorkouts.firstOrNull()
        )

        if (evaluationWorkouts.isEmpty()) {
            Log.w("WorkoutSimManager", "No workouts found for evaluation date: $evaluationWorkoutDate")
            _workoutTimingState.value = WorkoutTimingState.Idle
            return
        }

        Log.i("WorkoutSimManager", "Found ${evaluationWorkouts.size} workouts for evaluation date $evaluationWorkoutDate")

        // Process each workout in sequence for evaluation
        evaluationWorkouts.forEach { workout ->
            processEvaluationWorkout(context, workout)
        }
    }

    private suspend fun waitForHealthDataSimulation() {
        Log.i("WorkoutSimManager", "Waiting for health data simulation to be ready...")
        var attempts = 0
        val maxAttempts = 30 // 30 seconds max wait

        while (attempts < maxAttempts && !SimulationManager.isRunning()) {
            delay(1000)
            attempts++
        }

        if (SimulationManager.isRunning()) {
            Log.i("WorkoutSimManager", "Health data simulation is ready after ${attempts} seconds")
        } else {
            Log.w("WorkoutSimManager", "Health data simulation not ready after ${attempts} seconds - proceeding anyway")
        }
    }

    private suspend fun processNormalMode(context: Context, workoutCsvFileName: String) {
        while (scope.isActive) {
            // Check if health data simulation is still running
            if (!SimulationManager.isRunning()) {
                Log.w("WorkoutSimManager", "Health data simulation stopped - pausing workout monitoring")
                _workoutState.value = WorkoutState.SyncRequired("Health data simulation stopped")
                delay(Duration.ofMinutes(1)) // Check again in 1 minute
                continue
            }

            val upcomingWorkouts = findUpcomingWorkoutsForToday(context, workoutCsvFileName)
            val nextWorkout = upcomingWorkouts.minByOrNull { it.startTime }

            _workoutSchedule.value = WorkoutSchedule(
                upcomingWorkouts = upcomingWorkouts,
                nextWorkout = nextWorkout
            )

            if (nextWorkout == null) {
                Log.i("WorkoutSimManager", "No more workouts scheduled for today. Checking again in 5 minutes.")
                _workoutTimingState.value = WorkoutTimingState.Idle
                delay(Duration.ofMinutes(5))
                continue
            }

            Log.i("WorkoutSimManager", "Next workout found: ${nextWorkout.workoutType} at ${nextWorkout.startTime}")

            val now = LocalDateTime.now()
            val notificationTime = nextWorkout.startTime.minusMinutes(5)
            val autoStartTime = nextWorkout.startTime.minusSeconds(30)

            // Schedule and send notification 5 minutes before workout
            if (now.isBefore(notificationTime)) {
                val delayMillis = ChronoUnit.MILLIS.between(now, notificationTime)
                _workoutTimingState.value = WorkoutTimingState.NotificationScheduled(
                    nextWorkout.workoutType,
                    delayMillis,
                    SimulationManager.isRunning() && !SimulationManager.isPaused()
                )
                delay(delayMillis)
                sendWorkoutNotification(context, nextWorkout)
            }

            // Handle period between notification and auto-start
            if (now.isBefore(autoStartTime)) {
                val remainingTime = ChronoUnit.MILLIS.between(LocalDateTime.now(), autoStartTime)

                // Check periodically if user started early
                var currentTime = LocalDateTime.now()
                while (currentTime.isBefore(autoStartTime) && !launchedWorkouts.contains(nextWorkout.startTime)) {
                    // Also check if health simulation is still running
                    if (!SimulationManager.isRunning()) {
                        Log.w("WorkoutSimManager", "Health data simulation stopped during workout wait period")
                        _workoutState.value = WorkoutState.SyncRequired("Health data simulation stopped")
                        break
                    }

                    delay(1000)
                    currentTime = LocalDateTime.now()
                }

                if (launchedWorkouts.contains(nextWorkout.startTime)) {
                    // User started early - show countdown to actual start time
                    userStartedEarly = true
                    val timeToActualStart = ChronoUnit.MILLIS.between(currentTime, nextWorkout.startTime)

                    _workoutTimingState.value = WorkoutTimingState.UserStartedEarly(
                        nextWorkout.workoutType,
                        timeToActualStart,
                        SimulationManager.isRunning() && !SimulationManager.isPaused()
                    )

                    if (timeToActualStart > 0) {
                        delay(timeToActualStart)
                    }
                } else if (SimulationManager.isRunning()) {
                    // Start 30-second auto-start countdown if health sim is still running
                    startAutoStartCountdown(context, nextWorkout)
                }
            }

            // Launch workout if not already started by user and health sim is running
            if (!launchedWorkouts.contains(nextWorkout.startTime) && SimulationManager.isRunning()) {
                launchWorkoutWithHealthData(context, nextWorkout)
                _workoutState.value = WorkoutState.InProgress(nextWorkout.workoutType)
                _workoutTimingState.value = WorkoutTimingState.InProgress(
                    nextWorkout.workoutType,
                    nextWorkout.startTime,
                    SimulationManager.isRunning() && !SimulationManager.isPaused()
                )
            }

            // Wait for workout to end
            val endDelay = ChronoUnit.MILLIS.between(LocalDateTime.now(), nextWorkout.endTime)
            if (endDelay > 0) {
                delay(endDelay)
            }

            // Generate comprehensive workout summary with synchronized health data
            val summary = generateEnhancedWorkoutSummary(context, nextWorkout)
            _workoutState.value = WorkoutState.Finished(summary)
            _workoutTimingState.value = WorkoutTimingState.Finished(
                summary,
                SimulationManager.isRunning() && !SimulationManager.isPaused()
            )

            Log.i("WorkoutSimManager", "Workout finished. Enhanced summary generated.")

            launchedWorkouts.remove(nextWorkout.startTime)
            userStartedEarly = false
            delay(1000)
        }
    }

    private suspend fun processEvaluationWorkout(context: Context, workout: WorkoutDetails) {
        val evaluationStart = evaluationStartTime ?: return

        Log.i("WorkoutSimManager", "Processing evaluation workout: ${workout.workoutType}")

        try {
            // Schedule notification 5 minutes after evaluation starts
            val notificationTime = evaluationStart.plusMinutes(EVALUATION_NOTIFICATION_DELAY_MINUTES)

            _workoutTimingState.value = WorkoutTimingState.NotificationScheduled(
                workout.workoutType,
                ChronoUnit.MILLIS.between(LocalDateTime.now(), notificationTime),
                SimulationManager.isRunning() && !SimulationManager.isPaused()
            )

            // Wait for notification time
            val notificationDelay = ChronoUnit.MILLIS.between(LocalDateTime.now(), notificationTime)
            if (notificationDelay > 0) {
                Log.i("WorkoutSimManager", "Waiting ${notificationDelay}ms for notification time")
                delay(notificationDelay)
            }

            // Send notification
            sendWorkoutNotification(context, workout)
            Log.i("WorkoutSimManager", "Notification sent at ${LocalDateTime.now()}")

            // Calculate actual start time (5 minutes after notification + 30 second countdown)
            val actualStartTime = notificationTime.plusSeconds(EVALUATION_AUTO_START_COUNTDOWN_SECONDS)

            // Check if user started early during the 5-minute window
            val currentTime = LocalDateTime.now()

            if (launchedWorkouts.contains(workout.startTime)) {
                // User started early - show countdown to actual start time
                userStartedEarly = true
                val remainingTime = ChronoUnit.MILLIS.between(currentTime, actualStartTime)

                _workoutTimingState.value = WorkoutTimingState.UserStartedEarly(
                    workout.workoutType,
                    remainingTime,
                    SimulationManager.isRunning() && !SimulationManager.isPaused()
                )

                if (remainingTime > 0) {
                    delay(remainingTime)
                }
            } else {
                // Auto-start countdown (30 seconds)
                startAutoStartCountdown(context, workout, actualStartTime)

                // Launch workout activity after countdown
                val evaluationWorkout = workout.copy(
                    startTime = actualStartTime,
                    endTime = actualStartTime.plusMinutes(workout.duration.toLong())
                )

                launchWorkoutWithHealthData(context, evaluationWorkout)
                _workoutState.value = WorkoutState.InProgress(workout.workoutType)
                _workoutTimingState.value = WorkoutTimingState.InProgress(
                    workout.workoutType,
                    actualStartTime,
                    SimulationManager.isRunning() && !SimulationManager.isPaused()
                )
            }

            // Schedule workout end based on actual start time
            val workoutEndTime = actualStartTime.plusMinutes(workout.duration.toLong())
            val endDelay = ChronoUnit.MILLIS.between(LocalDateTime.now(), workoutEndTime)

            if (endDelay > 0) {
                Log.i("WorkoutSimManager", "Workout will end in ${endDelay}ms at $workoutEndTime")
                delay(endDelay)
            }

            // Generate comprehensive workout summary with health data from the workout period
            val summary = generateEnhancedWorkoutSummary(context, workout.copy(
                startTime = actualStartTime,
                endTime = workoutEndTime
            ))

            _workoutState.value = WorkoutState.Finished(summary)
            _workoutTimingState.value = WorkoutTimingState.Finished(
                summary,
                SimulationManager.isRunning() && !SimulationManager.isPaused()
            )

            Log.i("WorkoutSimManager", "Evaluation workout finished at ${LocalDateTime.now()}")

        } catch (e: Exception) {
            Log.e("WorkoutSimManager", "Error processing evaluation workout", e)
        }
    }

    private suspend fun startAutoStartCountdown(
        context: Context,
        workout: WorkoutDetails,
        startTime: LocalDateTime = workout.startTime
    ) {
        Log.i("WorkoutSimManager", "Starting 30-second auto-start countdown")

        countdownJob = scope.launch {
            repeat(30) { second ->
                val remaining = 30 - second
                _workoutTimingState.value = WorkoutTimingState.AutoStartCountdown(
                    workout.workoutType,
                    remaining,
                    SimulationManager.isRunning() && !SimulationManager.isPaused()
                )
                delay(1000)
            }
        }

        countdownJob?.join() // Wait for countdown to complete
    }

    private suspend fun generateEnhancedWorkoutSummary(context: Context, workout: WorkoutDetails): WorkoutSummary {
        // Get health data during workout period from the simulation
        val workoutHealthData = getHealthDataDuringWorkout(context, workout)

        // Calculate heart rate zones based on actual health data during workout
        val heartZones = calculateHeartRateZones(context, workoutHealthData)

        // Calculate actual workout metrics from health data
        val (avgHeartRateActual, maxHeartRateActual, totalActiveEnergyActual) =
            calculateActualWorkoutMetrics(workoutHealthData)

        // Get current health data for cycle information
        val currentHealthData = SimulationManager.healthDataStream.value

        return WorkoutSummary(
            details = workout,
            cyclePhase = currentHealthData.cyclePhase,
            cycleDay = currentHealthData.cycleDay,
            dayOfCycle = currentHealthData.cycleDay,
            heartZones = heartZones,
            workoutHealthData = workoutHealthData,
            avgHeartRateActual = avgHeartRateActual,
            maxHeartRateActual = maxHeartRateActual,
            totalActiveEnergyActual = totalActiveEnergyActual
        )
    }

    private fun calculateActualWorkoutMetrics(healthData: List<HealthDataRecord>): Triple<Double, Double, Double> {
        if (healthData.isEmpty()) {
            return Triple(0.0, 0.0, 0.0)
        }

        val avgHeartRate = healthData.map { it.heartRate }.average()
        val maxHeartRate = healthData.maxOfOrNull { it.heartRate } ?: 0.0
        val totalActiveEnergy = healthData.lastOrNull()?.activeEnergyBurned ?: 0.0

        return Triple(avgHeartRate, maxHeartRate, totalActiveEnergy)
    }

    private suspend fun getHealthDataDuringWorkout(context: Context, workout: WorkoutDetails): List<HealthDataRecord> {
        return withContext(Dispatchers.IO) {
            try {
                val startTimestamp = workout.startTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val endTimestamp = workout.endTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

                val dao = HealthDatabase.getInstance(context).healthRecordDao()
                val entities = dao.getRecordsBetween(startTimestamp, endTimestamp)

                val healthRecords = entities.map { entity ->
                    HealthDataRecord(
                        heartRate = entity.heartRate,
                        stepCount = entity.stepCount,
                        activeEnergyBurned = entity.activeEnergyBurned,
                        basalEnergyBurned = entity.basalEnergyBurned,
                        cyclePhase = entity.cyclePhase,
                        cycleDay = entity.cycleDay,
                        vo2Max = entity.vo2Max,
                        restingHeartRate = entity.restingHeartRate,
                        basalBodyTemperature = entity.basalBodyTemperature
                    )
                }

                Log.i("WorkoutSimManager", "Retrieved ${healthRecords.size} synchronized health records for workout period")
                healthRecords

            } catch (e: Exception) {
                Log.e("WorkoutSimManager", "Error getting synchronized workout health data", e)
                emptyList()
            }
        }
    }

    private suspend fun calculateHeartRateZones(context: Context, healthData: List<HealthDataRecord>): Map<Int, Long> {
        if (healthData.isEmpty()) {
            Log.w("WorkoutSimManager", "No health data available for heart rate zone calculation")
            return emptyMap()
        }

        // Get user age for max heart rate calculation
        val userAge = try {
            context.setupDataStore.data
                .map { preferences -> preferences[SetupPreferences.AGE] ?: 25 }
                .first()
        } catch (e: Exception) {
            Log.e("WorkoutSimManager", "Error getting user age", e)
            25 // Default age if unable to get user age
        }

        val maxHeartRate = 220 - userAge

        // Define heart rate zones based on max heart rate
        val zones = listOf(
            HeartRateZone(1, 0, (maxHeartRate * 0.6).toInt(), "Recovery"),
            HeartRateZone(2, (maxHeartRate * 0.6).toInt(), (maxHeartRate * 0.7).toInt(), "Base"),
            HeartRateZone(3, (maxHeartRate * 0.7).toInt(), (maxHeartRate * 0.8).toInt(), "Aerobic"),
            HeartRateZone(4, (maxHeartRate * 0.8).toInt(), (maxHeartRate * 0.9).toInt(), "Threshold"),
            HeartRateZone(5, (maxHeartRate * 0.9).toInt(), maxHeartRate, "Neuromuscular")
        )

        val zoneDurations = mutableMapOf<Int, Long>()

        // Calculate time spent in each zone
        // Assuming each data point represents roughly 1 minute of data
        healthData.forEach { record ->
            val heartRate = record.heartRate.toInt()
            val zone = zones.find { heartRate >= it.minBpm && heartRate < it.maxBpm }?.zone ?: 1
            zoneDurations[zone] = (zoneDurations[zone] ?: 0L) + 60L // Add 60 seconds per data point
        }

        Log.i("WorkoutSimManager", "Calculated heart rate zones: $zoneDurations")
        return zoneDurations
    }

    private fun launchWorkoutWithHealthData(context: Context, workout: WorkoutDetails) {
        launchedWorkouts.add(workout.startTime)

        val intent = Intent(context, WorkoutActivity::class.java).apply {
            putExtra("workout_type", workout.workoutType)
            putExtra("workout_start_time", workout.startTime.toString())
            putExtra("workout_duration", workout.duration.toInt())
            putExtra("workout_distance", workout.distance)
            putExtra("workout_avg_heart_rate", workout.avgHeartRate)
            putExtra("workout_max_heart_rate", workout.maxHeartRate)
            putExtra("workout_active_energy", workout.activeEnergy)
            putExtra("workout_total_energy", workout.totalEnergyBurned)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.i("WorkoutSimManager", "Launching synchronized WorkoutActivity for workout at ${workout.startTime}")
    }

    // Public method for the Activity to call to prevent auto-launch
    fun userInitiatedWorkout(startTime: LocalDateTime) {
        Log.i("WorkoutSimManager", "User initiated workout for $startTime. Preventing auto-launch.")
        launchedWorkouts.add(startTime)
        userStartedEarly = true
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        countdownJob?.cancel()
        synchronizationJob?.cancel()
        monitoringJob = null
        countdownJob = null
        synchronizationJob = null
        launchedWorkouts.clear()
        evaluationStartTime = null
        userStartedEarly = false
        _workoutState.value = WorkoutState.Idle
        _workoutTimingState.value = WorkoutTimingState.Idle
        Log.i("WorkoutSimManager", "Enhanced workout monitoring stopped.")
    }

    // Public method to check synchronization status
    fun isHealthDataSynchronized(): Boolean {
        return SimulationManager.isRunning() && !SimulationManager.isPaused()
    }

    // Public method to get synchronization status details
    fun getSynchronizationStatus(): String {
        return when {
            !SimulationManager.isRunning() -> "Health data simulation stopped"
            SimulationManager.isPaused() -> "Health data simulation paused"
            else -> "Synchronized"
        }
    }

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
                workouts.sortedBy { it.startTime }

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
        val syncStatus = if (isHealthDataSynchronized()) "✓" else "⚠"

        val notificationText = if (EvaluationState.isEvaluationMode.value) {
            "$syncStatus Evaluation workout: ${workout.workoutType} starting soon!"
        } else {
            "$syncStatus ${workout.workoutType} workout scheduled for ${workout.startTime.format(timeFormatter)}. Tap to start early."
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.run_icon)
            .setContentTitle("Workout Approaching")
            .setContentText(notificationText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)
        Log.i("WorkoutSimManager", "Enhanced notification sent: $notificationText")
    }
}