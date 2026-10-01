package com.sensorstream.core.filter

sealed interface FilterStatus {
    data object WaitingForRate : FilterStatus
    data object Running : FilterStatus
    data class Invalid(val message: String) : FilterStatus
}

/**
 * The phone's filters, fed from the local sensor preview (its own samples, at each sensor's
 * configured period). Configs come from the laptop (keyed "<type>:<catalog name>"); chains are
 * per handle, built once the rate is known and rebuilt only on a sustained rate change.
 * Synchronized: configs are replaced from the network thread while the sensor thread filters.
 */
class PhoneFilterBank {
    private var configs: Map<String, FilterConfig> = emptyMap()
    private val rates = HashMap<Int, RateEstimator>()
    private val chains = HashMap<Int, FilterChain>()
    private val drift = HashMap<Int, Int>()
    private val statuses = HashMap<Int, FilterStatus>()

    @Synchronized fun setConfigs(m: Map<String, FilterConfig>) {
        configs = m
        chains.clear(); drift.clear(); statuses.clear()
    }

    /** Drop [handle]'s filter state (kept: its rate) so it re-primes when samples resume. */
    @Synchronized fun reset(handle: Int) {
        chains.remove(handle); drift.remove(handle); statuses.remove(handle)
    }

    @Synchronized fun configFor(key: String): FilterConfig? = configs[key]
    @Synchronized fun status(handle: Int): FilterStatus? = statuses[handle]
    @Synchronized fun fs(handle: Int): Double? = rates[handle]?.fs()

    @Synchronized fun process(handle: Int, key: String, type: Int, tNs: Long, values: FloatArray): FloatArray? {
        rates.getOrPut(handle) { RateEstimator() }.observe(tNs)
        val cfg = configs[key]
        if (cfg == null || type in Biquad.EXCLUDED_TYPES) {
            statuses.remove(handle); chains.remove(handle)
            return null
        }
        val fs = rates[handle]!!.fs()
        if (fs == null) { statuses[handle] = FilterStatus.WaitingForRate; return null }
        val nAxes = minOf(values.size, 3)
        var ch = chains[handle]
        if (ch != null && ch.nAxes == nAxes && ch.config == cfg) {
            if (Math.abs(fs - ch.fs) / ch.fs > 0.05) {
                val d = (drift[handle] ?: 0) + 1
                drift[handle] = d
                if (d >= 200) ch = null
            } else drift[handle] = 0
        } else ch = null
        if (ch == null) {
            val err = Biquad.validate(cfg, fs)
            if (err != null) { statuses[handle] = FilterStatus.Invalid(err); chains.remove(handle); return null }
            ch = FilterChain(cfg, fs, nAxes)
            chains[handle] = ch
            drift[handle] = 0
        }
        statuses[handle] = FilterStatus.Running
        return ch.process(values.copyOf(nAxes))
    }
}
