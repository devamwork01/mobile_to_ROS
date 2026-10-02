package com.sensorstream.core.filter

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A notch at [hz] with quality [q]. */
data class Notch(val hz: Double, val q: Double)

/**
 * Same shape as the laptop's filter config: optional Butterworth low-pass + up to 3 notches.
 * [axes] (X, Y, Z) makes it a per-axis filter; the top-level fields then mirror X.
 */
data class FilterConfig(
    val lowpassHz: Double?,
    val order: Int = 4,
    val notches: List<Notch> = emptyList(),
    val axes: List<FilterConfig>? = null,
)

/**
 * Port of laptop/sensorstream/filters.py (RBJ cookbook biquads). Kept numerically identical - a
 * golden-vector test compares this against the Python output - so the phone shows exactly what
 * the laptop publishes.
 */
object Biquad {
    private val BUTTER_Q = mapOf(2 to doubleArrayOf(0.7071067811865476), 4 to doubleArrayOf(0.5411961001461970, 1.3065629648763766))
    const val MAX_NOTCHES = 3
    /** Orientation quaternions and on-change sensors are never filtered. */
    val EXCLUDED_TYPES = setOf(11, 15, 20, 5, 8, 17, 18, 19)

    /** b0, b1, b2, a1, a2 with a0 normalised to 1. */
    private fun rbj(lowpass: Boolean, hz: Double, q: Double, fs: Double): DoubleArray {
        val w0 = 2.0 * PI * hz / fs
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val b0: Double; val b1: Double; val b2: Double
        if (lowpass) { b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2 } else { b0 = 1.0; b1 = -2.0 * c; b2 = 1.0 }
        val a0 = 1 + alpha
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, -2.0 * c / a0, (1 - alpha) / a0)
    }

    fun sections(cfg: FilterConfig, fs: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        cfg.lowpassHz?.let { hz -> BUTTER_Q[cfg.order]?.forEach { q -> out.add(rbj(true, hz, q, fs)) } }
        cfg.notches.forEach { out.add(rbj(false, it.hz, it.q, fs)) }
        return out
    }

    /** null when the config is usable at [fs] (or structurally valid when fs is unknown), else a message. */
    fun validate(cfg: FilterConfig, fs: Double?): String? {
        cfg.axes?.let { axes ->
            // Per-axis: every axis must be usable, or the whole filter is refused (never half-applied).
            if (axes.size != 3 || axes.any { it.axes != null }) return "Per-axis filters need X, Y and Z settings."
            axes.forEachIndexed { i, ax -> validate(ax, fs)?.let { return "${"XYZ"[i]}: $it" } }
            return null
        }
        val nyq = if (fs != null) 0.45 * fs else Double.MAX_VALUE
        if (cfg.lowpassHz == null && cfg.notches.isEmpty()) return "No low-pass or notch set."
        cfg.lowpassHz?.let { hz ->
            if (cfg.order != 2 && cfg.order != 4) return "Low-pass order must be 2 or 4."
            if (!hz.isFinite() || hz < 0.1 || hz > nyq) return "Cutoff ${"%.1f".format(hz)} Hz can't run at ${fs?.let { "%.0f".format(it) } ?: "?"} Hz sampling."
        }
        if (cfg.notches.size > MAX_NOTCHES) return "At most $MAX_NOTCHES notches."
        for (n in cfg.notches) {
            if (!n.hz.isFinite() || n.hz < 0.2 || n.hz > nyq) return "Notch ${"%.1f".format(n.hz)} Hz can't run at this sampling rate."
            if (!n.q.isFinite() || n.q < 1.0 || n.q > 50.0) return "Notch Q must be between 1 and 50."
        }
        return null
    }
}

/** Cascade of biquads per axis (direct form II transposed, double state), kept between calls. */
class FilterChain(val config: FilterConfig, val fs: Double, val nAxes: Int) {
    // One section list per axis: a per-axis config filters each axis with its own settings.
    private val secs = Array(nAxes) { a -> Biquad.sections(config.axes?.getOrNull(a) ?: config, fs) }
    private val z = Array(nAxes) { a -> Array(secs[a].size) { DoubleArray(2) } }
    private val primed = BooleanArray(nAxes)

    /** Start axis [a] in steady state for input [x0] (no step response from the first sample). */
    private fun prime(a: Int, x0: Double) {
        var x = x0
        val sec = secs[a]
        for (i in sec.indices) {
            val s = sec[i]
            val y = x * (s[0] + s[1] + s[2]) / (1.0 + s[3] + s[4])
            val z2 = s[2] * x - s[4] * y
            z[a][i][0] = s[1] * x - s[3] * y + z2
            z[a][i][1] = z2
            x = y
        }
    }

    fun process(values: DoubleArray): DoubleArray {
        val out = DoubleArray(nAxes)
        for (a in 0 until nAxes) {
            var x = if (a < values.size) values[a] else Double.NaN
            if (!x.isFinite()) { out[a] = Double.NaN; continue }
            if (!primed[a]) { prime(a, x); primed[a] = true }
            val sec = secs[a]
            for (i in sec.indices) {
                val s = sec[i]
                val zi = z[a][i]
                val y = s[0] * x + zi[0]
                zi[0] = s[1] * x - s[3] * y + zi[1]
                zi[1] = s[2] * x - s[4] * y
                x = y
            }
            out[a] = x
        }
        return out
    }

    fun process(values: FloatArray): FloatArray {
        val d = DoubleArray(values.size) { values[it].toDouble() }
        val r = process(d)
        return FloatArray(nAxes) { r[it].toFloat() }
    }
}
