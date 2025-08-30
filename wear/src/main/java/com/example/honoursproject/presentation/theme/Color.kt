package com.example.honoursproject.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.ColorBuilders


//ALL APP COLOURS
val backgroundBlue = Color(0xFF212a3a)

val iconBlue = Color(0xFF41b8d5)
val WhiteText = Color(0xFFFFFFFF)
val GrayInactive = Color(0xFF9E9E9E)
val menstrualRed= Color(0xFFFF0000)
val lutealPurple = Color(0xFF9C27B0)
val ovulationPink = Color(0xFFff66c4)
val follicleGreen = Color(0xFF4CAF50)
val YellowHZ = Color(0xFFFFbd59)
val OrangeHZ = Color(0xFFd97638)
val darkBlueHZ=Color(0xFF004aad)
val CyanHZ = Color(0xFF5ce1e6)


val maroonHZ=Color(0xff800000)

//Convert Compose colors to tile colors
fun Color.toTileColorProp(): ColorBuilders.ColorProp {
    val argbValue = this.toArgb()
    return ColorBuilders.argb(argbValue)
}
//Tile-Compatible Colors
val TILE_backgroundBlue = backgroundBlue.toTileColorProp()
val TILE_ovulationPink = ovulationPink.toTileColorProp()
val TILE_iconBlue = iconBlue.toTileColorProp()
val TILE_WhiteText = WhiteText.toTileColorProp()
val TILE_GrayInactive = GrayInactive.toTileColorProp()
val TILE_menstrualRed= menstrualRed.toTileColorProp()
val TILE_YellowHZ = YellowHZ.toTileColorProp()
val TILE_OrangeHZ = OrangeHZ.toTileColorProp()
val TILE_darkBlueHZ= darkBlueHZ.toTileColorProp()
val TILE_CyanHZ = CyanHZ.toTileColorProp()
val TILE_lutealPurple = lutealPurple.toTileColorProp()
val TILE_follicleGreen = follicleGreen.toTileColorProp()
val TILE_maroonHZ= maroonHZ.toTileColorProp()