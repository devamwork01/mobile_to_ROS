package com.sensorstream.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Explore
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
import androidx.compose.ui.graphics.vector.ImageVector

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
