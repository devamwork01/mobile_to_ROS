package com.sensorstream.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RatesTest {
    @Test fun `fixed period gives its rate`() {
        assertEquals(100f, requestedHz(periodUs = 10_000, maxFrequencyHz = 480f, liveHz = null), 1e-3f)
    }

    @Test fun `max counts the sensor's max rate when not streaming`() {
        assertEquals(480f, requestedHz(periodUs = 0, maxFrequencyHz = 480f, liveHz = null), 1e-3f)
    }

    @Test fun `max counts the measured rate while streaming`() {
        assertEquals(470f, requestedHz(periodUs = 0, maxFrequencyHz = 480f, liveHz = 470f), 1e-3f)
    }

    @Test fun `on-change sensor at max adds nothing`() {
        assertEquals(0f, requestedHz(periodUs = 0, maxFrequencyHz = 0f, liveHz = null), 1e-3f)
    }
}
