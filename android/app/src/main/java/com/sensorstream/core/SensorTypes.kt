package com.sensorstream.core

import android.hardware.Sensor

/**
 * Human-readable names and nominal value counts per Android sensor type. The
 * wire encoder always uses the *actual* `SensorEvent.values.size`; these counts
 * are informational (for the UI/catalog) and never assumed on the hot path.
 */
object SensorTypes {

    fun valueCount(type: Int): Int = when (type) {
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> 5 // x,y,z,w,heading-accuracy (device dependent)
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> 6
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_ORIENTATION -> 3
        Sensor.TYPE_LIGHT,
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_PROXIMITY,
        Sensor.TYPE_RELATIVE_HUMIDITY,
        Sensor.TYPE_AMBIENT_TEMPERATURE,
        Sensor.TYPE_TEMPERATURE,
        Sensor.TYPE_STEP_COUNTER,
        Sensor.TYPE_STEP_DETECTOR,
        Sensor.TYPE_SIGNIFICANT_MOTION,
        Sensor.TYPE_HEART_RATE -> 1
        else -> 3
    }

    fun displayName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
        Sensor.TYPE_MAGNETIC_FIELD -> "Magnetic field"
        Sensor.TYPE_ORIENTATION -> "Orientation (deprecated)"
        Sensor.TYPE_GYROSCOPE -> "Gyroscope"
        Sensor.TYPE_LIGHT -> "Light"
        Sensor.TYPE_PRESSURE -> "Pressure"
        Sensor.TYPE_PROXIMITY -> "Proximity"
        Sensor.TYPE_GRAVITY -> "Gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "Linear acceleration"
        Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "Relative humidity"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient temperature"
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "Magnetic field (uncal)"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game rotation vector"
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "Gyroscope (uncal)"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant motion"
        Sensor.TYPE_STEP_DETECTOR -> "Step detector"
        Sensor.TYPE_STEP_COUNTER -> "Step counter"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic rotation vector"
        Sensor.TYPE_HEART_RATE -> "Heart rate"
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "Accelerometer (uncal)"
        else -> "Type $type"
    }
}
