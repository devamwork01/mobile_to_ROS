package com.sensorstream.ui.viz

/**
 * Timestamped sample history for the real-time graph. The visible window is defined in
 * *time*, not sample count, so a 5 s / 10 s / 20 s selection shows exactly that span regardless
 * of the sensor rate or how often the screen recomposes. History up to [maxWindowMs] is kept,
 * so widening the window immediately reveals data already collected.
 */
class TimeWindowBuffer(private val maxWindowMs: Long = 20_000L) {

    class Point(val tMs: Long, val v: FloatArray)

    private val points = ArrayDeque<Point>()

    fun add(tMs: Long, v: FloatArray) {
        points.addLast(Point(tMs, v))
        val cutoff = tMs - maxWindowMs
        while (points.isNotEmpty() && points.first().tMs < cutoff) points.removeFirst()
    }

    /** Points within [windowMs] of the newest sample (oldest first). */
    fun visible(windowMs: Long): List<Point> {
        if (points.isEmpty()) return emptyList()
        val cutoff = points.last().tMs - windowMs
        return points.filter { it.tMs >= cutoff }
    }
}
