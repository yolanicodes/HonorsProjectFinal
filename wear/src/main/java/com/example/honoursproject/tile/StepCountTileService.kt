package com.example.honoursproject.tile

import android.annotation.SuppressLint
import android.content.ContentValues.TAG
import android.util.Log
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
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
import com.example.honoursproject.dataservices.HealthDataRecord
import com.example.honoursproject.dataservices.HealthDataSnapshot
import com.example.honoursproject.dataservices.HealthDataStreamManager
import com.example.honoursproject.dataservices.IntentExtras
import com.example.honoursproject.presentation.SetupPreferences
import com.example.honoursproject.presentation.setupDataStore
import com.example.honoursproject.presentation.theme.*
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

data class MenstrualCycleInfo(
    val phase: CyclePhase,
    val dayOfCycle: Int
)

@OptIn(ExperimentalHorologistApi::class)
class StepCountTileService : SuspendingTileService() {
    override val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dataCollectionJob: Job? = null

    // Cache management
    @Volatile
    private var cachedHealthData = HealthDataRecord()
    private val cacheExpiryMs = 30000L // 30 seconds
    private var lastCacheUpdate = 0L

    // Update throttling
    private val lastUpdateTime = AtomicLong(0)
    private val minUpdateInterval = 5000L // 5 seconds minimum between tile updates

    companion object {
        private const val CYCLE_ICON_ID = "cycle_icon"
        const val EXTRA_LAUNCHED_FROM = "launched_from"
        const val EXTRA_CURRENT_STEPS = "current_steps"
        const val VALUE_STEP_TILE = "step_tile"
    }

    override fun onCreate() {
        super.onCreate()

        // Cancel any existing job
        dataCollectionJob?.cancel()

        dataCollectionJob = serviceScope.launch {
            // Wait for historical data to be loaded first
            HealthDataStreamManager.dataStream
                .filter { it.isHistoricalDataLoaded }
                .first()

            Log.d(TAG, "Historical data loaded, starting step count tile updates")

            // Now collect all subsequent emissions
            HealthDataStreamManager.dataStream
                .distinctUntilChanged { old, new ->
                    // Only update if relevant fields changed
                    old.cumulativeSteps == new.cumulativeSteps &&
                            old.cyclePhase == new.cyclePhase &&
                            old.cycleDay == new.cycleDay
                }
                .collect { snapshot ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime.get() >= minUpdateInterval) {
                        Log.d(TAG, "Step count data changed - updating tile: " +
                                "Steps=${snapshot.cumulativeSteps}, " +
                                "Cycle=${snapshot.cyclePhase} D${snapshot.cycleDay}")

                        // Update cache
                        updateCacheFromSnapshot(snapshot)

                        // Request tile update
                        requestTileUpdate()
                        lastUpdateTime.set(currentTime)
                    }
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dataCollectionJob?.cancel()
    }

    private fun updateCacheFromSnapshot(snapshot: HealthDataSnapshot) {
        try {
            cachedHealthData = HealthDataRecord(
                heartRate = snapshot.heartRate,
                stepCount = snapshot.cumulativeSteps,
                activeEnergyBurned = snapshot.cumulativeEnergyBurned,
                cyclePhase = snapshot.cyclePhase,
                cycleDay = snapshot.cycleDay,
                basalEnergyBurned = snapshot.basalEnergyBurned,
                vo2Max = snapshot.vo2Max,
                restingHeartRate = snapshot.restingHeartRate,
                basalBodyTemperature = snapshot.basalBodyTemperature
            )
            lastCacheUpdate = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating cache from snapshot", e)
        }
    }

    private fun requestTileUpdate() {
        try {
            getUpdater(this).requestUpdate(StepCountTileService::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting tile update", e)
        }
    }

    override suspend fun resourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ResourceBuilders.Resources {
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

    override suspend fun tileRequest(
        requestParams: RequestBuilders.TileRequest
    ): TileBuilders.Tile {
        val stepGoal = getStepGoal()
        val snapshot = HealthDataStreamManager.getCurrentSnapshot()
        val currentSteps = snapshot.cumulativeSteps

        Log.d(TAG, "Tile request - Steps: $currentSteps, Goal: $stepGoal")

        val menstrualInfo = getMenstrualCycleInfo(snapshot)
        val progressPercentage = if (stepGoal > 0) {
            min(currentSteps.toFloat() / stepGoal.toFloat(), 1.0f)
        } else {
            0f
        }
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

    private fun getMenstrualCycleInfo(snapshot: HealthDataSnapshot): MenstrualCycleInfo {
        val phase = try {
            CyclePhase.valueOf(snapshot.cyclePhase.uppercase())
        } catch (e: IllegalArgumentException) {
            CyclePhase.UNKNOWN
        }
        return MenstrualCycleInfo(phase = phase, dayOfCycle = snapshot.cycleDay)
    }

    private fun createLayout(
        currentSteps: Int,
        stepGoal: Int,
        progressDegrees: Int,
        goalAchieved: Boolean,
        menstrualInfo: MenstrualCycleInfo
    ): LayoutElementBuilders.LayoutElement {
        val activityClassName = "${this.packageName}.presentation.StepAnalyticsActivity"
        Log.d(TAG, "Setting up tile click to launch: $activityClassName")

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
                                                IntentExtras.EXTRA_CURRENT_STEPS,
                                                ActionBuilders.AndroidIntExtra.Builder()
                                                    .setValue(currentSteps)
                                                    .build()
                                            )
                                            .addKeyToExtraMapping(
                                                IntentExtras.EXTRA_LAUNCHED_FROM,
                                                ActionBuilders.AndroidStringExtra.Builder()
                                                    .setValue(IntentExtras.VALUE_STEP_TILE)
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
    private fun createProgressArc(
        progressDegrees: Int,
        goalAchieved: Boolean
    ): LayoutElementBuilders.LayoutElement {
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

    private fun createMenstrualCycleDisplay(
        info: MenstrualCycleInfo
    ): LayoutElementBuilders.LayoutElement {
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