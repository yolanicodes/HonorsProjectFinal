package com.example.fitnessandmensturalcylcevisualisation

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Rect
import android.view.SurfaceHolder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

@SuppressLint("Deprecated")
class FitnessAndMenstrualCycleWatchFaceService : WatchFaceService() {
    override fun createUserStyleSchema(): UserStyleSchema = UserStyleSchema(emptyList())

    override fun createComplicationSlotsManager(
        currentUserStyleRepository: CurrentUserStyleRepository
    ): ComplicationSlotsManager = ComplicationSlotsManager(listOf(), currentUserStyleRepository)

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        // Instantiates the watch face renderer.
        val renderer = ComposeWatchFaceRenderer(
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            currentUserStyleRepository = currentUserStyleRepository
        )

        return WatchFace(
            watchFaceType = WatchFaceType.DIGITAL,
            renderer = renderer
        )

    }


    inner class ComposeWatchFaceRenderer(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        currentUserStyleRepository: CurrentUserStyleRepository
    ) : Renderer.CanvasRenderer2<Renderer.SharedAssets>(
        surfaceHolder = surfaceHolder,
        currentUserStyleRepository = currentUserStyleRepository,
        watchState = watchState,
        canvasType = androidx.wear.watchface.CanvasType.HARDWARE,
        interactiveDrawModeUpdateDelayMillis = 16L, // Standard frame rate.
        clearWithBackgroundTintBeforeRenderingHighlightLayer = false
    ) {

        // Health data state
        private var heartRate by mutableIntStateOf(75)
        private var steps by mutableIntStateOf(4567)
        private var cycleDay by mutableIntStateOf(12)
        private var cyclePhase by mutableStateOf("Follicular")

        // Simulate data updates
        private fun updateHealthData() {
            heartRate = Random.nextInt(60, 180)
            steps = (steps + Random.nextInt(0, 50)).coerceAtMost(15000)
            cycleDay = Random.nextInt(1, 28)
            cyclePhase = listOf("Menstrual", "Follicular", "Ovulation", "Luteal").random()
        }


        override fun render(
            canvas: Canvas,
            bounds: Rect,
            zonedDateTime: ZonedDateTime,
            sharedAssets: SharedAssets
        ) {
            // Update health data periodically
            if (zonedDateTime.second % 5 == 0) {
                updateHealthData()
            }

            // Use Compose to draw the watch face. Note that this is NOT a @Composable function.
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                density = androidx.compose.ui.unit.Density(
                    density = 1f,
                    fontScale = 1f
                ),
                layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
                canvas = androidx.compose.ui.graphics.Canvas(canvas),
                size = androidx.compose.ui.geometry.Size(
                    width = bounds.width().toFloat(),
                    height = bounds.height().toFloat()
                )
            ) {
                drawWatchFace(
                    currentTime = zonedDateTime,
                    heartRate = heartRate,
                    steps = steps,
                    cycleDay = cycleDay,
                    cyclePhase = cyclePhase
                )
            }
        }

        override fun renderHighlightLayer(
            canvas: Canvas,
            bounds: Rect,
            zonedDateTime: ZonedDateTime,
            sharedAssets: SharedAssets
        ) {
            TODO("Not yet implemented")
        }

        override suspend fun createSharedAssets(): SharedAssets = object : SharedAssets {
            override fun onDestroy() {
                // No shared assets to destroy in this example
            }
        }
    }
}

// Compose drawing logic
private fun DrawScope.drawWatchFace(
    currentTime: ZonedDateTime,
    heartRate: Int,
    steps: Int,
    cycleDay: Int,
    cyclePhase: String
) {
    val centerX = size.width / 2f
    val centerY = size.height / 2f

    // Background
    drawRect(
        color = Color(0xFF212A3A),
        size = size
    )

    // Time
    drawTime(currentTime, centerX, centerY - 100f)

    // Health metrics
    drawHeartRate(heartRate, centerX - 120f, centerY + 50f)
    drawSteps(steps, centerX + 120f, centerY + 50f)
    drawCycleInfo(cycleDay, cyclePhase, centerX, centerY + 150f)
}

private fun DrawScope.drawTime(time: ZonedDateTime, x: Float, y: Float) {
    val timeText = time.format(DateTimeFormatter.ofPattern("HH:mm"))

    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = Color.White.toArgb()
            textSize = 48f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
        drawText(timeText, x, y, paint)
    }
}

private fun DrawScope.drawHeartRate(heartRate: Int, x: Float, y: Float) {
    // Heart icon
    drawHeart(x, y - 30f, getHeartRateColor(heartRate))

    // Heart rate text
    val heartRateText = "$heartRate BPM"
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = getHeartRateColor(heartRate).toArgb()
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        drawText(heartRateText, x, y + 20f, paint)
    }
}

private fun DrawScope.drawSteps(steps: Int, x: Float, y: Float) {
    // Steps icon
    drawCircle(
        color = getStepsColor(steps),
        radius = 15f,
        center = Offset(x, y - 30f)
    )

    // Steps text
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = getStepsColor(steps).toArgb()
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        drawText(steps.toString(), x, y + 20f, paint)
    }
}

private fun DrawScope.drawCycleInfo(cycleDay: Int, cyclePhase: String, x: Float, y: Float) {
    // Cycle phase indicator
    val dropletPath = androidx.compose.ui.graphics.Path().apply {
        val centerY = y - 30f
        val radius = 20f

        // Start at the top point of the droplet
        moveTo(x, centerY - radius)

        // Right side curve - more gradual slope from top
        cubicTo(
            x + radius * 0.3f, centerY - radius * 0.9f,  // Control point 1 (gentle slope start)
            x + radius * 0.9f, centerY - radius * 0.4f,  // Control point 2 (wider bulge)
            x + radius * 0.4f, centerY + radius * 0.4f   // End point (bottom-right)
        )

        // Bottom curve - from bottom-right to bottom-left (rounded bottom)
        cubicTo(
            x + radius * 0.2f, centerY + radius * 0.7f,  // Control point 1
            x - radius * 0.2f, centerY + radius * 0.7f,  // Control point 2
            x - radius * 0.4f, centerY + radius * 0.4f   // End point (bottom-left)
        )

        // Left side curve - steeper slope back to top (asymmetrical)
        cubicTo(
            x - radius * 0.9f, centerY - radius * 0.4f,  // Control point 1 (wider bulge)
            x - radius * 0.4f, centerY - radius * 0.9f,  // Control point 2 (steeper slope)
            x, centerY - radius                           // End point (top)
        )

        close()
    }

    // Draw the droplet shape
    drawPath(
        path = dropletPath,
        color = getCycleColor(cyclePhase)
    )
    // Cycle text
    val cycleText = "Day $cycleDay"
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = getCycleColor(cyclePhase).toArgb()
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        drawText(cycleText, x, y + 20f, paint)
    }
}

//Draws the heart indicating the current heart rate
private fun DrawScope.drawHeart(x: Float, y: Float, color: Color) {
    val path = androidx.compose.ui.graphics.Path()
    val size = 20f

    path.moveTo(x, y + size / 2)
    path.cubicTo(x - size / 2, y - size / 2, x - size, y + size / 3, x, y + size)
    path.cubicTo(x + size, y + size / 3, x + size / 2, y - size / 2, x, y + size / 2)

    drawPath(path, color)
}
//Shows red  if heart rate gets too high and yellow if its  too low
private fun getHeartRateColor(heartRate: Int): Color = when {
    heartRate > 150 -> Color.Red
    heartRate > 120 -> Color.Yellow
    else -> Color.Cyan
}
//Displays  grey color if no steps are recorded
private fun getStepsColor(steps: Int): Color = when {
    steps >0-> Color.Cyan
    else -> Color.Gray
}
  //Changes the colour  of  cycle  indicator according to the current phase  of  the  cycle
private fun getCycleColor(phase: String): Color = when (phase.lowercase()) {
    "menstrual" -> Color.Red
    "follicular" -> Color.Green
    "ovulation" -> Color.Magenta
    "luteal" -> Color.Blue
    else -> Color.Gray
}

@Preview(device = "id:wearos_large_round", showBackground = true, backgroundColor = 0xff000000)
@Composable
fun WatchFacePreview() {
    // This Composable is for preview purposes only and will not be part of the actual watch face.
    // It simulates the drawing logic in a Composable environment.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            // Simulate data for preview
            val currentTime = ZonedDateTime.now()
            val heartRate = 88
            val steps = 7890
            val cycleDay = 15
            val cyclePhase = "menstrual"

            drawWatchFace(currentTime, heartRate, steps, cycleDay, cyclePhase)
        }
    }
}

