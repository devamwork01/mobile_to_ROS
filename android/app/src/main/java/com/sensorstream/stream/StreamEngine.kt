package com.sensorstream.stream

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import com.sensorstream.core.SensorInfo
import com.sensorstream.core.SensorSample
import com.sensorstream.net.WsControlClient
import com.sensorstream.sensor.SensorEventSource
import com.sensorstream.sensor.SensorRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A user's choice to stream one sensor at a requested period. */
data class Selection(val handle: Int, val periodUs: Int)

data class EngineState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val streaming: Boolean = false,
    val deviceId: Int = 0,
    val udpPort: Int = 0,
    val rttMs: Float = 0f,
    val sentPackets: Long = 0L,
    val droppedSamples: Long = 0L,
    val sendBps: Long = 0L, // outgoing telemetry bytes/sec (network load this app puts out)
    val recBytes: Long = 0L, // on-phone recording size on disk
    val recDropped: Long = 0L, // records lost to ring prune (over cap = permanent gap)
    val recOldestAgeMs: Long = 0L, // age of the oldest buffered record
    val recFanoutDropped: Long = 0L, // samples dropped at the fan-out channel (never reached recorder)
    val recWriteErrors: Long = 0L, // recorder disk write failures
    val error: String? = null,
)

/**
 * Orchestrates the whole streaming session: opens the control channel, sends the
 * device info + sensor catalog, and once the laptop assigns a device id + UDP
 * port, starts multi-sensor telemetry. Kept free of Android UI / ViewModel /
 * Service types so it can be hosted by a foreground Service later without rework.
 */
class StreamEngine(context: Context) {

    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val repo = SensorRepository(sm)
    private val controller = StreamController(sm)
    private val control = WsControlClient()
    private var scope: CoroutineScope? = null

    val catalog: List<SensorInfo> by lazy { repo.enumerate() }

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val latest = ConcurrentHashMap<Int, SensorSample>()
    private val hz = ConcurrentHashMap<Int, Float>()
    private val lastTs = ConcurrentHashMap<Int, Long>()

    fun latestFor(handle: Int): SensorSample? = latest[handle]
    fun hzFor(handle: Int): Float = hz[handle] ?: 0f

    private var host: String = ""
    private var controlPort: Int = 0
    private var selections: List<Selection> = emptyList()
    @Volatile private var desired = false
    private var reconnectAttempts = 0
    // Increments on every (re)connect; callbacks from superseded/closed sockets
    // carry a stale generation and are ignored, preventing a reconnect storm.
    private var connGen = 0

    private var recordDir: File? = null
    private var recMaxBytes = 150L * 1024 * 1024
    private var recMaxAgeMs = 20L * 60 * 1000
    private val recSegmentMs = 10_000L
    // Built ONCE per streaming session (first successful connect) and reused across reconnects, so
    // reconnects never orphan/rebuild it; closed only when the session actually stops.
    private var recorder: LocalRecorder? = null

    /** Configure on-phone lossless recording; call before [start]. */
    fun configureRecording(dir: File, maxBytes: Long, maxAgeMs: Long) {
        recordDir = dir
        recMaxBytes = maxBytes
        recMaxAgeMs = maxAgeMs
    }

    fun recorderStats(): LocalRecorder.Stats? = controller.recorderStats()

    fun start(host: String, controlPort: Int, selections: List<Selection>) {
        stop()
        this.host = host
        this.controlPort = controlPort
        this.selections = selections
        desired = true
        reconnectAttempts = 0
        latest.clear(); hz.clear(); lastTs.clear()
        _state.value = EngineState(connecting = true)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s

        controller.onUiSample = { sample ->
            latest[sample.handle] = sample
            val lt = lastTs[sample.handle]
            if (lt != null && sample.timestampNs > lt) {
                val inst = 1e9f / (sample.timestampNs - lt)
                val prev = hz[sample.handle]
                hz[sample.handle] = if (prev == null) inst else 0.9f * prev + 0.1f * inst
            }
            lastTs[sample.handle] = sample.timestampNs
        }
        controller.onError = { e -> _state.value = _state.value.copy(error = e.message ?: e.toString()) }

        connectControl()

        s.launch {
            var hb = 0
            var lastBytes = 0L
            while (isActive) {
                // The connection now depends on this loop for heartbeats + stats; a transient
                // control.send* / stats throw must not silently kill it. Swallow non-cancellation
                // errors and keep ticking (cancellation still propagates to stop the loop).
                try {
                    if (_state.value.connected) {
                        control.sendHeartbeat(hb++)
                        control.sendStats(
                            JSONObject()
                                .put("sent", controller.sentPackets.get())
                                .put("dropped", controller.droppedSamples.get())
                        )
                    }
                    val nowBytes = controller.sentBytes.get()
                    val bps = (nowBytes - lastBytes).coerceAtLeast(0L) // loop cadence is ~1s
                    lastBytes = nowBytes
                    val rs = controller.recorderStats()
                    _state.value = _state.value.copy(
                        sentPackets = controller.sentPackets.get(),
                        droppedSamples = controller.droppedSamples.get(),
                        sendBps = bps,
                        recBytes = rs?.bytesOnDisk ?: 0L,
                        recDropped = rs?.droppedOldest ?: 0L,
                        recOldestAgeMs = rs?.oldestAgeMs ?: 0L,
                        recFanoutDropped = controller.recorderDropped.get(),
                        recWriteErrors = rs?.writeErrors ?: 0L,
                    )
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    // transient (e.g. a WS send racing a drop) — retry next tick
                }
                delay(1000)
            }
        }
    }

    private fun connectControl() {
        val gen = ++connGen
        _state.value = _state.value.copy(connecting = true, error = null)
        control.connect(host, controlPort, buildHello(), object : WsControlClient.Listener {
            override fun onConnected() {}
            override fun onHelloAck(deviceId: Int, udpPort: Int) {
                if (gen != connGen) return
                reconnectAttempts = 0
                controller.deviceId = deviceId
                _state.value = _state.value.copy(connecting = false, connected = true, deviceId = deviceId, udpPort = udpPort)
                startTelemetry(udpPort)
            }
            override fun onConfigure(msg: JSONObject) { /* laptop-driven config: Phase 2b */ }
            override fun onRtt(ms: Float) { if (gen == connGen) _state.value = _state.value.copy(rttMs = ms) }
            override fun onClosed(reason: String?) { if (gen == connGen) onDropped() }
            override fun onFailure(t: Throwable) {
                if (gen != connGen) return
                _state.value = _state.value.copy(error = t.message ?: t.toString())
                onDropped()
            }
        })
    }

    // Control channel dropped: stop telemetry and, if still desired, reconnect with backoff.
    private fun onDropped() {
        controller.stop()
        _state.value = _state.value.copy(connected = false, streaming = false)
        if (!desired) {
            _state.value = _state.value.copy(connecting = false)
            return
        }
        val attempt = reconnectAttempts.coerceAtMost(4)
        reconnectAttempts += 1
        val delayMs = (500L shl attempt).coerceAtMost(8000L) // 0.5, 1, 2, 4, 8s
        _state.value = _state.value.copy(connecting = true)
        scope?.launch {
            delay(delayMs)
            if (desired && isActive) connectControl()
        }
    }

    private fun startTelemetry(udpPort: Int) {
        val regs = selections.mapNotNull { sel ->
            repo.sensorAt(sel.handle)?.let { SensorEventSource.Reg(it, sel.handle, sel.periodUs) }
        }
        if (regs.isEmpty()) {
            _state.value = _state.value.copy(error = "No sensors selected")
            return
        }
        // Build the recorder ONCE per session (this runs again on every reconnect via onHelloAck).
        // Reuse the same instance across reconnects so its files/index/cap accounting persist; a
        // fresh instance per reconnect would orphan the prior segments (unbounded growth).
        // deviceId is the laptop-assigned id (set in onHelloAck before this call, on first connect).
        if (recorder == null) {
            recorder = recordDir?.let {
                // flags = 0: the recorder's encode must not read tSerializeNs, which the network
                // drain coroutine mutates concurrently on the same fanned-out sample; serialize-for-
                // send time is meaningless for an on-phone recording anyway.
                LocalRecorder(File(it, "onphone"), controller.deviceId, recMaxBytes, recMaxAgeMs, recSegmentMs, flags = 0)
            }
        }
        controller.start(host, udpPort, regs, recorder)
        _state.value = _state.value.copy(streaming = true)
        control.sendActive(regs.map { it.handle })
    }

    /**
     * Live-reconfigure the streamed sensor set (add/remove/rate-change) without a
     * reconnect. Safe to call any time; only takes effect while streaming. The new
     * active set is announced to the laptop so the dashboard updates immediately.
     */
    fun updateSelections(newSelections: List<Selection>) {
        selections = newSelections
        if (!_state.value.streaming) return
        val regs = newSelections.mapNotNull { sel ->
            repo.sensorAt(sel.handle)?.let { SensorEventSource.Reg(it, sel.handle, sel.periodUs) }
        }
        val keep = regs.map { it.handle }.toSet()
        for (h in latest.keys.toList()) if (h !in keep) { latest.remove(h); hz.remove(h); lastTs.remove(h) }
        controller.updateRegs(regs)
        control.sendActive(regs.map { it.handle })
    }

    private fun buildHello(): JSONObject {
        val sensors = JSONArray()
        for (info in catalog) {
            sensors.put(
                JSONObject()
                    .put("handle", info.handle)
                    .put("name", info.name)
                    .put("type", info.type)
                    .put("stringType", info.stringType)
                    .put("vendor", info.vendor)
                    .put("version", info.version)
                    .put("resolution", info.resolution.toDouble())
                    .put("maxRange", info.maximumRange.toDouble())
                    .put("power", info.power.toDouble())
                    .put("minDelayUs", info.minDelayUs)
                    .put("maxDelayUs", info.maxDelayUs)
                    .put("reportingMode", info.reportingMode)
                    .put("isWakeUp", info.isWakeUp)
                    .put("valueCount", info.valueCount)
                    .put("units", info.units)
                    .put("maxHz", info.maxFrequencyHz.toDouble())
            )
        }
        return JSONObject()
            .put("type", "hello")
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("app_version", "0.1.0")
            .put("sensors", sensors)
    }

    fun stop() {
        desired = false
        connGen++  // invalidate in-flight callbacks so nothing reconnects after stop
        controller.onUiSample = null
        controller.stop()          // flushes the recorder's buffered tail; leaves it open
        control.close()
        scope?.cancel()            // stops the heartbeat loop -> no more recorderStats() reads
        scope = null
        recorder?.close()          // now safe: StreamEngine owns the recorder's lifecycle
        recorder = null
        _state.value = _state.value.copy(connecting = false, connected = false, streaming = false)
    }
}
