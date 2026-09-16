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
import com.sensorstream.ui.settings.AppSettings
import com.sensorstream.ui.settings.SettingsStore
import com.sensorstream.ui.settings.ThemeMode
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

    /** Transient result of the last "Find Laptop" run, shown on the Connection screen. */
    data class Notice(val text: String, val isError: Boolean)
    private val _discoveryNotice = MutableStateFlow<Notice?>(null)
    val discoveryNotice: StateFlow<Notice?> = _discoveryNotice.asStateFlow()

    // --- Display settings (UI only) ------------------------------------------------------------
    // Theme + visualization defaults. Persisted to SharedPreferences; the Activity reads
    // [settings].themeMode to choose the palette. Nothing here touches the telemetry pipeline.
    private val settingsStore = SettingsStore(app)
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        settingsStore.save(next)
    }

    fun setThemeMode(mode: ThemeMode) = updateSettings { it.copy(themeMode = mode) }
    fun setDefault3dWorldFrame(on: Boolean) = updateSettings { it.copy(default3dWorldFrame = on) }
    fun setDefault3dLabels(on: Boolean) = updateSettings { it.copy(default3dLabels = on) }

    // --- Local sensor preview (UI only) --------------------------------------------------------
    // Drives the 3D visualizations + detail values so screens respond to device motion whether or
    // not we're streaming. These are SEPARATE, read-only listeners purely for the visualization:
    // they do not touch SensorEventSource, the telemetry pipeline, timestamps, fusion, or the
    // network. Keyed by catalog HANDLE (not type) and registered against the EXACT sensor so
    // vendor/uncalibrated/duplicate/proximity sensors that getDefaultSensor(type) misses still
    // deliver data. Ref-counted per handle; released when screens dispose.
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val _preview = MutableStateFlow<Map<Int, FloatArray>>(emptyMap())
    /** Latest preview values keyed by catalog handle. */
    val preview: StateFlow<Map<Int, FloatArray>> = _preview.asStateFlow()
    private val previewRefs = HashMap<Int, Int>()
    private val previewAccuracy = HashMap<Int, Int>()
    private val previewListeners = HashMap<Int, SensorEventListener>()

    /** Handle of the primary orientation (rotation vector) sensor, if present. */
    val orientationHandle: Int? = catalog.firstOrNull { it.type == Sensor.TYPE_ROTATION_VECTOR }?.handle

    private fun makeListener(handle: Int) = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            _preview.value = _preview.value + (handle to e.values.copyOf())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            previewAccuracy[handle] = accuracy
        }
    }

    /** Convenience for the hero orientation. */
    val orientationPreview: StateFlow<FloatArray?> =
        MutableStateFlow<FloatArray?>(null).also { flow ->
            viewModelScope.launch { preview.collect { m -> flow.value = orientationHandle?.let { m[it] } } }
        }.asStateFlow()

    fun previewAccuracyOf(handle: Int): Int = previewAccuracy[handle] ?: -1

    /** The preview sampling delay (µs) for a handle, taken from the user's selected rate so
     *  changing the rate on the detail screen visibly changes the on-device preview cadence.
     *  0 / "Max" maps to the fastest the device allows. */
    private fun previewDelayUs(handle: Int): Int {
        val periodUs = _sel.value.periodByHandle[handle] ?: 10_000
        return if (periodUs <= 0) SensorManager.SENSOR_DELAY_FASTEST else periodUs
    }

    private fun registerPreview(handle: Int) {
        val sensor = engine.sensorFor(handle) ?: return
        val listener = makeListener(handle)
        previewListeners[handle] = listener
        sensorManager.registerListener(listener, sensor, previewDelayUs(handle))
    }

    private fun unregisterPreview(handle: Int) {
        previewListeners.remove(handle)?.let { sensorManager.unregisterListener(it) }
    }

    /** Register read-only preview listeners for [handles] (ref-counted). */
    fun startPreview(vararg handles: Int) {
        for (handle in handles) {
            if ((previewRefs[handle] ?: 0) == 0) registerPreview(handle)
            previewRefs[handle] = (previewRefs[handle] ?: 0) + 1
        }
    }

    fun stopPreview(vararg handles: Int) {
        for (handle in handles) {
            val n = (previewRefs[handle] ?: 0)
            if (n <= 1) {
                previewRefs.remove(handle)
                unregisterPreview(handle)
            } else {
                previewRefs[handle] = n - 1
            }
        }
    }

    /** Convenience for the hero orientation (rotation vector handle). */
    fun startOrientationPreview() { orientationHandle?.let { startPreview(it) } }
    fun stopOrientationPreview() { orientationHandle?.let { stopPreview(it) } }

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

    fun setHost(h: String) { _sel.value = _sel.value.copy(host = h); _discoveryNotice.value = null }
    fun setPort(p: String) { _sel.value = _sel.value.copy(port = p); _discoveryNotice.value = null }

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
        // If this sensor is being previewed on-screen, re-register it so the new rate takes effect
        // immediately (preview is UI-only and independent of the streaming pipeline).
        if ((previewRefs[handle] ?: 0) > 0) {
            unregisterPreview(handle)
            registerPreview(handle)
        }
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
        _discoveryNotice.value = null
        discovery.start(Discovery.Listener { host, controlPort ->
            _sel.value = _sel.value.copy(host = host, port = controlPort.toString())
            discovery.stop()
            _discovering.value = false
            _discoveryNotice.value = Notice("Found laptop at $host:$controlPort", isError = false)
        })
        viewModelScope.launch {
            delay(8000)
            if (_discovering.value) {
                discovery.stop()
                _discovering.value = false
                _discoveryNotice.value = Notice(
                    "No laptop found. Make sure the laptop app is running on the same Wi-Fi, " +
                        "then try again — or enter the IP manually.",
                    isError = true,
                )
            }
        }
    }

    // Streaming is owned by StreamingService (survives recreation); only stop discovery here.
    override fun onCleared() {
        discovery.stop()
        previewListeners.values.forEach { sensorManager.unregisterListener(it) }
        previewListeners.clear()
    }
}
