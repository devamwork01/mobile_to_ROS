package com.sensorstream.ui.viz

import android.hardware.Sensor

/** The attitude an orientation-type sensor itself reports, as a rotation vector `[x,y,z,(w)]`. */
object OrientationSource {
    /**
     * Rotation-vector sensors (rotation / game / geomagnetic) report one directly. The deprecated
     * TYPE_ORIENTATION reports [azimuth, pitch, roll] in degrees; its roll is the opposite sign of
     * [Projection.eulerDeg]'s. Null for anything else, or before the first sample.
     */
    fun rotationVector(type: Int, values: FloatArray?): FloatArray? {
        if (values == null) return null
        return when (type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> values.takeIf { it.size >= 3 }
            @Suppress("DEPRECATION") Sensor.TYPE_ORIENTATION ->
                if (values.size >= 3) Projection.rotationFromEuler(-values[2], values[1], values[0]) else null
            else -> null
        }
    }
}
