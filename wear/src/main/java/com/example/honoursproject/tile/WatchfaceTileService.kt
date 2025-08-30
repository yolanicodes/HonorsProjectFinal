package com.example.honoursproject.tile

import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.datastore.preferences.preferencesDataStore
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.tooling.preview.TilePreviewData
import com.example.honoursproject.CyclePhase
import com.example.honoursproject.R
import com.example.honoursproject.dataservices.HealthDataRecord
import com.example.honoursproject.dataservices.SimulationManager
import com.example.honoursproject.presentation.theme.TILE_follicleGreen
import com.example.honoursproject.presentation.theme.TILE_lutealPurple
import com.example.honoursproject.presentation.theme.TILE_menstrualRed
import com.example.honoursproject.presentation.theme.TILE_ovulationPink
import com.example.honoursproject.tile.WatchfaceTileService.Companion.ID_ENERGY_ICON
import com.example.honoursproject.tile.WatchfaceTileService.Companion.ID_STEPS_ICON
import com.example.honoursproject.tile.WatchfaceTileService.Companion.METRIC_ENERGY
import com.example.honoursproject.tile.WatchfaceTileService.Companion.METRIC_STEPS
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

val Context.healthDataStore by preferencesDataStore(name = "health_data_store")
const val RESOURCES_VERSION = "1"
@OptIn(ExperimentalHorologistApi::class)
class WatchfaceTileService : SuspendingTileService() {

    override val serviceScope = CoroutineScope(SupervisorJob() +Dispatchers.IO)
    private var dataCollectionJob: Job? = null

    companion object {
        const val ID_HEART_ICON = "heart_icon"
        const val ID_STEPS_ICON = "steps_icon"
        const val ID_ENERGY_ICON = "energy_icon"
        const val ID_CYCLE_ICON = "cycle_icon"
        const val ID_CALENDAR_ICON = "calendar_icon"
        const val EXTRA_METRIC_TYPE = "METRIC_TYPE"
        const val METRIC_HEART_RATE = "HEART_RATE"
        const val METRIC_STEPS = "STEPS"
        const val METRIC_ENERGY = "ENERGY"
        const val METRIC_CYCLE = "CYCLE"

        fun requestTileUpdate(context: Context) {
            val intent = Intent(context, WatchfaceTileService::class.java)
            context.sendBroadcast(intent) // or use TileManager.requestTileUpdate() if available

        }
    }


    override fun onCreate() {
        super.onCreate()
        //  Just observe SimulationManager
        dataCollectionJob = serviceScope.launch {
            SimulationManager.healthDataStream.collect {
                // Request UI refresh whenever data updates
                getUpdater(this@WatchfaceTileService).requestUpdate(WatchfaceTileService::class.java)
            }
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        dataCollectionJob?.cancel()
    }

    override suspend fun resourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ResourceBuilders.Resources {
        Log.d(TAG, "onResourcesRequest called")
        return ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(ID_HEART_ICON, resourceBuilderFor(R.drawable.heart_icon))
            .addIdToImageMapping(ID_STEPS_ICON, resourceBuilderFor(R.drawable.steps_icon))
            .addIdToImageMapping(ID_ENERGY_ICON, resourceBuilderFor(R.drawable.fire_icon))
            .addIdToImageMapping(ID_CYCLE_ICON, resourceBuilderFor(R.drawable.cycle_icon))
            .addIdToImageMapping(ID_CALENDAR_ICON, resourceBuilderFor(R.drawable.calendar_icon))
            .build()
    }

    private fun resourceBuilderFor(resourceId: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(
            ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resourceId).build()
        ).build()


    override suspend fun tileRequest(
        requestParams: RequestBuilders.TileRequest
    ): TileBuilders.Tile {
        Log.d(TAG, "onTileRequest called")
        val latestData = SimulationManager.healthDataStream.value
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder().addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder().setLayout(
                        LayoutElementBuilders.Layout.Builder()
                            .setRoot(tileLayout(this, requestParams.deviceConfiguration, latestData))
                            .build()
                    ).build()
                ).build()
            ).build()
    }
}


// --- Layout Definition ---
private fun tileLayout(
    context: Context,
    deviceParams: DeviceParametersBuilders.DeviceParameters,
    healthData: HealthDataRecord
): LayoutElementBuilders.LayoutElement {
    val cyclePhaseEnum = try {
        CyclePhase.valueOf(healthData.cyclePhase.uppercase())
    } catch (e: IllegalArgumentException) {
        CyclePhase.UNKNOWN
    }
    val cycleColor = getCycleColor(cyclePhaseEnum)
    val now = ZonedDateTime.now()

    return Box.Builder()
        .setWidth(DimensionBuilders.expand())
        .setHeight(DimensionBuilders.expand())
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setBackground(
                    ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(0xFF212A3A.toInt())).build()
                )
                .setPadding(
                    ModifiersBuilders.Padding.Builder().setAll(DimensionBuilders.dp(12f)).build()
                ).build()
        )
        .addContent(
            Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(dateHeader(context, now))
                .addContent(Spacer.Builder().setHeight(DimensionBuilders.weight(1f)).build())
                .addContent(
                    Row.Builder()
                        .setWidth(DimensionBuilders.wrap())
                        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                        .addContent(
                            Column.Builder()
                                .setWidth(DimensionBuilders.wrap())
                                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                .addContent(metricColumn(context, WatchfaceTileService.METRIC_HEART_RATE, WatchfaceTileService.ID_HEART_ICON, R.drawable.heart_icon, healthData.heartRate.toInt().toString()))
                                .addContent(Spacer.Builder().setHeight(DimensionBuilders.dp(16f)).build())
                                .addContent(cycleInfoIcon(context, healthData.cycleDay, cycleColor))
                                .build()
                        )
                        .addContent(Spacer.Builder().setWidth(DimensionBuilders.dp(16f)).build())
                        .addContent(timeDisplay(context, now))
                        .addContent(Spacer.Builder().setWidth(DimensionBuilders.dp(16f)).build())
                        .addContent(
                            Column.Builder()
                                .setWidth(DimensionBuilders.wrap())
                                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                .addContent(metricColumn(context, METRIC_STEPS, ID_STEPS_ICON, R.drawable.steps_icon, SimulationManager.getCumulativeSteps().toString()))
                                .addContent(Spacer.Builder().setHeight(DimensionBuilders.dp(16f)).build())
                                .addContent(metricColumn(context, METRIC_ENERGY, ID_ENERGY_ICON, R.drawable.fire_icon, String.format("%.2f", SimulationManager.getCumulativeEnergy())))
                                .build()
                        )
                        .build()
                )
                .addContent(Spacer.Builder().setHeight(DimensionBuilders.weight(1f)).build())
                .build()
        )
        .build()
}

private fun clickableForMetric(context: Context, metricType: String,iconResId: Int): ModifiersBuilders.Clickable {
    return ModifiersBuilders.Clickable.Builder()
        .setId(metricType)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(context.packageName)
                        .setClassName("${context.packageName}.presentation.MetricDetailActivity")
                        .addKeyToExtraMapping(
                            WatchfaceTileService.EXTRA_METRIC_TYPE,
                            ActionBuilders.AndroidStringExtra.Builder().setValue(metricType).build()
                        )
                        .addKeyToExtraMapping(
                            "iconResId",
                            ActionBuilders.AndroidIntExtra.Builder().setValue(iconResId).build()
                        )
                        .build()
                )
                .build()
        )
        .build()
}






private fun dateHeader(context: Context, time: ZonedDateTime): LayoutElementBuilders.LayoutElement {
    return Row.Builder()
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(
            LayoutElementBuilders.Image.Builder()
                .setResourceId(WatchfaceTileService.ID_CALENDAR_ICON)
                .setWidth(DimensionBuilders.dp(16f))
                .setHeight(DimensionBuilders.dp(16f))
                .build()
        )
        .addContent(Spacer.Builder().setWidth(DimensionBuilders.dp(6f)).build())
        .addContent(
            Text.Builder(context, time.format(DateTimeFormatter.ofPattern("dd MMMM")))
                .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                .setColor(ColorBuilders.argb(Color.WHITE))
                .build()
        )
        .build()
}

private fun timeDisplay(context: Context, time: ZonedDateTime): LayoutElementBuilders.LayoutElement {
    return Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .addContent(
            Text.Builder(context, time.format(DateTimeFormatter.ofPattern("HH")))
                .setTypography(Typography.TYPOGRAPHY_DISPLAY2)
                .setColor(ColorBuilders.argb(Color.WHITE))
                .build()
        )
        .addContent(
            Text.Builder(context, time.format(DateTimeFormatter.ofPattern("mm")))
                .setTypography(Typography.TYPOGRAPHY_DISPLAY2)
                .setColor(ColorBuilders.argb(Color.WHITE))
                .build()
        )
        .build()
}

private fun metricColumn(
    context: Context,
    metricType: String,
    iconId: String,
    iconResId: Int,
    value: String
): LayoutElementBuilders.LayoutElement {
    return Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(clickableForMetric(context, metricType, iconResId))
                .build()
        )
        .addContent(
            LayoutElementBuilders.Image.Builder()
                .setResourceId(iconId)
                .setWidth(DimensionBuilders.dp(24f))
                .setHeight(DimensionBuilders.dp(24f))
                .build()
        )
        .addContent(Spacer.Builder().setHeight(DimensionBuilders.dp(4f)).build())
        .addContent(
            Text.Builder(context, value)
                .setTypography(Typography.TYPOGRAPHY_BODY2)
                .setColor(ColorBuilders.argb(Color.WHITE))
                .build()
        )
        .build()
}

private fun cycleInfoIcon(context: Context, cycleDay: Int, color: ColorBuilders.ColorProp): LayoutElementBuilders.LayoutElement {
    return Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(clickableForMetric(
                    context, WatchfaceTileService.METRIC_CYCLE,
                    iconResId = R.drawable.cycle_icon
                ))
                .build()
        )
        .addContent(
            LayoutElementBuilders.Image.Builder()
                .setResourceId(WatchfaceTileService.ID_CYCLE_ICON)
                .setWidth(DimensionBuilders.dp(24f))
                .setHeight(DimensionBuilders.dp(24f))
                .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(color).build())
                .build()
        )
        .addContent(Spacer.Builder().setHeight(DimensionBuilders.dp(4f)).build())
        .addContent(
            Text.Builder(context, cycleDay.toString())
                .setTypography(Typography.TYPOGRAPHY_BODY2)
                .setColor(color)
                .build()
        )
        .build()
}

private fun getCycleColor(phase: CyclePhase): ColorBuilders.ColorProp = when (phase) {
    CyclePhase.MENSTRUATION -> TILE_menstrualRed
    CyclePhase.FOLLICULAR -> TILE_follicleGreen
    CyclePhase.OVULATION -> TILE_ovulationPink
    CyclePhase.LUTEAL -> TILE_lutealPurple
    else -> ColorBuilders.argb(Color.GRAY)
}

@Preview(
    device = "id:wearos_small_round",
    showSystemUi = true,
    backgroundColor = 0xff212A3A,
    showBackground = true
)
@Composable
fun WatchfaceTileServicePreview() {
    val context = LocalContext.current
    val sampleHealthData = HealthDataRecord(
        heartRate = 89.0,
        stepCount = 4598,
        activeEnergyBurned = 210.0,
        cyclePhase = "LUTEAL",
        cycleDay = 22
    )
    TilePreviewData(
        onTileRequest = { requestParams ->
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(
                    TimelineBuilders.Timeline.Builder().addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder().setLayout(
                            LayoutElementBuilders.Layout.Builder()
                                .setRoot(
                                    tileLayout(
                                        context,
                                        requestParams.deviceConfiguration,
                                        sampleHealthData
                                    )
                                )
                                .build()
                        ).build()
                    ).build()
                ).build()
        }
    )
}
