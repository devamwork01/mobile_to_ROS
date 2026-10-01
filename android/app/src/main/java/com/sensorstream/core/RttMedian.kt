package com.sensorstream.core

/**
 * Median of the last [size] heartbeat round trips. A single RTT is dominated by Wi-Fi jitter
 * (one sample per second ranges 5-50 ms on a quiet link); the median shows the typical latency.
 */
class RttMedian(private val size: Int = 10) {
    private val window = ArrayDeque<Float>()

    @get:Synchronized val value: Float
        get() {
            if (window.isEmpty()) return 0f
            val s = window.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
        }

    @Synchronized fun add(ms: Float) {
        window.addLast(ms)
        while (window.size > size) window.removeFirst()
    }

    @Synchronized fun reset() = window.clear()
}
