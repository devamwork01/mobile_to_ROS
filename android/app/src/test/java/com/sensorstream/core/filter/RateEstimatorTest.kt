package com.sensorstream.core.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RateEstimatorTest {
    private fun feed(r: RateEstimator, n: Int, fs: Double, t0: Long): Long {
        var t = t0
        repeat(n) { r.observe(t); t += (1e9 / fs).toLong() }
        return t
    }

    @Test fun readyAfterOneSecond() {
        val r = RateEstimator()
        feed(r, 50, 100.0, 1_000_000_000)
        assertNull(r.fs())
        feed(r, 100, 100.0, 1_500_000_000)
        assertEquals(100.0, r.fs()!!, 1.0)
    }

    @Test fun isolatedPauseIgnored() {
        val r = RateEstimator()
        val t = feed(r, 300, 100.0, 1_000_000_000)
        feed(r, 100, 100.0, t + 2_000_000_000)
        assertEquals(100.0, r.fs()!!, 2.0)
    }

    @Test fun clockBackResets() {
        val r = RateEstimator()
        feed(r, 1000, 400.0, 500_000_000_000)
        feed(r, 300, 50.0, 1_000_000_000)
        assertEquals(50.0, r.fs()!!, 2.0)
    }
}
