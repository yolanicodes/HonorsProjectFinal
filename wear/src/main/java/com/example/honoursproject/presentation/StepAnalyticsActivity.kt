// Updated StepAnalyticsActivity.kt with Interactive Bar Chart

package com.example.honoursproject.presentation

import com.example.honoursproject.R
import android.content.Context
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material.*
import com.example.honoursproject.CyclePhase
import com.example.honoursproject.dataservices.*
import com.example.honoursproject.presentation.theme.*
import com.example.honoursproject.tile.StepCountTileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

// Enhanced data classes for monthly view
data class DailyStepData(
    val date: LocalDate,
    val stepCount: Int,
    val goalAchieved: Boolean,
    val dayOfWeek: String,
    val cyclePhase: CyclePhase = CyclePhase.UNKNOWN,
    val cycleDay: Int = 0
)

data class MonthlyStepSummary(
    val dailyData: List<DailyStepData>,
    val monthRange: String,
    val totalSteps: Int,
    val avgSteps: Int,
    val daysGoalAchieved: Int,
    val stepGoal: Int,
    val currentSteps: Int,
    val maxSteps: Int,
    val isEvaluationMode: Boolean = false,
    val evaluationDataIntegrity: Boolean = true
)

sealed class StepAnalyticsUiState {
    object Loading : StepAnalyticsUiState()
    data class Success(val monthlyData: MonthlyStepSummary) : StepAnalyticsUiState()
    data class Error(val message: String) : StepAnalyticsUiState()
}

class StepAnalyticsViewModel(
    private val context: Context,
    private val tileStepCount: Int = -1
) : ViewModel() {

    private val _uiState = MutableStateFlow<StepAnalyticsUiState>(StepAnalyticsUiState.Loading)
    val uiState: StateFlow<StepAnalyticsUiState> = _uiState.asStateFlow()

    private val archiveDao = ArchiveDatabase.getInstance(context).archiveRecordDao()

    init {
        loadMonthlyData()
    }

    private fun loadMonthlyData() {
        viewModelScope.launch {
            try {
                val stepGoal = getStepGoal()
                val isEvaluation = EvaluationState.isEvaluationMode.value

                Log.d("StepAnalytics", "Loading data - Evaluation mode: $isEvaluation, Tile steps: $tileStepCount")

                if (isEvaluation) {
                    // First verify that we have proper evaluation data
                    val dataIntegrity = SimulationManager.verifyEvaluationModeData(context)
                    Log.d("StepAnalytics", "Evaluation data integrity check: $dataIntegrity")

                    if (!dataIntegrity) {
                        Log.w("StepAnalytics", "Evaluation data integrity issue detected")
                    }

                    loadEvaluationModeData(stepGoal, dataIntegrity)
                } else {
                    loadNormalModeData(stepGoal)
                }

            } catch (e: Exception) {
                Log.e("StepAnalytics", "Failed to load monthly data", e)
                _uiState.value = StepAnalyticsUiState.Error("Failed to load step data: ${e.message}")
            }
        }
    }

    private suspend fun loadEvaluationModeData(stepGoal: Int, dataIntegrity: Boolean) {
        Log.d("StepAnalytics", "Loading evaluation mode data with integrity: $dataIntegrity")

        try {
            // Use the SimulationManager's getDailyStepDataForRange method
            val dailyStepPairs = SimulationManager.getDailyStepDataForRange(
                context,
                EvaluationState.HISTORICAL_START_DATE,
                EvaluationState.EVALUATION_DATE
            )

            Log.d("StepAnalytics", "Retrieved ${dailyStepPairs.size} daily step records from SimulationManager")

            val monthlyData = mutableListOf<DailyStepData>()

            // Convert the pairs to DailyStepData objects
            dailyStepPairs.forEach { (date, steps) ->
                // Get cycle information for each day
                val (cyclePhase, cycleDay) = if (date == EvaluationState.EVALUATION_DATE) {
                    // Current evaluation day - use live simulation data
                    val currentHealthData = SimulationManager.healthDataStream.value
                    Pair(parseCyclePhase(currentHealthData.cyclePhase), currentHealthData.cycleDay)
                } else {
                    // Historical days - get from archive data
                    getHistoricalCycleInfo(date)
                }

                monthlyData.add(
                    DailyStepData(
                        date = date,
                        stepCount = steps,
                        goalAchieved = steps >= stepGoal,
                        dayOfWeek = date.dayOfWeek.name.take(3),
                        cyclePhase = cyclePhase,
                        cycleDay = cycleDay
                    )
                )

                Log.d("StepAnalytics", "Evaluation day $date: Steps=$steps, Phase=$cyclePhase, Day=$cycleDay")
            }

            // Use tile step count for current day if available and valid
            val currentSteps = if (tileStepCount > 0) {
                tileStepCount
            } else {
                SimulationManager.getCumulativeSteps()
            }

            // Update today's data with the most current step count
            val updatedMonthlyData = monthlyData.map { dayData ->
                if (dayData.date == EvaluationState.EVALUATION_DATE) {
                    dayData.copy(
                        stepCount = currentSteps,
                        goalAchieved = currentSteps >= stepGoal
                    )
                } else {
                    dayData
                }
            }

            val totalSteps = updatedMonthlyData.sumOf { it.stepCount }
            val avgSteps = if (updatedMonthlyData.isNotEmpty()) totalSteps / updatedMonthlyData.size else 0
            val daysGoalAchieved = updatedMonthlyData.count { it.goalAchieved }
            val maxSteps = updatedMonthlyData.maxOfOrNull { it.stepCount } ?: stepGoal

            Log.d("StepAnalytics", "Evaluation summary - Total: $totalSteps, Avg: $avgSteps, Goals: $daysGoalAchieved, Current: $currentSteps")

            val summary = MonthlyStepSummary(
                dailyData = updatedMonthlyData,
                monthRange = "Aug 2023 (Evaluation)",
                totalSteps = totalSteps,
                avgSteps = avgSteps,
                daysGoalAchieved = daysGoalAchieved,
                stepGoal = stepGoal,
                currentSteps = currentSteps,
                maxSteps = maxSteps,
                isEvaluationMode = true,
                evaluationDataIntegrity = dataIntegrity
            )

            _uiState.value = StepAnalyticsUiState.Success(summary)

        } catch (e: Exception) {
            Log.e("StepAnalytics", "Error in loadEvaluationModeData", e)
            _uiState.value = StepAnalyticsUiState.Error("Evaluation mode data error: ${e.message}")
        }
    }

    private suspend fun loadNormalModeData(stepGoal: Int) {
        val today = LocalDate.now()
        val startOfMonth = today.withDayOfMonth(1)

        // Use tile step count for current day if available
        val currentSteps = if (tileStepCount > 0) {
            Log.d("StepAnalytics", "Using tile step count: $tileStepCount")
            tileStepCount
        } else {
            Log.d("StepAnalytics", "Using simulation manager step count: ${SimulationManager.getCumulativeSteps()}")
            SimulationManager.getCumulativeSteps()
        }

        // Use SimulationManager's method for consistency
        val dailyStepPairs = SimulationManager.getDailyStepDataForRange(
            context,
            startOfMonth,
            today
        )

        val monthlyData = dailyStepPairs.map { (date, steps) ->
            val (cyclePhase, cycleDay) = if (date == today) {
                val currentHealthData = SimulationManager.healthDataStream.value
                Pair(parseCyclePhase(currentHealthData.cyclePhase), currentHealthData.cycleDay)
            } else {
                getHistoricalCycleInfo(date)
            }

            val actualSteps = if (date == today) currentSteps else steps

            DailyStepData(
                date = date,
                stepCount = actualSteps,
                goalAchieved = actualSteps >= stepGoal,
                dayOfWeek = date.dayOfWeek.name.take(3),
                cyclePhase = cyclePhase,
                cycleDay = cycleDay
            )
        }

        val totalSteps = monthlyData.sumOf { it.stepCount }
        val avgSteps = if (monthlyData.isNotEmpty()) totalSteps / monthlyData.size else 0
        val daysGoalAchieved = monthlyData.count { it.goalAchieved }
        val maxSteps = monthlyData.maxOfOrNull { it.stepCount } ?: stepGoal

        val summary = MonthlyStepSummary(
            dailyData = monthlyData,
            monthRange = "${startOfMonth.format(DateTimeFormatter.ofPattern("MMM yyyy"))}",
            totalSteps = totalSteps,
            avgSteps = avgSteps,
            daysGoalAchieved = daysGoalAchieved,
            stepGoal = stepGoal,
            currentSteps = currentSteps,
            maxSteps = maxSteps,
            isEvaluationMode = false
        )

        _uiState.value = StepAnalyticsUiState.Success(summary)
    }

    private suspend fun getHistoricalCycleInfo(date: LocalDate): Pair<CyclePhase, Int> {
        return try {
            val dayStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val dayRecords = archiveDao.getRecordsBetween(dayStart, dayEnd)

            if (dayRecords.isNotEmpty()) {
                val lastRecord = dayRecords.last()
                Pair(parseCyclePhase(lastRecord.cyclePhase), lastRecord.cycleDay)
            } else {
                Log.d("StepAnalytics", "No cycle info found for $date")
                Pair(CyclePhase.UNKNOWN, 0)
            }
        } catch (e: Exception) {
            Log.e("StepAnalytics", "Error getting historical cycle info for $date", e)
            Pair(CyclePhase.UNKNOWN, 0)
        }
    }

    private fun parseCyclePhase(phaseString: String): CyclePhase {
        return try {
            when (phaseString.trim().uppercase()) {
                "MENSTRUATION", "MENSTRUAL", "PERIOD" -> CyclePhase.MENSTRUATION
                "FOLLICULAR", "FOLLICLE" -> CyclePhase.FOLLICULAR
                "OVULATION", "OVULATORY" -> CyclePhase.OVULATION
                "LUTEAL" -> CyclePhase.LUTEAL
                else -> CyclePhase.UNKNOWN
            }
        } catch (e: Exception) {
            Log.w("StepAnalytics", "Could not parse cycle phase: '$phaseString', using UNKNOWN")
            CyclePhase.UNKNOWN
        }
    }

    private suspend fun getStepGoal(): Int {
        return context.setupDataStore.data
            .first()
            .let { prefs -> prefs[SetupPreferences.STEP_GOAL] ?: 10000 }
    }
}

class StepAnalyticsActivity : ComponentActivity() {

    private val viewModel: StepAnalyticsViewModel by viewModels {
        StepAnalyticsViewModelFactory(
            applicationContext,
            intent.getIntExtra(StepCountTileService.EXTRA_CURRENT_STEPS, -1)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enhanced logging for tile integration
        val tileSteps = intent.getIntExtra(StepCountTileService.EXTRA_CURRENT_STEPS, -1)
        val launchedFrom = intent.getStringExtra(StepCountTileService.EXTRA_LAUNCHED_FROM)

        Log.d("StepAnalyticsActivity", "Launched - From: $launchedFrom, Tile Steps: $tileSteps, Evaluation: ${EvaluationState.isEvaluationMode.value}")

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            StepAnalyticsScreen(state = uiState)
        }
    }
}

// Crash-safe gesture handler wrapper
@Composable
fun SafeDragGestureHandler(
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Offset, Offset) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var lastUpdate by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    try {
                        isDragging = true
                        lastUpdate = System.currentTimeMillis()
                        onDragStart(offset)
                    } catch (e: Exception) {
                        Log.e("SafeDragGestureHandler", "Error in onDragStart", e)
                        isDragging = false
                    }
                },
                onDragEnd = {
                    try {
                        isDragging = false
                        onDragEnd()
                    } catch (e: Exception) {
                        Log.e("SafeDragGestureHandler", "Error in onDragEnd", e)
                    }
                },
                onDrag = { change, dragAmount ->
                    try {
                        val currentTime = System.currentTimeMillis()
                        // Throttle drag events to prevent crashes from fast scrolling
                        if (currentTime - lastUpdate >= 50L) { // Minimum 50ms between updates
                            onDrag(change.position, dragAmount)
                            lastUpdate = currentTime
                        }
                    } catch (e: Exception) {
                        Log.e("SafeDragGestureHandler", "Error in onDrag", e)
                        isDragging = false
                    }
                }
            )
        }
    ) {
        content()
    }
}
// Interactive Step Chart Component - Updated with 5-day default and 14-day zoom
@Composable
fun InteractiveStepChart(
    data: List<DailyStepData>,
    stepGoal: Int,
    maxSteps: Int,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty() || stepGoal <= 0) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No step data available",
                color = WhiteText.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
        return
    }

    val isEvaluationMode by EvaluationState.isEvaluationMode.collectAsState()
    val today = if (isEvaluationMode) EvaluationState.EVALUATION_DATE else LocalDate.now()

    // Update today's data with live steps
    val liveSteps = SimulationManager.getCumulativeSteps()
    val mergedData = data.map { daily ->
        if (daily.date == today) {
            daily.copy(stepCount = liveSteps, goalAchieved = liveSteps >= stepGoal)
        } else daily
    }

    // Calculate chart parameters
    val chartMax = (stepGoal * 1.67f).toInt().coerceAtLeast(maxSteps) // Show ~67% above goal
    val safeMax = chartMax.toFloat()

    // Zoom state: 1 = 5 days, 2 = 14 days
    var zoomLevel by remember { mutableIntStateOf(1) } // Start with 5-day view
    var scrollOffset by remember { mutableIntStateOf(0) }

    // Calculate visible data based on zoom and scroll
    val itemsToShow = when (zoomLevel) {
        1 -> 5 // Default: show 5 days
        2 -> 14 // Zoom out: show 14 days
        else -> 5
    }

    // Calculate scroll parameters to always show current day as the last item
    val totalDays = mergedData.size
    val maxScrollOffset = if (totalDays > itemsToShow) {
        totalDays - itemsToShow
    } else {
        0
    }

    // Initialize scroll to show current day (last item) by default
    val initialScrollOffset = if (maxScrollOffset > 0) maxScrollOffset else 0
    if (scrollOffset == 0 && maxScrollOffset > 0) {
        scrollOffset = initialScrollOffset
    }

    scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset)

    val visibleData = if (totalDays > itemsToShow) {
        val startIdx = scrollOffset
        val endIdx = (startIdx + itemsToShow).coerceAtMost(totalDays)
        if (startIdx < endIdx) mergedData.subList(startIdx, endIdx) else mergedData.takeLast(itemsToShow)
    } else {
        mergedData
    }

    // Drag state for scrolling with crash prevention
    var isDragging by remember { mutableStateOf(false) }
    var dragStartX by remember { mutableFloatStateOf(0f) }
    var dragAccumulation by remember { mutableFloatStateOf(0f) }
    var lastScrollTime by remember { mutableLongStateOf(0L) }

    Column(
        modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        // Chart title with zoom indicator and drag state feedback
        Icon(
            painter = painterResource(id = R.drawable.steps_icon),
            contentDescription = "Goal",
            tint = Color.White,
            modifier = Modifier.size(12.dp)
        )



        // Main chart canvas with crash-safe gesture handling
        SafeDragGestureHandler(
            onDragStart = { offset ->
                isDragging = true
                dragStartX = offset.x
                dragAccumulation = 0f
            },
            onDragEnd = {
                isDragging = false
                dragAccumulation = 0f
            },
            onDrag = { currentPosition, _ ->
                if (maxScrollOffset > 0) { // Only allow scrolling when there's more data than visible
                    val currentTime = System.currentTimeMillis()

                    // Throttle scroll events to prevent crashes
                    if (currentTime - lastScrollTime >= 100L) {
                        val currentDragX = currentPosition.x - dragStartX
                        dragAccumulation += currentDragX

                        // Use larger threshold to prevent crash from fast scrolling
                        val scrollThreshold = 40f

                        if (abs(dragAccumulation) > scrollThreshold) {
                            try {
                                if (dragAccumulation > 0 && scrollOffset > 0) {
                                    scrollOffset = (scrollOffset - 1).coerceAtLeast(0)
                                } else if (dragAccumulation < 0 && scrollOffset < maxScrollOffset) {
                                    scrollOffset = (scrollOffset + 1).coerceAtMost(maxScrollOffset)
                                }
                                dragAccumulation = 0f
                                dragStartX = currentPosition.x
                                lastScrollTime = currentTime
                            } catch (e: Exception) {
                                Log.e("InteractiveStepChart", "Error during scroll", e)
                            }
                        }
                    }
                }
            }
        ) {
            Canvas(
                modifier = Modifier
                    .width(150.dp)
                    .height(80.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { _ ->
                                try {
                                    // Toggle between 5-day and 14-day views
                                    if (zoomLevel == 1) {
                                        zoomLevel = 2 // Switch to 14-day view
                                        // Reset to show current day
                                        scrollOffset = if (totalDays > 14) totalDays - 14 else 0
                                    } else {
                                        zoomLevel = 1 // Switch to 5-day view
                                        // Reset to show current day
                                        scrollOffset = if (totalDays > 5) totalDays - 5 else 0
                                    }
                                } catch (e: Exception) {
                                    Log.e("InteractiveStepChart", "Error during zoom", e)
                                }
                            }
                        )
                    }
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val chartArea = canvasHeight * 0.85f // Leave space for labels
                val bottomMargin = canvasHeight * 0.15f

                if (visibleData.isEmpty()) return@Canvas

                // Calculate bar dimensions with smaller width and bigger spacing
                val totalSpacing = canvasWidth * 0.4f // Increase spacing to 40% of width
                val totalBarArea = canvasWidth * 0.6f // Bars take 60% of width
                val barWidth = totalBarArea / visibleData.size
                val spacing = totalSpacing / (visibleData.size + 1)

                // Draw Y-axis grid lines and labels
                val yAxisSteps = 5
                repeat(yAxisSteps + 1) { i ->
                    val y = chartArea * (1 - i.toFloat() / yAxisSteps)
                    val value = (chartMax * i / yAxisSteps)

                    // Grid line
                    drawLine(
                        color = Color.White.copy(alpha = 0.2f),
                        start = Offset(0f, y),
                        end = Offset(canvasWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )

                    // Y-axis label
                    if (i < yAxisSteps) { // Don't draw label at the very top
                        drawContext.canvas.nativeCanvas.apply {
                            drawText(
                                formatStepNumber(value),
                                -5.dp.toPx(),
                                y + 3.dp.toPx(),
                                Paint().apply {
                                    color = android.graphics.Color.WHITE
                                    textAlign = Paint.Align.RIGHT
                                    textSize = 8.sp.toPx()
                                    alpha = 180
                                }
                            )
                        }
                    }
                }

                // Draw step goal line (solid white)
                val goalY = chartArea * (1 - stepGoal.toFloat() / safeMax)
                drawLine(
                    color = Color.White,
                    start = Offset(0f, goalY),
                    end = Offset(canvasWidth, goalY),
                    strokeWidth = 2.dp.toPx()
                )

                // Draw bars
                visibleData.forEachIndexed { index, dayData ->
                    val ratio = (dayData.stepCount.toFloat() / safeMax).coerceIn(0f, 1f)
                    val barHeight = ratio * chartArea

                    val x = spacing + index * (barWidth + spacing)
                    val y = chartArea - barHeight

                    // Bar color based on cycle phase
                    val baseColor = getCycleColor(dayData.cyclePhase)
                    val barColor = if (isEvaluationMode && dayData.date == today) {
                        baseColor.copy(alpha = 1f)
                    } else {
                        baseColor.copy(alpha = 0.8f)
                    }

                    // Draw bar
                    drawRect(
                        color = barColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight)
                    )

                    // Goal achievement indicator
                    if (dayData.goalAchieved) {
                        drawRect(
                            color = follicleGreen,
                            topLeft = Offset(x, y - 2.dp.toPx()),
                            size = Size(barWidth, 2.dp.toPx())
                        )
                    }

                    // Current day indicator for evaluation mode
                    if (isEvaluationMode && dayData.date == today) {
                        drawRect(
                            color = Color.Cyan,
                            topLeft = Offset(x - 1.dp.toPx(), y - 1.dp.toPx()),
                            size = Size(barWidth + 2.dp.toPx(), barHeight + 2.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    // Cycle day number on horizontal axis
                    drawContext.canvas.nativeCanvas.apply {
                        drawText(
                            dayData.cycleDay.toString(),
                            x + barWidth / 2,
                            canvasHeight - bottomMargin / 2,
                            Paint().apply {
                                color = if ( dayData.date == today) {
                                    android.graphics.Color.CYAN
                                } else {
                                    android.graphics.Color.WHITE
                                }
                                textAlign = Paint.Align.CENTER
                                textSize = 9.sp.toPx()
                                if (dayData.date == today) {
                                    isFakeBoldText = true
                                }
                            }
                        )
                    }

                    // Show step counts on bars if in 14-day view and bars are tall enough
                    if (zoomLevel == 2 && barHeight > 20.dp.toPx()) {
                        drawContext.canvas.nativeCanvas.apply {
                            drawText(
                                formatStepNumber(dayData.stepCount),
                                x + barWidth / 2,
                                y + barHeight / 2 + 3.dp.toPx(),
                                Paint().apply {
                                    color = android.graphics.Color.WHITE
                                    textAlign = Paint.Align.CENTER
                                    textSize = 7.sp.toPx() // Smaller text for 14-day view
                                    isFakeBoldText = true
                                    setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
                                }
                            )
                        }
                    }
                }


                // Draw drag overlay when actively dragging
                if (isDragging && maxScrollOffset > 0) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.1f),
                        topLeft = Offset(0f, 0f),
                        size = Size(canvasWidth, canvasHeight)
                    )
                }
            }
        }
    }

    // Axis labels with icons - centered
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Current cycle day with cycle icon
            val currentDay = visibleData.lastOrNull {
                it.date == today
            } ?: visibleData.lastOrNull()

            if (currentDay != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.cycle_icon),
                        contentDescription = "Cycle Day",
                        tint = getCycleColor(currentDay.cyclePhase),
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = currentDay.cycleDay.toString(),
                        color = getCycleColor(currentDay.cyclePhase),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Goal with steps icon
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.steps_icon),
                    contentDescription = "Goal",
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = formatStepNumber(stepGoal),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


// Helper function to format step numbers
private fun formatStepNumber(steps: Int): String {
    return when {
        steps >= 10000 -> "${steps / 1000}K"
        steps >= 1000 -> "${steps / 1000}.${(steps % 1000) / 100}K"
        else -> steps.toString()
    }
}

// Updated StepAnalyticsScreen to use the new interactive chart
// Updated StepAnalyticsScreen with swipe navigation
@Composable
fun StepAnalyticsScreen(state: StepAnalyticsUiState) {
    var currentPage by remember { mutableIntStateOf(0) } // 0 = Chart, 1 = Summary
    val pagerState = rememberPagerState(pageCount = { 2 })

    // Sync pager state with current page
    LaunchedEffect(pagerState.currentPage) {
        currentPage = pagerState.currentPage
    }

    when (state) {
        is StepAnalyticsUiState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBlue),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (EvaluationState.isEvaluationMode.collectAsState().value) "Loading evaluation data..." else "Loading step data...",
                        color = WhiteText.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }
        }

        is StepAnalyticsUiState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBlue),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Error",
                        color = menstrualRed,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.message,
                        color = WhiteText.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        is StepAnalyticsUiState.Success -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBlue)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> {
                            // Chart Page
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                // Header with evaluation mode indicator
                                val isEvaluationMode = state.monthlyData.isEvaluationMode
                                if (isEvaluationMode && !state.monthlyData.evaluationDataIntegrity) {
                                    Text(
                                        text = "Data integrity issue detected",
                                        color = menstrualRed,
                                        fontSize = 7.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }

                                // Interactive Bar Chart
                                InteractiveStepChart(
                                    data = state.monthlyData.dailyData,
                                    stepGoal = state.monthlyData.stepGoal,
                                    maxSteps = state.monthlyData.maxSteps,
                                    modifier = Modifier.weight(1f)
                                )

                                // Swipe indicator
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Swipe left for summary",
                                        color = WhiteText.copy(alpha = 0.6f),
                                        fontSize = 8.sp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "→",
                                        color = WhiteText.copy(alpha = 0.6f),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                        1 -> {
                            // Summary Page
                            SummaryPage(summary = state.monthlyData)
                        }
                    }
                }

                // Page indicator
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(2) { index ->
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (currentPage == index) Color.White else Color.White.copy(alpha = 0.3f),
                                    CircleShape
                                )
                        )
                        if (index < 1) Spacer(modifier = Modifier.width(4.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SummaryPage(summary: MonthlyStepSummary) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "Summary",
            color = WhiteText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Scrollable content
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Evaluation mode header
            if (summary.isEvaluationMode) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "EVALUATION DATA",
                            color = ovulationPink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (!summary.evaluationDataIntegrity) {
                            Text(
                                text = "Warning: Data integrity issues detected",
                                color = menstrualRed,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }

            // Main statistics
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    onClick = { /* Handle click if needed */ } ,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Period range
                        Text(
                            text = summary.monthRange,
                            color = WhiteText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        // Main stats grid
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SummaryStatItem(
                                    label = "Total Steps",
                                    value = formatLargeNumber(summary.totalSteps),
                                    color = WhiteText
                                )
                                SummaryStatItem(
                                    label = "Daily Average",
                                    value = formatLargeNumber(summary.avgSteps),
                                    color = if (summary.isEvaluationMode) ovulationPink else CyanHZ
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SummaryStatItem(
                                    label = "Goals Achieved",
                                    value = "${summary.daysGoalAchieved}/${summary.dailyData.size}",
                                    color = if (summary.daysGoalAchieved >= summary.dailyData.size * 0.7)
                                        follicleGreen else lutealPurple
                                )
                                SummaryStatItem(
                                    label = if (summary.isEvaluationMode) "Current Steps" else "Today's Steps",
                                    value = formatLargeNumber(summary.currentSteps),
                                    color = if (summary.currentSteps >= summary.stepGoal) follicleGreen else WhiteText
                                )
                            }
                        }
                    }
                }
            }

            // Goal information
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    onClick = { /* Handle click if needed */ } ,

                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.steps_icon),
                                contentDescription = "Step Goal",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Daily Goal",
                                color = WhiteText.copy(alpha = 0.8f),
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            text = formatLargeNumber(summary.stepGoal),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Cycle phase legend
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),onClick = { /* Handle click if needed */ } ,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Cycle Phases",
                            color = WhiteText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CyclePhase.entries.filter { it != CyclePhase.UNKNOWN }.chunked(2).forEach { phaseRow ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    phaseRow.forEach { phase ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .background(getCycleColor(phase), RoundedCornerShape(3.dp))
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = phase.name.lowercase().replaceFirstChar { it.uppercase() },
                                                color = WhiteText.copy(alpha = 0.8f),
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Performance insights
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { /* Handle click if needed */ } ,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Performance",
                            color = WhiteText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val goalPercentage = ((summary.daysGoalAchieved.toFloat() / summary.dailyData.size) * 100).toInt()
                        Text(
                            text = "$goalPercentage% goal achievement rate",
                            color = if (goalPercentage >= 70) follicleGreen else if (goalPercentage >= 50) lutealPurple else menstrualRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )

                        val bestDay = summary.dailyData.maxByOrNull { it.stepCount }
                        if (bestDay != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Best day: ${formatLargeNumber(bestDay.stepCount)} steps",
                                color = WhiteText.copy(alpha = 0.7f),
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }

            // Back navigation hint
            item {
                Text(
                    text = "Swipe right to return to chart",
                    color = WhiteText.copy(alpha = 0.5f),
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun SummaryStatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = WhiteText.copy(alpha = 0.7f),
            fontSize = 9.sp,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatLargeNumber(number: Int): String {
    return when {
        number >= 100000 -> "${number / 1000}K"
        number >= 10000 -> "${number / 1000}.${(number % 1000) / 100}K"
        number >= 1000 -> "${number / 1000}.${(number % 1000) / 100}K"
        else -> number.toString()
    }
}

private fun getCycleColor(phase: CyclePhase): Color {
    return when (phase) {
        CyclePhase.MENSTRUATION -> menstrualRed
        CyclePhase.FOLLICULAR -> follicleGreen
        CyclePhase.OVULATION -> ovulationPink
        CyclePhase.LUTEAL -> lutealPurple
        else -> Color.Gray
    }
}

class StepAnalyticsViewModelFactory(
    private val appContext: Context,
    private val tileStepCount: Int = -1
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(StepAnalyticsViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return StepAnalyticsViewModel(appContext.applicationContext, tileStepCount) as T
    }
}

@Preview(device = "id:wearos_large_round", showSystemUi = true)
@Composable
fun InteractiveStepAnalyticsPreview() {
    val sampleData = (1..19).map { day ->
        val phases = listOf(CyclePhase.MENSTRUATION, CyclePhase.FOLLICULAR, CyclePhase.OVULATION, CyclePhase.LUTEAL)
        DailyStepData(
            date = LocalDate.of(2023, 8, day),
            stepCount = (3000..15000).random(),
            goalAchieved = (3000..15000).random() >= 10000,
            dayOfWeek = LocalDate.of(2023, 8, day).dayOfWeek.name.take(3),
            cyclePhase = phases[day % phases.size],
            cycleDay = ((day - 1) % 28) + 1
        )
    }

    val sampleSummary = MonthlyStepSummary(
        dailyData = sampleData,
        monthRange = "Aug 2023 (Evaluation)",
        totalSteps = sampleData.sumOf { it.stepCount },
        avgSteps = sampleData.sumOf { it.stepCount } / sampleData.size,
        daysGoalAchieved = sampleData.count { it.goalAchieved },
        stepGoal = 10000,
        currentSteps = 8432,
        maxSteps = sampleData.maxOfOrNull { it.stepCount } ?: 10000,
        isEvaluationMode = true,
        evaluationDataIntegrity = true
    )

    StepAnalyticsScreen(state = StepAnalyticsUiState.Success(sampleSummary))
}