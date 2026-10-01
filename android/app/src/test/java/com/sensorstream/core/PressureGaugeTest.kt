package com.sensorstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureGaugeTest {
    @Test fun `inland altitude reading still fills the gauge`() {
        // S25 at ~850 m reads 913 hPa: the old 950-1050 range showed an empty gauge
        val f = PressureGauge.fraction(913.28f)
        assertTrue(f > 0.4f && f < 0.7f)
    }

    @Test fun `sea level sits high, extremes clamp`() {
        assertTrue(PressureGauge.fraction(1013.25f) > 0.8f)
        assertEquals(0f, PressureGauge.fraction(500f), 0f)
        assertEquals(1f, PressureGauge.fraction(1200f), 0f)
    }

    @Test fun `altitude from the standard atmosphere`() {
        assertEquals(0f, PressureGauge.altitudeM(1013.25f), 1f)
        assertEquals(860f, PressureGauge.altitudeM(913.28f), 20f)
    }
}
