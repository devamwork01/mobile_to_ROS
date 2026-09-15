package com.sensorstream.vm

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sensorstream.core.SensorInfo
import com.sensorstream.net.Discovery
import com.sensorstream.service.StreamingService
import com.sensorstream.stream.EngineState
import com.sensorstream.stream.Selection
import com.sensorstream.stream.StreamEngine
import com.sensorstream.stream.StreamHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state holder for Phase 2: manages the connection target, per-sensor
 * enable/rate selection, and a decimated snapshot of live values, while
 * delegating the actual streaming to [StreamEngine].
 */
class StreamViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = StreamHolder.engine(app)
    private val discovery = Discovery(app)

    val catalog: List<SensorInfo> = engine.catalog
    val engineState: StateFlow<EngineState> = engine.state

    /** Requested-rate presets: label -> sampling period (µs); 0 = fastest. */
    val presets: List<Pair<String, Int>> =
        listOf("10 Hz" to 100_000, "50 Hz" to 20_000, "100 Hz" to 10_000, "200 Hz" to 5_000, "Max" to 0)

    data class Selections(
        val host: String = "192.168.1.100",
        val port: String = "8081",
        val enabled: Set<Int> = emptySet(),
        val periodByHandle: Map<Int, Int> = emptyMap(),
    )

    /** Snapshot of live values for enabled sensors, refreshed ~12 Hz. */
    class Live(val values: FloatArray, val hz: Float)

    private val _sel = MutableStateFlow(Selections())
    val sel: StateFlow<Selections> = _sel.asStateFlow()

    private val _live = MutableStateFlow<Map<Int, Live>>(emptyMap())
    val live: StateFlow<Map<Int, Live>> = _live.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    // --- Local orientation preview (UI only) ---------------------------------------------------
    // Drives the hero 3D phone so it responds to device motion whether or not we're streaming. This
    // is a SEPARATE, read-only rotation-vector listener purely for the visualization: it does not
    // touch SensorEventSource, the telemetry pipeline, timestamps, fusion, or the network. Started
    // only while a screen is showing the hero (startOrientationPreview) and stopped on dispose.
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val _orientationPreview = MutableStateFlow<FloatArray?>(null)
    val orientationPreview: StateFlow<FloatArray?> = _orientationPreview.asStateFlow()
    private var previewRefs = 0
    private val previewListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) _orientationPreview.value = e.values.copyOf()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Ref-counted so multiple screens can share one registration. */
    fun startOrientationPreview() {
        if (previewRefs++ == 0) {
            rotationSensor?.let { sensorManager.registerListener(previewListener, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stopOrientationPreview() {
        if (previewRefs > 0 && --previewRefs == 0) {
            sensorManager.unregisterListener(previewListener)
        }
    }

    init {
        val defaultTypes = setOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_ROTATION_VECTOR,
        )
        val enabled = catalog.filter { it.type in defaultTypes }.map { it.handle }.toSet()
        val periods = catalog.associate { it.handle to 10_000 } // default 100 Hz
        _sel.value = _sel.value.copy(enabled = enabled, periodByHandle = periods)

        viewModelScope.launch {
            while (true) {
                if (engine.state.value.streaming) {
                    val snapshot = HashMap<Int, Live>()
                    for (handle in _sel.value.enabled) {
                        val s = engine.latestFor(handle) ?: continue
                        snapshot[handle] = Live(s.values.copyOf(s.valueCount), engine.hzFor(handle))
                    }
                    _live.value = snapshot
                }
                delay(80)
            }
        }
    }

    fun setHost(h: String) { _sel.value = _sel.value.copy(host = h) }
    fun setPort(p: String) { _sel.value = _sel.value.copy(port = p) }

    fun toggle(handle: Int) {
        val e = _sel.value.enabled.toMutableSet()
        if (!e.add(handle)) e.remove(handle)
        _sel.value = _sel.value.copy(enabled = e)
        applyLiveReconfig()
    }

    fun periodOf(handle: Int): Int = _sel.value.periodByHandle[handle] ?: 10_000

    fun setPeriod(handle: Int, periodUs: Int) {
        _sel.value = _sel.value.copy(periodByHandle = _sel.value.periodByHandle + (handle to periodUs))
        applyLiveReconfig()
    }

    /** When already streaming, push selection/rate changes to the engine without a reconnect. */
    private fun applyLiveReconfig() {
        if (!engine.state.value.streaming) return
        engine.updateSelections(_sel.value.enabled.map { Selection(it, periodOf(it)) })
    }

    fun toggleStreaming() {
        val st = engine.state.value
        if (st.streaming || st.connecting || st.connected) {
            StreamingService.stop(getApplication())
            return
        }
        val port = _sel.value.port.trim().toIntOrNull()
        if (port == null || port !in 1..65535) return
        val selections = _sel.value.enabled.map { Selection(it, periodOf(it)) }
        if (selections.isEmpty()) return
        StreamingService.start(getApplication(), _sel.value.host.trim(), port, selections)
    }

    fun discover() {
        if (_discovering.value) return
        _discovering.value = true
        discovery.start(Discovery.Listener { host, controlPort ->
            _sel.value = _sel.value.copy(host = host, port = controlPort.toString())
            discovery.stop()
            _discovering.value = false
        })
        viewModelScope.launch {
            delay(8000)
            if (_discovering.value) {
                discovery.stop()
                _discovering.value = false
            }
        }
    }

    // Streaming is owned by StreamingService (survives recreation); only stop discovery here.
    override fun onCleared() {
        discovery.stop()
        sensorManager.unregisterListener(previewListener)
    }
}
