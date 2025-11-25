package com.example.honoursproject.presentation.workoutsimulation

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material.Text
import com.example.honoursproject.CyclePhase
import com.example.honoursproject.R
import com.example.honoursproject.dataservices.*
import com.example.honoursproject.presentation.SetupPreferences
import com.example.honoursproject.presentation.setupDataStore
import com.example.honoursproject.presentation.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// ============ DATA CLASSES ============

data class MenstrualCycleInfo(val phase: CyclePhase, val dayOfCycle: Int)

data class WorkoutUiState(
    val heartRate: Int = 0,
    val avgHeartRate: Int = 0,
    val distance: Double = 0.0,
    val energy: Double = 0.0,
    val basalEnergy: Double = 0.0,
    val cycleInfo: MenstrualCycleInfo? = null,
    val heartZone: HeartRateZone = HeartRateZone.ZONE_1,
    val isPaused: Boolean = false
)

data class SimulationProgress(
    val currentIndex: Int = 0,
    val totalRecords: Int = 0,
    val isSyncing: Boolean = false,
    val isComplete: Boolean = false
)

enum class HeartRateZone(val displayName: String) {
    ZONE_1("Zone 1 - Recovery"),
    ZONE_2("Zone 2 - Base"),
    ZONE_3("Zone 3 - Aerobic"),
    ZONE_4("Zone 4 - Threshold"),
    ZONE_5("Zone 5 - Max")
}

enum class WorkoutScreenState {
    WAITING,
    EARLY_START_WAITING,
    COUNTING_DOWN,
    IN_PROGRESS,
    FINISHED
}

// ============ VIEW MODEL ============

/**
 * Refactored WorkoutViewModel
 * Uses HealthDataStreamManager instead of SimulationManager
 */
class WorkoutViewModel(
    private val context: Context,
    workoutType: String = "",
    workoutDuration: Int = 30,
    userAge: Int = 25
) : ViewModel() {

    companion object {
        private const val TAG = "WorkoutViewModel"
    }

    // UI State
    private val _uiState = MutableStateFlow(WorkoutUiState())
    val uiState = _uiState.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _screenState = MutableStateFlow(WorkoutScreenState.WAITING)
    val screenState = _screenState.asStateFlow()

    private val _simulationProgress = MutableStateFlow(SimulationProgress())
    val simulationProgress = _simulationProgress.asStateFlow()

    // Workout data
    private val workoutStartTime: LocalDateTime
    private val workoutEndTime: LocalDateTime
    private val isEarlyStart: Boolean
    private var userAge = userAge

    // Tracking
    private var startTime: LocalDateTime? = null
    private var initialStepCount = 0
    private var initialActiveEnergy = 0.0
    private var initialBasalEnergy = 0.0
    private var isFirstDataPoint = true
    private val heartRateHistory = mutableListOf<Int>()
    private val zoneDistribution = mutableMapOf<Int, Long>()

    // Jobs
    private var workoutJob: Job? = null
    private var progressJob: Job? = null
    private var countdownJob: Job? = null

    init {
        Log.i(TAG, "Initializing workout: $workoutType, Duration: ${workoutDuration}min")

        val now = LocalDateTime.now()
        workoutStartTime = now.plusMinutes(5)
        workoutEndTime = workoutStartTime.plusMinutes(workoutDuration.toLong())
        isEarlyStart = false

        // Get user age from setup data
        viewModelScope.launch {
            this@WorkoutViewModel.userAge = getSetupUserAge()
        }

        prepareForWorkout()
    }

    /**
     * Get user age from setup data store
     */
    private suspend fun getSetupUserAge(): Int {
        return try {
            context.setupDataStore.data
                .first()
                .let { prefs -> prefs[SetupPreferences.AGE] ?: 25 }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user age", e)
            25
        }
    }

    /**
     * Prepare for workout - handle timing and countdowns
     */
    private fun prepareForWorkout() {
        viewModelScope.launch {
            val now = LocalDateTime.now()

            if (isEarlyStart) {
                _screenState.value = WorkoutScreenState.EARLY_START_WAITING
                val millisecondsToWait = ChronoUnit.MILLIS.between(now, workoutStartTime)
                if (millisecondsToWait > 3000) {
                    delay(millisecondsToWait - 3000)
                }
                _screenState.value = WorkoutScreenState.COUNTING_DOWN
                startCountdown()
            } else if (now.isAfter(workoutStartTime)) {
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

    /**
     * Start 30-second countdown before workout begins
     */
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            repeat(30) { second ->
                val remaining = 30 - second
                Log.d(TAG, "Countdown: $remaining seconds")
                delay(1000)
            }
            _screenState.value = WorkoutScreenState.IN_PROGRESS
            startWorkout()
            startProgressMonitoring()
        }
    }

    /**
     * Start the workout - collect health data from unified stream
     */
    private fun startWorkout() {
        if (workoutJob?.isActive == true) return

        startTime = LocalDateTime.now()
        _isPaused.value = false

        workoutJob = viewModelScope.launch {
            try {
                if (!HealthDataStreamManager.isHistoricalDataLoaded()) {
                    HealthDataStreamManager.dataStream
                        .filter { it.isHistoricalDataLoaded }
                        .first()
                    Log.d(TAG, "Historical data loaded, starting workout data collection")
                }
                HealthDataStreamManager.dataStream.collectLatest { snapshot ->
                    // Skip if paused
                    while (isPaused.value) {
                        delay(1000)
                    }

                    // Initialize baseline on first data point
                    if (isFirstDataPoint && snapshot.cumulativeSteps > 0) {
                        initialStepCount = snapshot.cumulativeSteps
                        initialActiveEnergy = snapshot.cumulativeEnergyBurned
                        initialBasalEnergy = snapshot.basalEnergyBurned
                        isFirstDataPoint = false
                        Log.d(TAG, "Baseline set: Steps=$initialStepCount, Energy=$initialActiveEnergy")
                    }

                    // Calculate heart rate zone
                    val heartZone = calculateHeartRateZone(snapshot.heartRate)
                    heartRateHistory.add(snapshot.heartRate.toInt())
                    val newAvgHeartRate = heartRateHistory.average().roundToInt()

                    // Calculate workout metrics relative to start
                    val stepsInWorkout = snapshot.cumulativeSteps - initialStepCount
                    val distanceInKm = (stepsInWorkout * 0.762) / 1000.0
                    val activeEnergyInWorkout = snapshot.cumulativeEnergyBurned - initialActiveEnergy
                    val basalEnergyInWorkout = snapshot.basalEnergyBurned - initialBasalEnergy

                    // Parse cycle phase
                    val cyclePhase = try {
                        CyclePhase.valueOf(snapshot.cyclePhase.uppercase())
                    } catch (e: Exception) {
                        CyclePhase.UNKNOWN
                    }

                    // Update UI state with new metrics
                    _uiState.value = _uiState.value.copy(
                        heartRate = snapshot.heartRate.toInt(),
                        distance = distanceInKm,
                        energy = activeEnergyInWorkout,
                        basalEnergy = basalEnergyInWorkout,
                        avgHeartRate = newAvgHeartRate,
                        cycleInfo = MenstrualCycleInfo(
                            phase = cyclePhase,
                            dayOfCycle = snapshot.cycleDay
                        ),
                        heartZone = heartZone
                    )

                    // Track zone distribution
                    updateZoneDistribution(heartZone.ordinal + 1)

                    // Check if workout time has ended
                    if (LocalDateTime.now().isAfter(workoutEndTime)) {
                        stopWorkout()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in workout: ${e.message}", e)
            }
        }
    }

    /**
     * Calculate which heart rate zone the user is in
     */
    private fun calculateHeartRateZone(heartRate: Double): HeartRateZone {
        val maxHeartRate = 220 - userAge
        val percentage = (heartRate / maxHeartRate) * 100

        return when {
            percentage < 60 -> HeartRateZone.ZONE_1
            percentage < 70 -> HeartRateZone.ZONE_2
            percentage < 80 -> HeartRateZone.ZONE_3
            percentage < 90 -> HeartRateZone.ZONE_4
            else -> HeartRateZone.ZONE_5
        }
    }

    /**
     * Update zone distribution tracking
     */
    private fun updateZoneDistribution(zone: Int) {
        zoneDistribution[zone] = (zoneDistribution[zone] ?: 0L) + 1L
    }

    /**
     * Monitor simulation progress
     */
    private fun startProgressMonitoring() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val state = SimulationManager.simulationState.value
                    _simulationProgress.value = SimulationProgress(
                        currentIndex = state.currentIndex,
                        totalRecords = state.totalRecords,
                        isSyncing = state.isPaused,
                        isComplete = state.currentIndex >= state.totalRecords
                    )
                    delay(2000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring progress", e)
                }
            }
        }
    }

    /**
     * Pause the workout
     */
    fun pauseWorkout() {
        _isPaused.value = true
        SimulationManager.pauseSimulation()
        WorkoutSimulationManager.pauseSimulation()
        Log.i(TAG, "Workout paused")
    }

    /**
     * Resume the workout
     */
    fun resumeWorkout() {
        _isPaused.value = false
        SimulationManager.resumeSimulation()
        WorkoutSimulationManager.resumeSimulation()
        Log.i(TAG, "Workout resumed")
    }

    /**
     * Stop the workout
     */
    fun stopWorkout() {
        workoutJob?.cancel()
        progressJob?.cancel()
        _screenState.value = WorkoutScreenState.FINISHED
        Log.i(TAG, "Workout stopped")
    }

    override fun onCleared() {
        super.onCleared()
        workoutJob?.cancel()
        progressJob?.cancel()
        countdownJob?.cancel()
    }
}

// ============ VIEW MODEL FACTORY ============

class WorkoutViewModelFactory(
    private val context: Context,
    private val workoutType: String = "",
    private val workoutDuration: Int = 30,
    private val userAge: Int = 25
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkoutViewModel::class.java)) {
            return WorkoutViewModel(context, workoutType, workoutDuration, userAge) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ============ ACTIVITY ============

/**
 * Main WorkoutActivity
 */
class WorkoutActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WorkoutActivity"
        private const val ACTIVITY_NAME = "WorkoutActivity"
    }
    private val viewModel: WorkoutViewModel by viewModels {
        val workoutType = intent.getStringExtra("workout_type") ?: ""
        val workoutDuration = intent.getIntExtra("workout_duration", 30)

        WorkoutViewModelFactory(
            this@WorkoutActivity,
            workoutType,
            workoutDuration,
            25  // Default age
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate - Workout Activity starting")

        // STEP 1: Configure window to show over lock screen
        configureWindowForLockScreen()

        // STEP 2: Register this activity with NavigationCoordinator
        registerActivityWithCoordinator()

        // STEP 3: Log intent extras for debugging
        logIntentExtras()

        // STEP 4: Set up the UI
        setContent {
            WorkoutScreen(viewModel)
        }

        Log.d(TAG, "onCreate - Workout Activity setup complete")
    }
    private fun logIntentExtras() {
        intent?.let { intent ->
            Log.d(TAG, """
                Intent extras:
                - workout_type: ${intent.getStringExtra("workout_type")}
                - workout_duration: ${intent.getIntExtra("workout_duration", -1)}
                - workout_start_time: ${intent.getStringExtra("workout_start_time")}
                - workout_distance: ${intent.getDoubleExtra("workout_distance", 0.0)}
                - workout_avg_heart_rate: ${intent.getDoubleExtra("workout_avg_heart_rate", 0.0)}
            """.trimIndent())
        } ?: Log.w(TAG, "No intent found")
    }
    private fun configureWindowForLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Modern way (API 27+)
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            Log.d(TAG, "Lock screen flags set (modern)")
        } else {
            // Legacy way (pre-API 27)
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            Log.d(TAG, "Lock screen flags set (legacy)")
        }
    }
    private fun registerActivityWithCoordinator() {
        lifecycleScope.launch {
            try {
                // Mark this activity as active
                // Note: canLaunchActivity already marks as active if allowed,
                // but we check here to ensure proper state
                val isAlreadyActive = NavigationCoordinator.isActivityActive(ACTIVITY_NAME)

                if (isAlreadyActive) {
                    Log.w(TAG, "⚠ Activity already registered - possible duplicate launch")
                } else {
                    // Force registration (this handles cases where notification launched it)
                    NavigationCoordinator.canLaunchActivity(ACTIVITY_NAME)
                    Log.d(TAG, "✓ Activity registered with NavigationCoordinator")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering with NavigationCoordinator", e)
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent - Activity brought to foreground with new intent")

        setIntent(intent)

        // Re-register to reset cooldown timer
        lifecycleScope.launch {
            NavigationCoordinator.canLaunchActivity(ACTIVITY_NAME)
        }
    }

    // ADD THIS: Prevent accidental back press during workout
    override fun onBackPressed() {
        super.onBackPressed()
        // Show confirmation dialog or ignore
        Log.d("WorkoutActivity", "Back pressed during workout - ignoring")
        // Optionally, you can show a confirmation dialog here
    }
    override fun onPause() {
        super.onPause()
        Log.d("WorkoutActivity", "onPause - Activity paused but workout continues")
        // Workout continues in background via WorkoutSimulationManager
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - Activity resumed")

        // Verify that simulation is still running
        lifecycleScope.launch {
            val healthState = SimulationManager.simulationState.value
            val workoutState = WorkoutSimulationManager.workoutState.value

            Log.d(TAG, """
                Resume state check:
                - Health simulation running: ${healthState.isRunning}
                - Health simulation paused: ${healthState.isPaused}
                - Workout state: ${workoutState::class.simpleName}
            """.trimIndent())
        }
    }
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop - Activity stopped but still in memory")
    }
    override fun onDestroy() {
        super.onDestroy()

        Log.d(TAG, "onDestroy - Cleaning up Workout Activity")

        // Stop the workout in the ViewModel
        viewModel.stopWorkout()

        // Unregister from NavigationCoordinator
        lifecycleScope.launch {
            try {
                NavigationCoordinator.onActivityClosed(ACTIVITY_NAME)
                Log.d(TAG, "✓ Activity unregistered from NavigationCoordinator")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering from NavigationCoordinator", e)
            }
        }

        Log.d(TAG, "onDestroy - Workout Activity destroyed")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.d(TAG, "onSaveInstanceState - Saving state")

        // Save any critical state here if needed
        outState.putString("workout_type", intent.getStringExtra("workout_type"))
        outState.putInt("workout_duration", intent.getIntExtra("workout_duration", 30))
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        Log.d(TAG, "onRestoreInstanceState - Restoring state")

        // Restore state if needed
    }
}

// ============ COMPOSE UI ============

@Composable
fun WorkoutScreen(viewModel: WorkoutViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val screenState by viewModel.screenState.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        when (screenState) {
            WorkoutScreenState.WAITING -> WaitingScreen()
            WorkoutScreenState.EARLY_START_WAITING -> EarlyStartScreen()
            WorkoutScreenState.COUNTING_DOWN -> CountdownScreen()
            WorkoutScreenState.IN_PROGRESS -> InProgressScreen(uiState, viewModel)
            WorkoutScreenState.FINISHED -> FinishedScreen(uiState)
        }
    }
}

@Composable
private fun WaitingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Get Ready",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = WhiteText
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Workout starts in 5 minutes",
            fontSize = 14.sp,
            color = WhiteText.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun EarlyStartScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "You started early!",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = ovulationPink
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Waiting for official start time...",
            fontSize = 14.sp,
            color = WhiteText
        )
    }
}

@Composable
private fun CountdownScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Workout Starts In",
            fontSize = 16.sp,
            color = WhiteText.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "30",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = iconBlue
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Seconds",
            fontSize = 14.sp,
            color = WhiteText
        )
    }
}

@Composable
private fun InProgressScreen(state: WorkoutUiState, viewModel: WorkoutViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Workout In Progress",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = WhiteText
        )

        MetricCard(
            title = "Heart Rate",
            value = "${state.heartRate}",
            unit = "bpm",
            color = menstrualRed
        )

        MetricCard(
            title = "Average HR",
            value = "${state.avgHeartRate}",
            unit = "bpm",
            color = ovulationPink
        )

        MetricCard(
            title = "Distance",
            value = String.format("%.2f", state.distance),
            unit = "km",
            color = follicleGreen
        )

        MetricCard(
            title = "Active Energy",
            value = String.format("%.1f", state.energy),
            unit = "kcal",
            color = lutealPurple
        )

        MetricCard(
            title = "Heart Zone",
            value = state.heartZone.displayName,
            unit = "",
            color = YellowHZ
        )

        if (state.cycleInfo != null) {
            MetricCard(
                title = "Cycle Phase",
                value = state.cycleInfo.phase.name,
                unit = "Day ${state.cycleInfo.dayOfCycle}",
                color = follicleGreen
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                text = "Pause",
                onClick = { viewModel.pauseWorkout() },
                modifier = Modifier.weight(1f)
            )

            Button(
                text = "Stop",
                onClick = { viewModel.stopWorkout() },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    unit: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = color.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = title,
                fontSize = 12.sp,
                color = WhiteText.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
            ) {
                Text(
                    text = value,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = unit,
                        fontSize = 14.sp,
                        color = WhiteText.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = iconBlue,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = WhiteText
        )
    }
}

@Composable
private fun FinishedScreen(state: WorkoutUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Workout Complete!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = follicleGreen
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Distance: ${String.format("%.2f", state.distance)} km",
            fontSize = 14.sp,
            color = WhiteText
        )
        Text(
            text = "Avg HR: ${state.avgHeartRate} bpm",
            fontSize = 14.sp,
            color = WhiteText
        )
        Text(
            text = "Energy: ${String.format("%.1f", state.energy)} kcal",
            fontSize = 14.sp,
            color = WhiteText
        )
    }
}
