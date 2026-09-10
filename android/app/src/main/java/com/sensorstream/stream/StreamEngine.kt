package com.sensorstream.stream

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import com.sensorstream.core.SensorInfo
import com.sensorstream.core.SensorSample
import com.sensorstream.net.WsControlClient
import com.sensorstream.sensor.SensorEventSource
import com.sensorstream.sensor.SensorRepository
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
    private var selections: List<Selection> = emptyList()

    fun start(host: String, controlPort: Int, selections: List<Selection>) {
        stop()
        this.host = host
        this.selections = selections
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

        control.connect(host, controlPort, buildHello(), object : WsControlClient.Listener {
            override fun onConnected() {}
            override fun onHelloAck(deviceId: Int, udpPort: Int) {
                controller.deviceId = deviceId
                _state.value = _state.value.copy(connecting = false, connected = true, deviceId = deviceId, udpPort = udpPort)
                startTelemetry(udpPort)
            }
            override fun onConfigure(msg: JSONObject) { /* laptop-driven config: Phase 2b */ }
            override fun onRtt(ms: Float) { _state.value = _state.value.copy(rttMs = ms) }
            override fun onClosed(reason: String?) { _state.value = _state.value.copy(connected = false, streaming = false) }
            override fun onFailure(t: Throwable) {
                _state.value = _state.value.copy(connecting = false, connected = false, streaming = false, error = t.message ?: t.toString())
            }
        })

        s.launch {
            var hb = 0
            while (isActive) {
                if (_state.value.connected) {
                    control.sendHeartbeat(hb++)
                    control.sendStats(
                        JSONObject()
                            .put("sent", controller.sentPackets.get())
                            .put("dropped", controller.droppedSamples.get())
                    )
                }
                _state.value = _state.value.copy(
                    sentPackets = controller.sentPackets.get(),
                    droppedSamples = controller.droppedSamples.get(),
                )
                delay(1000)
            }
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
        controller.start(host, udpPort, regs)
        _state.value = _state.value.copy(streaming = true)
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
        controller.onUiSample = null
        controller.stop()
        control.close()
        scope?.cancel()
        scope = null
        _state.value = _state.value.copy(connecting = false, connected = false, streaming = false)
    }
}
