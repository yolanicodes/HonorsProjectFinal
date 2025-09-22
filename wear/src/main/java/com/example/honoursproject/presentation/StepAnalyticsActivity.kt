package com.example.honoursproject.presentation

import android.os.Bundle
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material.*
import com.example.honoursproject.dataservices.*
import com.example.honoursproject.presentation.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class DailyStepData(
    val date: LocalDate,
    val stepCount: Int,
    val goalAchieved: Boolean,
    val dayOfWeek: String
)

data class WeeklyStepSummary(
    val dailyData: List<DailyStepData>,
    val weekRange: String,
    val totalSteps: Int,
    val avgSteps: Int,
    val daysGoalAchieved: Int,
    val stepGoal: Int,
    val currentSteps: Int
)

sealed class StepAnalyticsUiState {
    object Loading : StepAnalyticsUiState()
    data class Success(val weeklyData: WeeklyStepSummary) : StepAnalyticsUiState()
    data class Error(val message: String) : StepAnalyticsUiState()
}

class StepAnalyticsViewModel(
    private val context: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<StepAnalyticsUiState>(StepAnalyticsUiState.Loading)
    val uiState: StateFlow<StepAnalyticsUiState> = _uiState.asStateFlow()

    private val archiveDao = ArchiveDatabase.getInstance(context).archiveRecordDao()

    init {
        loadWeeklyData()
    }

    private fun loadWeeklyData() {
        viewModelScope.launch {
            try {
                val today = LocalDate.now()
                val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val endOfWeek = startOfWeek.plusDays(6)

                val stepGoal = getStepGoal()
                val currentSteps = SimulationManager.getCumulativeSteps()

                // Get data for the past 7 days
                val weeklyData = mutableListOf<DailyStepData>()

                for (i in 0..6) {
                    val date = startOfWeek.plusDays(i.toLong())
                    val dayStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val dayEnd = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

                    val daySteps = if (date == today) {
                        // Use current cumulative steps for today
                        currentSteps
                    } else {
                        // Get max step count for the day from archive (since steps are cumulative)
                        val dayRecords = archiveDao.getRecordsBetween(dayStart, dayEnd)
                        dayRecords.maxOfOrNull { it.stepCount } ?: 0
                    }

                    weeklyData.add(
                        DailyStepData(
                            date = date,
                            stepCount = daySteps,
                            goalAchieved = daySteps >= stepGoal,
                            dayOfWeek = date.dayOfWeek.name.take(3) // MON, TUE, etc.
                        )
                    )
                }

                val totalSteps = weeklyData.sumOf { it.stepCount }
                val avgSteps = totalSteps / 7
                val daysGoalAchieved = weeklyData.count { it.goalAchieved }

                val weekRange = "${startOfWeek.format(DateTimeFormatter.ofPattern("MMM dd"))} - ${endOfWeek.format(DateTimeFormatter.ofPattern("MMM dd"))}"

                val summary = WeeklyStepSummary(
                    dailyData = weeklyData,
                    weekRange = weekRange,
                    totalSteps = totalSteps,
                    avgSteps = avgSteps,
                    daysGoalAchieved = daysGoalAchieved,
                    stepGoal = stepGoal,
                    currentSteps = currentSteps
                )

                _uiState.value = StepAnalyticsUiState.Success(summary)

            } catch (e: Exception) {
                _uiState.value = StepAnalyticsUiState.Error("Failed to load step data: ${e.message}")
            }
        }
    }

    private suspend fun getStepGoal(): Int {
        return context.setupDataStore.data
            .first()
            .let { prefs -> prefs[SetupPreferences.STEP_GOAL] ?: 10000 }
    }
}

class StepAnalyticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel by viewModels<StepAnalyticsViewModel> {
            StepAnalyticsViewModelFactory(this.applicationContext)
        }

        setContent {
            val uiState = viewModel.uiState.collectAsState().value
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
                    text = "Weekly Steps",
                    color = WhiteText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = state.weeklyData.weekRange,
                    color = WhiteText.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Bar Chart
                WeeklyStepChart(
                    data = state.weeklyData.dailyData,
                    stepGoal = state.weeklyData.stepGoal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Weekly Summary
                WeeklySummaryCard(summary = state.weeklyData)
            }
        }
    }
}

@Composable
fun WeeklyStepChart(
    data: List<DailyStepData>,
    stepGoal: Int,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return

    val maxSteps = maxOf(data.maxOfOrNull { it.stepCount } ?: 0, stepGoal)
    val goalLineColor = CyanHZ
    val achievedColor = follicleGreen
    val notAchievedColor = lutealPurple.copy(alpha = 0.7f)

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val barWidth = canvasWidth / (data.size * 1.5f) // Leave space between bars
        val spacing = barWidth * 0.3f

        // Draw goal line
        val goalY = canvasHeight * (1 - stepGoal.toFloat() / maxSteps)
        drawLine(
            color = goalLineColor,
            start = Offset(0f, goalY),
            end = Offset(canvasWidth, goalY),
            strokeWidth = 2.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        )

        // Draw bars
        data.forEachIndexed { index, dayData ->
            val barHeight = (dayData.stepCount.toFloat() / maxSteps) * canvasHeight
            val x = index * (barWidth + spacing) + spacing
            val y = canvasHeight - barHeight

            val barColor = if (dayData.goalAchieved) achievedColor else notAchievedColor

            // Draw bar
            drawRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight)
            )

            // Draw day label
            drawContext.canvas.nativeCanvas.apply {
                drawText(
                    dayData.dayOfWeek,
                    x + barWidth / 2,
                    canvasHeight + 20.dp.toPx(),
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = 10.sp.toPx()
                    }
                )
            }
        }
    }
}

@Composable
fun WeeklySummaryCard(summary: WeeklyStepSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(iconBlue.copy(alpha = 0.3f))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
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
                color = CyanHZ
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SummaryItem(
                label = "Goals Hit",
                value = "${summary.daysGoalAchieved}/7",
                color = if (summary.daysGoalAchieved >= 5) follicleGreen else lutealPurple
            )
            SummaryItem(
                label = "Today",
                value = formatLargeNumber(summary.currentSteps),
                color = if (summary.currentSteps >= summary.stepGoal) follicleGreen else WhiteText
            )
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

class StepAnalyticsViewModelFactory(
    private val context: android.content.Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return StepAnalyticsViewModel(context) as T
    }
}

@Preview(device = "id:wearos_large_round", showSystemUi = true)
@Composable
fun StepAnalyticsPreview() {
    val sampleData = (0..6).map { i ->
        DailyStepData(
            date = LocalDate.now().minusDays(6 - i.toLong()),
            stepCount = (5000..15000).random(),
            goalAchieved = (5000..15000).random() >= 10000,
            dayOfWeek = LocalDate.now().minusDays(6 - i.toLong()).dayOfWeek.name.take(3)
        )
    }

    val sampleSummary = WeeklyStepSummary(
        dailyData = sampleData,
        weekRange = "Dec 16 - Dec 22",
        totalSteps = sampleData.sumOf { it.stepCount },
        avgSteps = sampleData.sumOf { it.stepCount } / 7,
        daysGoalAchieved = sampleData.count { it.goalAchieved },
        stepGoal = 10000,
        currentSteps = 8432
    )

    StepAnalyticsScreen(state = StepAnalyticsUiState.Success(sampleSummary))
}