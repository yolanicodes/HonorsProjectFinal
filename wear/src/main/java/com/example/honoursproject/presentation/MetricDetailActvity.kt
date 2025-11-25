package com.example.honoursproject.presentation

import android.content.ContentValues.TAG
import android.graphics.Paint
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.*

// ============================================================================
// DOMAIN MODELS (Single Responsibility Principle)
// ============================================================================

data class DataPoint(val time: Long, val value: Float)

data class TimeRange(val start: LocalDateTime, val end: LocalDateTime) {
    fun contains(timestamp: Long): Boolean {
        val time = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        return !time.isBefore(start) && !time.isAfter(end)
    }
}

data class ZoomLevel(
    val name: String,
    val hours: Long,
    val displayFormat: String,
    val gridIntervalMinutes: Int,
    val labelIntervalMinutes: Int
) {
    companion object {
        val TWO_HOUR = ZoomLevel("2H", 2, "HH:mm", 15, 30)      // Grid every 15min, labels every 30min
        val SIX_HOUR = ZoomLevel("6H", 6, "HH:mm", 30, 60)      // Grid every 30min, labels every 1hr
        val TWELVE_HOUR = ZoomLevel("12H", 12, "HH:mm", 60, 120) // Grid every 1hr, labels every 2hrs
        val SEVEN_DAY = ZoomLevel("7D", 168, "dd MMM", 1440, 1440) // Grid and labels daily

        val ALL_LEVELS = listOf(TWO_HOUR, SIX_HOUR, TWELVE_HOUR, SEVEN_DAY)
    }
}

data class GraphMetrics(
    val minValue: Float,
    val maxValue: Float,
    val latestValue: Float,
    val trend: TrendDirection
)

enum class TrendDirection { UP, DOWN, STABLE }

// ============================================================================
// UI STATE (Single Responsibility Principle)
// ============================================================================

sealed class MetricUiState {
    object Loading : MetricUiState()
    object LoadingMore : MetricUiState() // New state for scrolling

    data class Success(
        val graphData: List<DataPoint>,
        val currentRange: TimeRange,
        val zoomLevel: ZoomLevel,
        val cycleDay: Int,
        val cyclePhase: String,
        val metrics: GraphMetrics,
        val cycleData: Map<Long, CyclePhase> = emptyMap(),
        val isLoadingMore: Boolean = false // For scroll loading
    ) : MetricUiState()

    data class Error(val message: String) : MetricUiState()
}

// ============================================================================
// DATA REPOSITORY INTERFACE (Dependency Inversion Principle)
// ============================================================================

interface MetricDataRepository {
    suspend fun getDataForTimeRange(
        startTimestamp: Long,
        endTimestamp: Long,
        metricType: String
    ): List<DataPoint>

    suspend fun getDailyAggregates(
        startTimestamp: Long,
        endTimestamp: Long,
        metricType: String
    ): List<DataPoint>

    suspend fun getCycleDataForRange(
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<Long, CyclePhase>

    suspend fun getCurrentLiveValue(metricType: String): Float
}

// ============================================================================
// REPOSITORY IMPLEMENTATION
// ============================================================================

class HealthMetricRepository(private val context: Context) : MetricDataRepository {

    private val dao = HealthDatabase.getInstance(context).healthRecordDao()
    private val archiveDao = ArchiveDatabase.getInstance(context).archiveRecordDao()

    override suspend fun getDataForTimeRange(
        startTimestamp: Long,
        endTimestamp: Long,
        metricType: String
    ): List<DataPoint> {
        val entities = dao.getRecordsBetween(startTimestamp, endTimestamp)
        return entities.map { entity ->
            DataPoint(
                time = entity.timestamp,
                value = extractValue(entity, metricType)
            )
        }
    }

    override suspend fun getDailyAggregates(
        startTimestamp: Long,
        endTimestamp: Long,
        metricType: String
    ): List<DataPoint> {
        val archiveRecords = archiveDao.getRecordsBetween(startTimestamp, endTimestamp)

        return archiveRecords
            .groupBy { record ->
                Instant.ofEpochMilli(record.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            }
            .map { (date, records) ->
                DataPoint(
                    time = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    value = aggregateRecords(records, metricType)
                )
            }
            .sortedBy { it.time }
    }

    override suspend fun getCycleDataForRange(
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<Long, CyclePhase> {
        val cycleData = mutableMapOf<Long, CyclePhase>()
        val startTimestamp = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endTimestamp = endDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        try {
            val archiveRecords = archiveDao.getRecordsBetween(startTimestamp, endTimestamp)

            archiveRecords.groupBy { record ->
                Instant.ofEpochMilli(record.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            }.forEach { (date, records) ->
                val dayTimestamp = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val mostCommonPhase = records
                    .groupingBy {
                        try {
                            CyclePhase.valueOf(it.cyclePhase.uppercase())
                        } catch (e: IllegalArgumentException) {
                            CyclePhase.UNKNOWN
                        }
                    }
                    .eachCount()
                    .maxByOrNull { it.value }?.key ?: CyclePhase.UNKNOWN

                cycleData[dayTimestamp] = mostCommonPhase
            }
        } catch (e: Exception) {
            Log.e("HealthMetricRepository", "Error getting cycle data", e)
        }

        return cycleData
    }

    override suspend fun getCurrentLiveValue(metricType: String): Float {
        val snapshot = HealthDataStreamManager.getCurrentSnapshot()
        return when (metricType) {
            WatchfaceTileService.METRIC_HEART_RATE -> snapshot.heartRate.toFloat()
            WatchfaceTileService.METRIC_STEPS -> snapshot.cumulativeSteps.toFloat()
            WatchfaceTileService.METRIC_ENERGY -> snapshot.cumulativeEnergyBurned.toFloat()
            else -> 0f
        }
    }

    private fun extractValue(entity: HealthRecordEntity, metricType: String): Float {
        return when (metricType) {
            WatchfaceTileService.METRIC_HEART_RATE -> entity.heartRate.toFloat()
            WatchfaceTileService.METRIC_STEPS -> entity.stepCount.toFloat()
            WatchfaceTileService.METRIC_ENERGY -> entity.activeEnergyBurned.toFloat()
            else -> 0f
        }
    }

    private fun aggregateRecords(records: List<ArchiveRecordEntity>, metricType: String): Float {
        return when (metricType) {
            WatchfaceTileService.METRIC_HEART_RATE ->
                records.map { it.heartRate.toFloat() }.average().toFloat()
            WatchfaceTileService.METRIC_STEPS ->
                records.maxOfOrNull { it.stepCount }?.toFloat() ?: 0f
            WatchfaceTileService.METRIC_ENERGY ->
                records.map { it.activeEnergyBurned.toFloat() }.sum()
            else -> 0f
        }
    }
}

// ============================================================================
// TIME MANAGER (Single Responsibility Principle)
// ============================================================================

class TimeRangeManager {

    fun calculateTimeRange(
        zoomLevel: ZoomLevel,
        offsetHours: Long,
        isEvaluationMode: Boolean
    ): TimeRange {
        val now = LocalDateTime.now()
        val endTime = now.plusHours(offsetHours)
        val startTime = endTime.minusHours(zoomLevel.hours)

        return TimeRange(startTime, endTime)
    }

    fun canScrollLeft(
        currentRange: TimeRange,
        zoomLevel: ZoomLevel,
        isEvaluationMode: Boolean
    ): Boolean {
        val startOfDataRange = if (isEvaluationMode) {
            if (zoomLevel.hours >= 168) {
                LocalDate.of(2023, 8, 1).atStartOfDay()
            } else {
                EvaluationState.EVALUATION_DATE.atStartOfDay()
            }
        } else {
            if (zoomLevel.hours >= 168) {
                LocalDateTime.now().minusDays(30).toLocalDate().atStartOfDay()
            } else {
                LocalDateTime.now().toLocalDate().atStartOfDay()
            }
        }

        return currentRange.start.isAfter(startOfDataRange)
    }

    fun canScrollRight(offsetHours: Long): Boolean {
        return offsetHours < 0
    }

    fun getScrollIncrement(zoomLevel: ZoomLevel): Double {
        return when {
            zoomLevel.hours <= 2 -> 0.25 // 15 minutes
            zoomLevel.hours <= 12 -> 0.5 // 30 minutes
            zoomLevel.hours <= 24 -> 1.0 // 1 hour
            else -> 6.0 // 6 hours
        }
    }
}

// ============================================================================
// METRICS CALCULATOR (Single Responsibility Principle)
// ============================================================================

class MetricsCalculator {

    fun calculateMetrics(data: List<DataPoint>): GraphMetrics {
        if (data.isEmpty()) {
            return GraphMetrics(0f, 0f, 0f, TrendDirection.STABLE)
        }

        val minVal = data.minOf { it.value }
        val maxVal = data.maxOf { it.value }
        val latestVal = data.lastOrNull()?.value ?: 0f

        val trend = if (data.size >= 2) {
            val current = data.last().value
            val previous = data[data.size - 2].value
            when {
                current > previous * 1.05f -> TrendDirection.UP
                current < previous * 0.95f -> TrendDirection.DOWN
                else -> TrendDirection.STABLE
            }
        } else {
            TrendDirection.STABLE
        }

        return GraphMetrics(minVal, maxVal, latestVal, trend)
    }
}

// ============================================================================
// VIEW MODEL (Open/Closed Principle - open for extension via repository)
// ============================================================================

class MetricDetailViewModel(
    private val metricType: String,
    private val repository: MetricDataRepository,
    private val timeManager: TimeRangeManager,
    private val metricsCalculator: MetricsCalculator
) : ViewModel() {

    private val _uiState = MutableStateFlow<MetricUiState>(MetricUiState.Loading)
    val uiState: StateFlow<MetricUiState> = _uiState.asStateFlow()

    private var currentZoomIndex = 0
    private var currentTimeOffset = 0L
    private var isLoadingData = false

    init {
        loadData()
    }

    fun zoomIn() {
        if (currentZoomIndex > 0 && !isLoadingData) {
            currentZoomIndex--
            currentTimeOffset = 0L
            loadData()
        }
    }

    fun zoomOut() {
        if (currentZoomIndex < ZoomLevel.ALL_LEVELS.size - 1 && !isLoadingData) {
            currentZoomIndex++
            currentTimeOffset = 0L
            loadData()
        }
    }

    fun scrollLeft() {
        if (isLoadingData) return

        val zoomLevel = ZoomLevel.ALL_LEVELS[currentZoomIndex]
        val scrollIncrement = timeManager.getScrollIncrement(zoomLevel)
        currentTimeOffset -= scrollIncrement.toLong()
        loadData(showLoadingState = true)
    }

    fun scrollRight() {
        if (isLoadingData) return

        val zoomLevel = ZoomLevel.ALL_LEVELS[currentZoomIndex]
        val scrollIncrement = timeManager.getScrollIncrement(zoomLevel)
        currentTimeOffset += scrollIncrement.toLong()

        if (currentTimeOffset > 0) currentTimeOffset = 0
        loadData(showLoadingState = true)
    }

    fun canScrollLeft(): Boolean {
        val currentState = _uiState.value
        if (currentState !is MetricUiState.Success) return false

        return timeManager.canScrollLeft(
            currentState.currentRange,
            currentState.zoomLevel,
            EvaluationState.isEvaluationMode.value
        )
    }

    fun canScrollRight(): Boolean {
        return timeManager.canScrollRight(currentTimeOffset)
    }

    private fun loadData(showLoadingState: Boolean = false) {
        if (isLoadingData) return

        viewModelScope.launch {
            try {
                isLoadingData = true

                // Show loading indicator for scrolling
                if (showLoadingState) {
                    val currentState = _uiState.value
                    if (currentState is MetricUiState.Success) {
                        _uiState.value = currentState.copy(isLoadingMore = true)
                    } else {
                        _uiState.value = MetricUiState.LoadingMore
                    }
                    // Small delay to show loading state
                    delay(100)
                } else {
                    _uiState.value = MetricUiState.Loading
                }

                val zoomLevel = ZoomLevel.ALL_LEVELS[currentZoomIndex]
                val isEvaluationMode = EvaluationState.isEvaluationMode.value

                if (!HealthDataStreamManager.isHistoricalDataLoaded()) {
                    // Wait for historical data to load
                    HealthDataStreamManager.dataStream
                        .filter { it.isHistoricalDataLoaded }
                        .first()
                }

                val timeRange = timeManager.calculateTimeRange(
                    zoomLevel,
                    currentTimeOffset,
                    isEvaluationMode
                )

                val data = if (zoomLevel.hours >= 168) {
                    loadWeeklyData(timeRange)
                } else {
                    loadHourlyData(timeRange)
                }

                if (data.isEmpty()) {
                    _uiState.value = MetricUiState.Error("No data available for selected time range")
                    return@launch
                }

                val metrics = metricsCalculator.calculateMetrics(data)
                val snapshot = HealthDataStreamManager.getCurrentSnapshot()

                val cycleData = if (zoomLevel.hours >= 168) {
                    repository.getCycleDataForRange(
                        timeRange.start.toLocalDate(),
                        timeRange.end.toLocalDate()
                    )
                } else {
                    emptyMap()
                }

                _uiState.value = MetricUiState.Success(
                    graphData = data,
                    currentRange = timeRange,
                    zoomLevel = zoomLevel,
                    cycleDay = snapshot.cycleDay,
                    cyclePhase = snapshot.cyclePhase,
                    metrics = metrics,
                    cycleData = cycleData,
                    isLoadingMore = false
                )

            } catch (e: Exception) {
                Log.e("MetricDetailViewModel", "Error loading data", e)
                _uiState.value = MetricUiState.Error("Failed to load data: ${e.message}")
            } finally {
                isLoadingData = false
            }
        }
    }

    private suspend fun loadHourlyData(timeRange: TimeRange): List<DataPoint> {
        val startTimestamp = timeRange.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endTimestamp = timeRange.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val dbData = repository.getDataForTimeRange(startTimestamp, endTimestamp, metricType)

        // Add live data if within range
        val currentTime = System.currentTimeMillis()
        return if (currentTime >= startTimestamp && currentTime <= endTimestamp) {
            val liveValue = repository.getCurrentLiveValue(metricType)
            (dbData + DataPoint(currentTime, liveValue)).sortedBy { it.time }
        } else {
            dbData.sortedBy { it.time }
        }
    }

    private suspend fun loadWeeklyData(timeRange: TimeRange): List<DataPoint> {
        val startTimestamp = timeRange.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endTimestamp = timeRange.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        return repository.getDailyAggregates(startTimestamp, endTimestamp, metricType)
    }
}

// ============================================================================
// VIEW MODEL FACTORY
// ============================================================================

class MetricDetailViewModelFactory(
    private val metricType: String,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val repository = HealthMetricRepository(context)
        val timeManager = TimeRangeManager()
        val metricsCalculator = MetricsCalculator()

        @Suppress("UNCHECKED_CAST")
        return MetricDetailViewModel(metricType, repository, timeManager, metricsCalculator) as T
    }
}

// ============================================================================
// ACTIVITY
// ============================================================================

class MetricDetailActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MetricDetailActivity"
        private const val ACTIVITY_NAME = "MetricDetailActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate - MetricDetailActivity starting")

        // Register with NavigationCoordinator
        lifecycleScope.launch {
            if (!NavigationCoordinator.canLaunchActivity(ACTIVITY_NAME)) {
                Log.w(TAG, "Activity launch blocked by NavigationCoordinator")
                finish()
                return@launch
            }
        }

        val metricType = intent?.getStringExtra(WatchfaceTileService.EXTRA_METRIC_TYPE)
            ?: WatchfaceTileService.METRIC_HEART_RATE
        val iconResId = intent?.getIntExtra("iconResId", R.drawable.heart_icon)
            ?: R.drawable.heart_icon

        val factory = MetricDetailViewModelFactory(metricType, applicationContext)
        val viewModel = viewModels<MetricDetailViewModel> { factory }.value

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
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy - MetricDetailActivity closing")

        // Unregister from NavigationCoordinator
        lifecycleScope.launch {
            NavigationCoordinator.onActivityClosed(ACTIVITY_NAME)
        }
    }
}

// ============================================================================
// COMPOSABLES (Interface Segregation - each has specific responsibility)
// ============================================================================

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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        MetricIcon(iconResId)

        when (state) {
            is MetricUiState.Loading -> {
                LoadingIndicator()
            }
            is MetricUiState.LoadingMore -> {
                LoadingIndicator(text = "Loading data...")
            }
            is MetricUiState.Error -> {
                ErrorMessage(state.message)
            }
            is MetricUiState.Success -> {
                SuccessContent(
                    state = state,
                    onZoomIn = onZoomIn,
                    onZoomOut = onZoomOut,
                    onScrollLeft = onScrollLeft,
                    onScrollRight = onScrollRight,
                    canScrollLeft = canScrollLeft,
                    canScrollRight = canScrollRight
                )
            }
        }
    }
}

@Composable
private fun MetricIcon(iconResId: Int) {
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
}

@Composable
private fun LoadingIndicator(text: String = "Loading...") {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            strokeWidth = 3.dp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            color = WhiteText,
            fontSize = 8.sp
        )
    }
}

@Composable
private fun ErrorMessage(message: String) {
    Text(
        text = message,
        color = menstrualRed,
        fontSize = 10.sp,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun SuccessContent(
    state: MetricUiState.Success,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onScrollLeft: () -> Unit,
    onScrollRight: () -> Unit,
    canScrollLeft: Boolean,
    canScrollRight: Boolean
) {
    // Show loading overlay if loading more data
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InteractiveLineGraph(
                data = state.graphData,
                timeRange = state.currentRange,
                zoomLevel = state.zoomLevel,
                metrics = state.metrics,
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                onScrollLeft = onScrollLeft,
                onScrollRight = onScrollRight,
                canScrollLeft = canScrollLeft,
                canScrollRight = canScrollRight,
                cycleData = state.cycleData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
            )

            TimeRangeLabel(state.zoomLevel)
            CycleInfo(state.cycleDay, state.cyclePhase)
        }

        // Loading overlay
        if (state.isLoadingMore) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun TimeRangeLabel(zoomLevel: ZoomLevel) {
    Text(
        text = when (zoomLevel.name) {
            "2H" -> "Past 2 Hours"
            "6H" -> "Past 6 Hours"
            "12H" -> "Past 12 Hours"
            "7D" -> "Past 7 Days"
            else -> "Past ${zoomLevel.hours} Hours"
        },
        color = WhiteText,
        fontSize = 8.sp,
        fontWeight = FontWeight.Normal
    )
}

@Composable
private fun CycleInfo(cycleDay: Int, cyclePhase: String) {
    val cyclePhaseEnum = try {
        CyclePhase.valueOf(cyclePhase.uppercase())
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
            modifier = Modifier.size(12.dp),
            colorFilter = ColorFilter.tint(cycleColor)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$cycleDay",
            color = cycleColor,
            fontSize = 8.sp
        )
    }
}

// ============================================================================
// GRAPH COMPONENT (Single Responsibility - just drawing)
// ============================================================================

@Composable
fun InteractiveLineGraph(
    modifier: Modifier = Modifier,
    data: List<DataPoint>,
    timeRange: TimeRange,
    zoomLevel: ZoomLevel,
    metrics: GraphMetrics,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onScrollLeft: () -> Unit,
    onScrollRight: () -> Unit,
    canScrollLeft: Boolean,
    canScrollRight: Boolean,
    cycleData: Map<Long, CyclePhase>,
    lineColor: Color = WhiteText,
    gridColor: Color = Color.White.copy(alpha = 0.2f)
) {
    if (data.size < 2) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Not enough data", color = WhiteText, fontSize = 9.sp)
        }
        return
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragStartX by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize(0.9f)
                .padding(bottom = 5.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragStartX = offset.x
                        },
                        onDragEnd = { isDragging = false },
                        onDrag = { change, _ ->
                            val totalDragX = change.position.x - dragStartX
                            if (abs(totalDragX) > 5f) {
                                if (totalDragX > 0 && canScrollLeft) {
                                    onScrollLeft()
                                    dragStartX = change.position.x
                                } else if (totalDragX < 0 && canScrollRight) {
                                    onScrollRight()
                                    dragStartX = change.position.x
                                }
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2) onZoomIn() else onZoomOut()
                        }
                    )
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height - 20.dp.toPx()
            val yRange = (metrics.maxValue - metrics.minValue).takeIf { it > 0 } ?: 1f

            // Draw grid with proper intervals
            drawEnhancedGrid(
                canvasWidth, canvasHeight, gridColor,
                timeRange, zoomLevel
            )

            // Draw Y-axis labels
            drawYAxisLabels(canvasHeight, metrics.minValue, metrics.maxValue, gridColor)

            // Helper function to map data to screen coordinates
            fun getPoint(dataPoint: DataPoint, index: Int): Offset {
                val x = canvasWidth * index / (data.size - 1).coerceAtLeast(1)
                val y = canvasHeight * (1 - (dataPoint.value - metrics.minValue) / yRange)
                return Offset(x, y.coerceIn(0f, canvasHeight))
            }

            // Draw gradient fill
            if (data.isNotEmpty()) {
                drawGradientFill(data, ::getPoint, canvasHeight, lineColor)
            }

            // Draw line with cycle colors for weekly view
            drawDataLine(data, zoomLevel, cycleData, lineColor, ::getPoint)

            // Draw latest point indicator
            if (data.isNotEmpty()) {
                val latestPoint = getPoint(data.last(), data.size - 1)
                drawLatestPointIndicator(latestPoint, lineColor)
            }

            // Draw X-axis labels with proper alignment
            drawAlignedXAxisLabels(
                canvasWidth = canvasWidth,
                canvasHeight = size.height,
                timeRange = timeRange,
                zoomLevel = zoomLevel,
                cycleData = cycleData,
                data = data
            )
        }

        // Value display with trend
        ValueDisplay(
            metrics = metrics,
            modifier = Modifier.align(Alignment.TopEnd)
        )

        // Scroll indicators
        ScrollIndicators(
            canScrollLeft = canScrollLeft,
            canScrollRight = canScrollRight,
            isDragging = isDragging,
            modifier = Modifier
        )
    }
}

// ============================================================================
// DRAWING FUNCTIONS (Single Responsibility)
// ============================================================================

private fun DrawScope.drawEnhancedGrid(
    canvasWidth: Float,
    canvasHeight: Float,
    gridColor: Color,
    timeRange: TimeRange,
    zoomLevel: ZoomLevel
) {
    // Horizontal grid lines (Y-axis)
    (0..4).forEach { i ->
        val y = canvasHeight * i / 4
        drawLine(
            gridColor.copy(alpha = 0.3f),
            Offset(0f, y),
            Offset(canvasWidth, y),
            strokeWidth = 1.dp.toPx()
        )
    }

    // Vertical grid lines (X-axis) - aligned with time intervals
    val intervalMinutes = zoomLevel.gridIntervalMinutes
    val totalMinutes = zoomLevel.hours * 60
    val gridCount = (totalMinutes / intervalMinutes).toInt()

    (0..gridCount).forEach { i ->
        val x = canvasWidth * i / gridCount.coerceAtLeast(1)
        drawLine(
            gridColor.copy(alpha = 0.2f),
            Offset(x, 0f),
            Offset(x, canvasHeight),
            strokeWidth = 0.5.dp.toPx()
        )
    }
}

private fun DrawScope.drawYAxisLabels(
    canvasHeight: Float,
    minValue: Float,
    maxValue: Float,
    gridColor: Color
) {
    val paint = Paint().apply {
        color = WhiteText.copy(alpha = 0.6f).toArgb()
        textSize = 7.sp.toPx()
        textAlign = Paint.Align.RIGHT
    }

    // Draw min, mid, max values
    val values = listOf(
        Pair(minValue, canvasHeight),
        Pair((minValue + maxValue) / 2, canvasHeight / 2),
        Pair(maxValue, 0f)
    )

    values.forEach { (value, y) ->
        drawContext.canvas.nativeCanvas.drawText(
            "%.0f".format(value),
            -5.dp.toPx(),
            y + 3.dp.toPx(),
            paint
        )
    }
}

private fun DrawScope.drawGradientFill(
    data: List<DataPoint>,
    getPoint: (DataPoint, Int) -> Offset,
    canvasHeight: Float,
    lineColor: Color
) {
    val firstPoint = getPoint(data.first(), 0)
    val fillPath = Path().apply {
        moveTo(0f, canvasHeight)
        lineTo(firstPoint.x, firstPoint.y)

        data.forEachIndexed { index, dataPoint ->
            if (index > 0) {
                val point = getPoint(dataPoint, index)
                lineTo(point.x, point.y)
            }
        }

        lineTo(size.width, canvasHeight)
        close()
    }

    drawPath(
        fillPath,
        brush = Brush.verticalGradient(
            listOf(lineColor.copy(alpha = 0.2f), Color.Transparent)
        )
    )
}

private fun DrawScope.drawDataLine(
    data: List<DataPoint>,
    zoomLevel: ZoomLevel,
    cycleData: Map<Long, CyclePhase>,
    lineColor: Color,
    getPoint: (DataPoint, Int) -> Offset
) {
    if (zoomLevel.hours >= 168 && cycleData.isNotEmpty()) {
        // Weekly view - draw with cycle colors
        for (i in 1 until data.size) {
            val prevPoint = getPoint(data[i - 1], i - 1)
            val currentPoint = getPoint(data[i], i)

            val dataPointDate = Instant.ofEpochMilli(data[i - 1].time)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            val phase = cycleData[dataPointDate] ?: CyclePhase.UNKNOWN
            val phaseColor = getCycleColor(phase)

            drawLine(
                color = phaseColor,
                start = prevPoint,
                end = currentPoint,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    } else {
        // Hourly views - single color
        for (i in 1 until data.size) {
            val prevPoint = getPoint(data[i - 1], i - 1)
            val currentPoint = getPoint(data[i], i)

            drawLine(
                color = lineColor,
                start = prevPoint,
                end = currentPoint,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

private fun DrawScope.drawLatestPointIndicator(latestPoint: Offset, lineColor: Color) {
    // Outer pulse ring
    drawCircle(
        lineColor.copy(alpha = 0.3f),
        radius = 6.dp.toPx(),
        center = latestPoint
    )
    // Inner solid circle
    drawCircle(
        Color.White,
        radius = 4.dp.toPx(),
        center = latestPoint
    )
    // Center dot
    drawCircle(
        lineColor,
        radius = 2.dp.toPx(),
        center = latestPoint
    )
}

private fun DrawScope.drawAlignedXAxisLabels(
    canvasWidth: Float,
    canvasHeight: Float,
    timeRange: TimeRange,
    zoomLevel: ZoomLevel,
    cycleData: Map<Long, CyclePhase>,
    data: List<DataPoint>
) {
    val paint = Paint().apply {
        color = WhiteText.toArgb()
        textSize = 8.sp.toPx()
        textAlign = Paint.Align.CENTER
        alpha = 200
    }

    when {
        // 7-Day view - show cycle days (one per day)
        zoomLevel.hours >= 168 -> {
            drawWeeklyLabels(canvasWidth, canvasHeight, data, cycleData, paint)
        }
        // 12-Hour view - show every 2 hours
        zoomLevel.hours >= 12 -> {
            drawHourlyLabels(canvasWidth, canvasHeight, timeRange, 2, paint)
        }
        // 6-Hour view - show every hour
        zoomLevel.hours >= 6 -> {
            drawHourlyLabels(canvasWidth, canvasHeight, timeRange, 1, paint)
        }
        // 2-Hour view - show every 30 minutes
        else -> {
            drawMinuteLabels(canvasWidth, canvasHeight, timeRange, 30, paint)
        }
    }
}

private fun DrawScope.drawWeeklyLabels(
    canvasWidth: Float,
    canvasHeight: Float,
    data: List<DataPoint>,
    cycleData: Map<Long, CyclePhase>,
    paint: Paint
) {
    val daysCount = data.size.coerceAtMost(7)

    data.forEachIndexed { index, dataPoint ->
        val x = canvasWidth * (index + 0.5f) / daysCount.coerceAtLeast(1)

        val date = Instant.ofEpochMilli(dataPoint.time)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        // Find cycle day from cycleData
        val cycleDay = cycleData.entries.minByOrNull { abs(it.key - date) }
            ?.let { entry ->
                val daysDiff = ChronoUnit.DAYS.between(
                    Instant.ofEpochMilli(entry.key).atZone(ZoneId.systemDefault()).toLocalDate(),
                    Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).toLocalDate()
                )
                ((daysDiff) % 28 + 1).toInt()
            } ?: (index + 1)

        drawContext.canvas.nativeCanvas.drawText(
            "D$cycleDay",
            x,
            canvasHeight - 2.dp.toPx(),
            paint
        )
    }
}

private fun DrawScope.drawHourlyLabels(
    canvasWidth: Float,
    canvasHeight: Float,
    timeRange: TimeRange,
    hourInterval: Int,
    paint: Paint
) {
    val totalHours = ChronoUnit.HOURS.between(timeRange.start, timeRange.end)
    val labelCount = (totalHours / hourInterval).toInt() + 1

    (0 until labelCount).forEach { i ->
        val hoursFromStart = i * hourInterval.toLong()
        if (hoursFromStart <= totalHours) {
            val x = canvasWidth * hoursFromStart / totalHours.toFloat()
            val time = timeRange.start.plusHours(hoursFromStart)

            drawContext.canvas.nativeCanvas.drawText(
                time.format(DateTimeFormatter.ofPattern("HH:mm")),
                x,
                canvasHeight - 2.dp.toPx(),
                paint
            )
        }
    }
}

private fun DrawScope.drawMinuteLabels(
    canvasWidth: Float,
    canvasHeight: Float,
    timeRange: TimeRange,
    minuteInterval: Int,
    paint: Paint
) {
    val totalMinutes = ChronoUnit.MINUTES.between(timeRange.start, timeRange.end)
    val labelCount = (totalMinutes / minuteInterval).toInt() + 1

    (0 until labelCount).forEach { i ->
        val minutesFromStart = i * minuteInterval
        if (minutesFromStart <= totalMinutes) {
            val x = canvasWidth * minutesFromStart / totalMinutes.toFloat()
            val time = timeRange.start.plusMinutes(minutesFromStart.toLong())

            drawContext.canvas.nativeCanvas.drawText(
                time.format(DateTimeFormatter.ofPattern("HH:mm")),
                x,
                canvasHeight - 2.dp.toPx(),
                paint
            )
        }
    }
}

// ============================================================================
// UI COMPONENTS
// ============================================================================

@Composable
private fun ValueDisplay(
    metrics: GraphMetrics,
    modifier: Modifier = Modifier
) {
    val trendSymbol = when (metrics.trend) {
        TrendDirection.UP -> "↗"
        TrendDirection.DOWN -> "↘"
        TrendDirection.STABLE -> "→"
    }

    val trendColor = when (metrics.trend) {
        TrendDirection.UP -> Color.Green
        TrendDirection.DOWN -> Color.Red
        TrendDirection.STABLE -> Color.Gray
    }

    Column(
        modifier = modifier.padding(4.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            text = "%.1f".format(metrics.latestValue),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = trendSymbol,
            color = trendColor,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun ScrollIndicators(
    canScrollLeft: Boolean,
    canScrollRight: Boolean,
    isDragging: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left scroll indicator
        if (canScrollLeft) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(
                        Color.White.copy(alpha = if (isDragging) 0.8f else 0.3f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "‹",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Right scroll indicator
        if (canScrollRight) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(
                        Color.White.copy(alpha = if (isDragging) 0.8f else 0.3f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "›",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

private fun getCycleColor(phase: CyclePhase): Color {
    return when (phase) {
        CyclePhase.MENSTRUATION -> menstrualRed
        CyclePhase.FOLLICULAR -> follicleGreen
        CyclePhase.OVULATION -> ovulationPink
        CyclePhase.LUTEAL -> lutealPurple
        else -> Color.Gray
    }
}