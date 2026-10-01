package com.sensorstream.core.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFilterBankTest {
    private val lp = FilterConfig(5.0, 2)

    private fun feed(b: PhoneFilterBank, n: Int, fs: Double, t0: Long = 1_000_000_000L, key: String = "1:Acc", type: Int = 1): FloatArray? {
        var out: FloatArray? = null
        var t = t0
        repeat(n) { out = b.process(3, key, type, t, floatArrayOf(1f, 2f, 3f)); t += (1e9 / fs).toLong() }
        return out
    }

    @Test fun waitsForRateThenRuns() {
        val b = PhoneFilterBank()
        b.setConfigs(mapOf("1:Acc" to lp))
        assertNull(feed(b, 50, 100.0))
        assertEquals(FilterStatus.WaitingForRate, b.status(3))
        assertNotNull(feed(b, 100, 100.0, t0 = 1_500_000_000L))
        assertEquals(FilterStatus.Running, b.status(3))
    }

    @Test fun noConfigOrExcludedTypeGivesNothing() {
        val b = PhoneFilterBank()
        assertNull(feed(b, 300, 100.0))
        b.setConfigs(mapOf("11:Rot" to lp))
        assertNull(feed(b, 300, 100.0, key = "11:Rot", type = 11))
        assertNull(b.status(3))
    }

    @Test fun invalidAtThisRate() {
        val b = PhoneFilterBank()
        b.setConfigs(mapOf("1:Acc" to FilterConfig(40.0, 4)))
        assertNull(feed(b, 300, 50.0))   // 0.45 * 50 = 22.5 Hz < 40 Hz
        assertTrue(b.status(3) is FilterStatus.Invalid)
    }

    @Test fun newConfigsRebuildTheChain() {
        val b = PhoneFilterBank()
        b.setConfigs(mapOf("1:Acc" to lp))
        assertNotNull(feed(b, 300, 100.0))
        b.setConfigs(emptyMap())
        assertNull(feed(b, 10, 100.0, t0 = 4_000_000_000L))
        assertNull(b.status(3))
    }
}
