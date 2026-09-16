package com.sensorstream.ui.signal

import android.hardware.Sensor
import com.sensorstream.core.SensorTypes
import com.sensorstream.core.Units

/** Visual grouping for the sensor list (Spec §19). */
enum class SensorCategory { MOTION, ORIENTATION, MAGNETIC, ENVIRONMENT, PROXIMITY, OTHER }

/** UI-facing description of a sensor type: human-readable, categorized, with units + icon key.
 *  This is presentation only — the wire catalog still uses [SensorTypes.displayName]. */
data class SignalInfo(
    val humanName: String,
    val description: String,
    val category: SensorCategory,
    val icon: String,
    val unit: String,
    val componentLabels: List<String>,
)

object SignalCatalog {
    private val XYZ = listOf("X", "Y", "Z")

    /** A clean short technical label for a sensor, never a raw "android.sensor.*" or "Type N".
     *  Prefers the standard type name; falls back to a prettified string type. */
    fun typeLabel(type: Int, stringType: String? = null): String {
        val mapped = SensorTypes.displayName(type)
        if (!mapped.startsWith("Type ")) return mapped
        val s = stringType?.substringAfterLast('.')?.replace('_', ' ')?.trim()
        return if (!s.isNullOrBlank())
            s.split(' ').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        else "Sensor"
    }

    fun of(type: Int, stringType: String? = null): SignalInfo = when (type) {
        Sensor.TYPE_ACCELEROMETER -> SignalInfo(
            "Acceleration", "Total acceleration incl. gravity", SensorCategory.MOTION, "accel",
            Units.forType(type), XYZ,
        )
        Sensor.TYPE_LINEAR_ACCELERATION -> SignalInfo(
            "Linear Acceleration", "Acceleration excluding gravity", SensorCategory.MOTION, "accel",
            Units.forType(type), XYZ,
        )
        Sensor.TYPE_GYROSCOPE -> SignalInfo(
            "Angular Velocity", "Rotational speed in device coordinates", SensorCategory.MOTION, "gyro",
            Units.forType(type), XYZ,
        )
        Sensor.TYPE_GRAVITY -> SignalInfo(
            "Gravity", "Direction of gravity in device coordinates", SensorCategory.MOTION, "gravity",
            Units.forType(type), XYZ,
        )
        Sensor.TYPE_ROTATION_VECTOR -> SignalInfo(
            "Orientation", "Device attitude in real time", SensorCategory.ORIENTATION, "orient",
            Units.forType(type), listOf("X", "Y", "Z", "W"),
        )
        Sensor.TYPE_GAME_ROTATION_VECTOR -> SignalInfo(
            "Game Orientation", "Attitude without magnetometer drift correction", SensorCategory.ORIENTATION, "orient",
            Units.forType(type), listOf("X", "Y", "Z", "W"),
        )
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> SignalInfo(
            "Geomagnetic Orientation", "Attitude from accelerometer + magnetometer", SensorCategory.ORIENTATION, "orient",
            Units.forType(type), listOf("X", "Y", "Z", "W"),
        )
        Sensor.TYPE_MAGNETIC_FIELD -> SignalInfo(
            "Magnetic Field", "Earth's magnetic field in device coordinates", SensorCategory.MAGNETIC, "magnet",
            Units.forType(type), XYZ,
        )
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> SignalInfo(
            "Magnetic Field (uncal.)", "Magnetic field without hard-iron correction", SensorCategory.MAGNETIC, "magnet",
            Units.forType(type), XYZ,
        )
        Sensor.TYPE_PRESSURE -> SignalInfo(
            "Atmospheric Pressure", "Barometric pressure", SensorCategory.ENVIRONMENT, "pressure",
            Units.forType(type), listOf("P"),
        )
        Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_TEMPERATURE -> SignalInfo(
            "Ambient Temperature", "Air temperature", SensorCategory.ENVIRONMENT, "temp",
            Units.forType(type), listOf("T"),
        )
        Sensor.TYPE_RELATIVE_HUMIDITY -> SignalInfo(
            "Humidity", "Relative humidity", SensorCategory.ENVIRONMENT, "humidity",
            Units.forType(type), listOf("RH"),
        )
        Sensor.TYPE_LIGHT -> SignalInfo(
            "Ambient Light", "Illuminance at the screen", SensorCategory.ENVIRONMENT, "light",
            Units.forType(type), listOf("E"),
        )
        Sensor.TYPE_PROXIMITY -> SignalInfo(
            "Proximity", "Distance to a nearby object", SensorCategory.PROXIMITY, "proximity",
            Units.forType(type), listOf("d"),
        )
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> SignalInfo(
            "Angular Velocity (uncal.)", "Gyroscope without drift correction", SensorCategory.MOTION, "gyro",
            Units.forType(type), XYZ,
        )
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> SignalInfo(
            "Acceleration (uncal.)", "Accelerometer without bias correction", SensorCategory.MOTION, "accel",
            Units.forType(type), XYZ,
        )
        Sensor.TYPE_STEP_COUNTER -> SignalInfo(
            "Step Counter", "Steps since last reboot", SensorCategory.OTHER, "steps",
            Units.forType(type), listOf("steps"),
        )
        Sensor.TYPE_STEP_DETECTOR -> SignalInfo(
            "Step Detector", "Fires once per step", SensorCategory.OTHER, "steps",
            Units.forType(type), listOf("step"),
        )
        Sensor.TYPE_SIGNIFICANT_MOTION -> SignalInfo(
            "Significant Motion", "Triggers on significant movement", SensorCategory.OTHER, "motion",
            Units.forType(type), listOf("trigger"),
        )
        Sensor.TYPE_HEART_RATE -> SignalInfo(
            "Heart Rate", "Beats per minute", SensorCategory.OTHER, "heart",
            Units.forType(type), listOf("bpm"),
        )
        Sensor.TYPE_ORIENTATION -> SignalInfo(
            "Orientation (deprecated)", "Legacy azimuth / pitch / roll", SensorCategory.ORIENTATION, "orient",
            Units.forType(type), listOf("Azimuth", "Pitch", "Roll"),
        )
        else -> SignalInfo(
            typeLabel(type, stringType), "Device sensor", SensorCategory.OTHER, "other",
            Units.forType(type),
            List(SensorTypes.valueCount(type).coerceAtMost(6)) { "v$it" },
        )
    }
}
