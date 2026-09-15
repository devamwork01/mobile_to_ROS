package com.sensorstream.stream

import android.hardware.SensorManager
import android.os.SystemClock
import com.sensorstream.codec.BinaryPacketCodec
import com.sensorstream.core.SensorSample
import com.sensorstream.net.UdpTelemetrySender
import com.sensorstream.sensor.SensorEventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Ties acquisition to networking through bounded channels. Acquisition + recording run for the
 * whole streaming session ([startSession]/[stopSession]); the UDP sender is a per-connection job
 * ([connectSender]/[disconnectSender]) so a control-channel drop pauses only the network send while
 * the sensors keep firing and the recorder keeps writing (seq stays continuous, recording lossless).
 * The network channel is DROP_OLDEST: during a sender outage nothing drains it, so it sheds the
 * exact samples Phase-2B backfill recovers, while the recorder channel (SUSPEND) keeps all of them.
 */
class StreamController(sm: SensorManager) {

    private val source = SensorEventSource(sm)
    private val sender = UdpTelemetrySender()

    private var sessionScope: CoroutineScope? = null
    private var channel: Channel<SensorSample>? = null   // network path (DROP_OLDEST, best-effort)
    // Recorder is OWNED by StreamEngine (built once per session, kept across reconnects); the
    // controller only holds a ref to fan out to it and never closes it. @Volatile: written on
    // startSession (WS callback thread), read by recorderStats() on the heartbeat thread.
    @Volatile private var recorder: LocalRecorder? = null
    private var recCh: Channel<SensorSample>? = null      // recorder path (SUSPEND, lossless)
    private var recDrainJob: Job? = null
    private var senderJob: Job? = null

    val sentPackets = AtomicLong(0)
    val sentBytes = AtomicLong(0)
    val droppedSamples = AtomicLong(0)   // network-channel drops (recovered later via backfill)
    // Samples dropped at the fan-out boundary because the recorder channel was full (should stay 0).
    // Distinct from LocalRecorder.Stats.droppedOldest (ring prune) — this is upstream of the recorder.
    val recorderDropped = AtomicLong(0)
    // Datagrams served back to the laptop via backfill (Phase 2B); cumulative for the session.
    val backfillServed = AtomicLong(0)

    @Volatile var deviceId: Int = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
    @Volatile var onUiSample: ((SensorSample) -> Unit)? = null
    @Volatile var onError: ((Throwable) -> Unit)? = null

    /** Start acquisition + recording for the whole session. The sender is attached separately via
     *  [connectSender] and re-attached on every reconnect, so acquisition never restarts mid-session
     *  (per-sensor seq stays continuous) and recording continues across control-channel outages. */
    fun startSession(regs: List<SensorEventSource.Reg>, recorder: LocalRecorder?) {
        stopSession()
        sentPackets.set(0)
        sentBytes.set(0)
        droppedSamples.set(0)
        recorderDropped.set(0)
        backfillServed.set(0)
        this.recorder = recorder

        val ch = Channel<SensorSample>(capacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        channel = ch
        val rc = Channel<SensorSample>(capacity = 16384, onBufferOverflow = BufferOverflow.SUSPEND)
        recCh = rc
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope = s

        source.onSample = { sample ->
            if (ch.trySend(sample).isFailure) droppedSamples.incrementAndGet()
            if (recorder != null && rc.trySend(sample).isFailure) recorderDropped.incrementAndGet()
            onUiSample?.invoke(sample)
        }

        recDrainJob = s.launch {
            for (sample in rc) recorder?.write(sample)
        }

        source.start(regs)
    }

    /** (Re)attach the UDP sender. Call on every (re)connect; drains the shared network channel.
     *  Cancels any prior sender first, so it is safe to call repeatedly across reconnects. */
    fun connectSender(host: String, port: Int) {
        val s = sessionScope ?: return
        disconnectSender()
        val ch = channel ?: return
        senderJob = s.launch {
            try {
                sender.connect(host, port)
            } catch (t: Throwable) {
                onError?.invoke(t)
                return@launch
            }
            val one = ArrayList<SensorSample>(1)
            for (sample in ch) {
                one.clear()
                one.add(sample)
                try {
                    // Stamp serialize time before encoding so stage timestamps are in the packet.
                    sample.tSerializeNs = SystemClock.elapsedRealtimeNanos()
                    val bytes = BinaryPacketCodec.encode(deviceId, one, BinaryPacketCodec.FLAG_STAGE_TS)
                    sender.send(bytes)
                    sentPackets.incrementAndGet()
                    sentBytes.addAndGet(bytes.size.toLong())
                } catch (t: Throwable) {
                    onError?.invoke(t)
                }
            }
        }
    }

    /** Pause the UDP sender only; acquisition + recorder keep running (lossless during an outage). */
    fun disconnectSender() {
        senderJob?.cancel()
        senderJob = null
        sender.close()
    }

    /** Live-reconfigure the streamed sensor set without dropping acquisition. */
    fun updateRegs(regs: List<SensorEventSource.Reg>) {
        if (sessionScope == null) return
        source.updateRegs(regs)
    }

    /** Tear down the whole session. The recorder is owned + closed by StreamEngine. */
    fun stopSession() {
        source.onSample = null
        source.stop()
        disconnectSender()
        channel?.close()
        channel = null
        // Flush the recorder's buffered tail before tearing down the scope: draining a closed
        // channel completes quickly; the timeout only guards against a stuck write() (no tail loss
        // on a normal stop). The recorder itself is owned + closed by StreamEngine.
        recCh?.close()
        recCh = null
        val drain = recDrainJob
        // 500ms is ample: draining a closed channel of tiny disk appends finishes in ms; the cap
        // only guards a stuck write() and keeps the main-thread ACTION_STOP block well under ANR.
        if (drain != null) runBlocking { withTimeoutOrNull(500) { drain.join() } }
        recDrainJob = null
        sessionScope?.cancel()
        sessionScope = null
        recorder = null
    }

    fun recorderStats(): LocalRecorder.Stats? = recorder?.stats()
}
