package com.sensorstream.core

import kotlin.math.pow

/**
 * Barometer gauge scale. Covers real readings from sea-level storms down to ~3 km altitude
 * (a sea-level-only 950-1050 hPa scale shows an empty gauge anywhere inland and high up).
 */
object PressureGauge {
    const val MIN_HPA = 700f
    const val MAX_HPA = 1050f

    fun fraction(hPa: Float): Float = ((hPa - MIN_HPA) / (MAX_HPA - MIN_HPA)).coerceIn(0f, 1f)

    /** Approximate altitude (m) in the standard atmosphere; same formula as SensorManager.getAltitude. */
    fun altitudeM(hPa: Float, seaLevelHPa: Float = 1013.25f): Float =
        (44330.0 * (1.0 - (hPa / seaLevelHPa).toDouble().pow(1.0 / 5.255))).toFloat()
}
