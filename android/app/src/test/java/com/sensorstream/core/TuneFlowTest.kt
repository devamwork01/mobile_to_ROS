package com.sensorstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneFlowTest {
    private val sensors = listOf(TuneSensor(0, "Acc"), TuneSensor(2, "Gyro"))
    private fun prompt(attention: Boolean = false) = TunePrompt(5, if (attention) "new_sensor" else "connect", attention, 3000, 10000, sensors)

    @Test fun `timeline - buzz, mark start after settling, mark end with the done buzz`() {
        val f = TuneFlow(prompt(), 1000)
        assertEquals(emptyList<TuneAction>(), f.step(1000))
        assertEquals(listOf<TuneAction>(TuneAction.Vibrate(Buzz.START)), f.step(4000))
        assertEquals(listOf<TuneAction>(TuneAction.MarkStart), f.step(4500))
        assertEquals(emptyList<TuneAction>(), f.step(10000))
        assertEquals(listOf(TuneAction.MarkEnd, TuneAction.Vibrate(Buzz.DONE)), f.step(14500))
        assertTrue(f.finished)
    }

    @Test fun `attention prompts start with the attention pattern`() {
        val f = TuneFlow(prompt(attention = true), 0)
        assertEquals(listOf<TuneAction>(TuneAction.Vibrate(Buzz.ATTENTION)), f.step(0))
    }

    @Test fun `a late step catches up in order`() {
        val f = TuneFlow(prompt(), 0)
        assertEquals(listOf(TuneAction.Vibrate(Buzz.START), TuneAction.MarkStart, TuneAction.MarkEnd, TuneAction.Vibrate(Buzz.DONE)), f.step(60_000))
    }

    @Test fun `phases and countdowns`() {
        val f = TuneFlow(prompt(), 0)
        assertEquals(TunePhase.Lead(3), f.phase(0))
        assertEquals(TunePhase.Lead(1), f.phase(2500))
        assertEquals(TunePhase.Capture(10), f.phase(3200))
        assertEquals(TunePhase.Capture(5), f.phase(9000))
        assertEquals(TunePhase.Waiting, f.phase(13600))
        val r = listOf(TuneSensorResult(0, "Acc", true, "X/Y/Z LP 5 Hz", null))
        f.onResult(r)
        assertEquals(TunePhase.Result(r), f.phase(13700))
    }

    @Test fun `buzz patterns`() {
        assertTrue(Buzz.START.pattern.contentEquals(longArrayOf(0, 250)))
        assertTrue(Buzz.DONE.pattern.contentEquals(longArrayOf(0, 150, 150, 150)))
        assertTrue(Buzz.ATTENTION.pattern.contentEquals(longArrayOf(0, 120, 120, 120, 120, 120)))
    }

    @Test fun `still phase - marks, then the buzz, then the motion capture`() {
        val p = TunePrompt(6, "connect", false, 3000, 10000, sensors, stillMs = 3000)
        val f = TuneFlow(p, 0)
        assertEquals(emptyList<TuneAction>(), f.step(3000))          // settling: the phone was just put down
        assertEquals(listOf<TuneAction>(TuneAction.MarkStillStart), f.step(3500))
        assertEquals(listOf(TuneAction.MarkStillEnd, TuneAction.Vibrate(Buzz.START)), f.step(6000))
        assertEquals(listOf<TuneAction>(TuneAction.MarkStart), f.step(6500))
        assertEquals(listOf(TuneAction.MarkEnd, TuneAction.Vibrate(Buzz.DONE)), f.step(16500))
        assertEquals(TunePhase.Lead(3), f.phase(0))
        assertEquals(TunePhase.Still(2), f.phase(4200))
        assertEquals(TunePhase.Capture(10), f.phase(6200))
        assertEquals(TunePhase.Waiting, f.phase(16600))
    }
}
