package com.example.honoursproject.presentation.workoutsimulation

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.honoursproject.R
import com.example.honoursproject.CyclePhase
import com.example.honoursproject.dataservices.*
import com.example.honoursproject.presentation.theme.*
import com.example.honoursproject.tile.MenstrualCycleInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// --- Activity ---
class WorkoutActivity : ComponentActivity() {

    private lateinit var viewModel: WorkoutViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val workoutType = intent.getStringExtra("workout_type") ?: "run"
        val workoutDurationMinutes = intent.getIntExtra("workout_duration", 30)

        val workoutStartTimeString = intent.getStringExtra("workout_start_time")
        val actualStartTime = try {
            LocalDateTime.parse(workoutStartTimeString)
        } catch (e: Exception) {
            Log.e("WorkoutActivity", "Could not parse start time! Using current time.", e)
            LocalDateTime.now()
        }
        val actualEndTime = actualStartTime.plusMinutes(workoutDurationMinutes.toLong())

        // Notify the manager that this workout was started by the user
        WorkoutSimulationManager.userInitiatedWorkout(actualStartTime)

        lifecycleScope.launch {
            val userAge = SimulationManager.getUserAge(applicationContext)

            val factory = WorkoutViewModel.Factory(
                context = applicationContext,
                workoutType = workoutType,
                workoutStartTime = actualStartTime,
                workoutEndTime = actualEndTime,
                userAge = userAge
            )

                viewModel = ViewModelProvider(this@WorkoutActivity, factory)[WorkoutViewModel::class.java]

                setContent {
                    HonoursProjectTheme {
                        val uiState by viewModel.uiState.collectAsState()
                        val isPaused by viewModel.isPaused.collectAsState()
                        val isStopped by viewModel.isStopped.collectAsState()
                        val zoneDistribution by viewModel.zoneDistribution.collectAsState()
                        val screenState by viewModel.screenState.collectAsState()
                        val countdownValue by viewModel.countdownValue.collectAsState()

                        // Observe WorkoutSimulationManager states
                        val workoutTimingState by WorkoutSimulationManager.workoutTimingState.collectAsState()
                        val simulationProgress by viewModel.simulationProgress.collectAsState()

                        val navController = rememberNavController()

                        LaunchedEffect(isStopped) {
                            if (isStopped) {
                                navController.navigate("summary") {
                                    popUpTo("workout") { inclusive = true }
                                }
                            }
                        }

                        // Remember evaluation mode state to avoid recomposition issues
                        val isEvaluationMode by remember { derivedStateOf { EvaluationState.isEvaluationMode.value } }

                        NavHost(navController = navController, startDestination = "workout") {
                            composable("workout") {
                                when (val currentTimingState = workoutTimingState) {
                                    // Handle WorkoutSimulationManager timing states first
                                    is WorkoutTimingState.NotificationScheduled -> {
                                        NotificationScheduledScreen(
                                            workoutType = currentTimingState.workoutType,
                                            timeRemaining = currentTimingState.timeRemaining
                                        )
                                    }
                                    is WorkoutTimingState.UserStartedEarly -> {
                                        UserEarlyStartScreen(
                                            workoutType = currentTimingState.workoutType,
                                            timeToActualStart = currentTimingState.actualStartCountdown
                                        )
                                    }
                                    is WorkoutTimingState.AutoStartCountdown -> {
                                        AutoStartCountdownScreen(
                                            workoutType = currentTimingState.workoutType,
                                            countdown = currentTimingState.countdown
                                        )
                                    }
                                    else -> {
                                        // Fall back to local ViewModel states
                                        when (screenState) {
                                            WorkoutScreenState.WAITING -> {
                                                WaitingScreen(simulationProgress = simulationProgress)
                                            }
                                            WorkoutScreenState.COUNTING_DOWN -> {
                                                CountdownScreen(
                                                    countdownValue = countdownValue,
                                                    isEvaluationMode = isEvaluationMode
                                                )
                                            }
                                            WorkoutScreenState.IN_PROGRESS -> {
                                                WorkoutScreen(
                                                    elapsedTime = uiState.elapsedTime,
                                                    heartRate = uiState.heartRate,
                                                    distance = uiState.distance,
                                                    energy = uiState.energy,
                                                    cycleInfo = uiState.cycleInfo,
                                                    workoutType = workoutType,
                                                    isPaused = isPaused,
                                                    onPauseOrResumeClick = { viewModel.pauseOrResumeWorkout() },
                                                    onStopClick = { viewModel.stopWorkout() },
                                                    currentZone = uiState.heartZone,
                                                    isEvaluationMode = isEvaluationMode,
                                                    simulationProgress = simulationProgress
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            composable("summary") {
                                // Use comprehensive summary from WorkoutSimulationManager if available
                                val finalSummary = remember(workoutTimingState) {
                                    when (val currentTimingState = workoutTimingState) {
                                        is WorkoutTimingState.Finished -> currentTimingState.summary
                                        else -> {
                                            // Fallback to ViewModel-generated summary
                                            WorkoutSummary(
                                                details = WorkoutDetails(
                                                    workoutType = workoutType,
                                                    startTime = viewModel.startTime ?: LocalDateTime.now(),
                                                    endTime = LocalDateTime.now(),
                                                    distance = uiState.distance,
                                                    duration = uiState.elapsedTime / 60.0,
                                                    maxHeartRate = viewModel.heartRateHistory.maxOrNull()?.toDouble() ?: 0.0,
                                                    avgHeartRate = uiState.avgHeartRate.toDouble(),
                                                    totalEnergyBurned = (uiState.energy + uiState.basalEnergy).toDouble(),
                                                    activeEnergy = uiState.energy.toDouble(),
                                                    basalEnergy = uiState.basalEnergy.toDouble()
                                                ),
                                                cyclePhase = uiState.cycleInfo.phase.name,
                                                cycleDay = uiState.cycleInfo.dayOfCycle,
                                                dayOfCycle = uiState.cycleInfo.dayOfCycle,
                                                heartZones = zoneDistribution
                                            )
                                        }
                                    }
                                }

                                WorkoutSummaryScreen(
                                    summary = finalSummary,
                                    zoneDistribution = finalSummary.heartZones,
                                    onDismiss = { finish() }
                                )
                            }
                        }
                    }
                }

        }
    }
}

enum class WorkoutScreenState {
    WAITING,
    COUNTING_DOWN,
    IN_PROGRESS
}

private fun getCyclePhaseColor(phase: CyclePhase): Color {
    return when (phase) {
        CyclePhase.MENSTRUATION -> menstrualRed
        CyclePhase.FOLLICULAR -> follicleGreen
        CyclePhase.OVULATION -> ovulationPink
        CyclePhase.LUTEAL -> lutealPurple
        else -> GrayInactive
    }
}

enum class HeartRateZone(val color: Color) {
    ZONE_1(darkBlueHZ),
    ZONE_2(CyanHZ),
    ZONE_3(YellowHZ),
    ZONE_4(OrangeHZ),
    ZONE_5(maroonHZ)
}

data class WorkoutMetrics(
    val elapsedTime: Long = 0,
    val heartRate: Int = 0,
    val distance: Double = 0.0,
    val energy: Int = 0,
    val basalEnergy: Int = 0,
    val avgHeartRate: Int = 0,
    val cycleInfo: MenstrualCycleInfo = MenstrualCycleInfo(
        phase = CyclePhase.UNKNOWN,
        dayOfCycle = 0
    ),
    val heartZone: HeartRateZone = HeartRateZone.ZONE_1
)

data class SimulationProgress(
    val currentIndex: Int = 0,
    val totalRecords: Int = 0,
    val isSyncing: Boolean = false,
    val isComplete: Boolean = false
) {
    val progressPercentage: Float
        get() = if (totalRecords > 0) currentIndex.toFloat() / totalRecords.toFloat() else 0f
}

// --- Enhanced ViewModel ---
class WorkoutViewModel(
    private val context: Context,
    private val workoutType: String,
    private val workoutEndTime: LocalDateTime,
    private val workoutStartTime: LocalDateTime,
    private val userAge: Int
) : ViewModel() {
    private val _screenState = MutableStateFlow(WorkoutScreenState.WAITING)
    val screenState = _screenState.asStateFlow()

    private val _countdownValue = MutableStateFlow(3)
    val countdownValue = _countdownValue.asStateFlow()

    private val _zoneDistribution = MutableStateFlow(mapOf<Int, Long>())
    val zoneDistribution = _zoneDistribution.asStateFlow()
    private var zoneTimestamps = mutableMapOf<Int, Long>()

    private val _uiState = MutableStateFlow(WorkoutMetrics())
    val uiState: StateFlow<WorkoutMetrics> = _uiState.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isStopped = MutableStateFlow(false)
    val isStopped: StateFlow<Boolean> = _isStopped.asStateFlow()

    private val _simulationProgress = MutableStateFlow(SimulationProgress())
    val simulationProgress: StateFlow<SimulationProgress> = _simulationProgress.asStateFlow()

    var heartRateHistory = mutableListOf<Int>()
    var startTime: LocalDateTime? = null
    private var pausedDuration = 0L

    private var workoutJob: Job? = null
    private var timerJob: Job? = null
    private var progressJob: Job? = null
    private var initialStepCount = 0
    private var initialActiveEnergy = 0.0
    private var initialBasalEnergy = 0.0
    private var isFirstDataPoint = true

    init {
        startProgressMonitoring()
        prepareForWorkout()
    }

    private fun startProgressMonitoring() {
        progressJob = viewModelScope.launch {
            while (isActive) {
                val progress = SimulationManager.getCurrentProgress()
                val isComplete = SimulationManager.isComplete()

                _simulationProgress.value = SimulationProgress(
                    currentIndex = progress.first,
                    totalRecords = progress.second,
                    isSyncing = false, // Could be enhanced to detect sync state
                    isComplete = isComplete
                )

                delay(2000) // Update every 2 seconds
            }
        }
    }

    private fun prepareForWorkout() {
        viewModelScope.launch {
            val now = LocalDateTime.now()

            if (now.isAfter(workoutStartTime)) {
                _screenState.value = WorkoutScreenState.COUNTING_DOWN
                startCountdown()
            } else {
                _screenState.value = WorkoutScreenState.WAITING
                val secondsToWait = ChronoUnit.SECONDS.between(now, workoutStartTime)

                if (secondsToWait > 3) {
                    delay((secondsToWait - 3) * 1000)
                }

                _screenState.value = WorkoutScreenState.COUNTING_DOWN
                startCountdown()
            }
        }
    }

    private fun updateZoneDistribution(currentZone: Int) {
        if (currentZone in 1..5) {
            zoneTimestamps[currentZone] = (zoneTimestamps[currentZone] ?: 0L) + 1
            _zoneDistribution.value = zoneTimestamps.toMap()
        }
    }

    private fun calculateHeartRateZone(hr: Double, userAge: Int): HeartRateZone {
        val maxHeartRate = 220 - userAge // Standardized with WorkoutSimulationManager
        val percentage = hr / maxHeartRate
        return when {
            percentage >= 0.9 -> HeartRateZone.ZONE_5  // Zone 5: 90%+
            percentage >= 0.8 -> HeartRateZone.ZONE_4  // Zone 4: 80-90%
            percentage >= 0.7 -> HeartRateZone.ZONE_3  // Zone 3: 70-80%
            percentage >= 0.6 -> HeartRateZone.ZONE_2  // Zone 2: 60-70%
            else -> HeartRateZone.ZONE_1               // Zone 1: <60%
        }
    }

    private fun startCountdown() {
        viewModelScope.launch {
            for (i in 3 downTo 1) {
                _countdownValue.value = i
                delay(1000)
            }
            _screenState.value = WorkoutScreenState.IN_PROGRESS
            startTimer()
            startWorkout()
        }
    }

    private fun startTimer() {
        timerJob = viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                delay(1000)
                if (!_isPaused.value) {
                    val currentElapsed = _uiState.value.elapsedTime + 1
                    _uiState.value = _uiState.value.copy(elapsedTime = currentElapsed)
                }
            }
        }
    }

    private fun startWorkout() {
        if (workoutJob?.isActive == true) return
        startTime = LocalDateTime.now()
        _isPaused.value = false

        workoutJob = viewModelScope.launch {
            try {
                SimulationManager.healthDataStream.collect { healthData ->
                    while (isPaused.value) {
                        delay(1000)
                    }

                    if (isFirstDataPoint && healthData.stepCount > 0) {
                        initialStepCount = healthData.stepCount
                        initialActiveEnergy = healthData.activeEnergyBurned
                        initialBasalEnergy = healthData.basalEnergyBurned
                        isFirstDataPoint = false
                    }

                    val heartZone = calculateHeartRateZone(healthData.heartRate, userAge)

                    heartRateHistory.add(healthData.heartRate.toInt())
                    val newAvgHeartRate = heartRateHistory.average().roundToInt()

                    val stepsInWorkout = healthData.stepCount - initialStepCount
                    val distanceInKm = (stepsInWorkout * 0.762) / 1000.0
                    val activeEnergyInWorkout = (healthData.activeEnergyBurned - initialActiveEnergy).toInt()
                    val basalEnergyInWorkout = (healthData.basalEnergyBurned - initialBasalEnergy).toInt()

                    val cyclePhase = try {
                        CyclePhase.valueOf(healthData.cyclePhase.uppercase())
                    } catch (e: Exception) {
                        CyclePhase.UNKNOWN
                    }

                    _uiState.value = _uiState.value.copy(
                        heartRate = healthData.heartRate.toInt(),
                        distance = distanceInKm,
                        energy = activeEnergyInWorkout,
                        basalEnergy = basalEnergyInWorkout,
                        avgHeartRate = newAvgHeartRate,
                        cycleInfo = MenstrualCycleInfo(
                            phase = cyclePhase,
                            dayOfCycle = healthData.cycleDay
                        ),
                        heartZone = heartZone
                    )

                    updateZoneDistribution(heartZone.ordinal + 1)

                    if (LocalDateTime.now().isAfter(workoutEndTime)) {
                        stopWorkout()
                    }
                }
            } catch (e: Exception) {
                Log.e("WorkoutViewModel", "Error in workout simulation: ${e.message}")
            }
        }
    }

    fun stopWorkout() {
        workoutJob?.cancel()
        timerJob?.cancel()
        progressJob?.cancel()
        _isStopped.value = true
    }

    fun pauseOrResumeWorkout() {
        _isPaused.value = !_isPaused.value
        val now = LocalDateTime.now()
        if (_isPaused.value) {
            val elapsed = ChronoUnit.SECONDS.between(startTime, now)
            pausedDuration = elapsed - _uiState.value.elapsedTime
        } else {
            val pauseInterval = ChronoUnit.SECONDS.between(startTime, now) - _uiState.value.elapsedTime
            startTime = startTime?.plusSeconds(pauseInterval)
        }
    }

    class Factory(
        private val context: Context,
        private val workoutType: String,
        private val workoutEndTime: LocalDateTime,
        private val workoutStartTime: LocalDateTime,
        private val userAge: Int
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(WorkoutViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return WorkoutViewModel(context, workoutType, workoutEndTime, workoutStartTime, userAge) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

// --- Enhanced Composable Functions ---

@Composable
fun NotificationScheduledScreen(workoutType: String, timeRemaining: Long) {
    val minutes = (timeRemaining / 60000).toInt()
    val seconds = ((timeRemaining % 60000) / 1000).toInt()
    val isEvaluationMode by remember { derivedStateOf { EvaluationState.isEvaluationMode.value } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            if (isEvaluationMode) {
                Text(
                    text = "EVALUATION MODE",
                    color = menstrualRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Image(
                painterResource(id = R.drawable.heart_icon),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                colorFilter = ColorFilter.tint(menstrualRed)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Workout Notification Sent",
                color = WhiteText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = workoutType.uppercase(),
                color = follicleGreen,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Auto-start in ${String.format("%02d:%02d", minutes, seconds)}",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun UserEarlyStartScreen(workoutType: String, timeToActualStart: Long) {
    val minutes = (timeToActualStart / 60000).toInt()
    val seconds = ((timeToActualStart % 60000) / 1000).toInt()
    val isEvaluationMode by remember { derivedStateOf { EvaluationState.isEvaluationMode.value } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            if (isEvaluationMode) {
                Text(
                    text = "EVALUATION MODE",
                    color = menstrualRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = "Started Early!",
                color = follicleGreen,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = workoutType.uppercase(),
                color = WhiteText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Workout begins in",
                color = WhiteText,
                fontSize = 12.sp
            )
            Text(
                text = String.format("%02d:%02d", minutes, seconds),
                color = menstrualRed,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun AutoStartCountdownScreen(workoutType: String, countdown: Int) {
    val isEvaluationMode by remember { derivedStateOf { EvaluationState.isEvaluationMode.value } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isEvaluationMode) {
                Text(
                    text = "EVALUATION MODE",
                    color = menstrualRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = "Auto-starting",
                color = WhiteText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = workoutType.uppercase(),
                color = follicleGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = countdown.toString(),
                color = menstrualRed,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun WaitingScreen(simulationProgress: SimulationProgress) {
    val isEvaluationMode by remember { derivedStateOf { EvaluationState.isEvaluationMode.value } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isEvaluationMode) {
                Text(
                    text = "EVALUATION MODE",
                    color = menstrualRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = "Waiting for workout to start...",
                color = WhiteText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )

            if (simulationProgress.totalRecords > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Simulation: ${simulationProgress.currentIndex}/${simulationProgress.totalRecords}",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
                if (simulationProgress.isSyncing) {
                    Text(
                        text = "Syncing data...",
                        color = follicleGreen,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CountdownScreen(countdownValue: Int, isEvaluationMode: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isEvaluationMode) {
                Text(
                    text = "EVALUATION MODE",
                    color = menstrualRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = "Starting in",
                color = WhiteText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = countdownValue.toString(),
                color = menstrualRed,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun WorkoutScreen(
    elapsedTime: Long,
    heartRate: Int,
    distance: Double,
    energy: Int,
    cycleInfo: MenstrualCycleInfo,
    workoutType: String,
    isPaused: Boolean,
    onPauseOrResumeClick: () -> Unit,
    onStopClick: () -> Unit,
    currentZone: HeartRateZone,
    isEvaluationMode: Boolean,
    simulationProgress: SimulationProgress
) {
    Box(
        modifier = Modifier.fillMaxSize().background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround,
            modifier = Modifier.fillMaxSize().padding(12.dp)
        ) {
            // Header with evaluation mode indicator
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = if (workoutType.contains("walk", ignoreCase = true)) R.drawable.steps_icon else R.drawable.run_icon
                    Image(
                        painterResource(id = icon),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(WhiteText)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatElapsedTime(elapsedTime),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = WhiteText
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HeartRateZones(currentZone)
                Spacer(Modifier.height(4.dp))
                MetricRow(
                    iconRes = R.drawable.heart_icon,
                    label = "BPM",
                    value = heartRate.toString(),
                    valueColor = menstrualRed,
                    iconTint = menstrualRed
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                MetricColumn(label = "KCAL", value = energy.toString())
                MetricColumn(label = "KM", value = String.format("%.2f", distance))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(id = R.drawable.cycle_icon),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(getCyclePhaseColor(cycleInfo.phase))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = cycleInfo.dayOfCycle.toString(),
                        fontSize = 14.sp,
                        color = getCyclePhaseColor(cycleInfo.phase),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Gray)
                        .clickable(onClick = onPauseOrResumeClick),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = if (isPaused) R.drawable.play_icon else R.drawable.pause_icon),
                        contentDescription = if (isPaused) "Resume" else "Pause",
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                }
                Spacer(Modifier.width(24.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(menstrualRed)
                        .clickable(onClick = onStopClick),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.stop_icon),
                        contentDescription = "Stop",
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
fun WorkoutSummaryScreen(
    summary: WorkoutSummary,
    zoneDistribution: Map<Int, Long>,
    onDismiss: () -> Unit
) {
    var showGraph by remember { mutableStateOf(false) }
    val isEvaluationMode by remember { derivedStateOf { EvaluationState.isEvaluationMode.value } }

    Box(
        modifier = Modifier.fillMaxSize().background(backgroundBlue)
            .clickable { if (showGraph) showGraph = false else onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isEvaluationMode) {
                    Text(
                        text = "EVALUATION COMPLETE",
                        color = menstrualRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text("Workout Summary", style = MaterialTheme.typography.title3, color = WhiteText)
            }

            if (showGraph) {
                HeartZoneGraph(zoneDistribution = zoneDistribution)
            } else {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        MetricColumn(
                            "TIME",
                            formatElapsedTime(summary.details.duration.toLong() * 60)
                        )
                        MetricColumn("KCAL", summary.details.activeEnergy.roundToInt().toString())
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        MetricColumn("KM", String.format("%.2f", summary.details.distance))
                        MetricColumn("AVG HR", summary.details.avgHeartRate.roundToInt().toString())
                    }

                    // Show heart zone data if available
                    if (zoneDistribution.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showGraph = true }
                        ) {
                            Image(
                                painterResource(id = R.drawable.heart_icon),
                                contentDescription = "View Heart Zones",
                                modifier = Modifier.size(24.dp),
                                colorFilter = ColorFilter.tint(menstrualRed)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("View Zones", color = WhiteText, fontSize = 12.sp)
                        }
                    }

                    // Show cycle information if available
                    if (summary.cyclePhase != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Cycle: ${summary.cyclePhase} (Day ${summary.dayOfCycle})",
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Text("Tap to dismiss", color = Color.Gray, fontSize = 10.sp)
        }
    }
}

@Composable
fun HeartRateZones(currentZone: HeartRateZone) {
    val zoneColors = listOf(darkBlueHZ, CyanHZ, YellowHZ, OrangeHZ, maroonHZ)
    Row {
        (1..5).forEach { zone ->
            val isActiveZone = zone == currentZone.ordinal + 1
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (isActiveZone) zoneColors[zone - 1] else Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                if (isActiveZone) {
                    Image(
                        painterResource(id = R.drawable.heart_icon),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        colorFilter = ColorFilter.tint(Color.Black)
                    )
                }
            }
        }
    }
}

@Composable
fun HeartZoneGraph(zoneDistribution: Map<Int, Long>) {
    val totalTime = zoneDistribution.values.sum().toFloat().coerceAtLeast(1f)
    val zoneColors = listOf(darkBlueHZ, CyanHZ, YellowHZ, OrangeHZ, maroonHZ)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
    ) {
        Text("Time In Zones", color = WhiteText, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(80.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            (1..5).forEach { zone ->
                val zoneTime = zoneDistribution[zone] ?: 0L
                val percentage = if (totalTime > 0) zoneTime / totalTime else 0f
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .fillMaxHeight(percentage.coerceAtLeast(0.05f)) // Minimum height for visibility
                            .background(zoneColors[zone - 1])
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(zone.toString(), color = WhiteText, fontSize = 10.sp)
                    if (zoneTime > 0) {
                        Text(
                            text = "${(zoneTime / 60)}m",
                            color = Color.Gray,
                            fontSize = 8.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Total workout time: ${formatElapsedTime(totalTime.toLong())}",
            color = Color.Gray,
            fontSize = 10.sp
        )
    }
}

@Composable
fun MetricRow(
    iconRes: Int? = null,
    label: String,
    value: String,
    valueColor: Color = WhiteText,
    iconTint: Color = WhiteText
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(iconTint)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = value,
            fontSize = 24.sp,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = " $label",
            fontSize = 12.sp,
            color = WhiteText,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun MetricColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 20.sp,
            color = WhiteText,
            fontWeight = FontWeight.SemiBold
        )
        Text(text = label, fontSize = 12.sp, color = WhiteText)
    }
}

@Composable
fun formatElapsedTime(seconds: Long): String {
    val hours = TimeUnit.SECONDS.toHours(seconds)
    val minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60
    val secs = seconds % 60
    if (hours > 0) {
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
    return String.format("%02d:%02d", minutes, secs)
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun WorkoutScreenPreview() {
    WorkoutScreen(
        elapsedTime = 1523,
        heartRate = 148,
        energy = 112,
        cycleInfo = MenstrualCycleInfo(phase = CyclePhase.OVULATION, dayOfCycle = 14),
        workoutType = "run",
        currentZone = HeartRateZone.ZONE_3,
        distance = 2.54,
        isPaused = false,
        onPauseOrResumeClick = {},
        onStopClick = {},
        isEvaluationMode = true,
        simulationProgress = SimulationProgress(150, 200, false, false)
    )
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun NotificationScheduledScreenPreview() {
    NotificationScheduledScreen(
        workoutType = "run",
        timeRemaining = 180000 // 3 minutes
    )
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun AutoStartCountdownScreenPreview() {
    AutoStartCountdownScreen(
        workoutType = "walk",
        countdown = 15
    )
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun UserEarlyStartScreenPreview() {
    UserEarlyStartScreen(
        workoutType = "run",
        timeToActualStart = 120000 // 2 minutes
    )
}