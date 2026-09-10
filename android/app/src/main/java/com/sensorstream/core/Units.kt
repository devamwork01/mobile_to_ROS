package com.sensorstream.core

import android.hardware.Sensor

/** Explicit SI-ish units per sensor type (spec section 5). */
object Units {
    fun forType(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "m/s²"
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "rad/s"
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "µT"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_AMBIENT_TEMPERATURE,
        Sensor.TYPE_TEMPERATURE -> "°C"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        Sensor.TYPE_LIGHT -> "lx"
        Sensor.TYPE_PROXIMITY -> "cm"
        Sensor.TYPE_ORIENTATION -> "°"
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "quaternion"
        Sensor.TYPE_STEP_COUNTER,
        Sensor.TYPE_STEP_DETECTOR -> "steps"
        Sensor.TYPE_HEART_RATE -> "bpm"
        else -> ""
    }
}
