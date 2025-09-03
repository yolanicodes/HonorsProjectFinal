package com.example.honoursproject.presentation

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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


// Helper function to get cycle phase color
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

// --- ViewModel ---
class WorkoutViewModel(
    private val context: Context,
    private val workoutType: String,
    private val workoutEndTime: LocalDateTime,
    private val userAge: Int
) : ViewModel() {
    private val _isCountingDown = MutableStateFlow(true)
    val isCountingDown = _isCountingDown.asStateFlow()

    private val _countdownValue = MutableStateFlow(3)
    val countdownValue = _countdownValue.asStateFlow()

    private val _elapsedTime = MutableStateFlow(0L)
    val elapsedTime = _elapsedTime.asStateFlow()

    private val _zoneDistribution = MutableStateFlow(mapOf<Int, Long>())
    val zoneDistribution = _zoneDistribution.asStateFlow()
    private var zoneTimestamps = mutableMapOf<Int, Long>()

    private val _uiState = MutableStateFlow(WorkoutMetrics())
    val uiState: StateFlow<WorkoutMetrics> = _uiState.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isStopped = MutableStateFlow(false)
    val isStopped: StateFlow<Boolean> = _isStopped.asStateFlow()
    var heartRateHistory = mutableListOf<Int>()
    var startTime: LocalDateTime? = null
    private var pausedDuration = 0L

    private var workoutJob: Job? = null
    private var timerJob: Job? = null
    private var initialStepCount = 0
    private var initialActiveEnergy = 0.0
    private var initialBasalEnergy = 0.0
    private var isFirstDataPoint = true

    init {
        startCountdown()
        startWorkout()
    }

    private fun updateZoneDistribution(currentZone: Int) {
        if (currentZone in 1..5) {
            zoneTimestamps[currentZone] = (zoneTimestamps[currentZone] ?: 0L) + 1
            _zoneDistribution.value = zoneTimestamps.toMap()
        }
    }

    private fun calculateHeartRateZone(hr: Double, userAge: Int): HeartRateZone {
        val maxHeartRate = SimulationManager.MAX_HEART_RATE - userAge
        val percentage = hr / maxHeartRate
        return when {
            percentage >= 0.9 -> HeartRateZone.ZONE_5
            percentage >= 0.8 -> HeartRateZone.ZONE_4
            percentage >= 0.7 -> HeartRateZone.ZONE_3
            percentage >= 0.6 -> HeartRateZone.ZONE_2
            else -> HeartRateZone.ZONE_1
        }
    }

    private fun startCountdown() {
        viewModelScope.launch {
            for (i in 3 downTo 1) {
                _countdownValue.value = i
                delay(1000)
            }
            _isCountingDown.value = false
            startTimer()
        }
    }

    private fun startTimer() {
        timerJob = viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                delay(1000)
                if (!_isPaused.value) {
                    _elapsedTime.value++
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
                    val newElapsedTime = ChronoUnit.SECONDS.between(startTime, LocalDateTime.now()) - pausedDuration

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
                        elapsedTime = newElapsedTime,
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
        private val userAge: Int
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(WorkoutViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return WorkoutViewModel(context, workoutType, workoutEndTime, userAge) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

// --- Activity ---
class WorkoutActivity : ComponentActivity() {

    private val viewModel: WorkoutViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WorkoutViewModel(applicationContext, "run", LocalDateTime.now(), 25) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val userAge = SimulationManager.getUserAge(applicationContext)
            val factory = WorkoutViewModel.Factory(
                context = applicationContext,
                workoutType = intent.getStringExtra("workoutType") ?: "run",
                workoutEndTime = intent.getSerializableExtra("workoutEndTime") as? LocalDateTime ?: LocalDateTime.now().plusHours(1),
                userAge = userAge
            )
            val viewModel: WorkoutViewModel by viewModels { factory }

            setContent {
                HonoursProjectTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    val isPaused by viewModel.isPaused.collectAsState()
                    val isStopped by viewModel.isStopped.collectAsState()
                    val zoneDistribution by viewModel.zoneDistribution.collectAsState()
                    val navController = rememberNavController()

                    LaunchedEffect(isStopped) {
                        if (isStopped) {
                            navController.navigate("summary") {
                                popUpTo("workout") { inclusive = true }
                            }
                        }
                    }

                    NavHost(navController = navController, startDestination = "workout") {
                        composable("workout") {
                            WorkoutScreen(
                                elapsedTime = uiState.elapsedTime,
                                heartRate = uiState.heartRate,
                                distance = uiState.distance,
                                energy = uiState.energy,
                                cycleInfo = uiState.cycleInfo,
                                workoutType = intent.getStringExtra("workoutType") ?: "run",
                                isPaused = isPaused,
                                onPauseOrResumeClick = { viewModel.pauseOrResumeWorkout() },
                                onStopClick = { viewModel.stopWorkout() },
                                currentZone = uiState.heartZone
                            )
                        }
                        composable("summary") {
                            WorkoutSummaryScreen(
                                summary = WorkoutSummary(
                                    details = WorkoutDetails(
                                        workoutType = intent.getStringExtra("workoutType") ?: "run",
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
                                ),
                                zoneDistribution = zoneDistribution,
                                onDismiss = { finish() }
                            )
                        }
                    }
                }
            }
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
    currentZone: HeartRateZone
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
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = WhiteText
                )
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp) // Slightly reduced padding
            ) {
                MetricColumn(label = "KCAL", value = energy.toString())
                MetricColumn(label = "KM", value = String.format("%.2f", distance))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(id = R.drawable.cycle_icon),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp), // Slightly reduced icon size
                        colorFilter = ColorFilter.tint(getCyclePhaseColor(cycleInfo.phase))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = cycleInfo.dayOfCycle.toString(),
                        fontSize = 14.sp, // Slightly reduced font size
                        color = getCyclePhaseColor(cycleInfo.phase),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(16.dp)) // Increased spacing between buttons and metrics
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
                Spacer(Modifier.width(24.dp)) // Increased spacing between buttons
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
            Text("Workout Summary", style = MaterialTheme.typography.title3, color = WhiteText)

            if (showGraph) {
                HeartZoneGraph(zoneDistribution = zoneDistribution)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MetricColumn(
                        "TIME",
                        formatElapsedTime(summary.details.duration.toLong() * 60)
                    )
                    MetricColumn("KCAL", summary.details.activeEnergy.roundToInt().toString())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MetricColumn("KM", String.format("%.2f", summary.details.distance))
                    MetricColumn("AVG HR", summary.details.avgHeartRate.roundToInt().toString())
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showGraph = true }) {
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
                val percentage = zoneTime / totalTime
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .fillMaxHeight(percentage)
                            .background(zoneColors[zone - 1])
                    )
                    Text(zone.toString(), color = WhiteText, fontSize = 10.sp)
                }
            }
        }
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
fun WorkoutScreenPreview() {
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
        onStopClick = {}
    )
}