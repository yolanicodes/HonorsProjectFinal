// Updated StepAnalyticsActivity.kt with requested changes

package com.example.honoursproject.presentation

import android.annotation.SuppressLint
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material.*
import com.example.honoursproject.CyclePhase
import com.example.honoursproject.dataservices.*
import com.example.honoursproject.presentation.theme.*
import com.example.honoursproject.tile.StepCountTileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
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
    val evaluationDataIntegrity: Boolean = true,
    val bestPerformingPhase: Pair<CyclePhase, Int>? = null
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

                Log.d("StepAnalytics", "Loading data - Evaluation mode: $isEvaluation")

                if (isEvaluation) {
                    loadEvaluationModeData(stepGoal)
                } else {
                    loadNormalModeData(stepGoal)
                }
            } catch (e: Exception) {
                Log.e("StepAnalytics", "Failed to load monthly data", e)
                _uiState.value = StepAnalyticsUiState.Error("Failed to load step data: ${e.message}")
            }
        }
    }


    private suspend fun loadEvaluationModeData(stepGoal: Int) {
        Log.d("StepAnalytics", "Loading evaluation mode data")
        try {
            // Wait for historical data to load
            if (!HealthDataStreamManager.isHistoricalDataLoaded()) {
                HealthDataStreamManager.dataStream
                    .filter { it.isHistoricalDataLoaded }
                    .first()
            }

            // Get daily step data from repository
            val repository = HealthDataRepositoryImpl(
                HealthDatabase.getInstance(context).healthRecordDao(),
                ArchiveDatabase.getInstance(context).archiveRecordDao()
            )

            val dailyAggregates = repository.getDailyAggregates(
                EvaluationState.HISTORICAL_START_DATE,
                EvaluationState.EVALUATION_DATE
            )

            Log.d("StepAnalytics", "Retrieved ${dailyAggregates.size} daily aggregates")

            val monthlyData = mutableListOf<DailyStepData>()

            dailyAggregates.forEach { aggregate ->
                monthlyData.add(
                    DailyStepData(
                        date = aggregate.date,
                        stepCount = aggregate.totalSteps,
                        goalAchieved = aggregate.totalSteps >= stepGoal,
                        dayOfWeek = aggregate.date.dayOfWeek.name.take(3),
                        cyclePhase = parseCyclePhase(""),  // Will get from archive
                        cycleDay = 0  // Will get from archive
                    )
                )
            }

            // Get current snapshot for today's live data
            val snapshot = HealthDataStreamManager.getCurrentSnapshot()

            // Update today's data with live values
            val today = EvaluationState.EVALUATION_DATE
            val updatedMonthlyData = monthlyData.map { dayData ->
                if (dayData.date == today) {
                    dayData.copy(
                        stepCount = snapshot.cumulativeSteps,
                        goalAchieved = snapshot.cumulativeSteps >= stepGoal,
                        cyclePhase = parseCyclePhase(snapshot.cyclePhase),
                        cycleDay = snapshot.cycleDay
                    )
                } else {
                    dayData
                }
            }

            val totalSteps = updatedMonthlyData.sumOf { it.stepCount }
            val avgSteps = if (updatedMonthlyData.isNotEmpty()) totalSteps / updatedMonthlyData.size else 0
            val daysGoalAchieved = updatedMonthlyData.count { it.goalAchieved }
            val maxSteps = updatedMonthlyData.maxOfOrNull { it.stepCount } ?: stepGoal
            val bestPhase = calculateBestPerformingPhase(updatedMonthlyData)

            Log.d("StepAnalytics", "Evaluation summary - Total: $totalSteps, Avg: $avgSteps, Current: ${snapshot.cumulativeSteps}")

            val summary = MonthlyStepSummary(
                dailyData = updatedMonthlyData,
                monthRange = "Aug 2023",
                totalSteps = totalSteps,
                avgSteps = avgSteps,
                daysGoalAchieved = daysGoalAchieved,
                stepGoal = stepGoal,
                currentSteps = snapshot.cumulativeSteps,
                maxSteps = maxSteps,
                isEvaluationMode = true,
                evaluationDataIntegrity = true,
                bestPerformingPhase = bestPhase
            )

            _uiState.value = StepAnalyticsUiState.Success(summary)

        } catch (e: Exception) {
            Log.e("StepAnalytics", "Error in loadEvaluationModeData", e)
            _uiState.value = StepAnalyticsUiState.Error("Evaluation mode data error: ${e.message}")
        }
    }


    private suspend fun loadNormalModeData(stepGoal: Int) {
        if (!HealthDataStreamManager.isHistoricalDataLoaded()) {
            HealthDataStreamManager.dataStream
                .filter { it.isHistoricalDataLoaded }
                .first()
        }
        val today = LocalDate.now()
        val startOfMonth = today.withDayOfMonth(1)

        // Get repository
        val repository = HealthDataRepositoryImpl(
            HealthDatabase.getInstance(context).healthRecordDao(),
            ArchiveDatabase.getInstance(context).archiveRecordDao()
        )

        // Get daily aggregates from database
        val dailyAggregates = repository.getDailyAggregates(startOfMonth, today)

        val monthlyData = dailyAggregates.map { aggregate ->
            DailyStepData(
                date = aggregate.date,
                stepCount = aggregate.totalSteps,
                goalAchieved = aggregate.totalSteps >= stepGoal,
                dayOfWeek = aggregate.date.dayOfWeek.name.take(3),
                cyclePhase = parseCyclePhase(""),
                cycleDay = 0
            )
        }

        // Get current live data from stream
        val snapshot = HealthDataStreamManager.getCurrentSnapshot()

        // Update today with live data
        val updatedMonthlyData = monthlyData.map { dayData ->
            if (dayData.date == today) {
                dayData.copy(
                    stepCount = snapshot.cumulativeSteps,
                    goalAchieved = snapshot.cumulativeSteps >= stepGoal,
                    cyclePhase = parseCyclePhase(snapshot.cyclePhase),
                    cycleDay = snapshot.cycleDay
                )
            } else {
                dayData
            }
        }

        val totalSteps = updatedMonthlyData.sumOf { it.stepCount }
        val avgSteps = if (updatedMonthlyData.isNotEmpty()) totalSteps / updatedMonthlyData.size else 0
        val daysGoalAchieved = updatedMonthlyData.count { it.goalAchieved }
        val maxSteps = updatedMonthlyData.maxOfOrNull { it.stepCount } ?: stepGoal
        val bestPhase = calculateBestPerformingPhase(updatedMonthlyData)

        val summary = MonthlyStepSummary(
            dailyData = updatedMonthlyData,
            monthRange = "${startOfMonth.format(DateTimeFormatter.ofPattern("MMM yyyy"))}",
            totalSteps = totalSteps,
            avgSteps = avgSteps,
            daysGoalAchieved = daysGoalAchieved,
            stepGoal = stepGoal,
            currentSteps = snapshot.cumulativeSteps,
            maxSteps = maxSteps,
            isEvaluationMode = false,
            bestPerformingPhase = bestPhase
        )

        _uiState.value = StepAnalyticsUiState.Success(summary)
    }


    private fun calculateBestPerformingPhase(data: List<DailyStepData>): Pair<CyclePhase, Int>? {
        val validPhases = data.filter { it.cyclePhase != CyclePhase.UNKNOWN }

        if (validPhases.isEmpty()) return null

        val phaseAverages = validPhases.groupBy { it.cyclePhase }
            .mapValues { (_, days) ->
                if (days.isNotEmpty()) days.sumOf { it.stepCount } / days.size else 0
            }

        val bestPhase = phaseAverages.maxByOrNull { it.value }

        return bestPhase?.let { Pair(it.key, it.value) }
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

    companion object {
        private const val TAG = "StepAnalyticsActivity"
        private const val ACTIVITY_NAME = "StepAnalyticsActivity"
    }

    private val viewModel: StepAnalyticsViewModel by viewModels {
        StepAnalyticsViewModelFactory(
            applicationContext,
            intent.getIntExtra(IntentExtras.EXTRA_CURRENT_STEPS, -1)  // USE SHARED CONSTANT
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use shared constants instead of StepCountTileService constants
        val tileSteps = intent.getIntExtra(IntentExtras.EXTRA_CURRENT_STEPS, -1)
        val launchedFrom = intent.getStringExtra(IntentExtras.EXTRA_LAUNCHED_FROM)

        Log.d(
            "StepAnalyticsActivity",
            "Launched - From: $launchedFrom, Tile Steps: $tileSteps, Evaluation: ${EvaluationState.isEvaluationMode.value}"
        )

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            StepAnalyticsScreen(state = uiState)
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy - StepAnalyticsActivity closing")

        // Unregister from NavigationCoordinator
        lifecycleScope.launch {
            NavigationCoordinator.onActivityClosed(ACTIVITY_NAME)
        }
    }
}

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
                        if (currentTime - lastUpdate >= 50L) {
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

@SuppressLint("StateFlowValueCalledInComposition")
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
    val snapshot = HealthDataStreamManager.getCurrentSnapshot()
    val today = if (EvaluationState.isEvaluationMode.value) {
        EvaluationState.EVALUATION_DATE
    } else {
        LocalDate.now()
    }

    // Update chart data with live current steps
    val mergedData = data.map { daily ->
        if (daily.date == today && snapshot.isHistoricalDataLoaded) {
            daily.copy(
                stepCount = snapshot.cumulativeSteps,
                goalAchieved = snapshot.cumulativeSteps >= stepGoal
            )
        } else {
            daily
        }
    }

    val chartMax = (stepGoal * 1.67f).toInt().coerceAtLeast(maxSteps)
    val safeMax = chartMax.toFloat()

    var zoomLevel by remember { mutableIntStateOf(1) }
    var scrollOffset by remember { mutableIntStateOf(0) }

    val itemsToShow = when (zoomLevel) {
        1 -> 5
        2 -> 14
        else -> 5
    }

    val totalDays = mergedData.size
    val maxScrollOffset = if (totalDays > itemsToShow) {
        totalDays - itemsToShow
    } else {
        0
    }

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

    var isDragging by remember { mutableStateOf(false) }
    var dragStartX by remember { mutableFloatStateOf(0f) }
    var dragAccumulation by remember { mutableFloatStateOf(0f) }
    var lastScrollTime by remember { mutableLongStateOf(0L) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Step icon with light blue background
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(iconBlue, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.steps_icon),
                contentDescription = "Steps",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

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
                if (maxScrollOffset > 0) {
                    val currentTime = System.currentTimeMillis()

                    if (currentTime - lastScrollTime >= 100L) {
                        val currentDragX = currentPosition.x - dragStartX
                        dragAccumulation += currentDragX

                        val scrollThreshold = 40f

                        if (abs(dragAccumulation) > scrollThreshold) {
                            try {
                                // Prevent scrolling past boundaries
                                if (dragAccumulation > 0 && scrollOffset > 0) {
                                    scrollOffset = (scrollOffset - 1).coerceAtLeast(0)
                                    dragAccumulation = 0f
                                    dragStartX = currentPosition.x
                                } else if (dragAccumulation < 0 && scrollOffset < maxScrollOffset) {
                                    scrollOffset = (scrollOffset + 1).coerceAtMost(maxScrollOffset)
                                    dragAccumulation = 0f
                                    dragStartX = currentPosition.x
                                } else {
                                    // At boundary - reset accumulation
                                    dragAccumulation = 0f
                                }
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
                                    if (zoomLevel == 1) {
                                        zoomLevel = 2
                                        scrollOffset = if (totalDays > 14) totalDays - 14 else 0
                                    } else {
                                        zoomLevel = 1
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
                val chartArea = canvasHeight * 0.85f
                val bottomMargin = canvasHeight * 0.15f

                if (visibleData.isEmpty()) return@Canvas

                val totalSpacing = canvasWidth * 0.4f
                val totalBarArea = canvasWidth * 0.6f
                val barWidth = totalBarArea / visibleData.size
                val spacing = totalSpacing / (visibleData.size + 1)

                val yAxisSteps = 5
                repeat(yAxisSteps + 1) { i ->
                    val y = chartArea * (1 - i.toFloat() / yAxisSteps)
                    val value = (chartMax * i / yAxisSteps)

                    drawLine(
                        color = Color.White.copy(alpha = 0.2f),
                        start = Offset(0f, y),
                        end = Offset(canvasWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )

                    if (i < yAxisSteps) {
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

                val goalY = chartArea * (1 - stepGoal.toFloat() / safeMax)
                drawLine(
                    color = Color.White,
                    start = Offset(0f, goalY),
                    end = Offset(canvasWidth, goalY),
                    strokeWidth = 2.dp.toPx()
                )

                visibleData.forEachIndexed { index, dayData ->
                    val ratio = (dayData.stepCount.toFloat() / safeMax).coerceIn(0f, 1f)
                    val barHeight = ratio * chartArea

                    val x = spacing + index * (barWidth + spacing)
                    val y = chartArea - barHeight

                    val baseColor = getCycleColor(dayData.cyclePhase)
                    val barColor = if (isEvaluationMode && dayData.date == today) {
                        baseColor.copy(alpha = 1f)
                    } else {
                        baseColor.copy(alpha = 0.8f)
                    }

                    drawRect(
                        color = barColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight)
                    )

                    if (dayData.goalAchieved) {
                        drawRect(
                            color = follicleGreen,
                            topLeft = Offset(x, y - 2.dp.toPx()),
                            size = Size(barWidth, 2.dp.toPx())
                        )
                    }

                    // Highlight most recent day with bold text and background
                    val isCurrentDay = dayData.date == today

                    drawContext.canvas.nativeCanvas.apply {
                        // Draw background for current day
                        if (isCurrentDay) {
                            val bgPaint = Paint().apply {
                                color = android.graphics.Color.argb(100, 0, 255, 255)
                                style = Paint.Style.FILL
                            }
                            val textY = canvasHeight - bottomMargin / 2
                            val textX = x + barWidth / 2
                            drawCircle(textX, textY, 8.dp.toPx(), bgPaint)
                        }

                        drawText(
                            dayData.cycleDay.toString(),
                            x + barWidth / 2,
                            canvasHeight - bottomMargin / 2,
                            Paint().apply {
                                color = if (isCurrentDay) {
                                    android.graphics.Color.CYAN
                                } else {
                                    android.graphics.Color.WHITE
                                }
                                textAlign = Paint.Align.CENTER
                                textSize = 9.sp.toPx()
                                isFakeBoldText = isCurrentDay
                            }
                        )
                    }

                    if (zoomLevel == 2 && barHeight > 20.dp.toPx()) {
                        drawContext.canvas.nativeCanvas.apply {
                            drawText(
                                formatStepNumber(dayData.stepCount),
                                x + barWidth / 2,
                                y + barHeight / 2 + 3.dp.toPx(),
                                Paint().apply {
                                    color = android.graphics.Color.WHITE
                                    textAlign = Paint.Align.CENTER
                                    textSize = 7.sp.toPx()
                                    isFakeBoldText = true
                                    setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
                                }
                            )
                        }
                    }
                }

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

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
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

            Spacer(modifier = Modifier.width(8.dp))

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

private fun formatStepNumber(steps: Int): String {
    return when {
        steps >= 10000 -> "${steps / 1000}K"
        steps >= 1000 -> "${steps / 1000}.${(steps % 1000) / 100}K"
        else -> steps.toString()
    }
}

@Composable
fun StepAnalyticsScreen(state: StepAnalyticsUiState) {
    var currentPage by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 2 })

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
                        text = "Loading step data...",
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
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                InteractiveStepChart(
                                    data = state.monthlyData.dailyData,
                                    stepGoal = state.monthlyData.stepGoal,
                                    maxSteps = state.monthlyData.maxSteps,
                                    modifier = Modifier.weight(1f)
                                )

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
                            SummaryPage(summary = state.monthlyData)
                        }
                    }
                }

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
        Text(
            text = "Summary",
            color = WhiteText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    onClick = { }
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = summary.monthRange,
                            color = WhiteText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )

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
                                    color = CyanHZ
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
                                    label = "Current Steps",
                                    value = formatLargeNumber(summary.currentSteps),
                                    color = if (summary.currentSteps >= summary.stepGoal) follicleGreen else WhiteText
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    onClick = { }
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

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    onClick = { }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Cycle Phases",
                            color = WhiteText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CyclePhase.entries.filter { it != CyclePhase.UNKNOWN }.forEach { phase ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
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
                                        fontSize = 9.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    onClick = { }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Performance",
                            color = WhiteText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )

                        val goalPercentage = ((summary.daysGoalAchieved.toFloat() / summary.dailyData.size) * 100).toInt()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Goal Success Rate:",
                                color = WhiteText.copy(alpha = 0.8f),
                                fontSize = 9.sp
                            )
                            Text(
                                text = "$goalPercentage%",
                                color = if (goalPercentage >= 70) follicleGreen
                                else if (goalPercentage >= 50) lutealPurple
                                else menstrualRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val bestDay = summary.dailyData.maxByOrNull { it.stepCount }
                        if (bestDay != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Best Day:",
                                    color = WhiteText.copy(alpha = 0.8f),
                                    fontSize = 9.sp
                                )
                                Text(
                                    text = formatLargeNumber(bestDay.stepCount),
                                    color = follicleGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        summary.bestPerformingPhase?.let { (phase, avgSteps) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Best Phase:",
                                    color = WhiteText.copy(alpha = 0.8f),
                                    fontSize = 9.sp
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = phase.name.lowercase().replaceFirstChar { it.uppercase() },
                                        color = getCycleColor(phase),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "(${formatLargeNumber(avgSteps)})",
                                        color = WhiteText.copy(alpha = 0.7f),
                                        fontSize = 8.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

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
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = label,
            color = WhiteText.copy(alpha = 0.7f),
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
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
        monthRange = "Aug 2023",
        totalSteps = sampleData.sumOf { it.stepCount },
        avgSteps = sampleData.sumOf { it.stepCount } / sampleData.size,
        daysGoalAchieved = sampleData.count { it.goalAchieved },
        stepGoal = 10000,
        currentSteps = 8432,
        maxSteps = sampleData.maxOfOrNull { it.stepCount } ?: 10000,
        isEvaluationMode = true,
        evaluationDataIntegrity = true,
        bestPerformingPhase = Pair(CyclePhase.FOLLICULAR, 12500)
    )

    StepAnalyticsScreen(state = StepAnalyticsUiState.Success(sampleSummary))
}