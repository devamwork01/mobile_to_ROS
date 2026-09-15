package com.sensorstream.ui.signal

import java.util.Locale
import kotlin.math.sqrt

/** Engineering-appropriate number formatting for the UI (Spec §34). Display only. */
object Fmt {
    /** Plain fixed-decimal, no sign. */
    fun value(x: Float, decimals: Int = 3): String = String.format(Locale.US, "%.${decimals}f", x)

    /** Signed fixed-decimal for per-axis component displays (e.g. "+23.420"). */
    fun signed(x: Float, decimals: Int = 3): String = String.format(Locale.US, "%+.${decimals}f", x)

    /** Unsigned fixed-decimal (for magnitudes / positive-only quantities). */
    fun magnitude(v: FloatArray, decimals: Int = 3): String {
        var s = 0.0
        for (x in v) s += (x.toDouble() * x.toDouble())
        return String.format(Locale.US, "%.${decimals}f", sqrt(s))
    }

    fun hz(x: Float): String = String.format(Locale.US, "%.1f Hz", x)
}
