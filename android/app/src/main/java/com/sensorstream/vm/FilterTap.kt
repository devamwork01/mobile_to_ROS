package com.sensorstream.vm

import android.os.SystemClock
import com.sensorstream.core.filter.FilterStatus
import com.sensorstream.core.filter.PhoneFilterBank
import com.sensorstream.core.filter.vectorSigma
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One sample as shown on the phone: raw and (when the filter runs) filtered, first 3 axes. */
class TracePoint(val tMs: Long, val raw: FloatArray, val filtered: FloatArray?)

/** The last [FilterTap.WINDOW_MS] of a filtered sensor, with noise before/after. */
class FilterTrace(val points: List<TracePoint>, val sigmaRaw: Float, val sigmaFiltered: Float, val status: FilterStatus?)

/**
 * Runs every preview sample of the tapped sensors through the phone's filter bank (on the preview
 * sensor thread, so no sample is dropped) and publishes a snapshot for the UI at most every 100 ms.
 */
class FilterTap(private val bank: PhoneFilterBank) {
    private val buffers = HashMap<Int, ArrayDeque<TracePoint>>()
    private val _traces = MutableStateFlow<Map<Int, FilterTrace>>(emptyMap())
    val traces: StateFlow<Map<Int, FilterTrace>> = _traces.asStateFlow()
    @Volatile var handles: Set<Int> = emptySet()
        set(value) {
            field = value
            synchronized(buffers) { buffers.keys.retainAll(value) }
            publish()
        }
    private var lastPublishMs = 0L

    fun onSample(handle: Int, key: String, type: Int, tNs: Long, values: FloatArray) {
        if (handle !in handles) return
        val filtered = bank.process(handle, key, type, tNs, values)
        val now = SystemClock.uptimeMillis()
        synchronized(buffers) {
            val dq = buffers.getOrPut(handle) { ArrayDeque() }
            dq.addLast(TracePoint(now, values.copyOf(minOf(values.size, 3)), filtered))
            while (dq.isNotEmpty() && dq.first().tMs < now - WINDOW_MS) dq.removeFirst()
        }
        if (now - lastPublishMs >= 100) { lastPublishMs = now; publish() }
    }

    private fun publish() {
        val snapshot = synchronized(buffers) {
            handles.associateWith { h ->
                val pts = buffers[h]?.toList() ?: emptyList()
                FilterTrace(pts, vectorSigma(pts.map { it.raw }), vectorSigma(pts.map { it.filtered }), bank.status(h))
            }
        }
        _traces.value = snapshot
    }

    companion object { const val WINDOW_MS = 10_000L }
}
