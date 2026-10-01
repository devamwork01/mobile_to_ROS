package com.sensorstream.core

/**
 * Y range for the live graphs. Single-value environmental sensors get a minimum span by unit, so a
 * barometer's 0.01 hPa quantization steps (or 1 lx flicker) aren't stretched to the full height.
 * Same table as the laptop dashboard (seriesSpec.js). Other units keep min..max as before.
 */
object GraphScale {
    private val MIN_SPAN = mapOf("hPa" to 1f, "lx" to 10f, "°C" to 1f, "%" to 2f, "cm" to 1f)

    fun minSpanFor(unit: String): Float = MIN_SPAN[unit] ?: 0f

    fun range(lo: Float, hi: Float, unit: String): Pair<Float, Float> {
        val minSpan = minSpanFor(unit)
        if (minSpan > 0f) {
            val span = hi - lo
            if (span < minSpan) {
                val mid = (lo + hi) / 2f
                return Pair(mid - minSpan / 2f, mid + minSpan / 2f)
            }
            val pad = span * 0.05f
            return Pair(lo - pad, hi + pad)
        }
        return if (hi - lo < 1e-3f) Pair(lo - 1f, hi + 1f) else Pair(lo, hi)
    }
}
