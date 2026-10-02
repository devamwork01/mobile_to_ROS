package com.sensorstream.core

/**
 * One guided filter-tuning run on the phone (`--filter` on the server), as a clock-driven timeline -
 * pure, so it is unit-tested without Android. From the prompt's arrival: an attention buzz (later
 * sensors only), a lead countdown, an optional still phase (the phone lying untouched, so the server
 * can measure the sensor noise), one buzz, a short settle, the motion capture, then a double buzz.
 * The phone marks the still and motion windows from the sensors' own timestamps; the buzzes stay
 * outside both, so the vibration motor never shows up in the analysed data.
 */
data class TuneSensor(val handle: Int, val name: String)
data class TunePrompt(
    val id: Int, val reason: String, val attention: Boolean, val leadMs: Long, val captureMs: Long,
    val sensors: List<TuneSensor>, val stillMs: Long = 0,
)
data class TuneSensorResult(val handle: Int, val name: String, val ok: Boolean, val summary: String?, val message: String?)
/** One sensor's capture windows (sensor timestamps); the still range only when a still phase ran. */
data class TuneWindow(val handle: Int, val fromNs: Long, val toNs: Long, val stillFromNs: Long? = null, val stillToNs: Long? = null)

/** Vibrator waveforms (off/on ms, starting with a delay). */
enum class Buzz(val pattern: LongArray) {
    ATTENTION(longArrayOf(0, 120, 120, 120, 120, 120)),
    START(longArrayOf(0, 250)),
    DONE(longArrayOf(0, 150, 150, 150)),
}

sealed class TuneAction {
    data class Vibrate(val buzz: Buzz) : TuneAction()
    object MarkStillStart : TuneAction()
    object MarkStillEnd : TuneAction()
    object MarkStart : TuneAction()
    object MarkEnd : TuneAction()
}

sealed class TunePhase {
    data class Lead(val secondsLeft: Int) : TunePhase()
    data class Still(val secondsLeft: Int) : TunePhase()
    data class Capture(val secondsLeft: Int) : TunePhase()
    object Waiting : TunePhase()
    data class Result(val sensors: List<TuneSensorResult>) : TunePhase()
}

class TuneFlow(val prompt: TunePrompt, private val startMs: Long) {
    private val stillEnd = prompt.leadMs + prompt.stillMs
    private val captureStart = stillEnd + SETTLE_MS
    private val captureEnd = captureStart + prompt.captureMs
    private val timeline: List<Pair<Long, TuneAction>> = buildList {
        if (prompt.attention) add(0L to TuneAction.Vibrate(Buzz.ATTENTION))
        if (prompt.stillMs > 0) {
            add(prompt.leadMs to TuneAction.MarkStillStart)
            add(stillEnd to TuneAction.MarkStillEnd)
        }
        add(stillEnd to TuneAction.Vibrate(Buzz.START))
        add(captureStart to TuneAction.MarkStart)
        add(captureEnd to TuneAction.MarkEnd)
        add(captureEnd to TuneAction.Vibrate(Buzz.DONE))
    }
    private var emitted = 0
    @Volatile private var result: List<TuneSensorResult>? = null

    /** Actions due by [nowMs] that were not returned yet, in order. */
    fun step(nowMs: Long): List<TuneAction> {
        val t = nowMs - startMs
        val out = mutableListOf<TuneAction>()
        while (emitted < timeline.size && timeline[emitted].first <= t) out += timeline[emitted++].second
        return out
    }

    fun phase(nowMs: Long): TunePhase {
        result?.let { return TunePhase.Result(it) }
        val t = nowMs - startMs
        return when {
            t < prompt.leadMs -> TunePhase.Lead(ceilSec(prompt.leadMs - t))
            prompt.stillMs > 0 && t < stillEnd -> TunePhase.Still(ceilSec(stillEnd - t))
            t < captureEnd -> TunePhase.Capture(ceilSec(captureEnd - maxOf(t, captureStart)))
            else -> TunePhase.Waiting
        }
    }

    val finished: Boolean get() = emitted == timeline.size

    fun onResult(sensors: List<TuneSensorResult>) { result = sensors }

    companion object {
        const val SETTLE_MS = 500L
        private fun ceilSec(ms: Long): Int = ((ms + 999) / 1000).toInt()
    }
}
