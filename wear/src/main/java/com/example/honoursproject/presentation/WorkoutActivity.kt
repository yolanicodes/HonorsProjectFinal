/*package com.example.honoursproject.presentation

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material.Text
import com.example.honoursproject.R
import com.example.honoursproject.dataservices.SimulationManager
import com.example.honoursproject.presentation.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.TimeUnit

// --- Data Classes and Enums ---
enum class CyclePhase {
    MENSTRUATION, FOLLICULAR, OVULATION, LUTEAL
}

data class MenstrualCycleInfo(val phase: CyclePhase, val dayOfCycle: Int)

// --- ViewModel for State Management ---
class WorkoutViewModel(private val simulationManager: SecurityManager,private val context: Context) : ViewModel() {

    // --- UI State (Countdown, Timer, Pause) ---
    private val _isCountingDown = MutableStateFlow(true)
    val isCountingDown: StateFlow<Boolean> = _isCountingDown.asStateFlow()

    private val _countdownValue = MutableStateFlow(3)
    val countdownValue: StateFlow<Int> = _countdownValue.asStateFlow()

    private val _elapsedTime = MutableStateFlow(0L)
    val elapsedTime: StateFlow<Long> = _elapsedTime.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // --- Workout Data ---
    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private val _energy = MutableStateFlow(0)
    val energy: StateFlow<Int> = _energy.asStateFlow()

    private val _distance = MutableStateFlow(0.0)
    val distance: StateFlow<Double> = _distance.asStateFlow()

    private val _avgHeartRate = MutableStateFlow(0)
    val avgHeartRate: StateFlow<Int> = _avgHeartRate.asStateFlow()

    private var totalHeartRate = 0
    private var heartRateSamples = 0
    private var workoutJob: Job? = null
    val cycleInfo = getMenstrualCycleInfo()

    init {
        startCountdown()
        startCsvDataStream()
    }

    private fun startCsvDataStream() {
        viewModelScope.launch {
            // Start streaming CSV via SimulationManager
            launch {
                simulationManager.startStreaming(context, "P102-Health_Data_Processed.csv")
            }

            // Collect live data from SimulationManager
            simulationManager.healthDataStream.collect { record ->
                if (!_isPaused.value) {
                    _heartRate.value = record.heartRate.toInt()
                    _energy.value = record.activeEnergyBurned.toInt()

                    totalHeartRate += _heartRate.value
                    heartRateSamples++
                    _avgHeartRate.value = if (heartRateSamples > 0) totalHeartRate / heartRateSamples else 0
                }
            }
        }
    }

    private fun startCountdown() {
        viewModelScope.launch {
            while (_countdownValue.value > 0) {
                delay(1000)
                _countdownValue.value--
            }
            delay(1000)
            _isCountingDown.value = false
            startWorkout()
        }
    }

    private fun startWorkout() {
        workoutJob = viewModelScope.launch {
            while (true) {
                if (!_isPaused.value) {
                    delay(1000)
                    _elapsedTime.value++
                    // Only update distance here. HR and Energy come from the CSV stream.
                    _distance.value += 0.0015
                } else {
                    delay(100)
                }
            }
        }
    }

    fun pauseOrResumeWorkout() {
        _isPaused.value = !_isPaused.value
    }

    fun stopWorkout() {
        workoutJob?.cancel()
    }

    private fun getMenstrualCycleInfo(): MenstrualCycleInfo {
        val dayOfYear = LocalDate.now().dayOfYear
        val cycleDay = (dayOfYear % 28) + 1

        val phase = when {
            cycleDay <= 5 -> CyclePhase.MENSTRUATION
            cycleDay <= 13 -> CyclePhase.FOLLICULAR
            cycleDay <= 15 -> CyclePhase.OVULATION
            else -> CyclePhase.LUTEAL
        }
        return MenstrualCycleInfo(phase, cycleDay)
    }
}


// --- Activity ---

class WorkoutActivity : ComponentActivity() {

    private val viewModel: WorkoutViewModel by viewModels {
        ViewModelFactory(this,this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val workoutType = intent.getStringExtra("workout_type") ?: "run"

        setContent {
            val isCountingDown by viewModel.isCountingDown.collectAsState()

            if (isCountingDown) {
                val countdownValue by viewModel.countdownValue.collectAsState()
                CountdownScreen(countdownValue)
            } else {
                // Collect all states from the ViewModel
                val elapsedTime by viewModel.elapsedTime.collectAsState()
                val heartRate by viewModel.heartRate.collectAsState()
                val distance by viewModel.distance.collectAsState()
                val energy by viewModel.energy.collectAsState()
                val avgHeartRate by viewModel.avgHeartRate.collectAsState()
                val isPaused by viewModel.isPaused.collectAsState()

                // Pass the state values directly to the Composable
                WorkoutScreen(
                    elapsedTime = elapsedTime,
                    heartRate = heartRate,
                    distance = distance,
                    energy = energy,
                    avgHeartRate = avgHeartRate,
                    cycleInfo = viewModel.cycleInfo,
                    workoutType = workoutType,
                    isPaused = isPaused,
                    onPauseOrResumeClick = { viewModel.pauseOrResumeWorkout() },
                    onStopClick = {
                        viewModel.stopWorkout()
                        finish() // End the activity
                    }
                )
            }
        }
    }
}

// --- Composable Screens ---

@Composable
fun CountdownScreen(countdownValue: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        val animatedSize by animateFloatAsState(
            targetValue = if (countdownValue > 0) 100f else 0f,
            animationSpec = tween(durationMillis = 900)
        )
        if (countdownValue > 0) {
            Text(
                text = countdownValue.toString(),
                fontSize = animatedSize.sp,
                color = WhiteText,
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
    avgHeartRate: Int,
    cycleInfo: MenstrualCycleInfo,
    workoutType: String,
    isPaused: Boolean,
    onPauseOrResumeClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val cyclePhaseColor = when (cycleInfo.phase) {
        CyclePhase.FOLLICULAR -> follicleGreen
        CyclePhase.OVULATION -> ovulationPink
        CyclePhase.LUTEAL -> lutealPurple
        CyclePhase.MENSTRUATION -> menstrualRed
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            // Top Section: Workout Duration and Icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                val icon = if (workoutType.equals("walk", ignoreCase = true)) {
                    R.drawable.steps_icon
                } else {
                    R.drawable.run_icon
                }
                Image(
                    painter = painterResource(id = icon),
                    contentDescription = "$workoutType icon",
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(WhiteText)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = formatElapsedTime(elapsedTime),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = WhiteText
                )
            }

            // Middle Section: Metrics
            MetricRow(
                iconRes = R.drawable.heart_icon,
                label = "BPM",
                value = heartRate.toString(),
                valueColor = menstrualRed,
                iconTint = menstrualRed,
                valueFontSize = 18.sp,
                labelFontSize = 11.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricColumn(label = "KM", value = String.format("%.2f", distance))
                MetricColumn(label = "KCAL", value = energy.toString())
            }

            MetricRow(
                iconRes = R.drawable.heart_icon,
                label = "avg/hr",
                value = avgHeartRate.toString(),
                iconTint = menstrualRed,
                valueFontSize = 16.sp,
                labelFontSize = 11.sp
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(id = R.drawable.cycle_icon),
                    contentDescription = "Cycle Day",
                    modifier = Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(cyclePhaseColor)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = cycleInfo.dayOfCycle.toString(),
                    fontSize = 12.sp,
                    color = cyclePhaseColor,
                    fontWeight = FontWeight.Bold
                )
            }

            // Bottom Section: Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val pauseIcon = if (isPaused) R.drawable.play_icon else R.drawable.pause_icon
                ControlButton(iconRes = pauseIcon, onClick = onPauseOrResumeClick)
                Spacer(modifier = Modifier.width(24.dp))
                ControlButton(iconRes = R.drawable.stop_icon, onClick = onStopClick, backgroundColor = menstrualRed)
            }
        }
    }
}

@Composable
fun ControlButton(iconRes: Int, onClick: () -> Unit, backgroundColor: Color = iconBlue) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = "Control Button",
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(WhiteText)
        )
    }
}


@Composable
fun MetricRow(
    iconRes: Int? = null,
    label: String,
    value: String,
    valueColor: Color = WhiteText,
    iconTint: Color = WhiteText,
    valueFontSize: androidx.compose.ui.unit.TextUnit = 22.sp,
    labelFontSize: androidx.compose.ui.unit.TextUnit = 12.sp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                modifier = Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(iconTint)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = value, fontSize = valueFontSize, color = valueColor, fontWeight = FontWeight.SemiBold)
        Text(text = " " + label, fontSize = labelFontSize, color = WhiteText, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun MetricColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 16.sp, color = WhiteText, fontWeight = FontWeight.SemiBold)
        Text(text = label, fontSize = 10.sp, color = WhiteText)
    }
}

// --- Utility Function ---
fun formatElapsedTime(seconds: Long): String {
    val hours = TimeUnit.SECONDS.toHours(seconds)
    val minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60
    val secs = seconds % 60
    if (hours > 0) {
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
    return String.format("%02d:%02d", minutes, secs)
}


// --- Previews ---

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
fun CountdownPreview() {
    CountdownScreen(countdownValue = 3)
}

@Preview(device = "id:wearos_large_round", showSystemUi = true)
@Composable
fun ActivityWorkoutScreenPreview() {
    WorkoutScreen(
        elapsedTime = 3661L,
        heartRate = 155,
        distance = 5.23,
        energy = 350,
        avgHeartRate = 145,
        cycleInfo = MenstrualCycleInfo(phase = CyclePhase.OVULATION, dayOfCycle = 14),
        workoutType = "run",
        isPaused = false,
        onPauseOrResumeClick = {},
        onStopClick = {}
    )
}

@Preview(device = "id:wearos_large_round", showSystemUi = true)
@Composable
fun PausedWorkoutScreenPreview() {
    WorkoutScreen(
        elapsedTime = 120L,
        heartRate = 130,
        distance = 0.30,
        energy = 20,
        avgHeartRate = 125,
        cycleInfo = MenstrualCycleInfo(phase = CyclePhase.LUTEAL, dayOfCycle = 22),
        workoutType = "walk",
        isPaused = true,
        onPauseOrResumeClick = {},
        onStopClick = {}
    )
}*/