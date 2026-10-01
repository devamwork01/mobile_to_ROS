package com.sensorstream.core

/**
 * The rate a sensor contributes to Home's "Total Rate". A fixed period counts its requested rate;
 * "Max" (period 0) counts what the sensor actually delivers while streaming, otherwise its
 * advertised maximum (0 for on-change sensors).
 */
fun requestedHz(periodUs: Int, maxFrequencyHz: Float, liveHz: Float?): Float = when {
    periodUs > 0 -> 1_000_000f / periodUs
    liveHz != null && liveHz > 0f -> liveHz
    else -> maxOf(0f, maxFrequencyHz)
}
