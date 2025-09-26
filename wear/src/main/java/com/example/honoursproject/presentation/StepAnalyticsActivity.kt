// Enhanced StepAnalyticsActivity.kt with monthly historical data and cycle phase colors

package com.example.honoursproject.presentation

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
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
import androidx.core.graphics.toColorInt

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
    val maxSteps: Int
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
                if (EvaluationState.isEvaluationMode.value) {
                    Log.d("StepAnalytics", "Loading evaluation mode data")
                    loadEvaluationModeData(stepGoal)
                } else {
                    Log.d("StepAnalytics", "Loading normal mode data")
                    loadNormalModeData(stepGoal)
                }
            } catch (e: Exception) {
                Log.e("StepAnalytics", "Failed to load monthly data", e)
                _uiState.value = StepAnalyticsUiState.Error("Failed to load step data: ${e.message}")
            }
        }
    }

    private suspend fun loadEvaluationModeData(stepGoal: Int) {
        val isValid = SimulationManager.verifyEvaluationModeData(context)
        if (!isValid) {
            _uiState.value = StepAnalyticsUiState.Error("Evaluation data missing or incomplete")
            return
        }

        val startDate = EvaluationState.HISTORICAL_START_DATE
        val endDate = EvaluationState.EVALUATION_DATE

        val dailySteps = SimulationManager.getDailyStepDataForRange(context, startDate, endDate)

        val monthlyData = dailySteps.map { (date, steps) ->
            val isToday = date == EvaluationState.EVALUATION_DATE
            val (phase, cycleDay) = if (isToday) {
                val record = SimulationManager.healthDataStream.value
                parseCyclePhase(record.cyclePhase) to record.cycleDay
            } else {
                CyclePhase.UNKNOWN to 0
            }

            DailyStepData(
                date = date,
                stepCount = steps,
                goalAchieved = steps >= stepGoal,
                dayOfWeek = date.dayOfWeek.name.take(3),
                cyclePhase = phase,
                cycleDay = cycleDay
            )
        }

        val currentSteps = if (tileStepCount > 0) tileStepCount else SimulationManager.getCumulativeSteps()
        publishSummary(monthlyData, stepGoal, currentSteps, "Aug 2023 (Evaluation)")
    }

    private suspend fun loadNormalModeData(stepGoal: Int) {
        val today = LocalDate.now()
        val startOfMonth = today.withDayOfMonth(1)
        val endOfMonth = today.withDayOfMonth(today.lengthOfMonth())

        val dailySteps = SimulationManager.getDailyStepDataForRange(context, startOfMonth, endOfMonth)

        val monthlyData = dailySteps.map { (date, steps) ->
            val isToday = date == today
            val (phase, cycleDay) = if (isToday) {
                val record = SimulationManager.healthDataStream.value
                parseCyclePhase(record.cyclePhase) to record.cycleDay
            } else {
                CyclePhase.UNKNOWN to 0
            }

            DailyStepData(
                date = date,
                stepCount = steps,
                goalAchieved = steps >= stepGoal,
                dayOfWeek = date.dayOfWeek.name.take(3),
                cyclePhase = phase,
                cycleDay = cycleDay
            )
        }

        val currentSteps = if (tileStepCount > 0) tileStepCount else SimulationManager.getCumulativeSteps()
        val monthRange = "${startOfMonth.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${startOfMonth.year}"
        publishSummary(monthlyData, stepGoal, currentSteps, monthRange)
    }
    private fun publishSummary(
        monthlyData: List<DailyStepData>,
        stepGoal: Int,
        currentSteps: Int,
        monthRange: String
    ) {
        val totalSteps = monthlyData.sumOf { it.stepCount }
        val avgSteps = if (monthlyData.isNotEmpty()) totalSteps / monthlyData.size else 0
        val daysGoalAchieved = monthlyData.count { it.goalAchieved }
        val maxSteps = monthlyData.maxOfOrNull { it.stepCount } ?: stepGoal

        val summary = MonthlyStepSummary(
            dailyData = monthlyData,
            monthRange = monthRange,
            totalSteps = totalSteps,
            avgSteps = avgSteps,
            daysGoalAchieved = daysGoalAchieved,
            stepGoal = stepGoal,
            currentSteps = currentSteps,
            maxSteps = maxSteps
        )
        _uiState.value = StepAnalyticsUiState.Success(summary)
    }


    private suspend fun getEvaluationHistoricalDataForDay(date: LocalDate): Triple<Int, CyclePhase, Int> {
        return try {
            // For evaluation mode, we need to look at the original 2023 timestamps
            val dayStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            Log.d("StepAnalytics", "Getting evaluation historical data for $date (timestamp range: $dayStart to $dayEnd)")

            val dayRecords = archiveDao.getRecordsBetween(dayStart, dayEnd)

            if (dayRecords.isEmpty()) {
                Log.d("StepAnalytics", "No records found for evaluation date $date")
                return Triple(0, CyclePhase.UNKNOWN, 0)
            }

            // Sort records by timestamp and calculate daily step progression
            val sortedRecords = dayRecords.sortedBy { it.timestamp }
            Log.d("StepAnalytics", "Found ${sortedRecords.size} records for evaluation date $date")

            // For daily cumulative steps, we need to calculate the steps gained during this specific day
            // This assumes that stepCount in the database represents cumulative steps
            val dayStartSteps = sortedRecords.first().stepCount
            val dayEndSteps = sortedRecords.last().stepCount
            val dailySteps = dayEndSteps - dayStartSteps + (if (dayStartSteps == 0) dayEndSteps else 0)

            // Alternatively, if the data represents daily steps directly, use the last record:
            val finalDaySteps = sortedRecords.last().stepCount

            // Use whichever makes more sense based on your data structure
            val calculatedSteps = maxOf(dailySteps, finalDaySteps)

            val lastRecord = sortedRecords.last()
            val cyclePhase = parseCyclePhase(lastRecord.cyclePhase)
            val cycleDay = lastRecord.cycleDay

            Log.d("StepAnalytics", "Evaluation historical calculation for $date: dailySteps=$dailySteps, finalDaySteps=$finalDaySteps, using=$calculatedSteps, phase=$cyclePhase, cycleDay=$cycleDay")

            Triple(calculatedSteps, cyclePhase, cycleDay)

        } catch (e: Exception) {
            Log.e("StepAnalytics", "Error getting evaluation historical data for $date", e)
            Triple(0, CyclePhase.UNKNOWN, 0)
        }
    }

    private suspend fun getNormalHistoricalDataForDay(dayStart: Long, dayEnd: Long): Triple<Int, CyclePhase, Int> {
        return try {
            val dayRecords = archiveDao.getRecordsBetween(dayStart, dayEnd)

            if (dayRecords.isEmpty()) {
                Log.d("StepAnalytics", "No historical records found for time range: $dayStart to $dayEnd")
                return Triple(0, CyclePhase.UNKNOWN, 0)
            }

            val sortedRecords = dayRecords.sortedBy { it.timestamp }
            val dailyStepCount = sortedRecords.last().stepCount

            val lastRecord = sortedRecords.last()
            val cyclePhase = parseCyclePhase(lastRecord.cyclePhase)
            val cycleDay = lastRecord.cycleDay

            Log.d("StepAnalytics", "Normal historical data - Steps: $dailyStepCount, Phase: $cyclePhase, CycleDay: $cycleDay")
            Triple(dailyStepCount, cyclePhase, cycleDay)

        } catch (e: Exception) {
            Log.e("StepAnalytics", "Error getting normal historical data: ${e.message}", e)
            Triple(0, CyclePhase.UNKNOWN, 0)
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

        val stepsFromTile = intent.getIntExtra(StepCountTileService.EXTRA_CURRENT_STEPS, -1)
        Log.d("StepAnalyticsActivity", "Launched from tile with steps: $stepsFromTile")

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            StepAnalyticsScreen(state = uiState)
        }
    }
}

@Composable
fun StepAnalyticsScreen(state: StepAnalyticsUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (state) {
            is StepAnalyticsUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp
                    )
                }
            }

            is StepAnalyticsUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.message,
                        color = menstrualRed,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            is StepAnalyticsUiState.Success -> {
                // Header
                Text(
                    text = "Monthly Steps",
                    color = WhiteText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = state.monthlyData.monthRange,
                    color = WhiteText.copy(alpha = 0.8f),
                    fontSize = 8.sp
                )

                Spacer(modifier = Modifier.height(1.dp))

                // Enhanced Bar Chart with cycle colors
                MonthlyStepChart(
                    data = state.monthlyData.dailyData,
                    stepGoal = state.monthlyData.stepGoal,
                    maxSteps = state.monthlyData.maxSteps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Monthly Summary
                MonthlySummaryCard(summary = state.monthlyData)
            }
        }
    }
}

@Composable
fun MonthlyStepChart(
    data: List<DailyStepData>,
    stepGoal: Int,
    maxSteps: Int,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty() || stepGoal <= 0) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No step data available", color = WhiteText.copy(alpha = 0.7f), fontSize = 12.sp)
        }
        return
    }

    val isEvaluationMode by EvaluationState.isEvaluationMode.collectAsState()
    val today = if (isEvaluationMode) EvaluationState.EVALUATION_DATE else LocalDate.now()

    // Update today's data with live steps only if not in evaluation mode or if it's the evaluation day
    val liveSteps = if (isEvaluationMode) {
        SimulationManager.getCumulativeSteps()
    } else {
        SimulationManager.getCumulativeSteps()
    }

    val mergedData = data.map { daily ->
        if (daily.date == today) {
            daily.copy(stepCount = liveSteps, goalAchieved = liveSteps >= stepGoal)
        } else daily
    }

    // Use the maximum between actual max steps and step goal for scaling
    val safeMax = maxOf(maxSteps, stepGoal, 1).toFloat()

    // State for view mode and pagination
    var viewMode by remember { mutableStateOf(if (isEvaluationMode) "7-day" else "7-day") }
    var currentPage by remember { mutableIntStateOf(0) }

    // Adjust items per page based on evaluation mode
    val itemsPerPage = if (viewMode == "3-day") 3 else if (isEvaluationMode) 9 else 7 // Show more days in evaluation mode
    val totalPages = kotlin.math.ceil(mergedData.size.toFloat() / itemsPerPage).toInt()

    // Calculate which data to display
    val startIdx = (currentPage * itemsPerPage).coerceAtMost(mergedData.size - itemsPerPage).coerceAtLeast(0)
    val endIdx = (startIdx + itemsPerPage).coerceAtMost(mergedData.size)
    val displayData = if (startIdx < endIdx) mergedData.subList(startIdx, endIdx) else emptyList()

    Column {
        // Control buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous page button
            Button(
                onClick = {
                    if (currentPage > 0) currentPage--
                },
                enabled = currentPage > 0,
                modifier = Modifier.size(24.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = iconBlue.copy(alpha = 0.7f),
                    disabledBackgroundColor = Color.Gray.copy(alpha = 0.3f)
                )
            ) {
                Text("<", fontSize = 10.sp, color = WhiteText)
            }

            // View mode toggle - show different options for evaluation mode
            Button(
                onClick = {
                    viewMode = when (viewMode) {
                        "3-day" -> if (isEvaluationMode) "9-day" else "7-day"
                        "7-day" -> if (isEvaluationMode) "9-day" else "3-day"
                        "9-day" -> "3-day"
                        else -> "7-day"
                    }
                    currentPage = 0 // Reset to first page when changing modes
                },
                modifier = Modifier.height(24.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isEvaluationMode) ovulationPink.copy(alpha = 0.8f) else CyanHZ.copy(alpha = 0.8f)
                )
            ) {
                Text(
                    text = if (isEvaluationMode) "$viewMode EVAL" else viewMode,
                    fontSize = 8.sp,
                    color = WhiteText
                )
            }

            // Next page button
            Button(
                onClick = {
                    if (currentPage < totalPages - 1) currentPage++
                },
                enabled = currentPage < totalPages - 1,
                modifier = Modifier.size(24.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = iconBlue.copy(alpha = 0.7f),
                    disabledBackgroundColor = Color.Gray.copy(alpha = 0.3f)
                )
            ) {
                Text(">", fontSize = 10.sp, color = WhiteText)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Chart canvas
        Canvas(
            modifier = modifier
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            if (displayData.isEmpty()) return@Canvas

            // Calculate bar dimensions
            val barWidth = (canvasWidth / (displayData.size * 1.2f))
            val spacing = barWidth * 0.2f

            // Draw goal line with special styling for evaluation mode
            val goalY = canvasHeight * (1 - stepGoal.toFloat() / safeMax)
            drawLine(
                color = if (isEvaluationMode) ovulationPink else CyanHZ,
                start = Offset(0f, goalY),
                end = Offset(canvasWidth, goalY),
                strokeWidth = if (isEvaluationMode) 3.dp.toPx() else 2.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    if (isEvaluationMode) floatArrayOf(12f, 6f) else floatArrayOf(8f, 8f)
                )
            )

            // Draw bars with cycle phase colors
            displayData.forEachIndexed { index, dayData ->
                val ratio = (dayData.stepCount.toFloat() / safeMax).coerceIn(0f, 1f)
                val barHeight = ratio * canvasHeight

                val x = index * (barWidth + spacing) + spacing
                val y = canvasHeight - barHeight

                // Choose color based on cycle phase, with special handling for evaluation mode
                val baseColor = getCycleColor(dayData.cyclePhase)
                val barColor = if (isEvaluationMode && dayData.date == today) {
                    // Highlight current evaluation day with a brighter color
                    baseColor.copy(alpha = 1f)
                } else if (isEvaluationMode) {
                    // Make historical days slightly more transparent
                    baseColor.copy(alpha = 0.8f)
                } else {
                    baseColor
                }

                drawRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight)
                )

                // Add goal achievement indicator
                if (dayData.goalAchieved) {
                    val indicatorColor = if (isEvaluationMode) follicleGreen else Color.White
                    drawRect(
                        color = indicatorColor,
                        topLeft = Offset(x, y - 2.dp.toPx()),
                        size = Size(barWidth, 2.dp.toPx())
                    )
                }

                // Special indicator for current evaluation day
                if (isEvaluationMode && dayData.date == today) {
                    // Draw a border around current day bar
                    drawRect(
                        color = WhiteText,
                        topLeft = Offset(x - 1.dp.toPx(), y - 1.dp.toPx()),
                        size = Size(barWidth + 2.dp.toPx(), barHeight + 2.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )
                }

                // Draw day labels
                drawContext.canvas.nativeCanvas.apply {
                    val dayText = if (isEvaluationMode && viewMode == "3-day") {
                        "${dayData.date.dayOfMonth}\n${dayData.dayOfWeek}"
                    } else {
                        dayData.date.dayOfMonth.toString()
                    }

                    drawText(
                        dayText,
                        x + barWidth / 2,
                        canvasHeight + 12.dp.toPx(),
                        android.graphics.Paint().apply {
                            color = if (isEvaluationMode && dayData.date == today) {
                                android.graphics.Color.YELLOW // Highlight current day
                            } else {
                                android.graphics.Color.WHITE
                            }
                            textAlign = android.graphics.Paint.Align.CENTER
                            textSize = if (viewMode == "3-day") 10.sp.toPx() else 8.sp.toPx()
                            if (isEvaluationMode && dayData.date == today) {
                                isFakeBoldText = true
                            }
                        }
                    )

                    // In 3-day mode, also show day name for non-evaluation mode
                    if (viewMode == "3-day" && !isEvaluationMode) {
                        drawText(
                            dayData.dayOfWeek,
                            x + barWidth / 2,
                            canvasHeight + 24.dp.toPx(),
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textAlign = android.graphics.Paint.Align.CENTER
                                textSize = 8.sp.toPx()
                                alpha = 180
                            }
                        )
                    }
                }

                // Show step count on bars for evaluation mode or 3-day mode
                if ((isEvaluationMode || viewMode == "3-day") && barHeight > 15.dp.toPx()) {
                    drawContext.canvas.nativeCanvas.apply {
                        drawText(
                            formatLargeNumber(dayData.stepCount),
                            x + barWidth / 2,
                            y + barHeight / 2 + 4.dp.toPx(),
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textAlign = android.graphics.Paint.Align.CENTER
                                textSize = if (isEvaluationMode) 9.sp.toPx() else 10.sp.toPx()
                                isFakeBoldText = true
                                // Add shadow for better readability
                                setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
                            }
                        )
                    }
                }
            }

            // Draw evaluation mode indicator
            if (isEvaluationMode) {
                drawContext.canvas.nativeCanvas.apply {
                    drawText(
                        "EVALUATION MODE - Aug 2023",
                        canvasWidth / 2,
                        -8.dp.toPx(),
                        android.graphics.Paint().apply {
                            color = "#FF6B35".toColorInt() // Orange color
                            textAlign = android.graphics.Paint.Align.CENTER
                            textSize = 8.sp.toPx()
                            isFakeBoldText = true
                        }
                    )
                }
            }
        }

        // Page indicator dots - enhanced for evaluation mode
        if (totalPages > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(totalPages.coerceAtMost(10)) { pageIndex ->
                    Box(
                        modifier = Modifier
                            .size(if (isEvaluationMode) 8.dp else 6.dp)
                            .background(
                                if (pageIndex == currentPage) {
                                    if (isEvaluationMode) ovulationPink else CyanHZ
                                } else {
                                    Color.Gray
                                },
                                RoundedCornerShape(if (isEvaluationMode) 4.dp else 3.dp)
                            )
                    )
                    if (pageIndex < totalPages - 1) {
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }

                // Show evaluation mode page info
                if (isEvaluationMode) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${currentPage + 1}/$totalPages",
                        color = WhiteText.copy(alpha = 0.7f),
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}

// Enhanced summary card with evaluation mode awareness
@Composable
fun MonthlySummaryCard(summary: MonthlyStepSummary) {
    val isEvaluationMode by EvaluationState.isEvaluationMode.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isEvaluationMode)
                    ovulationPink.copy(alpha = 0.2f)
                else
                    iconBlue.copy(alpha = 0.3f)
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Show evaluation mode indicator in summary
        if (isEvaluationMode) {
            Text(
                text = "📊 EVALUATION DATA",
                color = ovulationPink,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SummaryItem(
                label = "Total",
                value = formatLargeNumber(summary.totalSteps),
                color = WhiteText
            )
            SummaryItem(
                label = "Daily Avg",
                value = formatLargeNumber(summary.avgSteps),
                color = if (isEvaluationMode) ovulationPink else CyanHZ
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SummaryItem(
                label = "Goals Hit",
                value = "${summary.daysGoalAchieved}/${summary.dailyData.size}",
                color = if (summary.daysGoalAchieved >= summary.dailyData.size * 0.7) follicleGreen else lutealPurple
            )
            SummaryItem(
                label = if (isEvaluationMode) "Current" else "Today",
                value = formatLargeNumber(summary.currentSteps),
                color = if (summary.currentSteps >= summary.stepGoal) follicleGreen else WhiteText
            )
        }

        // Cycle phase legend - enhanced for evaluation mode
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CyclePhase.entries.filter { it != CyclePhase.UNKNOWN }.forEach { phase ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(if (isEvaluationMode) 10.dp else 8.dp)
                            .background(getCycleColor(phase), RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = phase.name.take(3),
                        color = WhiteText.copy(alpha = 0.7f),
                        fontSize = if (isEvaluationMode) 7.sp else 6.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SummaryItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = WhiteText.copy(alpha = 0.7f),
            fontSize = 10.sp
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
fun MonthlyStepAnalyticsPreview() {
    val sampleData = (1..30).map { day ->
        val phases = listOf(CyclePhase.MENSTRUATION, CyclePhase.FOLLICULAR, CyclePhase.OVULATION, CyclePhase.LUTEAL)
        DailyStepData(
            date = LocalDate.now().withDayOfMonth(day),
            stepCount = (3000..15000).random(),
            goalAchieved = (3000..15000).random() >= 10000,
            dayOfWeek = LocalDate.now().withDayOfMonth(day).dayOfWeek.name.take(3),
            cyclePhase = phases[day % phases.size],
            cycleDay = ((day - 1) % 28) + 1
        )
    }

    val sampleSummary = MonthlyStepSummary(
        dailyData = sampleData,
        monthRange = "Dec 2023",
        totalSteps = sampleData.sumOf { it.stepCount },
        avgSteps = sampleData.sumOf { it.stepCount } / sampleData.size,
        daysGoalAchieved = sampleData.count { it.goalAchieved },
        stepGoal = 10000,
        currentSteps = 8432,
        maxSteps = sampleData.maxOfOrNull { it.stepCount } ?: 10000
    )

    StepAnalyticsScreen(state = StepAnalyticsUiState.Success(sampleSummary))
}