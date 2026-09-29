package com.sensorstream.stream

import android.hardware.SensorManager
import android.os.SystemClock
import com.sensorstream.codec.BinaryPacketCodec
import com.sensorstream.codec.DatagramPacker
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
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // select.onTimeout
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
    // UDP send failures (e.g. network unreachable); cumulative for the session.
    val sendErrors = AtomicLong(0)

    /** How long the sender waits to fill a datagram after its first sample; 0 = send what is
     *  already queued with no added latency. */
    @Volatile var batchWindowMs: Long = DEFAULT_BATCH_WINDOW_MS
    @Volatile private var lastErrorReportMs = 0L

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
        sendErrors.set(0)
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
                reportError(t)
                return@launch
            }
            // Pack several records per datagram: after the first sample arrives, keep adding samples
            // for up to [batchWindowMs] (0 = only what is already queued, i.e. zero added latency)
            // while the datagram stays under DatagramPacker's MTU-safe size. Same wire format — the
            // header already carries a record count — just far fewer packets at high rates.
            val batch = ArrayList<SensorSample>(DatagramPacker.MAX_RECORDS)
            val stage = true
            while (true) {
                val first = ch.receiveCatching().getOrNull() ?: break
                batch.clear()
                batch.add(first)
                var bytes = DatagramPacker.recordSize(first, stage)
                val windowMs = batchWindowMs
                val deadline = SystemClock.uptimeMillis() + windowMs
                var carry: SensorSample? = null
                while (true) {
                    val next = ch.tryReceive().getOrNull() ?: run {
                        val left = deadline - SystemClock.uptimeMillis()
                        if (left <= 0) null
                        // select is atomic: a sample is either taken or left queued. A cancelled
                        // timed receive (withTimeoutOrNull { receive() }) can take a sample and then
                        // drop it when the timeout wins the race — measured ~2.5% loss that way.
                        else select<SensorSample?> {
                            ch.onReceiveCatching { it.getOrNull() }
                            onTimeout(left) { null }
                        }
                    } ?: break
                    if (!DatagramPacker.fits(batch.size, bytes, next, stage)) { carry = next; break }
                    batch.add(next)
                    bytes += DatagramPacker.recordSize(next, stage)
                }
                sendBatch(batch)
                // A sample that didn't fit starts the next datagram immediately (no extra wait).
                carry?.let { batch.clear(); batch.add(it); sendBatch(batch) }
            }
        }
    }

    private fun sendBatch(batch: List<SensorSample>) {
        try {
            // Stamp serialize time before encoding so stage timestamps are in the packet.
            val now = SystemClock.elapsedRealtimeNanos()
            for (sample in batch) sample.tSerializeNs = now
            val bytes = BinaryPacketCodec.encode(deviceId, batch, BinaryPacketCodec.FLAG_STAGE_TS)
            sender.send(bytes)
            sentPackets.incrementAndGet()
            sentBytes.addAndGet(bytes.size.toLong())
        } catch (t: Throwable) {
            sendErrors.incrementAndGet()
            reportError(t)
        }
    }

    /** Surface an error to the UI at most once per [ERROR_REPORT_INTERVAL_MS]: with the network
     *  down every sample fails, and reporting each one would churn UI state hundreds of times/s. */
    private fun reportError(t: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastErrorReportMs < ERROR_REPORT_INTERVAL_MS) return
        lastErrorReportMs = now
        onError?.invoke(t)
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

    companion object {
        const val DEFAULT_BATCH_WINDOW_MS = 2L
        const val ERROR_REPORT_INTERVAL_MS = 2_000L
    }
}
