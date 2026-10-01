package com.sensorstream.core.filter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The laptop's filter settings as last received, persisted via [save]. Owned by the stream engine
 * (which outlives the UI while the service streams), so a push is never lost when no screen is open.
 */
class FilterSettings(initial: String?, private val save: (String) -> Unit) {
    private val _configs = MutableStateFlow(FilterConfigCodec.decode(initial))
    val configs: StateFlow<Map<String, FilterConfig>> = _configs.asStateFlow()

    /** Applies a `{"type":"filters","configs":{...}}` message; false (and no change) without `configs`. */
    fun apply(msg: JSONObject): Boolean {
        val c = msg.optJSONObject("configs") ?: return false
        val m = FilterConfigCodec.parseConfigs(c)
        _configs.value = m
        save(FilterConfigCodec.encode(m))
        return true
    }
}
