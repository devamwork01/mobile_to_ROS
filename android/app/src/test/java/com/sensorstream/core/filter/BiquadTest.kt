package com.sensorstream.core.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class BiquadTest {
    private fun gainDb(cfg: FilterConfig, fs: Double, f: Double): Double {
        val ch = FilterChain(cfg, fs, 1)
        val n = 8000
        var acc = 0.0
        for (i in 0 until n) {
            val y = ch.process(doubleArrayOf(sin(2 * PI * f * i / fs)))[0]
            if (i >= n / 2) acc += y * y
        }
        return 20 * log10(sqrt(acc / (n / 2)) * sqrt(2.0))
    }

    @Test fun butterworthIsMinus3dBAtCutoff() {
        assertEquals(-3.01, gainDb(FilterConfig(10.0, 2), 200.0, 10.0), 0.3)
        assertEquals(-3.01, gainDb(FilterConfig(10.0, 4), 200.0, 10.0), 0.3)
    }

    @Test fun fourthOrderRejectsTenTimesTheCutoff() {
        assertTrue(gainDb(FilterConfig(5.0, 4), 200.0, 50.0) <= -30.0)
    }

    @Test fun notchIsDeep() {
        assertTrue(gainDb(FilterConfig(null, 4, listOf(Notch(8.0, 10.0))), 100.0, 8.0) <= -30.0)
    }

    @Test fun primedChainHasNoStep() {
        val ch = FilterChain(FilterConfig(3.0, 4, listOf(Notch(10.0, 5.0))), 100.0, 3)
        val out = ch.process(floatArrayOf(1f, 2f, 9.8f))
        assertEquals(9.8f, out[2], 1e-5f)
        assertEquals(1f, out[0], 1e-6f)
    }

    @Test fun nanDoesNotPoisonState() {
        val ch = FilterChain(FilterConfig(5.0, 2), 100.0, 1)
        repeat(200) { ch.process(doubleArrayOf(1.0)) }
        assertTrue(ch.process(doubleArrayOf(Double.NaN))[0].isNaN())
        assertEquals(1.0, ch.process(doubleArrayOf(1.0))[0], 1e-3)
    }

    @Test fun validateRanges() {
        assertNull(Biquad.validate(FilterConfig(10.0, 4), 100.0))
        assertNotNull(Biquad.validate(FilterConfig(46.0, 4), 100.0))
        assertNotNull(Biquad.validate(FilterConfig(10.0, 3), 100.0))
        assertNotNull(Biquad.validate(FilterConfig(null, 4), 100.0))
        assertNotNull(Biquad.validate(FilterConfig(null, 4, listOf(Notch(5.0, 0.5))), 100.0))
        assertNotNull(Biquad.validate(FilterConfig(null, 4, List(4) { Notch(5.0, 10.0) }), 100.0))
        assertNull(Biquad.validate(FilterConfig(10.0, 4), null))
    }

    @Test fun `per-axis validation names the axis and scalar streams use X`() {
        val ok = FilterConfig(1.0, 4)
        val tooHigh = FilterConfig(40.0, 4)
        val per = FilterConfig(1.0, 4, axes = listOf(ok, ok, tooHigh))
        assertTrue(Biquad.validate(per, 50.0)!!.startsWith("Z: "))
        assertNull(Biquad.validate(per, 100.0))
        assertEquals(1, FilterChain(per, 100.0, 1).process(doubleArrayOf(1013.0)).size)
    }
}
