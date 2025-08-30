// In presentation/WorkoutActivity.kt

package com.example.honoursproject.presentation

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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.honoursproject.CyclePhase
import com.example.honoursproject.R
import com.example.honoursproject.dataservices.*
import com.example.honoursproject.presentation.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt


// --- ViewModel Factory ---
class WorkoutViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkoutViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            // Using context.applicationContext is a good practice to prevent memory leaks
            return WorkoutViewModel(context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// --- ViewModel ---
class WorkoutViewModel(context: Context) : ViewModel() {
    private val _isCountingDown = MutableStateFlow(true)
    val isCountingDown = _isCountingDown.asStateFlow()

    private val _countdownValue = MutableStateFlow(3)
    val countdownValue = _countdownValue.asStateFlow()

    private val _elapsedTime = MutableStateFlow(0L)
    val elapsedTime = _elapsedTime.asStateFlow()

    // Live workout data from SimulationManager
    private val _heartRate = MutableStateFlow(0)
    val heartRate = _heartRate.asStateFlow()

    private val _activeEnergy = MutableStateFlow(0.0)
    val activeEnergy = _activeEnergy.asStateFlow()

    private val _cycleInfo = MutableStateFlow(Pair(CyclePhase.UNKNOWN, 0))
    val cycleInfo = _cycleInfo.asStateFlow()

    // Heart Rate Zone data
    private val _currentHeartZone = MutableStateFlow(0)
    val currentHeartZone = _currentHeartZone.asStateFlow()

    private val _zoneDistribution = MutableStateFlow(mapOf<Int, Long>())
    val zoneDistribution = _zoneDistribution.asStateFlow()
    private var zoneTimestamps = mutableMapOf<Int, Long>()

    // Workout State from WorkoutSimulationManager
    private val _workoutState = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val workoutState = _workoutState.asStateFlow()

    private var maxHeartRate: Int = 200 // Default
    private var timerJob: Job? = null

    init {
        // Start other processes
        startCountdown()

        // Launch a coroutine to fetch data
        viewModelScope.launch {
            // Fetch the user's age from DataStore
            val age = context.setupDataStore.data
                .map { preferences ->
                    // Use the key to get the age, provide a default of 25 if not set
                    preferences[SetupPreferences.AGE] ?: 25
                }
                .first() // We only need the first value

            // Calculate max heart rate using the fetched age
            maxHeartRate = 220 - age

            // Now, start collecting from the health stream
            SimulationManager.healthDataStream.collect { record ->
                _heartRate.value = record.heartRate.roundToInt()
                _activeEnergy.value = record.activeEnergyBurned
                _cycleInfo.value = Pair(parseCyclePhase(record.cyclePhase), record.cycleDay)

                // The zone calculation will now use the correct maxHeartRate
                val zone = calculateHeartRateZone(_heartRate.value)
                _currentHeartZone.value = zone
                updateZoneDistribution(zone)
            }
        }

        // Collect workout state from WorkoutSimulationManager
        viewModelScope.launch {
            WorkoutSimulationManager.workoutState.collect { state ->
                _workoutState.value = state
                if (state is WorkoutState.Finished) {
                    timerJob?.cancel()
                }
            }
        }
    }

    private fun parseCyclePhase(phaseStr: String?): CyclePhase {
        return when (phaseStr?.lowercase()) {
            "menstruation", "menstrual" -> CyclePhase.MENSTRUATION
            "follicular" -> CyclePhase.FOLLICULAR
            "ovulation", "ovulatory" -> CyclePhase.OVULATION
            "luteal" -> CyclePhase.LUTEAL
            else -> CyclePhase.UNKNOWN
        }
    }

    private fun updateZoneDistribution(currentZone: Int) {
        if (currentZone in 1..5) {
            zoneTimestamps[currentZone] = (zoneTimestamps[currentZone] ?: 0L) + 1
            _zoneDistribution.value = zoneTimestamps.toMap()
        }
    }

    private fun calculateHeartRateZone(hr: Int): Int {
        return when ((hr.toDouble() / maxHeartRate) * 100) {
            in 90.0..Double.MAX_VALUE -> 5
            in 80.0..89.9 -> 4
            in 70.0..79.9 -> 3
            in 60.0..69.9 -> 2
            in 50.0..59.9 -> 1
            else -> 0
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
                _elapsedTime.value++
            }
        }
    }
}


// --- Activity ---
class WorkoutActivity : ComponentActivity() {
    private val viewModel: WorkoutViewModel by viewModels { WorkoutViewModelFactory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val workoutType = intent.getStringExtra("workout_type") ?: "run"

        setContent {
            HonoursProjectTheme {
                val isCountingDown by viewModel.isCountingDown.collectAsState()
                val workoutState by viewModel.workoutState.collectAsState()

                when {
                    isCountingDown -> {
                        val countdownValue by viewModel.countdownValue.collectAsState()
                        CountdownScreen(countdownValue)
                    }
                    workoutState is WorkoutState.Finished -> {
                        val summary = (workoutState as WorkoutState.Finished).summary
                        val zoneDistribution by viewModel.zoneDistribution.collectAsState()
                        WorkoutSummaryScreen(summary = summary, zoneDistribution = zoneDistribution) {
                            finish() // Close activity on dismiss
                        }
                    }
                    else -> {
                        // Live workout screen
                        val elapsedTime by viewModel.elapsedTime.collectAsState()
                        val heartRate by viewModel.heartRate.collectAsState()
                        val activeEnergy by viewModel.activeEnergy.collectAsState()
                        val cycleInfo by viewModel.cycleInfo.collectAsState()
                        val currentZone by viewModel.currentHeartZone.collectAsState()
                        WorkoutScreen(
                            elapsedTime = elapsedTime,
                            heartRate = heartRate,
                            energy = activeEnergy.roundToInt(),
                            cyclePhase = cycleInfo.first,
                            cycleDay = cycleInfo.second,
                            workoutType = workoutType,
                            currentZone = currentZone
                        )
                    }
                }
            }
        }
    }
}

// --- Composable Screens ---

@Composable
fun CountdownScreen(countdownValue: Int) {
    Box(modifier = Modifier.fillMaxSize().background(backgroundBlue), contentAlignment = Alignment.Center) {
        val animatedSize by animateFloatAsState(targetValue = 100f, animationSpec = tween(durationMillis = 900))
        if (countdownValue > 0) {
            Text(text = countdownValue.toString(), fontSize = animatedSize.sp, color = WhiteText, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun WorkoutScreen(
    elapsedTime: Long, heartRate: Int, energy: Int,
    cyclePhase: CyclePhase, cycleDay: Int, workoutType: String, currentZone: Int
) {
    val cyclePhaseColor = when (cyclePhase) {
        CyclePhase.FOLLICULAR -> follicleGreen
        CyclePhase.OVULATION -> ovulationPink
        CyclePhase.LUTEAL -> lutealPurple
        CyclePhase.MENSTRUATION -> menstrualRed
        else -> Color.Gray
    }

    Box(modifier = Modifier.fillMaxSize().background(backgroundBlue), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround,
            modifier = Modifier.fillMaxSize().padding(vertical = 16.dp)
        ) {
            // Top: Timer and Icon
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = if (workoutType == "walk") R.drawable.steps_icon else R.drawable.run_icon
                Image(painterResource(id = icon), contentDescription = null, modifier = Modifier.size(28.dp), colorFilter = ColorFilter.tint(WhiteText))
                Spacer(Modifier.width(8.dp))
                Text(formatElapsedTime(elapsedTime), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = WhiteText)
            }

            // Middle: HR and Zones
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HeartRateZones(currentZone)
                Spacer(Modifier.height(8.dp))
                MetricRow(iconRes = R.drawable.heart_icon, label = "BPM", value = heartRate.toString(), valueColor = menstrualRed, iconTint = menstrualRed)
            }

            // Bottom: Energy and Cycle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                MetricColumn(label = "KCAL", value = energy.toString())
                Spacer(Modifier.width(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(id = R.drawable.cycle_icon), contentDescription = null, modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(cyclePhaseColor))
                    Spacer(Modifier.width(4.dp))
                    Text(text = cycleDay.toString(), fontSize = 18.sp, color = cyclePhaseColor, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun WorkoutSummaryScreen(summary: WorkoutSummary, zoneDistribution: Map<Int, Long>, onDismiss: () -> Unit) {
    var showGraph by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(backgroundBlue).clickable { if (showGraph) showGraph = false else onDismiss() }, contentAlignment = Alignment.Center) {
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
                    MetricColumn("TIME", formatElapsedTime(summary.details.duration.toLong() * 60))
                    MetricColumn("KCAL", summary.details.activeEnergy.roundToInt().toString())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MetricColumn("KM", String.format("%.2f", summary.details.distance))
                    MetricColumn("AVG HR", summary.details.avgHeartRate.roundToInt().toString())
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showGraph = true }) {
                    Image(painterResource(id = R.drawable.heart_icon), contentDescription = "View Heart Zones", modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(menstrualRed))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("View Zones", color = WhiteText, fontSize = 12.sp)
                }
            }
            Text("Tap to dismiss", color = Color.Gray, fontSize = 10.sp)
        }
    }
}

// --- Reusable Composables ---

@Composable
fun HeartRateZones(currentZone: Int) {
    val zoneColors = listOf(darkBlueHZ, CyanHZ, YellowHZ, OrangeHZ, maroonHZ)
    Row {
        (1..5).forEach { zone ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (zone == currentZone) zoneColors[zone - 1] else Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                if(zone == currentZone) {
                    Image(painterResource(id = R.drawable.heart_icon), contentDescription = null, modifier = Modifier.size(14.dp), colorFilter = ColorFilter.tint(Color.Black))
                }
            }
        }
    }
}

@Composable
fun HeartZoneGraph(zoneDistribution: Map<Int, Long>) {
    val totalTime = zoneDistribution.values.sum().toFloat().coerceAtLeast(1f)
    val zoneColors = listOf(darkBlueHZ, CyanHZ, YellowHZ, OrangeHZ, maroonHZ)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text("Time In Zones", color = WhiteText, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
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


// Other composables (MetricRow, MetricColumn, formatElapsedTime) remain the same as your provided code.
@Composable
fun MetricRow( iconRes: Int? = null, label: String, value: String, valueColor: Color = WhiteText, iconTint: Color = WhiteText ) {
    Row( verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(vertical=4.dp)) {
        if (iconRes != null) {
            Image( painter = painterResource(id = iconRes), contentDescription = label, modifier = Modifier.size(20.dp), colorFilter = ColorFilter.tint(iconTint))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = value, fontSize = 24.sp, color = valueColor, fontWeight = FontWeight.SemiBold)
        Text(text = " $label", fontSize = 12.sp, color = WhiteText, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun MetricColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 20.sp, color = WhiteText, fontWeight = FontWeight.SemiBold)
        Text(text = label, fontSize = 12.sp, color = WhiteText)
    }
}

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
fun WorkoutScreenPreview() {
    WorkoutScreen(elapsedTime = 1523, heartRate = 148, energy = 112, cyclePhase = CyclePhase.OVULATION, cycleDay = 14, workoutType = "run", currentZone = 3)
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
fun WorkoutSummaryPreview() {
    val details = WorkoutDetails("Running", LocalDateTime.now(), LocalDateTime.now(), 25.5, 5.0, 155.0, 300.0, 50.0)
    val summary = WorkoutSummary(details, "Luteal", 22)
    val zones = mapOf(1 to 120L, 2 to 300L, 3 to 900L, 4 to 180L, 5 to 30L)
    WorkoutSummaryScreen(summary = summary, zoneDistribution = zones, onDismiss = {})
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
fun HeartZoneGraphPreview() {
    val zones = mapOf(1 to 120L, 2 to 300L, 3 to 900L, 4 to 180L, 5 to 30L)
    Box(modifier=Modifier.fillMaxSize().background(backgroundBlue)) {
        HeartZoneGraph(zoneDistribution = zones)
    }
}