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
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.example.honoursproject.R
import com.example.honoursproject.dataservices.*
import com.example.honoursproject.presentation.theme.*
import com.example.honoursproject.CyclePhase
import com.example.honoursproject.tile.WatchfaceTileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

// Data classes
data class DataPoint(val time: Long, val value: Float)
data class TimeRange(val start: LocalDateTime, val end: LocalDateTime)
data class ZoomLevel(val name: String, val hours: Long, val displayFormat: String)

// Enhanced UI State
sealed class MetricUiState {
    object Loading : MetricUiState()
    data class Success(
        val graphData: List<DataPoint>,
        val currentRange: TimeRange,
        val zoomLevel: ZoomLevel,
        val cycleDay: Int,
        val cyclePhase: String,
        val minValue: Float,
        val maxValue: Float,
        val latestValue: Float
    ) : MetricUiState()
    data class Error(val message: String) : MetricUiState()
}

// Enhanced ViewModel for Evaluation Mode
class MetricDetailViewModel(
    val metricType: String,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<MetricUiState>(MetricUiState.Loading)
    val uiState: StateFlow<MetricUiState> = _uiState.asStateFlow()

    private val dao = HealthDatabase.getInstance(context).healthRecordDao()
    private val archiveDao = ArchiveDatabase.getInstance(context).archiveRecordDao()

    // Zoom levels with scrollable functionality
    private val zoomLevels = listOf(
        ZoomLevel("2H", 2, "HH:mm"),
        ZoomLevel("6H", 6, "HH:mm"),
        ZoomLevel("12H", 12, "HH:mm"),
        ZoomLevel("1D", 24, "HH:mm"),
        ZoomLevel("7D", 168, "MMM dd")
    )

    private var currentZoomIndex = 0
    private var currentTimeOffset = 0L // Hours offset from now

    init {
        loadData()
    }

    fun zoomIn() {
        if (currentZoomIndex > 0) {
            currentZoomIndex--
            currentTimeOffset = 0L // Reset offset when zooming
            loadData()
        }
    }

    fun zoomOut() {
        if (currentZoomIndex < zoomLevels.size - 1) {
            currentZoomIndex++
            currentTimeOffset = 0L // Reset offset when zooming
            loadData()
        }
    }

    fun scrollLeft() {
        val zoomLevel = zoomLevels[currentZoomIndex]
        currentTimeOffset -= zoomLevel.hours / 4 // Scroll by quarter of the current view
        loadData()
    }

    fun scrollRight() {
        val zoomLevel = zoomLevels[currentZoomIndex]
        currentTimeOffset += zoomLevel.hours / 4

        // Don't scroll into the future beyond current time
        if (EvaluationState.isEvaluationMode.value) {
            if (currentTimeOffset > 0) currentTimeOffset = 0
        } else {
            if (currentTimeOffset > 0) currentTimeOffset = 0
        }
        loadData()
    }

    fun canScrollLeft(): Boolean {
        // In evaluation mode, limit scrolling to historical data bounds
        if (EvaluationState.isEvaluationMode.value) {
            val zoomLevel = zoomLevels[currentZoomIndex]
            if (zoomLevel.hours > 24) {
                // Weekly view - can't scroll beyond historical range
                return false
            } else {
                // Daily view - can scroll to start of day
                val maxOffset = 24L - zoomLevel.hours
                return currentTimeOffset > -maxOffset
            }
        }
        return true // In normal mode, can always scroll back
    }

    fun canScrollRight(): Boolean {
        return currentTimeOffset < 0 // Can only scroll right if we're in the past
    }

    // Enhanced data loading for evaluation mode
    private fun loadData() {
        viewModelScope.launch {
            try {
                val zoomLevel = zoomLevels[currentZoomIndex]

                if (EvaluationState.isEvaluationMode.value) {
                    loadEvaluationModeData(zoomLevel)
                } else {
                    loadNormalModeData(zoomLevel)
                }
            } catch (e: Exception) {
                Log.e("MetricDetailViewModel", "Error loading data", e)
                _uiState.value = MetricUiState.Error("Failed to load data: ${e.message}")
            }
        }
    }

    // Load data for evaluation mode
    private suspend fun loadEvaluationModeData(zoomLevel: ZoomLevel) {
        when {
            zoomLevel.hours <= 24 -> {
                // Current day view - use data from 2023-08-19 mapped to current time
                loadCurrentDayData(zoomLevel)
            }
            else -> {
                // Weekly view - use historical data from 2023-08-01 to 2023-08-12
                loadHistoricalWeekData(zoomLevel)
            }
        }
    }

    // Load current day data for evaluation mode
    private suspend fun loadCurrentDayData(zoomLevel: ZoomLevel) {
        val now = LocalDateTime.now()

        // Calculate time window based on scroll offset
        val endTime = now.plusHours(currentTimeOffset)
        val startTime = endTime.minusHours(zoomLevel.hours)
        val timeRange = TimeRange(startTime, endTime)

        val startTimestamp = timeRange.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endTimestamp = timeRange.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        Log.d("MetricDetailViewModel", "Loading data from ${timeRange.start} to ${timeRange.end}")

        try {
            // Get data from short-term database (current simulation)
            val entities = dao.getRecordsBetween(startTimestamp, endTimestamp)

            val dataPoints = entities.map { entity ->
                val value = when (metricType) {
                    WatchfaceTileService.METRIC_HEART_RATE -> entity.heartRate.toFloat()
                    WatchfaceTileService.METRIC_STEPS -> entity.stepCount.toFloat()
                    WatchfaceTileService.METRIC_ENERGY -> entity.activeEnergyBurned.toFloat()
                    else -> 0f
                }
                DataPoint(time = entity.timestamp, value = value)
            }

            // Add current live data if it falls within the time window
            val currentTime = System.currentTimeMillis()
            val allDataPoints = if (currentTime >= startTimestamp && currentTime <= endTimestamp) {
                val liveRecord = SimulationManager.healthDataStream.value
                val liveValue = when (metricType) {
                    WatchfaceTileService.METRIC_HEART_RATE -> liveRecord.heartRate.toFloat()
                    WatchfaceTileService.METRIC_STEPS -> liveRecord.stepCount.toFloat()
                    WatchfaceTileService.METRIC_ENERGY -> liveRecord.activeEnergyBurned.toFloat()
                    else -> 0f
                }
                dataPoints + DataPoint(currentTime, liveValue)
            } else {
                dataPoints
            }

            val sortedData = allDataPoints.sortedBy { it.time }

            if (sortedData.isNotEmpty()) {
                val minVal = sortedData.minOf { it.value }
                val maxVal = sortedData.maxOf { it.value }
                val latestVal = sortedData.lastOrNull()?.value ?: 0f
                val liveRecord = SimulationManager.healthDataStream.value

                _uiState.value = MetricUiState.Success(
                    graphData = sortedData,
                    currentRange = timeRange,
                    zoomLevel = zoomLevel,
                    cycleDay = liveRecord.cycleDay,
                    cyclePhase = liveRecord.cyclePhase,
                    minValue = minVal,
                    maxValue = maxVal,
                    latestValue = latestVal
                )
            } else {
                _uiState.value = MetricUiState.Error("No data available for selected time range")
            }
        } catch (e: Exception) {
            Log.e("MetricDetailViewModel", "Error loading current day data", e)
            _uiState.value = MetricUiState.Error("Error loading data: ${e.message}")
        }
    }

    // Load historical week data for evaluation mode
    private suspend fun loadHistoricalWeekData(zoomLevel: ZoomLevel) {
        try {
            val (historicalStart, historicalEnd) = EvaluationDateHelper.getHistoricalRange()

            Log.d("MetricDetailViewModel", "Loading historical data from $historicalStart to $historicalEnd")

            // Get historical data from archive
            val historicalRecords = archiveDao.getRecordsBetween(historicalStart, historicalEnd)

            if (historicalRecords.isEmpty()) {
                _uiState.value = MetricUiState.Error("No historical data available")
                return
            }

            // Group by day and calculate aggregates
            val dailyData = historicalRecords
                .groupBy { record ->
                    Instant.ofEpochMilli(record.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                }
                .map { (date, records) ->
                    val value = when (metricType) {
                        WatchfaceTileService.METRIC_HEART_RATE -> records.map { it.heartRate.toFloat() }.average().toFloat()
                        WatchfaceTileService.METRIC_STEPS -> records.maxOfOrNull { it.stepCount }?.toFloat() ?: 0f
                        WatchfaceTileService.METRIC_ENERGY -> records.map { it.activeEnergyBurned.toFloat() }.sum()
                        else -> 0f
                    }

                    DataPoint(
                        time = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        value = value
                    )
                }
                .sortedBy { it.time }

            if (dailyData.isNotEmpty()) {
                val minVal = dailyData.minOf { it.value }
                val maxVal = dailyData.maxOf { it.value }
                val latestVal = dailyData.lastOrNull()?.value ?: 0f
                val liveRecord = SimulationManager.healthDataStream.value

                val startDate = LocalDate.of(2023, 8, 1)
                val endDate = LocalDate.of(2023, 8, 12)
                val timeRange = TimeRange(
                    startDate.atStartOfDay(),
                    endDate.atTime(23, 59, 59)
                )

                _uiState.value = MetricUiState.Success(
                    graphData = dailyData,
                    currentRange = timeRange,
                    zoomLevel = zoomLevel,
                    cycleDay = liveRecord.cycleDay,
                    cyclePhase = liveRecord.cyclePhase,
                    minValue = minVal,
                    maxValue = maxVal,
                    latestValue = latestVal
                )
            } else {
                _uiState.value = MetricUiState.Error("No historical data processed")
            }
        } catch (e: Exception) {
            Log.e("MetricDetailViewModel", "Error loading historical data", e)
            _uiState.value = MetricUiState.Error("Error loading historical data: ${e.message}")
        }
    }

    // Normal mode data loading with enhanced scrolling
    private suspend fun loadNormalModeData(zoomLevel: ZoomLevel) {
        val now = LocalDateTime.now()
        val endTime = now.plusHours(currentTimeOffset)
        val startTime = endTime.minusHours(zoomLevel.hours)
        val timeRange = TimeRange(startTime, endTime)

        if (zoomLevel.hours <= 24) {
            // Hourly data from short-term database
            val startTimestamp = timeRange.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endTimestamp = timeRange.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            try {
                val entities = dao.getRecordsBetween(startTimestamp, endTimestamp)

                val dbData = entities.map { entity ->
                    val value = when (metricType) {
                        WatchfaceTileService.METRIC_HEART_RATE -> entity.heartRate.toFloat()
                        WatchfaceTileService.METRIC_STEPS -> entity.stepCount.toFloat()
                        WatchfaceTileService.METRIC_ENERGY -> entity.activeEnergyBurned.toFloat()
                        else -> 0f
                    }
                    DataPoint(time = entity.timestamp, value = value)
                }

                // Add live data if current time falls within window
                val currentTime = System.currentTimeMillis()
                val allData = if (currentTime >= startTimestamp && currentTime <= endTimestamp) {
                    val liveRecord = SimulationManager.healthDataStream.value
                    val liveValue = when (metricType) {
                        WatchfaceTileService.METRIC_HEART_RATE -> liveRecord.heartRate.toFloat()
                        WatchfaceTileService.METRIC_STEPS -> liveRecord.stepCount.toFloat()
                        WatchfaceTileService.METRIC_ENERGY -> liveRecord.activeEnergyBurned.toFloat()
                        else -> 0f
                    }
                    (dbData + DataPoint(currentTime, liveValue)).sortedBy { it.time }
                } else {
                    dbData.sortedBy { it.time }
                }

                if (allData.isNotEmpty()) {
                    val minVal = allData.minOf { it.value }
                    val maxVal = allData.maxOf { it.value }
                    val latestVal = allData.lastOrNull()?.value ?: 0f
                    val liveRecord = SimulationManager.healthDataStream.value

                    _uiState.value = MetricUiState.Success(
                        graphData = allData,
                        currentRange = timeRange,
                        zoomLevel = zoomLevel,
                        cycleDay = liveRecord.cycleDay,
                        cyclePhase = liveRecord.cyclePhase,
                        minValue = minVal,
                        maxValue = maxVal,
                        latestValue = latestVal
                    )
                } else {
                    _uiState.value = MetricUiState.Error("No data available for selected time range")
                }
            } catch (e: Exception) {
                Log.e("MetricDetailViewModel", "Error loading normal mode hourly data", e)
                _uiState.value = MetricUiState.Error("Error loading data: ${e.message}")
            }
        } else {
            // Weekly view - daily aggregates from archive
            val startTimestamp = timeRange.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endTimestamp = timeRange.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            try {
                val archiveRecords = archiveDao.getRecordsBetween(startTimestamp, endTimestamp)

                val dailyData = archiveRecords
                    .groupBy { record ->
                        Instant.ofEpochMilli(record.timestamp)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                    }
                    .map { (date, records) ->
                        val value = when (metricType) {
                            WatchfaceTileService.METRIC_HEART_RATE -> records.map { it.heartRate.toFloat() }.average().toFloat()
                            WatchfaceTileService.METRIC_STEPS -> records.maxOfOrNull { it.stepCount }?.toFloat() ?: 0f
                            WatchfaceTileService.METRIC_ENERGY -> records.map { it.activeEnergyBurned.toFloat() }.sum()
                            else -> 0f
                        }

                        DataPoint(
                            time = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            value = value
                        )
                    }
                    .sortedBy { it.time }

                if (dailyData.isNotEmpty()) {
                    val minVal = dailyData.minOf { it.value }
                    val maxVal = dailyData.maxOf { it.value }
                    val latestVal = dailyData.lastOrNull()?.value ?: 0f
                    val liveRecord = SimulationManager.healthDataStream.value

                    _uiState.value = MetricUiState.Success(
                        graphData = dailyData,
                        currentRange = timeRange,
                        zoomLevel = zoomLevel,
                        cycleDay = liveRecord.cycleDay,
                        cyclePhase = liveRecord.cyclePhase,
                        minValue = minVal,
                        maxValue = maxVal,
                        latestValue = latestVal
                    )
                } else {
                    _uiState.value = MetricUiState.Error("No weekly data available")
                }
            } catch (e: Exception) {
                Log.e("MetricDetailViewModel", "Error loading weekly data", e)
                _uiState.value = MetricUiState.Error("Error loading weekly data: ${e.message}")
            }
        }
    }
}

// Activity
class MetricDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metricType = intent?.getStringExtra(WatchfaceTileService.EXTRA_METRIC_TYPE) ?: WatchfaceTileService.METRIC_HEART_RATE
        val iconResId = intent?.getIntExtra("iconResId", R.drawable.heart_icon) ?: R.drawable.heart_icon

        val viewModel by viewModels<MetricDetailViewModel> {
            MetricDetailViewModelFactory(metricType, this.applicationContext)
        }

        setContent {
            val uiState = viewModel.uiState.collectAsState().value
            MetricDetailScreen(
                iconResId = iconResId,
                state = uiState,
                onZoomIn = { viewModel.zoomIn() },
                onZoomOut = { viewModel.zoomOut() },
                onScrollLeft = { viewModel.scrollLeft() },
                onScrollRight = { viewModel.scrollRight() },
                canScrollLeft = viewModel.canScrollLeft(),
                canScrollRight = viewModel.canScrollRight()
            )
        }
    }
}

@Composable
fun MetricDetailScreen(
    iconResId: Int,
    state: MetricUiState,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onScrollLeft: () -> Unit,
    onScrollRight: () -> Unit,
    canScrollLeft: Boolean,
    canScrollRight: Boolean
) {
    val isEvaluationMode by EvaluationState.isEvaluationMode.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        // Header with icon
        Box(
            modifier = Modifier.size(28.dp).background(iconBlue, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = iconResId),
                contentDescription = "Metric Icon",
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(WhiteText)
            )
        }

        when (state) {
            is MetricUiState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp
                )
            }
            is MetricUiState.Error -> {
                Text(
                    text = state.message,
                    color = menstrualRed,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
            }
            is MetricUiState.Success -> {
                // Mode indicator for evaluation mode
                if (isEvaluationMode) {
                    Text(
                        text = "EVALUATION MODE",
                        color = ovulationPink,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Zoom level indicator
                Text(
                    text = state.zoomLevel.name,
                    color = WhiteText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                // Interactive graph
                InteractiveLineGraph(
                    data = state.graphData,
                    timeRange = state.currentRange,
                    zoomLevel = state.zoomLevel,
                    minValue = state.minValue,
                    maxValue = state.maxValue,
                    latestValue = state.latestValue,
                    onZoomIn = onZoomIn,
                    onZoomOut = onZoomOut,
                    onScrollLeft = onScrollLeft,
                    onScrollRight = onScrollRight,
                    canScrollLeft = canScrollLeft,
                    canScrollRight = canScrollRight,
                    isEvaluationMode = isEvaluationMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                )

                // Control hints
                Text(
                    text = "Drag: scroll • Double-tap: zoom",
                    color = WhiteText.copy(alpha = 0.6f),
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center
                )

                // Cycle information
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
                        modifier = Modifier.size(16.dp),
                        colorFilter = ColorFilter.tint(cycleColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Day ${state.cycleDay}",
                        color = cycleColor,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun InteractiveLineGraph(
    modifier: Modifier = Modifier,
    data: List<DataPoint>,
    timeRange: TimeRange,
    zoomLevel: ZoomLevel,
    minValue: Float,
    maxValue: Float,
    latestValue: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onScrollLeft: () -> Unit,
    onScrollRight: () -> Unit,
    canScrollLeft: Boolean,
    canScrollRight: Boolean,
    isEvaluationMode: Boolean,
    lineColor: Color = WhiteText,
    gridColor: Color = Color.White.copy(alpha = 0.2f)
) {
    if (data.size < 2) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Not enough data", color = WhiteText, fontSize = 9.sp)
        }
        return
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            // Optional: Add haptic feedback or animation here
                        }
                    ) { change, _ ->
                        val dragX = change.position.x
                        if (abs(dragX) > 15) {
                            if (dragX > 0 && canScrollLeft) {
                                onScrollLeft()
                            } else if (dragX < 0 && canScrollRight) {
                                onScrollRight()
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val centerX = size.width / 2
                            if (offset.x < centerX) {
                                onZoomIn()
                            } else {
                                onZoomOut()
                            }
                        }
                    )
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val yRange = (maxValue - minValue).takeIf { it > 0 } ?: 1f

            // Draw grid
            (0..3).forEach { i ->
                val y = canvasHeight * i / 3
                drawLine(
                    gridColor,
                    Offset(0f, y),
                    Offset(canvasWidth, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Draw data line
            val path = Path()
            val fillPath = Path()

            fun getPoint(dataPoint: DataPoint, index: Int): Offset {
                val x = canvasWidth * index / (data.size - 1).coerceAtLeast(1)
                val y = canvasHeight * (1 - (dataPoint.value - minValue) / yRange)
                return Offset(x, y.coerceIn(0f, canvasHeight))
            }

            if (data.isNotEmpty()) {
                val firstPoint = getPoint(data.first(), 0)
                path.moveTo(firstPoint.x, firstPoint.y)
                fillPath.moveTo(0f, canvasHeight)
                fillPath.lineTo(firstPoint.x, firstPoint.y)

                for (i in 1 until data.size) {
                    val point = getPoint(data[i], i)
                    path.lineTo(point.x, point.y)
                    fillPath.lineTo(point.x, point.y)
                }

                fillPath.lineTo(canvasWidth, canvasHeight)
                fillPath.close()

                // Draw gradient fill
                drawPath(
                    fillPath,
                    brush = Brush.verticalGradient(
                        listOf(lineColor.copy(alpha = 0.3f), Color.Transparent)
                    )
                )

                // Draw main line
                drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx()))

                // Draw latest point indicator
                val latestPoint = getPoint(data.last(), data.size - 1)
                drawCircle(
                    Color.White,
                    radius = 3.dp.toPx(),
                    center = latestPoint
                )

                // Draw a subtle pulse effect for the latest point
                drawCircle(
                    lineColor.copy(alpha = 0.3f),
                    radius = 6.dp.toPx(),
                    center = latestPoint
                )
            }
        }

        // Value display
        Text(
            text = "%.0f".format(latestValue),
            color = Color.White,
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
        )

        // Time labels - adjusted for evaluation mode
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val formatter = DateTimeFormatter.ofPattern(zoomLevel.displayFormat)

            Text(
                text = if (isEvaluationMode && zoomLevel.hours > 24) {
                    "Historical"
                } else {
                    timeRange.start.format(formatter)
                },
                color = WhiteText.copy(alpha = 0.7f),
                fontSize = 7.sp
            )

            Text(
                text = if (isEvaluationMode && zoomLevel.hours > 24) {
                    "Aug 1-12"
                } else {
                    timeRange.end.format(formatter)
                },
                color = WhiteText.copy(alpha = 0.7f),
                fontSize = 7.sp
            )
        }

        // Scroll indicators
        if (!canScrollLeft) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(12.dp)
                    .background(
                        Color.Gray.copy(alpha = 0.5f),
                        CircleShape
                    )
            )
        }

        if (!canScrollRight) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(12.dp)
                    .background(
                        Color.Gray.copy(alpha = 0.5f),
                        CircleShape
                    )
            )
        }
    }
}

private fun getCycleColor(phase: CyclePhase): Color {
    return when (phase) {
        CyclePhase.MENSTRUATION -> Color(0xFFFF0000)
        CyclePhase.FOLLICULAR -> Color(0xFF4CAF50)
        CyclePhase.OVULATION -> Color(0xFFff66c4)
        CyclePhase.LUTEAL -> Color(0xFF9C27B0)
        else -> Color.Gray
    }
}

// Factory
class MetricDetailViewModelFactory(
    private val metricType: String,
    private val context: Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MetricDetailViewModel(metricType, context) as T
    }
}