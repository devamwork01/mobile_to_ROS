package com.sensorstream.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RttMedianTest {
    @Test fun `one Wi-Fi spike does not move the shown latency`() {
        val m = RttMedian(size = 5)
        listOf(6f, 7f, 50f, 6f, 8f).forEach { m.add(it) }
        assertEquals(7f, m.value, 1e-3f)
    }

    @Test fun `keeps only the most recent samples`() {
        val m = RttMedian(size = 3)
        listOf(40f, 40f, 40f, 5f, 6f, 7f).forEach { m.add(it) }
        assertEquals(6f, m.value, 1e-3f)
    }

    @Test fun `even count averages the middle two and reset empties`() {
        val m = RttMedian(size = 10)
        assertEquals(0f, m.value, 0f)
        listOf(4f, 8f).forEach { m.add(it) }
        assertEquals(6f, m.value, 1e-3f)
        m.reset()
        assertEquals(0f, m.value, 0f)
    }
}
