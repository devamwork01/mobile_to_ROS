package com.sensorstream.core

/**
 * One sensor measurement.
 *
 * [timestampNs] is the phone's original monotonic `SensorEvent.timestamp` and is
 * carried verbatim to the laptop — never rewritten. [tAcquireNs]/[tSerializeNs]
 * are optional instrumentation stamps used for end-to-end latency staging.
 *
 * Fields are `@JvmField var` where the hot path benefits (pooling, in Phase 7);
 * [valueCount] lets a pooled [values] array be larger than the used prefix.
 */
class SensorSample(
    @JvmField val sensorType: Int,
    @JvmField val handle: Int,
    @JvmField val seq: Long,
    @JvmField val timestampNs: Long,
    @JvmField val accuracy: Int,
    @JvmField val values: FloatArray,
    @JvmField val valueCount: Int = values.size,
    @JvmField var tAcquireNs: Long = 0L,
    @JvmField var tSerializeNs: Long = 0L,
)
