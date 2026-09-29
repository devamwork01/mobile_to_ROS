package com.sensorstream.ui.viz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeWindowBufferTest {

    private fun fill(buf: TimeWindowBuffer, seconds: Int, hz: Int) {
        val stepMs = 1000L / hz
        for (i in 0 until seconds * hz) buf.add(i * stepMs, floatArrayOf(i.toFloat()))
    }

    @Test
    fun windowsOf5_10_20sKeepDifferentHistory() {
        // 30 s of data at 100 Hz: each window must keep exactly its own span, not a shared cap.
        val spans = listOf(5_000L, 10_000L, 20_000L).map { w ->
            val buf = TimeWindowBuffer()
            fill(buf, seconds = 30, hz = 100)
            buf.visible(w).let { it.last().tMs - it.first().tMs }
        }
        assertTrue("5s span ${spans[0]}", spans[0] in 4_900L..5_000L)
        assertTrue("10s span ${spans[1]}", spans[1] in 9_900L..10_000L)
        assertTrue("20s span ${spans[2]}", spans[2] in 19_900L..20_000L)
    }

    @Test
    fun widerWindowShowsOlderDataAfterSwitching() {
        // Switching 5s -> 20s must reveal history that was already collected (not start empty).
        val buf = TimeWindowBuffer()
        fill(buf, seconds = 30, hz = 100)
        assertEquals(501, buf.visible(5_000L).size)
        assertTrue(buf.visible(20_000L).size > 1_900)
    }

    @Test
    fun windowIsTimeBasedNotRateBased() {
        // Same 10s window at 10 Hz vs 200 Hz covers the same time span.
        val slow = TimeWindowBuffer().also { fill(it, 30, 10) }.visible(10_000L)
        val fast = TimeWindowBuffer().also { fill(it, 30, 200) }.visible(10_000L)
        val slowSpan = (slow.last().tMs - slow.first().tMs).toDouble()
        val fastSpan = (fast.last().tMs - fast.first().tMs).toDouble()
        assertEquals(slowSpan, fastSpan, 100.0)
    }

    @Test
    fun retentionIsBoundedByMaxWindow() {
        val buf = TimeWindowBuffer(maxWindowMs = 20_000L)
        fill(buf, seconds = 120, hz = 100)
        val all = buf.visible(Long.MAX_VALUE / 2)
        assertTrue("kept ${all.last().tMs - all.first().tMs} ms", all.last().tMs - all.first().tMs <= 20_000L)
    }

    @Test
    fun emptyBufferIsEmpty() {
        assertTrue(TimeWindowBuffer().visible(5_000L).isEmpty())
    }
}
