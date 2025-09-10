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
import com.example.honoursproject.dataservices.SimulationManager
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import com.example.honoursproject.presentation.theme.WhiteText
import com.example.honoursproject.presentation.theme.toTileColorProp
import com.example.honoursproject.presentation.WorkoutActivity
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
import kotlinx.coroutines.launch

//private const val RESOURCES_VERSION = "0"

//data class MenstrualCycleInfo(val phase: CyclePhase, val dayOfCycle: Int)

@OptIn(ExperimentalHorologistApi::class)
class MainTileService : SuspendingTileService() {
    override val serviceScope = CoroutineScope(SupervisorJob() +Dispatchers.IO)
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
                            .setResourceId(R.drawable.run_icon) // Your run icon drawable
                            .build()
                    )
                    .build()
            )
            .addIdToImageMapping(
                "steps_icon", ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder()
                            .setResourceId(R.drawable.steps_icon) // Your steps/walk icon drawable
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
        SimulationManager.startStreaming(this, "P102-Health_Data_Processed.csv")
        val cycleInfo = getMenstrualCycleInfo()
        val singleTileTimeline = TimelineBuilders.Timeline.Builder().addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder().setLayout(
                LayoutElementBuilders.Layout.Builder().setRoot(
                    // Pass the device parameters to the layout function
                    workoutTileLayout(this, requestParams.deviceConfiguration, cycleInfo)
                ).build()
            ).build()
        ).build()

        return TileBuilders.Tile.Builder().setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(singleTileTimeline).build()
    }
    override fun onCreate() {
        super.onCreate()
        //  Just observe SimulationManager
        dataCollectionJob = serviceScope.launch {
            SimulationManager.healthDataStream.collect {
                // Request UI refresh whenever data updates
                getUpdater(this@MainTileService).requestUpdate(MainTileService::class.java)
            }
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        dataCollectionJob?.cancel()
    }

    // Modified to use SimulationService data
    private fun getMenstrualCycleInfo(): MenstrualCycleInfo {
            val healthData = SimulationManager.healthDataStream.value
            return MenstrualCycleInfo(
                phase = CyclePhase.valueOf(healthData.cyclePhase.uppercase()),
                dayOfCycle = healthData.cycleDay
            )
    }
}

private fun workoutTileLayout(
    context: Context,
    deviceParameters: DeviceParametersBuilders.DeviceParameters,
    cycleInfo: MenstrualCycleInfo
): LayoutElementBuilders.LayoutElement {

    val buttonSize = DimensionBuilders.dp(50f) // Size for your workout buttons

    // Clickable for Run Workout
    val runWorkoutClickable = ModifiersBuilders.Clickable.Builder()
        .setId("run_workout_click")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setClassName(WorkoutActivity::class.java.name)
                        .setPackageName(context.packageName)
                        .addKeyToExtraMapping(
                            "workout_type",
                            ActionBuilders.AndroidStringExtra.Builder()
                                .setValue("run")
                                .build()
                        )
                        .build()
                ).build()
        ).build()

    // Clickable for Walk Workout (using steps_icon)
    val walkWorkoutClickable = ModifiersBuilders.Clickable.Builder()
        .setId("walk_workout_click")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setClassName(WorkoutActivity::class.java.name)
                        .setPackageName(context.packageName)
                        .addKeyToExtraMapping(
                            "workout_type",
                            ActionBuilders.AndroidStringExtra.Builder()
                                .setValue("walk")
                                .build()
                        )
                        .build()
                ).build()
        ).build()

    // Get screen dimensions for fullscreen layout
    val deviceHeight = deviceParameters.screenHeightDp.toFloat()
    val deviceWidth = deviceParameters.screenWidthDp.toFloat()

    // NEW: Determine the icon color based on the current menstrual phase.
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
        .setHeight(DimensionBuilders.dp(deviceHeight)) // Set box height to screen height
        .setWidth(DimensionBuilders.dp(deviceWidth))   // Set box width to screen width
        .addContent(
            LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(
                    LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(8f)).build()
                )
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText("TODAY'S WORKOUT")
                        .setFontStyle(
                            // FIXED: Combined font style properties into a single builder call.
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(DimensionBuilders.sp(14f))
                                .setColor(TILE_WhiteText)
                                .build()
                        )
                        .build()
                )
                .addContent(
                    LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(16f)).build()
                )
                .addContent(
                    LayoutElementBuilders.Row.Builder()
                        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                        .addContent(
                            LayoutElementBuilders.Box.Builder()
                                .setModifiers(
                                    ModifiersBuilders.Modifiers.Builder()
                                        .setClickable(runWorkoutClickable)
                                        .setBackground(
                                            ModifiersBuilders.Background.Builder()
                                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(25f)).build()) // Circle shape
                                                .setColor(TILE_iconBlue)
                                                .build()
                                        )
                                        .build()
                                )
                                .setHeight(buttonSize)
                                .setWidth(buttonSize)
                                .addContent(
                                    LayoutElementBuilders.Image.Builder()
                                        .setResourceId("run_icon")
                                        .setWidth(DimensionBuilders.dp(30f))
                                        .setHeight(DimensionBuilders.dp(30f))
                                        .build()
                                )
                                .build()
                        )
                        .addContent(
                            LayoutElementBuilders.Spacer.Builder().setWidth(DimensionBuilders.dp(16f)).build()
                        )
                        .addContent(
                            LayoutElementBuilders.Box.Builder()
                                .setModifiers(
                                    ModifiersBuilders.Modifiers.Builder()
                                        .setClickable(walkWorkoutClickable)
                                        .setBackground(
                                            ModifiersBuilders.Background.Builder()
                                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(25f)).build()) // Circle shape
                                                .setColor(TILE_iconBlue)
                                                .build()
                                        )
                                        .build()
                                )
                                .setHeight(buttonSize)
                                .setWidth(buttonSize)
                                .addContent(
                                    LayoutElementBuilders.Image.Builder()
                                        .setResourceId("steps_icon")
                                        .setWidth(DimensionBuilders.dp(30f))
                                        .setHeight(DimensionBuilders.dp(30f))
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .addContent(
                    LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(8f)).build()
                )
                .addContent(
                    LayoutElementBuilders.Column.Builder()
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(
                            LayoutElementBuilders.Image.Builder()
                                .setResourceId("menstrual_drop_icon")
                                .setWidth(DimensionBuilders.dp(24f))
                                .setHeight(DimensionBuilders.dp(24f))
                                // NEW: Apply a color filter to tint the icon based on the cycle phase.
                                .setColorFilter(
                                    LayoutElementBuilders.ColorFilter.Builder().setTint(phaseColor).build()
                                )
                                .build()
                        )
                        .addContent(
                            LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(4f)).build()
                        )
                        .addContent(
                            LayoutElementBuilders.Text.Builder()
                                // MODIFIED: Use the dynamic day from cycleInfo.
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
        )
        .build()
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