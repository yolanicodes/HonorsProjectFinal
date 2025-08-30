package com.example.honoursproject.presentation

import android.content.Context
import android.os.Bundle
import androidx.compose.ui.graphics.Color
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.example.honoursproject.R
import com.example.honoursproject.dataservices.HealthDatabase
import com.example.honoursproject.dataservices.SimulationManager
import com.example.honoursproject.presentation.theme.*
import com.example.honoursproject.CyclePhase
import com.example.honoursproject.tile.WatchfaceTileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import com.example.honoursproject.presentation.theme.TILE_lutealPurple
import com.example.honoursproject.presentation.theme.TILE_menstrualRed
import com.example.honoursproject.presentation.theme.TILE_follicleGreen
import com.example.honoursproject.presentation.theme.TILE_ovulationPink
import com.example.honoursproject.presentation.theme.GrayInactive

// --- Generic Data and State Classes (Moved to top-level) ---
data class DataPoint(val time: Long, val value: Float)
private fun getCycleColor(phase: CyclePhase): Color {
    val menstrualRed= Color(0xFFFF0000)
    val lutealPurple = Color(0xFF9C27B0)
    val ovulationPink = Color(0xFFff66c4)
    val follicleGreen = Color(0xFF4CAF50)
    return when (phase) {
        CyclePhase.MENSTRUATION -> menstrualRed
        CyclePhase.FOLLICULAR -> follicleGreen
        CyclePhase.OVULATION -> ovulationPink
        CyclePhase.LUTEAL -> lutealPurple
        else ->Color.Gray
    }
}
data class GraphData(
    val dataPoints: List<DataPoint>,
    val minValue: Float,
    val maxValue: Float,
    val latestValue: Float
)

sealed class MetricUiState {
    object Loading : MetricUiState()
    data class Success(val graphData: GraphData, val cycleDay: Int,
                       val cyclePhase: String) : MetricUiState()
    data class Error(val message: String) : MetricUiState()
}

// --- ViewModel ---
class MetricDetailViewModel(
    val metricType: String,
    private val simulationManager: SimulationManager,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<MetricUiState>(MetricUiState.Loading)
    val uiState: StateFlow<MetricUiState> = _uiState.asStateFlow()

    private val dao = HealthDatabase.getInstance(context).healthRecordDao()

    init {
        observeDatabaseAndLiveStream()
    }

    private fun observeDatabaseAndLiveStream() {
        viewModelScope.launch {
            val threeHoursAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(3)

            dao.getRecordsSince(threeHoursAgo).combine(simulationManager.healthDataStream) { entities, liveRecord ->
                val dbData = entities.map { entity ->
                    val value = when (metricType) {
                        WatchfaceTileService.METRIC_HEART_RATE -> entity.heartRate.toFloat()
                        WatchfaceTileService.METRIC_STEPS -> entity.stepCount.toFloat()
                        WatchfaceTileService.METRIC_ENERGY -> entity.activeEnergyBurned.toFloat()
                        else -> 0f
                    }
                    DataPoint(time = entity.timestamp, value = value)
                }

                val latestLive = simulationManager.healthDataStream.value
                val liveValue = when (metricType) {
                    WatchfaceTileService.METRIC_HEART_RATE -> latestLive.heartRate.toFloat()
                    WatchfaceTileService.METRIC_STEPS -> latestLive.stepCount.toFloat()
                    WatchfaceTileService.METRIC_ENERGY -> latestLive.activeEnergyBurned.toFloat()
                    else -> 0f
                }

                dbData + DataPoint(time = System.currentTimeMillis(), value = liveValue)

            }.collect{mergedData->
                val minVal = mergedData.minOfOrNull { dp -> dp.value } ?: 0f
                val maxVal = mergedData.maxOfOrNull { dp -> dp.value } ?: 0f
                val latestVal = mergedData.lastOrNull()?.value ?: 0f
                val latestHealthRecord = simulationManager.healthDataStream.value
                val cycleDay = latestHealthRecord.cycleDay // Get cycle day
                val cyclePhase = latestHealthRecord.cyclePhase
                val graphData = GraphData(mergedData, minVal, maxVal, latestVal)
                _uiState.value = MetricUiState.Success(graphData,cycleDay,cyclePhase)
            }
        }
    }
}

// --- Activity ---
class MetricDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metricType = intent?.getStringExtra(WatchfaceTileService.EXTRA_METRIC_TYPE) ?: WatchfaceTileService.METRIC_HEART_RATE
        val iconResId = intent?.getIntExtra("iconResId", R.drawable.heart_icon) ?: R.drawable.heart_icon // Get the icon ID
        Log.d("MetricDetailActivity", "Launched from tile with metricType = $metricType")

        val viewModel by viewModels<MetricDetailViewModel> {
            MetricDetailViewModelFactory(metricType, this.applicationContext)
        }
        setContent {
            val uiState = viewModel.uiState.collectAsState().value
            MetricDetailScreen(
                iconResId = iconResId,
                state = uiState
            )
        }
    }
}

// --- Composable Screens (Now Dynamic) ---
@Composable
fun MetricDetailScreen(iconResId: Int, state: MetricUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        // Header
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(35.dp).background(iconBlue, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = iconResId),
                    contentDescription = "Metric Icon",
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(WhiteText)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Graph Area
        // Graph Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                is MetricUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                }
                is MetricUiState.Error -> Text(state.message, color = menstrualRed)
                is MetricUiState.Success -> {
                    val graphData = state.graphData
                    val timeLabel = if (graphData.dataPoints.isNotEmpty()) {
                        val formatter = DateTimeFormatter.ofPattern("HH:mm")
                        val endTime = Instant.ofEpochMilli(graphData.dataPoints.last().time).atZone(ZoneId.systemDefault()).toLocalTime()
                        val startTime = endTime.minusHours(2)
                        "${startTime.format(formatter)} - ${endTime.format(formatter)}"
                    } else "Last 2 Hours"

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LineGraph(
                            data = graphData.dataPoints,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            minValue = graphData.minValue,
                            maxValue = graphData.maxValue,
                            latestValue = graphData.latestValue
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = timeLabel, color = WhiteText, fontSize = 12.sp)
                    }
                }
            }
        }

        // Cycle Information Row (NEW)
        if (state is MetricUiState.Success) {
            val cyclePhaseEnum = try {
                CyclePhase.valueOf(state.cyclePhase.uppercase())
            } catch (e: IllegalArgumentException) {
                CyclePhase.UNKNOWN
            }
            val cycleColor = getCycleColor(cyclePhaseEnum)

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.cycle_icon),
                    contentDescription = "Cycle Icon",
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(cycleColor)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Day ${state.cycleDay}",
                    color = cycleColor,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// --- Generic Graph Composable ---
@Composable
fun LineGraph(
    modifier: Modifier = Modifier,
    data: List<DataPoint>,
    minValue: Float,
    maxValue: Float,
    latestValue: Float,
    lineColor: Color = WhiteText,
    gridColor: Color = Color.White.copy(alpha = 0.2f)
) {
    if (data.size < 2) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Not enough data to display graph.", color = WhiteText, textAlign = TextAlign.Center)
        }
        return
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val yRange = (maxValue - minValue).takeIf { it > 0 } ?: 1f

            // Draw grid lines
            (0..4).forEach { i ->
                val y = canvasHeight * i / 4
                drawLine(gridColor, Offset(0f, y), Offset(canvasWidth, y), strokeWidth = 1.dp.toPx())
            }

            // Function to normalize data points
            fun getPoint(dataPoint: DataPoint, index: Int): Offset {
                val x = canvasWidth * index / (data.size - 1)
                val y = canvasHeight * (1 - (dataPoint.value - minValue) / yRange)
                return Offset(x, y)
            }

            val linePath = Path()
            val fillPath = Path()
            val firstPoint = getPoint(data.first(), 0)
            linePath.moveTo(firstPoint.x, firstPoint.y)
            fillPath.moveTo(0f, canvasHeight)
            fillPath.lineTo(firstPoint.x, firstPoint.y)

            for (i in 1 until data.size) {
                val point = getPoint(data[i], i)
                linePath.lineTo(point.x, point.y)
                fillPath.lineTo(point.x, point.y)
            }

            val lastPointX = getPoint(data.last(), data.size - 1).x
            fillPath.lineTo(lastPointX, canvasHeight)
            fillPath.close()

            // Draw gradient fill and line
            drawPath(fillPath, brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.5f), Color.Transparent)))
            drawPath(linePath, color = lineColor, style = Stroke(width = 2.dp.toPx()))

            // Draw latest point indicator
            val latestPoint = getPoint(data.last(), data.size - 1)
            drawLine(Color.White, Offset(latestPoint.x, latestPoint.y), Offset(latestPoint.x, canvasHeight), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            drawCircle(Color.White, radius = 4.dp.toPx(), center = latestPoint)
        }

        Text(
            text = "%.0f".format(latestValue),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp)
        )
    }
}

// --- Previews ---
@Preview(device = "id:wearos_large_round", showSystemUi = true)
@Composable
fun HeartRateDetailPreview() {
    val sampleDataPoints = (0..60).map { i ->
        DataPoint(System.currentTimeMillis() - (60 - i) * 120 * 1000, (60 + (sin(i * 0.2) * 20)).toFloat())
    }
    val sampleGraphData = GraphData(
        dataPoints = sampleDataPoints,
        minValue = sampleDataPoints.minOf { it.value },
        maxValue = sampleDataPoints.maxOf { it.value },
        latestValue = sampleDataPoints.last().value
    )
    val cycleDay=7
    val cyclePhase="Follicular"
    MetricDetailScreen(
        state = MetricUiState.Success(sampleGraphData,cycleDay,cyclePhase),
        iconResId = R.drawable.heart_icon
    )
}

@Preview(device = "id:wearos_large_round", showSystemUi = true)
@Composable
fun StepsDetailPreview() {
    val sampleDataPoints = (0..60).map { i ->
        DataPoint(System.currentTimeMillis() - (60 - i) * 120 * 1000, (1000 + i * 50).toFloat())
    }
    val sampleGraphData = GraphData(
        dataPoints = sampleDataPoints,
        minValue = sampleDataPoints.minOf { it.value },
        maxValue = sampleDataPoints.maxOf { it.value },
        latestValue = sampleDataPoints.last().value
    )
    val cycleDay=7
    val cyclePhase="Follicular"
    MetricDetailScreen(
        state = MetricUiState.Success(sampleGraphData,cycleDay,cyclePhase),
        iconResId = R.drawable.heart_icon
    )
}