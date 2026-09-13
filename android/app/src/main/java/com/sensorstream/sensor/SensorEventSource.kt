package com.sensorstream.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.sensorstream.core.SensorSample
import java.util.concurrent.ConcurrentHashMap

/**
 * Registers sensor listeners on a dedicated [HandlerThread] so callbacks never
 * run on the UI thread, and emits [SensorSample]s via [onSample]. Per-sensor
 * sequence numbers are assigned here for downstream loss detection.
 */
class SensorEventSource(private val sm: SensorManager) : SensorEventListener {

    /** One sensor to register: [periodUs] is the requested sampling period;
     *  [maxReportLatencyUs] = 0 means no batching (lowest latency). */
    data class Reg(
        val sensor: Sensor,
        val handle: Int,
        val periodUs: Int,
        val maxReportLatencyUs: Int = 0,
    )

    @Volatile
    var onSample: ((SensorSample) -> Unit)? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val handleByType = ConcurrentHashMap<Int, Int>()
    private val seqByHandle = ConcurrentHashMap<Int, Long>()
    private val accuracyByHandle = ConcurrentHashMap<Int, Int>()
    private val activeRegs = ConcurrentHashMap<Int, Reg>() // handle -> currently-registered Reg

    fun start(regs: List<Reg>) {
        stop()
        val t = HandlerThread("sensor-acq").apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        for (r in regs) registerOne(r, h)
    }

    /**
     * Live reconfigure the registered set without tearing down the acquisition
     * thread: registers newly-enabled sensors, unregisters removed ones, and
     * re-registers any whose requested period changed. Unchanged sensors keep
     * streaming (and their sequence counters) uninterrupted. No-op if not started.
     */
    fun updateRegs(newRegs: List<Reg>) {
        val h = handler ?: return
        val newByHandle = newRegs.associateBy { it.handle }
        // Remove sensors no longer wanted, and drop ones whose rate changed (re-added below).
        for ((handle, reg) in activeRegs.toList()) {
            val nr = newByHandle[handle]
            val rateChanged = nr != null && (nr.periodUs != reg.periodUs || nr.maxReportLatencyUs != reg.maxReportLatencyUs)
            if (nr == null || rateChanged) {
                sm.unregisterListener(this, reg.sensor)
                activeRegs.remove(handle)
                if (nr == null) {
                    handleByType.remove(reg.sensor.type)
                    seqByHandle.remove(handle)
                    accuracyByHandle.remove(handle)
                }
            }
        }
        // Add new sensors (and re-add rate-changed ones), preserving seq across a rate change.
        for (r in newRegs) if (!activeRegs.containsKey(r.handle)) registerOne(r, h)
    }

    private fun registerOne(r: Reg, h: Handler) {
        handleByType[r.sensor.type] = r.handle
        seqByHandle.putIfAbsent(r.handle, 0L)
        accuracyByHandle.putIfAbsent(r.handle, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        sm.registerListener(this, r.sensor, r.periodUs, r.maxReportLatencyUs, h)
        activeRegs[r.handle] = r
    }

    fun stop() {
        sm.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        handler = null
        handleByType.clear()
        seqByHandle.clear()
        accuracyByHandle.clear()
        activeRegs.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val handle = handleByType[event.sensor.type] ?: return
        val n = event.values.size
        val vals = FloatArray(n)
        System.arraycopy(event.values, 0, vals, 0, n)
        val seq = seqByHandle.getOrDefault(handle, 0L)
        seqByHandle[handle] = seq + 1
        onSample?.invoke(
            SensorSample(
                sensorType = event.sensor.type,
                handle = handle,
                seq = seq,
                timestampNs = event.timestamp,
                accuracy = accuracyByHandle.getOrDefault(handle, SensorManager.SENSOR_STATUS_ACCURACY_HIGH),
                values = vals,
                valueCount = n,
                tAcquireNs = SystemClock.elapsedRealtimeNanos(),
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        val handle = handleByType[sensor.type] ?: return
        accuracyByHandle[handle] = accuracy
    }
}
