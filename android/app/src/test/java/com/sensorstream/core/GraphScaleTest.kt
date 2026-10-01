package com.sensorstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphScaleTest {
    @Test fun `barometer noise is shown inside a 1 hPa span`() {
        val (lo, hi) = GraphScale.range(913.15f, 913.16f, "hPa")
        assertEquals(1f, hi - lo, 1e-3f)
        assertEquals(913.155f, (lo + hi) / 2f, 1e-3f)
    }

    @Test fun `real change keeps its own span`() {
        val (lo, hi) = GraphScale.range(50f, 450f, "lx")
        assertTrue(lo <= 50f && hi >= 450f && hi - lo < 450f)
    }

    @Test fun `vector units keep the old behaviour`() {
        assertEquals(Pair(-0.2f, 0.3f), GraphScale.range(-0.2f, 0.3f, "m/s²"))
        assertEquals(Pair(-1f, 1f), GraphScale.range(0f, 0f, "m/s²"))
    }
}
