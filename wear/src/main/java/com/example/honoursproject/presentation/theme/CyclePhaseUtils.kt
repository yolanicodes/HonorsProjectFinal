package com.example.honoursproject.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.wear.protolayout.ColorBuilders
import com.example.honoursproject.presentation.theme.*

/**
 * Utility object for cycle phase color management
 * Provides case-insensitive color mapping for cycle phases
 */
object CyclePhaseUtils {

    /**
     * Get cycle phase color from string (case-insensitive)
     * Works with HealthDataSnapshot.cyclePhase
     *
     * Usage:
     *   val snapshot = HealthDataStreamManager.getCurrentSnapshot()
     *   val color = CyclePhaseUtils.getColor(snapshot.cyclePhase)
     *
     * @param cyclePhaseString The cycle phase as string (e.g., "menstruation", "FOLLICULAR", "Ovulation")
     * @return Color for the cycle phase
     */
    fun getColor(cyclePhaseString: String): Color {
        return when (cyclePhaseString) {
            "Menstruation" -> menstrualRed
            "Follicular" -> follicleGreen
            "Ovulation" -> ovulationPink
            "Luteal" -> lutealPurple
            "UNKNOWN", "" -> GrayInactive
            else -> {
                // Fallback: try to match partial strings
                when {
                    cyclePhaseString.contains("menst", ignoreCase = true) -> menstrualRed
                    cyclePhaseString.contains("follic", ignoreCase = true) -> follicleGreen
                    cyclePhaseString.contains("ovul", ignoreCase = true) -> ovulationPink
                    cyclePhaseString.contains("luteal", ignoreCase = true) -> lutealPurple
                    else -> GrayInactive
                }
            }
        }
    }

    /**
     * Get cycle phase color for tiles (case-insensitive)
     * Returns ColorBuilders.ColorProp for tile services
     *
     * Usage:
     *   val snapshot = HealthDataStreamManager.getCurrentSnapshot()
     *   val tileColor = CyclePhaseUtils.getTileColor(snapshot.cyclePhase)
     *
     * @param cyclePhaseString The cycle phase as string
     * @return ColorBuilders.ColorProp for tiles
     */
    fun getTileColor(cyclePhaseString: String): ColorBuilders.ColorProp {
        return when (cyclePhaseString.trim().uppercase()) {
            "MENSTRUATION" -> TILE_menstrualRed
            "FOLLICULAR" -> TILE_follicleGreen
            "OVULATION" -> TILE_ovulationPink
            "LUTEAL" -> TILE_lutealPurple
            "UNKNOWN", "" -> TILE_GrayInactive
            else -> {
                // Fallback: try to match partial strings
                when {
                    cyclePhaseString.contains("menst", ignoreCase = true) -> TILE_menstrualRed
                    cyclePhaseString.contains("follic", ignoreCase = true) -> TILE_follicleGreen
                    cyclePhaseString.contains("ovul", ignoreCase = true) -> TILE_ovulationPink
                    cyclePhaseString.contains("luteal", ignoreCase = true) -> TILE_lutealPurple
                    else -> TILE_GrayInactive
                }
            }
        }
    }

    /**
     * Get cycle phase display name (formatted)
     *
     * @param cyclePhaseString The cycle phase as string
     * @return Formatted display name
     */
    fun getDisplayName(cyclePhaseString: String): String {
        return when (cyclePhaseString.trim().uppercase()) {
            "MENSTRUATION" -> "Menstruation"
            "FOLLICULAR" -> "Follicular"
            "OVULATION" -> "Ovulation"
            "LUTEAL" -> "Luteal"
            "UNKNOWN", "" -> "Unknown"
            else -> cyclePhaseString.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
        }
    }

    /**
     * Check if cycle phase string is valid
     *
     * @param cyclePhaseString The cycle phase as string
     * @return true if recognized cycle phase
     */
    fun isValidPhase(cyclePhaseString: String): Boolean {
        return cyclePhaseString.trim().uppercase() in listOf(
            "MENSTRUATION", "FOLLICULAR", "OVULATION", "LUTEAL"
        )
    }

    /**
     * Get all available cycle phases with their colors
     * Useful for displaying a legend or picker
     *
     * @return Map of phase name to color
     */
    fun getAllPhasesWithColors(): Map<String, Color> {
        return mapOf(
            "Menstruation" to menstrualRed,
            "Follicular" to follicleGreen,
            "Ovulation" to ovulationPink,
            "Luteal" to lutealPurple
        )
    }
}
