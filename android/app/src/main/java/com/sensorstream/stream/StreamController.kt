package com.sensorstream.stream

import android.hardware.SensorManager
import android.os.SystemClock
import com.sensorstream.codec.BinaryPacketCodec
import com.sensorstream.core.SensorSample
import com.sensorstream.net.UdpTelemetrySender
import com.sensorstream.sensor.SensorEventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Ties acquisition to networking through a **bounded channel**: sensor callbacks
 * `trySend` samples (dropping the oldest under backpressure — a full queue never
 * blocks the sensor thread), and a single IO coroutine drains, serializes and
 * sends them. This is the Phase-1 shape of the "sensor event bus -> serialization
 * -> network queue -> UDP" pipeline; coalescing and buffer pooling arrive in Phase 7.
 */
class StreamController(sm: SensorManager) {

    private val source = SensorEventSource(sm)
    private val sender = UdpTelemetrySender()

    private var scope: CoroutineScope? = null
    private var channel: Channel<SensorSample>? = null

    val sentPackets = AtomicLong(0)
    val sentBytes = AtomicLong(0)
    val droppedSamples = AtomicLong(0)

    @Volatile var deviceId: Int = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
    @Volatile var onUiSample: ((SensorSample) -> Unit)? = null
    @Volatile var onError: ((Throwable) -> Unit)? = null

    fun start(host: String, port: Int, regs: List<SensorEventSource.Reg>) {
        stop()
        sentPackets.set(0)
        sentBytes.set(0)
        droppedSamples.set(0)

        val ch = Channel<SensorSample>(capacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        channel = ch
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        source.onSample = { sample ->
            if (ch.trySend(sample).isFailure) droppedSamples.incrementAndGet()
            onUiSample?.invoke(sample)
        }

        s.launch {
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

        source.start(regs)
    }

    /** Live-reconfigure the streamed sensor set without dropping the UDP sender. */
    fun updateRegs(regs: List<SensorEventSource.Reg>) {
        if (scope == null) return
        source.updateRegs(regs)
    }

    fun stop() {
        source.onSample = null
        source.stop()
        channel?.close()
        channel = null
        scope?.cancel()
        scope = null
        sender.close()
    }
}
