package com.example.honoursproject.tile

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.honoursproject.CyclePhase
import com.example.honoursproject.R
import com.example.honoursproject.dataservices.HealthDataStreamManager
import com.example.honoursproject.dataservices.IntentExtras
import com.example.honoursproject.dataservices.SimulationManager
import com.example.honoursproject.dataservices.WorkoutDetails
import com.example.honoursproject.dataservices.WorkoutSimulationManager
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import com.example.honoursproject.presentation.theme.WhiteText
import com.example.honoursproject.presentation.theme.toTileColorProp
import com.example.honoursproject.presentation.workoutsimulation.WorkoutActivity
import com.example.honoursproject.presentation.theme.TILE_WhiteText
import com.example.honoursproject.presentation.theme.TILE_backgroundBlue
import com.example.honoursproject.presentation.theme.TILE_follicleGreen
import com.example.honoursproject.presentation.theme.TILE_iconBlue
import com.example.honoursproject.presentation.theme.TILE_lutealPurple
import com.example.honoursproject.presentation.theme.TILE_menstrualRed
import com.example.honoursproject.presentation.theme.TILE_ovulationPink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter




//data class MenstrualCycleInfo(val phase: CyclePhase, val dayOfCycle: Int)

@OptIn(ExperimentalHorologistApi::class)
class MainTileService : SuspendingTileService() {
    override val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dataCollectionJob: Job? = null

    override suspend fun resourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(
                "run_icon", ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder()
                            .setResourceId(R.drawable.run_icon)
                            .build()
                    )
                    .build()
            )
            .addIdToImageMapping(
                "steps_icon", ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder()
                            .setResourceId(R.drawable.steps_icon)
                            .build()
                    )
                    .build()
            )
            .addIdToImageMapping(
                "menstrual_drop_icon", ResourceBuilders.ImageResource.Builder()
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
        requestParams: TileRequest
    ): TileBuilders.Tile {
        val schedule = WorkoutSimulationManager.workoutSchedule.first()
        val cycleInfo = getMenstrualCycleInfo()

        // rootLayout is now consistently a LayoutElement
        val rootLayout = if (schedule.upcomingWorkouts.isEmpty()) {
            noWorkoutLayout(this, requestParams.deviceConfiguration, cycleInfo)
        } else {
            workoutTileLayout(this, requestParams.deviceConfiguration, cycleInfo, schedule.upcomingWorkouts)
        }

        val singleTileTimeline = TimelineBuilders.Timeline.Builder().addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder().setLayout(
                // CORRECTED: No cast is needed as rootLayout is now the correct type.
                LayoutElementBuilders.Layout.Builder().setRoot(rootLayout).build()
            ).build()
        ).build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(singleTileTimeline)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        dataCollectionJob = serviceScope.launch {
            launch {
                HealthDataStreamManager.dataStream.collect {
                    getUpdater(this@MainTileService).requestUpdate(MainTileService::class.java)
                }
            }
            launch {
                WorkoutSimulationManager.workoutSchedule.collect {
                    getUpdater(this@MainTileService).requestUpdate(MainTileService::class.java)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dataCollectionJob?.cancel()
    }

    private fun getMenstrualCycleInfo(): MenstrualCycleInfo {
        val snapshot = HealthDataStreamManager.getCurrentSnapshot()
        val cyclePhase = try {
            CyclePhase.valueOf(snapshot.cyclePhase.uppercase())
        } catch (e: Exception) {
            CyclePhase.UNKNOWN
        }
        return MenstrualCycleInfo(phase = cyclePhase, dayOfCycle = snapshot.cycleDay)
    }
}
private fun noWorkoutLayout(
    context: Context,
    deviceParameters: DeviceParametersBuilders.DeviceParameters,
    cycleInfo: MenstrualCycleInfo
): LayoutElementBuilders.LayoutElement { // This function correctly returns LayoutElement
    return LayoutElementBuilders.Box.Builder()
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setBackground(
                    ModifiersBuilders.Background.Builder().setColor(TILE_backgroundBlue).build()
                )
                .build()
        )
        .setHeight(DimensionBuilders.dp(deviceParameters.screenHeightDp.toFloat()))
        .setWidth(DimensionBuilders.dp(deviceParameters.screenWidthDp.toFloat()))
        .addContent(
            LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText("No workouts are found for the day")
                        .setMaxLines(3) // Example: allow up to 3 lines
                        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER) // Or START/END
                        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_UNDEFINED)
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(DimensionBuilders.sp(14f))
                                .setColor(TILE_WhiteText)
                                .build()
                        ).
                        build()
                )
                .build()
        ).build() // build() is called here, returning a Box, which is a LayoutElement.
}


private fun workoutTileLayout(
    context: Context,
    deviceParameters: DeviceParametersBuilders.DeviceParameters,
    cycleInfo: MenstrualCycleInfo,
    workouts: List<WorkoutDetails>
): LayoutElementBuilders.LayoutElement {

    val workoutIcons = mapOf(
        "Running" to "run_icon",
        "Walking" to "steps_icon",
    )

    val workoutIconsRow = LayoutElementBuilders.Row.Builder()
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    workouts.forEachIndexed { index, workout ->
        val iconId = if (workout.workoutType.contains("walk", ignoreCase = true)) {
            "steps_icon"
        } else {
            "run_icon"
        }

        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId("workout_click_${workout.workoutType}")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setClassName(WorkoutActivity::class.java.name)
                            .setPackageName(context.packageName)
                            .addKeyToExtraMapping(
                                IntentExtras.EXTRA_WORKOUT_TYPE,  // FIXED: Use IntentExtras
                                ActionBuilders.AndroidStringExtra.Builder()
                                    .setValue(workout.workoutType).build()
                            )
                            .addKeyToExtraMapping(
                                IntentExtras.EXTRA_WORKOUT_START_TIME,  // FIXED: Use IntentExtras
                                ActionBuilders.AndroidStringExtra.Builder()
                                    .setValue(workout.startTime.toString()).build()
                            )
                            .addKeyToExtraMapping(
                                IntentExtras.EXTRA_WORKOUT_DURATION,  // FIXED: Use IntentExtras
                                ActionBuilders.AndroidIntExtra.Builder()
                                    .setValue(workout.duration.toInt())
                                    .build()
                            )
                            .build()
                    ).build()
            ).build()

        val workoutColumn = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(25f)).build())
                                    .setColor(TILE_iconBlue)
                                    .build()
                            )
                            .build()
                    )
                    .setHeight(DimensionBuilders.dp(50f))
                    .setWidth(DimensionBuilders.dp(50f))
                    .addContent(
                        LayoutElementBuilders.Image.Builder()
                            .setResourceId(iconId)
                            .setWidth(DimensionBuilders.dp(30f))
                            .setHeight(DimensionBuilders.dp(30f))
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(4f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(workout.startTime.format(timeFormatter))
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(12f))
                            .setColor(TILE_WhiteText)
                            .build()
                    )
                    .build()
            )

        // Add the BUILT column to the row builder
        workoutIconsRow.addContent(workoutColumn.build())

        if (index < workouts.size - 1) {
            workoutIconsRow.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(DimensionBuilders.dp(16f)).build())
        }
    }

    val deviceHeight = deviceParameters.screenHeightDp.toFloat()
    val deviceWidth = deviceParameters.screenWidthDp.toFloat()

    val phaseColor = when (cycleInfo.phase) {
        CyclePhase.MENSTRUATION -> TILE_menstrualRed
        CyclePhase.FOLLICULAR -> TILE_follicleGreen
        CyclePhase.OVULATION -> TILE_ovulationPink
        CyclePhase.LUTEAL -> TILE_lutealPurple
        CyclePhase.UNKNOWN -> TILE_WhiteText
    }


    return LayoutElementBuilders.Box.Builder()
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setBackground(
                    ModifiersBuilders.Background.Builder().setColor(TILE_backgroundBlue).build()
                )
                .build()
        )
        .setHeight(DimensionBuilders.dp(deviceHeight))
        .setWidth(DimensionBuilders.dp(deviceWidth))
        .addContent(
            LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(8f)).build())
                .addContent(
                    LayoutElementBuilders.Column.Builder()
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(8f)).build())
                        .addContent(LayoutElementBuilders.Text.Builder()
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(DimensionBuilders.sp(14f))
                                    .setColor(TILE_WhiteText)
                                    .build()
                        ).build()
                    ).build()
                )
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                // Add "Today's Workout" Title
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText("Today's Workout")
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(DimensionBuilders.sp(14f))
                                .setColor(TILE_WhiteText)
                                .build()
                        ).build()
                )
                .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(16f)).build())
                .addContent(workoutIconsRow.build())
                .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(8f)).build())
                .addContent(
                    LayoutElementBuilders.Column.Builder()
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(
                            LayoutElementBuilders.Image.Builder()
                                .setResourceId("menstrual_drop_icon")
                                .setWidth(DimensionBuilders.dp(24f))
                                .setHeight(DimensionBuilders.dp(24f))
                                .setColorFilter(
                                    LayoutElementBuilders.ColorFilter.Builder().setTint(phaseColor).build()
                                )
                                .build()
                        )
                        .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(4f)).build())
                        .addContent(
                            LayoutElementBuilders.Text.Builder()
                                .setText(cycleInfo.dayOfCycle.toString())
                                .setFontStyle(
                                    LayoutElementBuilders.FontStyle.Builder()
                                        .setSize(DimensionBuilders.sp(12f))
                                        .setColor(WhiteText.toTileColorProp())
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .build()
        ).build()
}
@Preview(
    device = WearDevices.SMALL_ROUND,
    showSystemUi = true,
    backgroundColor = 0xFF212a3a,
    showBackground = true
)
@Composable
fun TilePreview() {
    LocalContext.current
    DeviceParametersBuilders.DeviceParameters.Builder()
        .setScreenHeightDp(200)
        .setScreenWidthDp(200)
        .setScreenDensity(1.5f)
        .setFontScale(1.0f)
        .build()

    // In a real app, you might want to create a sample cycleInfo object for previewing.
    MenstrualCycleInfo(CyclePhase.OVULATION, 14)

    // TODO: Update preview method as LayoutRootPreview is deprecated.
    // For now, you can test the layout by running the tile on an emulator/device.
    // To preview this specific layout, you would call:
    // LayoutRootPreview(root = workoutTileLayout(context, deviceParameters, sampleCycleInfo))
}