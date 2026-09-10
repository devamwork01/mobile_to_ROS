package com.sensorstream.sensor

import android.hardware.Sensor
import android.hardware.SensorManager
import com.sensorstream.core.SensorInfo
import com.sensorstream.core.SensorTypes
import com.sensorstream.core.Units

/**
 * Dynamic sensor enumeration. Never assumes a fixed sensor set — the S25 Ultra
 * and M36 expose different hardware, and both are handled by asking the platform.
 */
class SensorRepository(private val sm: SensorManager) {

    // Enumerated once; the list index is the stable per-session sensor handle,
    // so [enumerate] metadata and [sensorAt] registration stay aligned.
    private val raw: List<Sensor> = sm.getSensorList(Sensor.TYPE_ALL)

    fun enumerate(): List<SensorInfo> = raw.mapIndexed { index, sensor -> sensor.toInfo(index) }

    fun sensorAt(handle: Int): Sensor? = raw.getOrNull(handle)

    fun defaultSensor(type: Int): Sensor? = sm.getDefaultSensor(type)

    private fun Sensor.toInfo(handle: Int): SensorInfo {
        val maxHz = if (minDelay > 0) 1_000_000f / minDelay else 0f
        return SensorInfo(
            handle = handle,
            name = name,
            type = type,
            stringType = stringType ?: "",
            vendor = vendor,
            version = version,
            resolution = resolution,
            maximumRange = maximumRange,
            power = power,
            minDelayUs = minDelay,
            maxDelayUs = maxDelay,
            reportingMode = reportingMode,
            isWakeUp = isWakeUpSensor,
            valueCount = SensorTypes.valueCount(type),
            units = Units.forType(type),
            maxFrequencyHz = maxHz,
        )
    }
}
