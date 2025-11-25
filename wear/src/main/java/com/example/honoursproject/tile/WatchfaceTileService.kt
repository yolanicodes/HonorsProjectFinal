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
import com.example.honoursproject.dataservices.HealthDataSnapshot
import com.example.honoursproject.dataservices.HealthDataStreamManager
import com.example.honoursproject.dataservices.IntentExtras
import com.example.honoursproject.dataservices.SimulationManager
import com.example.honoursproject.presentation.theme.TILE_follicleGreen
import com.example.honoursproject.presentation.theme.TILE_lutealPurple
import com.example.honoursproject.presentation.theme.TILE_menstrualRed
import com.example.honoursproject.presentation.theme.TILE_ovulationPink
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

val Context.healthDataStore by preferencesDataStore(name = "health_data_store")
const val RESOURCES_VERSION = "1"

// Constants for tile service
private const val METRIC_HEART_RATE = "HEART_RATE"
private const val METRIC_STEPS = "STEPS"
private const val METRIC_ENERGY = "ENERGY"
private const val METRIC_CYCLE = "CYCLE"
private const val EXTRA_METRIC_TYPE = "METRIC_TYPE"
private const val ID_HEART_ICON = "heart_icon"
private const val ID_STEPS_ICON = "steps_icon"
private const val ID_ENERGY_ICON = "energy_icon"
private const val ID_CYCLE_ICON = "cycle_icon"
private const val ID_CALENDAR_ICON = "calendar_icon"

// Data class to track which metrics changed
data class MetricSnapshot(
    val heartRate: Int = 0,
    val stepCount: Int = 0,
    val energyBurned: Int = 0,
    val cycleDay: Int = 0,
    val cyclePhase: String = ""
)

@OptIn(ExperimentalHorologistApi::class)
class WatchfaceTileService : SuspendingTileService() {


    override val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dataCollectionJob: Job? = null

    // Cache current data
    @Volatile
    private var cachedHealthData = HealthDataRecord()
    private val cacheExpiryMs = 30000L // 30 seconds
    private var lastCacheUpdate = 0L

    private val lastUpdateTime = AtomicLong(0)
    private val minUpdateInterval = 5000L // 5 seconds minimum between tile updates

    companion object {
         const val METRIC_HEART_RATE = "HEART_RATE"
        const val METRIC_STEPS = "STEPS"
        const val METRIC_ENERGY = "ENERGY"
        const val METRIC_CYCLE = "CYCLE"
        const val EXTRA_METRIC_TYPE = "METRIC_TYPE"
        const val ID_HEART_ICON = "heart_icon"
        const val ID_STEPS_ICON = "steps_icon"
        const val ID_ENERGY_ICON = "energy_icon"
        const val ID_CYCLE_ICON = "cycle_icon"
        const val ID_CALENDAR_ICON = "calendar_icon"
        var appContext: Context? = null

        fun initialize(context: Context) {
            appContext = context.applicationContext
        }

        fun requestTileUpdate(context: Context) {
            try {
                getUpdater(context).requestUpdate(WatchfaceTileService::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting tile update", e)
            }
        }

        // Legacy compatibility - these methods are called by SimulationManager/DataStreamService
        fun updateTileData(heartRate: Double, steps: Int, energy: Double) {
            appContext?.let { context ->
                requestTileUpdate(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        dataCollectionJob?.cancel()

        dataCollectionJob = serviceScope.launch {
            // Wait for historical data
            HealthDataStreamManager.dataStream
                .filter { it.isHistoricalDataLoaded }
                .first()

            Log.d(TAG, "Historical data loaded, starting watchface tile updates")

            // Collect all changes
            HealthDataStreamManager.dataStream
                .distinctUntilChanged { old, new ->
                    // Update only if data actually changed
                    old.cumulativeSteps == new.cumulativeSteps &&
                            old.heartRate == new.heartRate &&
                            old.cumulativeEnergyBurned == new.cumulativeEnergyBurned &&
                            old.cyclePhase == new.cyclePhase &&
                            old.cycleDay == new.cycleDay
                }
                .collect { snapshot ->
                    val currentTime = System.currentTimeMillis()

                    // Throttle updates to avoid excessive re-renders
                    if (currentTime - lastUpdateTime.get() >= minUpdateInterval) {
                        Log.d(TAG, "Metric change detected: " +
                                "HR=${snapshot.heartRate.toInt()}, " +
                                "Steps=${snapshot.cumulativeSteps}, " +
                                "Cycle=${snapshot.cyclePhase} D${snapshot.cycleDay}")

                        // Update cached data
                        cachedHealthData = HealthDataRecord(
                            heartRate = snapshot.heartRate,
                            stepCount = snapshot.cumulativeSteps,
                            activeEnergyBurned = snapshot.cumulativeEnergyBurned,
                            cyclePhase = snapshot.cyclePhase,
                            cycleDay = snapshot.cycleDay
                        )
                        lastCacheUpdate = System.currentTimeMillis()

                        // Trigger tile update
                        requestTileUpdate(appContext!!)
                        lastUpdateTime.set(currentTime)
                    }
                }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        dataCollectionJob?.cancel()
    }

    private suspend fun updateCacheFromSnapshot(snapshot: HealthDataSnapshot) {
        try {
            cachedHealthData = HealthDataRecord(
                heartRate = snapshot.heartRate,
                stepCount = snapshot.cumulativeSteps,
                activeEnergyBurned = snapshot.cumulativeEnergyBurned,
                cyclePhase = snapshot.cyclePhase,
                cycleDay = snapshot.cycleDay
            )
            lastCacheUpdate = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating cache from snapshot", e)
        }
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

        return try {
            // Get fresh data if cache is expired, otherwise use cache
            val healthData = if (System.currentTimeMillis() - lastCacheUpdate > cacheExpiryMs) {
                withTimeoutOrNull(1500L) {
                    // FIX: Get the current snapshot, not the flow itself
                    HealthDataStreamManager.getCurrentSnapshot().let { snapshot ->
                        HealthDataRecord(
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
                    }
                } ?: cachedHealthData
            } else {
                cachedHealthData
            }

            // Build tile with the full original UI layout
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(
                    TimelineBuilders.Timeline.Builder().addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder().setLayout(
                            LayoutElementBuilders.Layout.Builder()
                                .setRoot(tileLayout(this, requestParams.deviceConfiguration, healthData))
                                .build()
                        ).build()
                    ).build()
                ).build()

        } catch (e: Exception) {
            Log.e(TAG, "Error creating tile", e)
            createFallbackTile()
        }
    }

    private fun createFallbackTile(): TileBuilders.Tile {
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder().addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder().setLayout(
                        LayoutElementBuilders.Layout.Builder()
                            .setRoot(
                                Box.Builder()
                                    .setWidth(DimensionBuilders.expand())
                                    .setHeight(DimensionBuilders.expand())
                                    .setModifiers(
                                        ModifiersBuilders.Modifiers.Builder()
                                            .setBackground(
                                                ModifiersBuilders.Background.Builder()
                                                    .setColor(ColorBuilders.argb(0xFF212A3A.toInt())).build()
                                            )
                                            .build()
                                    )
                                    .addContent(
                                        Text.Builder(this, "Loading...")
                                            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                                            .setColor(ColorBuilders.argb(Color.WHITE))
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    ).build()
                ).build()
            ).build()
    }
}

// --- Full Original UI Layout ---
fun tileLayout(
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
                .addContent(Spacer.Builder().setHeight(DimensionBuilders.weight(0.5f)).build())
                .addContent(dateHeader(context, now))
                .addContent(Spacer.Builder().setHeight(DimensionBuilders.weight(0.5f)).build())
                .addContent(
                    Row.Builder()
                        .setWidth(DimensionBuilders.wrap())
                        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                        .addContent(
                            Column.Builder()
                                .setWidth(DimensionBuilders.wrap())
                                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                .addContent(metricColumn(context, METRIC_HEART_RATE, ID_HEART_ICON, R.drawable.heart_icon, healthData.heartRate.toInt().toString()))
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
                                // FIX: Use healthData.stepCount instead of calling getCumulativeSteps()
                                .addContent(metricColumn(context, METRIC_STEPS, ID_STEPS_ICON, R.drawable.steps_icon, healthData.stepCount.toString()))
                                .addContent(Spacer.Builder().setHeight(DimensionBuilders.dp(16f)).build())
                                // FIX: Use healthData.activeEnergyBurned instead of calling getCumulativeEnergy()
                                .addContent(metricColumn(context, METRIC_ENERGY, ID_ENERGY_ICON, R.drawable.fire_icon, String.format("%.2f", healthData.activeEnergyBurned)))
                                .build()
                        )
                        .build()
                )
                .addContent(Spacer.Builder().setHeight(DimensionBuilders.weight(1f)).build())
                .build()
        )
        .build()
}

private fun clickableForMetric(context: Context, metricType: String, iconResId: Int): ModifiersBuilders.Clickable {
    return ModifiersBuilders.Clickable.Builder()
        .setId(metricType)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(context.packageName)
                        .setClassName("${context.packageName}.presentation.MetricDetailActivity")
                        .addKeyToExtraMapping(
                            IntentExtras.EXTRA_METRIC_TYPE,  // FIXED: Use IntentExtras
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
                .setResourceId(ID_CALENDAR_ICON)
                .setWidth(DimensionBuilders.dp(14f))
                .setHeight(DimensionBuilders.dp(14f))
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
                    context, METRIC_CYCLE,
                    iconResId = R.drawable.cycle_icon
                ))
                .build()
        )
        .addContent(
            LayoutElementBuilders.Image.Builder()
                .setResourceId(ID_CYCLE_ICON)
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