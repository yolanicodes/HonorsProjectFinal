package com.example.honoursproject.tile

import android.annotation.SuppressLint
import android.util.Log
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Arc
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.expression.ProtoLayoutExperimental
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.example.honoursproject.R
import com.example.honoursproject.CyclePhase
import com.example.honoursproject.dataservices.SimulationManager
import com.example.honoursproject.presentation.SetupPreferences
import com.example.honoursproject.presentation.setupDataStore
import com.example.honoursproject.presentation.theme.*
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.min

data class MenstrualCycleInfo(
    val phase: CyclePhase,
    val dayOfCycle: Int
)

@OptIn(ExperimentalHorologistApi::class)
class StepCountTileService : SuspendingTileService() {
    override val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dataCollectionJob: Job? = null

    companion object {
        private const val CYCLE_ICON_ID = "cycle_icon"
        const val EXTRA_LAUNCHED_FROM = "launched_from"
        const val EXTRA_CURRENT_STEPS = "current_steps"
        const val VALUE_STEP_TILE = "step_tile"
    }

    override fun onCreate() {
        super.onCreate()
        dataCollectionJob = serviceScope.launch {
            SimulationManager.healthDataStream.collect {
                getUpdater(this@StepCountTileService).requestUpdate(StepCountTileService::class.java)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dataCollectionJob?.cancel()
    }

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(
                CYCLE_ICON_ID,
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder()
                            .setResourceId(R.drawable.cycle_icon)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val stepGoal = getStepGoal()
        val currentSteps = SimulationManager.getCumulativeSteps()
        Log.d("StepCountTileService", "Tile request - Steps: $currentSteps, Goal: $stepGoal")
        val menstrualInfo = getMenstrualCycleInfo()

        val progressPercentage = if (stepGoal > 0) {
            min(currentSteps.toFloat() / stepGoal.toFloat(), 1.0f)
        } else 0f

        val progressDegrees = (progressPercentage * 360f).toInt()
        val goalAchieved = currentSteps >= stepGoal

        val singleTileTimeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout.Builder()
                            .setRoot(createLayout(currentSteps, stepGoal, progressDegrees, goalAchieved, menstrualInfo))
                            .build()
                    )
                    .build()
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(singleTileTimeline)
            .build()
    }

    private fun getMenstrualCycleInfo(): MenstrualCycleInfo {
        val healthData = SimulationManager.healthDataStream.value
        val phase = try {
            CyclePhase.valueOf(healthData.cyclePhase.uppercase(Locale.ROOT))
        } catch (e: IllegalArgumentException) {
            CyclePhase.UNKNOWN
        }
        return MenstrualCycleInfo(
            phase = phase,
            dayOfCycle = healthData.cycleDay
        )
    }

    private fun createLayout(
        currentSteps: Int,
        stepGoal: Int,
        progressDegrees: Int,
        goalAchieved: Boolean,
        menstrualInfo: MenstrualCycleInfo
    ): LayoutElementBuilders.LayoutElement {
        return Box.Builder()
            .setWidth(dp(184f))
            .setHeight(dp(184f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(TILE_backgroundBlue)
                            .setCorner(Corner.Builder().setRadius(dp(92f)).build())
                            .build()
                    )
                    .setPadding(
                        Padding.Builder()
                            .setAll(dp(8f))
                            .build()
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(this.packageName)
                                            .setClassName("${this.packageName}.presentation.StepAnalyticsActivity")
                                            .addKeyToExtraMapping(
                                                StepCountTileService.EXTRA_CURRENT_STEPS,
                                                ActionBuilders.AndroidIntExtra.Builder()
                                                    .setValue(SimulationManager.getCumulativeSteps())
                                                    .build()
                                            )
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(createProgressArc(progressDegrees, goalAchieved))
            .addContent(createCenterContent(currentSteps, stepGoal, goalAchieved, menstrualInfo))
            .build()
    }

    @SuppressLint("RestrictedApi")
    private fun createProgressArc(progressDegrees: Int, goalAchieved: Boolean): LayoutElementBuilders.LayoutElement {
        return Arc.Builder()
            .setAnchorAngle(degrees(-180f))
            .addContent(
                ArcLine.Builder()
                    .setLength(degrees(360f))
                    .setColor(TILE_GrayInactive)
                    .setThickness(dp(12f))
                    .build()
            )
            .addContent(
                ArcLine.Builder()
                    .setLength(degrees(progressDegrees.toFloat()))
                    .setColor(if (goalAchieved) TILE_follicleGreen else TILE_CyanHZ)
                    .setThickness(dp(12f))
                    .build()
            )
            .build()
    }

    @androidx.annotation.OptIn(ProtoLayoutExperimental::class)
    private fun createCenterContent(
        currentSteps: Int,
        stepGoal: Int,
        goalAchieved: Boolean,
        menstrualInfo: MenstrualCycleInfo
    ): LayoutElementBuilders.LayoutElement {
        val progressPercentage = if (stepGoal > 0) {
            min(currentSteps * 100 / stepGoal, 100)
        } else 0

        return Column.Builder()
            .setWidth(dp(140f))
            .setHeight(dp(140f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(
                Text.Builder()
                    .setText(formatStepCount(currentSteps))
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(if (goalAchieved) TILE_follicleGreen else TILE_WhiteText)
                            .setSize(sp(32f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(
                Text.Builder()
                    .setText(if (goalAchieved) "GOAL ACHIEVED!" else "/${formatStepCount(stepGoal)} GOAL")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(if (goalAchieved) TILE_follicleGreen else TILE_GrayInactive)
                            .setSize(sp(10f))
                            .setWeight(if (goalAchieved) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(
                Text.Builder()
                    .setText("$progressPercentage%")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(TILE_CyanHZ)
                            .setSize(sp(12f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(createMenstrualCycleDisplay(menstrualInfo))
            .addContent(Spacer.Builder().setHeight(dp(8f)).build())
            .build()
    }

    private fun createMenstrualCycleDisplay(info: MenstrualCycleInfo): LayoutElementBuilders.LayoutElement {
        val phaseColor = when (info.phase) {
            CyclePhase.MENSTRUATION -> TILE_menstrualRed
            CyclePhase.FOLLICULAR -> TILE_follicleGreen
            CyclePhase.OVULATION -> TILE_ovulationPink
            CyclePhase.LUTEAL -> TILE_lutealPurple
            CyclePhase.UNKNOWN -> TILE_WhiteText
        }

        return LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(CYCLE_ICON_ID)
                    .setWidth(dp(18f))
                    .setHeight(dp(18f))
                    .setColorFilter(
                        LayoutElementBuilders.ColorFilter.Builder()
                            .setTint(phaseColor)
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setWidth(dp(4f)).build())
            .addContent(
                Text.Builder()
                    .setText(info.dayOfCycle.toString())
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(phaseColor)
                            .setSize(sp(14f))
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun formatStepCount(steps: Int): String {
        return when {
            steps >= 10000 -> "${steps / 1000}K"
            steps >= 1000 -> "${steps / 1000}.${(steps % 1000) / 100}K"
            else -> steps.toString()
        }
    }

    private suspend fun getStepGoal(): Int {
        return setupDataStore.data
            .first()
            .let { prefs -> prefs[SetupPreferences.STEP_GOAL] ?: 10000 }
    }
}