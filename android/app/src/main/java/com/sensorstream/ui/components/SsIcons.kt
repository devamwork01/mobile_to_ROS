package com.sensorstream.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** A distinct, theme-agnostic accent per sensor icon key, so each sensor reads as its own thing
 *  (the pastel tiles in the reference). Used for the icon tile tint; axis colors stay separate. */
object SsIconTint {
    fun forKey(key: String): Color = when (key) {
        "accel" -> Color(0xFF34C759)
        "gyro" -> Color(0xFFFF9F0A)
        "gravity" -> Color(0xFF5E9CFF)
        "orient" -> Color(0xFF7C5CFF)
        "magnet" -> Color(0xFF22C3C9)
        "pressure" -> Color(0xFF4FA3FF)
        "temp" -> Color(0xFFFF6B6B)
        "humidity" -> Color(0xFF3FA9FF)
        "light" -> Color(0xFFFFCC33)
        "proximity" -> Color(0xFFFF6FB5)
        "steps" -> Color(0xFF2BD4B4)
        "motion" -> Color(0xFFA06BFF)
        "heart" -> Color(0xFFFF4D6D)
        else -> Color(0xFF8B95A4)
    }
}

/** Resolves SignalCatalog icon keys + nav/status keys to Material icons (no custom assets). */
object SsIcons {
    fun forKey(key: String): ImageVector = when (key) {
        "accel" -> Icons.Filled.Vibration
        "gyro" -> Icons.Filled.Speed
        "gravity" -> Icons.Filled.NorthEast
        "orient" -> Icons.Filled.Explore
        "magnet" -> Icons.Filled.Navigation
        "pressure" -> Icons.Filled.Air
        "temp" -> Icons.Filled.DeviceThermostat
        "humidity" -> Icons.Filled.WaterDrop
        "light" -> Icons.Filled.LightMode
        "proximity" -> Icons.Filled.Straighten
        "steps" -> Icons.Filled.DirectionsWalk
        "motion" -> Icons.Filled.DirectionsRun
        "heart" -> Icons.Filled.Favorite
        "other" -> Icons.Filled.Sensors
        // nav / chrome
        "home" -> Icons.Filled.Home
        "sensors" -> Icons.Filled.Sensors
        "connection" -> Icons.Filled.Link
        "settings" -> Icons.Filled.Settings
        "diagnostics" -> Icons.Filled.Memory
        "info" -> Icons.Filled.Info
        "bolt" -> Icons.Filled.Bolt
        else -> Icons.Filled.Waves
    }
}
