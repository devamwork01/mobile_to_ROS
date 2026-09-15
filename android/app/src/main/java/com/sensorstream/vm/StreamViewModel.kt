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

    // --- Local sensor preview (UI only) --------------------------------------------------------
    // Drives the 3D visualizations + detail values so screens respond to device motion whether or
    // not we're streaming. These are SEPARATE, read-only listeners purely for the visualization:
    // they do not touch SensorEventSource, the telemetry pipeline, timestamps, fusion, or the
    // network. Registrations are ref-counted per sensor type and released when screens dispose.
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val _preview = MutableStateFlow<Map<Int, FloatArray>>(emptyMap())
    /** Latest preview values keyed by Android sensor type. */
    val preview: StateFlow<Map<Int, FloatArray>> = _preview.asStateFlow()
    private val previewRefs = HashMap<Int, Int>()
    private val previewAccuracy = HashMap<Int, Int>()
    private val previewListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            _preview.value = _preview.value + (e.sensor.type to e.values.copyOf())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor != null) previewAccuracy[sensor.type] = accuracy
        }
    }

    /** Convenience for the hero orientation. */
    val orientationPreview: StateFlow<FloatArray?> =
        MutableStateFlow<FloatArray?>(null).also { flow ->
            viewModelScope.launch { preview.collect { flow.value = it[Sensor.TYPE_ROTATION_VECTOR] } }
        }.asStateFlow()

    fun previewAccuracyOf(type: Int): Int = previewAccuracy[type] ?: -1

    /** Register read-only preview listeners for [types] (ref-counted). */
    fun startPreview(vararg types: Int) {
        for (type in types) {
            if ((previewRefs[type] ?: 0) == 0) {
                sensorManager.getDefaultSensor(type)?.let {
                    sensorManager.registerListener(previewListener, it, SensorManager.SENSOR_DELAY_GAME)
                }
            }
            previewRefs[type] = (previewRefs[type] ?: 0) + 1
        }
    }

    fun stopPreview(vararg types: Int) {
        for (type in types) {
            val n = (previewRefs[type] ?: 0)
            if (n <= 1) {
                previewRefs.remove(type)
                sensorManager.getDefaultSensor(type)?.let { sensorManager.unregisterListener(previewListener, it) }
            } else {
                previewRefs[type] = n - 1
            }
        }
    }

    /** Back-compat convenience for the Home hero (rotation vector only). */
    fun startOrientationPreview() = startPreview(Sensor.TYPE_ROTATION_VECTOR)
    fun stopOrientationPreview() = stopPreview(Sensor.TYPE_ROTATION_VECTOR)

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
