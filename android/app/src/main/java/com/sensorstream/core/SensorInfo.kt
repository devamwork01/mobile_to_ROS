package com.sensorstream.core

/**
 * Full metadata for one device sensor, sent once over the control channel so
 * high-rate telemetry can reference a compact [handle] instead of strings.
 * Mirrors the fields the spec (section 3) requires the app to expose.
 */
data class SensorInfo(
    val handle: Int,
    val name: String,
    val type: Int,
    val stringType: String,
    val vendor: String,
    val version: Int,
    val resolution: Float,
    val maximumRange: Float,
    val power: Float,          // mA
    val minDelayUs: Int,       // fastest supported period; 0 = on-change/one-shot
    val maxDelayUs: Int,
    val reportingMode: Int,    // Sensor.REPORTING_MODE_*
    val isWakeUp: Boolean,
    val valueCount: Int,
    val units: String,
    val maxFrequencyHz: Float, // derived from minDelayUs (0 if not continuous)
)
