package com.sensorstream.vm

import com.sensorstream.core.filter.Biquad
import com.sensorstream.core.filter.FilterConfig
import com.sensorstream.core.filter.FilterConfigCodec
import com.sensorstream.core.filter.PhoneFilterBank
import org.json.JSONObject
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sensorstream.core.SelectionCodec
import com.sensorstream.core.SensorInfo
import com.sensorstream.core.TargetValidator
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
import kotlinx.coroutines.flow.update
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

    /** Why the last Connect/Start was refused (bad address), shown on Home + Connection. */
    private val _connectNotice = MutableStateFlow<Notice?>(null)
    val connectNotice: StateFlow<Notice?> = _connectNotice.asStateFlow()

    // --- Persisted connection target + sensor selection -----------------------------------------
    // Remembered across launches so the app can reconnect to the last laptop in one tap instead
    // of resetting to a placeholder IP and default sensors every time.
    private val prefs = app.getSharedPreferences("sensorstream", Context.MODE_PRIVATE)
    private val catalogTypes: Map<Int, Int> by lazy { catalog.associate { it.handle to it.type } }

    /** True once the user has connected / discovered / typed a laptop address at least once. */
    private val _hasSavedTarget = MutableStateFlow(prefs.contains(KEY_HOST))
    val hasSavedTarget: StateFlow<Boolean> = _hasSavedTarget.asStateFlow()

    private fun saveTarget() {
        prefs.edit().putString(KEY_HOST, _sel.value.host).putString(KEY_PORT, _sel.value.port).apply()
        _hasSavedTarget.value = true
    }

    private fun saveSelection() {
        val v = _sel.value
        prefs.edit().putString(KEY_SELECTION, SelectionCodec.encode(v.enabled, v.periodByHandle) { catalogTypes[it] }).apply()
    }

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
    fun setShowFiltered(on: Boolean) = updateSettings { it.copy(showFiltered = on) }
    fun setBatchMode(mode: com.sensorstream.ui.settings.BatchMode) {
        updateSettings { it.copy(batchMode = mode) }
        engine.batchWindowMs = mode.windowMs
    }

    // --- Local sensor preview (UI only) --------------------------------------------------------
    // Drives the 3D visualizations + detail values so screens respond to device motion whether or
    // not we're streaming. These are SEPARATE, read-only listeners purely for the visualization:
    // they do not touch SensorEventSource, the telemetry pipeline, timestamps, fusion, or the
    // network. Keyed by catalog HANDLE (not type) and registered against the EXACT sensor so
    // vendor/uncalibrated/duplicate/proximity sensors that getDefaultSensor(type) misses still
    // deliver data. Ref-counted per handle; released when screens dispose.
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val _preview = MutableStateFlow<Map<Int, FloatArray>>(emptyMap())
    /** Latest preview values keyed by catalog handle. Written from the preview sensor thread; the
     *  StateFlow is conflated, so the UI picks up only the newest map once per frame. */
    val preview: StateFlow<Map<Int, FloatArray>> = _preview.asStateFlow()
    private val previewRefs = HashMap<Int, Int>()
    private val previewAccuracy = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val previewListeners = HashMap<Int, SensorEventListener>()

    // Preview sensor callbacks run on a background thread so the per-event map copy never lands on
    // the main thread; Compose collects the conflated StateFlow and coalesces updates per frame.
    // (A fixed-rate ticker publishing instead was measured WORSE on a 120 Hz display: updates out
    // of phase with vsync pushed ~50% of frames past deadline, vs ~7.5% baseline.)
    private val previewThread = android.os.HandlerThread("sensor-preview").apply { start() }
    private val previewHandler = android.os.Handler(previewThread.looper)

    /** Handle of the primary orientation (rotation vector) sensor, if present. */
    val orientationHandle: Int? = catalog.firstOrNull { it.type == Sensor.TYPE_ROTATION_VECTOR }?.handle

    private fun makeListener(handle: Int) = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values.copyOf()
            keyByHandle[handle]?.let { key -> filterTap.onSample(handle, key, catalogTypes[handle] ?: 0, e.timestamp, v) }
            _preview.update { it + (handle to v) }
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
        sensorManager.registerListener(listener, sensor, previewDelayUs(handle), previewHandler)
    }

    private fun unregisterPreview(handle: Int) {
        previewListeners.remove(handle)?.let { sensorManager.unregisterListener(it) }
        _preview.update { it - handle }
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

    // --- On-phone filtering (settings come from the laptop) --------------------------------------
    // The laptop pushes its filter settings over the control channel; they are persisted so the
    // phone keeps filtering its own preview samples offline. Filtered sensors are tapped (via the
    // preview listeners above, at their configured period) while a screen that shows them is up.
    private val filterBank = PhoneFilterBank()
    private val filterTap = FilterTap(filterBank)
    val filterTraces: StateFlow<Map<Int, FilterTrace>> = filterTap.traces
    private val keyByHandle: Map<Int, String> = catalog.associate { it.handle to "${it.type}:${it.name}" }
    private val _filterConfigs = MutableStateFlow(FilterConfigCodec.decode(prefs.getString(KEY_FILTERS, null)))
    val filterConfigs: StateFlow<Map<String, FilterConfig>> = _filterConfigs.asStateFlow()

    data class FilteredSensor(val handle: Int, val key: String, val info: SensorInfo, val config: FilterConfig, val enabled: Boolean)

    private val _filteredSensors = MutableStateFlow<List<FilteredSensor>>(emptyList())
    /** Sensors on this phone that have a laptop filter (filterable types only). */
    val filteredSensors: StateFlow<List<FilteredSensor>> = _filteredSensors.asStateFlow()

    private fun recomputeFiltered() {
        val cfgs = _filterConfigs.value
        val enabled = _sel.value.enabled
        _filteredSensors.value = catalog.mapNotNull { info ->
            val key = keyByHandle[info.handle] ?: return@mapNotNull null
            val cfg = cfgs[key] ?: return@mapNotNull null
            if (info.type in Biquad.EXCLUDED_TYPES) return@mapNotNull null
            FilteredSensor(info.handle, key, info, cfg, info.handle in enabled)
        }
        if (tapRefs > 0) applyTap()
    }

    private fun applyFilterMessage(msg: JSONObject) {
        val configs = msg.optJSONObject("configs") ?: return
        val m = FilterConfigCodec.parseConfigs(configs)
        filterBank.setConfigs(m)
        _filterConfigs.value = m
        prefs.edit().putString(KEY_FILTERS, FilterConfigCodec.encode(m)).apply()
        recomputeFiltered()
    }

    private var tapRefs = 0
    private var tapped: Set<Int> = emptySet()

    private fun applyTap() {
        val want = if (tapRefs > 0) _filteredSensors.value.filter { it.enabled }.map { it.handle }.toSet() else emptySet()
        (tapped - want).takeIf { it.isNotEmpty() }?.let { stopPreview(*it.toIntArray()) }
        (want - tapped).takeIf { it.isNotEmpty() }?.let { startPreview(*it.toIntArray()) }
        tapped = want
        filterTap.handles = want
    }

    /** Ref-counted: screens showing filtered signals call this while visible. */
    fun startFilterTap() { tapRefs++; if (tapRefs == 1) applyTap() }
    fun stopFilterTap() { tapRefs = maxOf(0, tapRefs - 1); if (tapRefs == 0) applyTap() }

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
        engine.batchWindowMs = _settings.value.batchMode.windowMs
        val defaultEnabled = catalog.filter { it.type in defaultTypes }.map { it.handle }.toSet()
        val defaultPeriods = catalog.associate { it.handle to 10_000 } // default 100 Hz
        val saved = SelectionCodec.decode(prefs.getString(KEY_SELECTION, null), catalogTypes)
        _sel.value = _sel.value.copy(
            host = prefs.getString(KEY_HOST, null) ?: _sel.value.host,
            port = prefs.getString(KEY_PORT, null) ?: _sel.value.port,
            enabled = saved?.enabled ?: defaultEnabled,
            periodByHandle = defaultPeriods + (saved?.periods ?: emptyMap()),
        )

        filterBank.setConfigs(_filterConfigs.value)
        recomputeFiltered()
        // Settings arrive on the network thread; apply them on the main thread (preview
        // registration is main-thread state).
        engine.onFilters = { msg -> viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) { applyFilterMessage(msg) } }
        viewModelScope.launch { _sel.collect { recomputeFiltered() } }

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

    fun setHost(h: String) {
        _sel.value = _sel.value.copy(host = h); _discoveryNotice.value = null; _connectNotice.value = null; saveTarget()
    }
    fun setPort(p: String) {
        _sel.value = _sel.value.copy(port = p); _discoveryNotice.value = null; _connectNotice.value = null; saveTarget()
    }

    fun toggle(handle: Int) {
        val e = _sel.value.enabled.toMutableSet()
        if (!e.add(handle)) e.remove(handle)
        _sel.value = _sel.value.copy(enabled = e)
        saveSelection()
        applyLiveReconfig()
    }

    fun periodOf(handle: Int): Int = _sel.value.periodByHandle[handle] ?: 10_000

    fun setPeriod(handle: Int, periodUs: Int) {
        _sel.value = _sel.value.copy(periodByHandle = _sel.value.periodByHandle + (handle to periodUs))
        saveSelection()
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
        TargetValidator.validate(_sel.value.host, _sel.value.port)?.let {
            _connectNotice.value = Notice(it, isError = true)
            return
        }
        val selections = _sel.value.enabled.map { Selection(it, periodOf(it)) }
        if (selections.isEmpty()) {
            _connectNotice.value = Notice("Select at least one sensor on the Sensors tab first.", isError = true)
            return
        }
        _connectNotice.value = null
        saveTarget()
        StreamingService.start(getApplication(), _sel.value.host.trim(), _sel.value.port.trim().toInt(), selections)
    }

    fun discover() {
        if (_discovering.value) return
        _discovering.value = true
        _discoveryNotice.value = null
        discovery.start(Discovery.Listener { host, controlPort ->
            _sel.value = _sel.value.copy(host = host, port = controlPort.toString())
            _connectNotice.value = null
            saveTarget()
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

    // --- On-phone recording export ---------------------------------------------------------------
    private val recordingDir get() = java.io.File(getApplication<Application>().filesDir, "onphone")

    /** What is currently buffered on the phone (segments on disk). */
    data class RecordingInfo(val segments: Int, val bytes: Long)

    fun recordingInfo(): RecordingInfo {
        val segs = com.sensorstream.stream.RecordingExporter.segmentsIn(recordingDir)
        return RecordingInfo(segs.size, segs.sumOf { it.length() })
    }

    /** Result of an export: the files to share (recording + metadata), or an error message. */
    data class Export(val files: List<java.io.File>, val frames: Long, val error: String?)

    /** Merge the on-phone segments into one laptop-format .ssbin + .meta.json in the cache dir.
     *  Only allowed while not streaming, so every segment is complete and flushed. */
    suspend fun exportRecording(): Export = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val st = engine.state.value
        if (st.streaming || st.connecting || st.connected) return@withContext Export(emptyList(), 0, "Stop streaming first, then export.")
        val segs = com.sensorstream.stream.RecordingExporter.segmentsIn(recordingDir)
        if (segs.isEmpty()) return@withContext Export(emptyList(), 0, "Nothing recorded yet — the phone records while streaming.")
        val outDir = java.io.File(getApplication<Application>().cacheDir, "exports").apply { deleteRecursively(); mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val base = "sensorstream-${android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9-]"), "_")}-$stamp"
        val bin = java.io.File(outDir, "$base.ssbin")
        val r = com.sensorstream.stream.RecordingExporter.merge(segs, bin)
        if (r.frames == 0L) return@withContext Export(emptyList(), 0, "The on-phone recording is empty.")
        val meta = java.io.File(outDir, "$base.meta.json")
        meta.writeText(
            org.json.JSONObject()
                .put("model", android.os.Build.MODEL)
                .put("android", android.os.Build.VERSION.RELEASE)
                .put("app_version", com.sensorstream.BuildConfig.VERSION_NAME)
                .put("device_id", st.deviceId)
                .put("source", "phone-export")
                .put("sensors", engine.catalogJson())
                .toString(2)
        )
        Export(listOf(bin, meta), r.frames, null)
    }

    private companion object {
        const val KEY_HOST = "target_host"
        const val KEY_PORT = "target_port"
        const val KEY_SELECTION = "selection_v1"
        const val KEY_FILTERS = "filters_v1"
    }

    // Streaming is owned by StreamingService (survives recreation); only stop discovery here.
    override fun onCleared() {
        engine.onFilters = null
        discovery.stop()
        previewListeners.values.forEach { sensorManager.unregisterListener(it) }
        previewListeners.clear()
        previewThread.quitSafely()
    }
}
