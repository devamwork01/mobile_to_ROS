package com.sensorstream.core.filter

import kotlin.math.sqrt

/**
 * Overall noise of a vector signal: sqrt(sum over axes of each axis' variance), NaN-safe and
 * skipping missing samples. NaN when no axis has at least two finite values.
 */
fun vectorSigma(points: List<FloatArray?>): Float {
    val nAxes = points.maxOfOrNull { it?.size ?: 0 } ?: 0
    var total = 0.0
    var any = false
    for (a in 0 until nAxes) {
        var n = 0
        var mean = 0.0
        var m2 = 0.0
        for (p in points) {
            val x = p?.getOrNull(a)?.toDouble() ?: continue
            if (!x.isFinite()) continue
            n++
            val d = x - mean
            mean += d / n
            m2 += d * (x - mean)
        }
        if (n >= 2) { total += m2 / (n - 1); any = true }
    }
    return if (any) sqrt(total).toFloat() else Float.NaN
}
