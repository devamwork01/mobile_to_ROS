package com.sensorstream.core.filter

/** Sample rate from sensor timestamps; same rules as the laptop's FilterBank (filters.py _Rate). */
class RateEstimator {
    private var first: Long? = null
    private var last: Long? = null
    private var ewma: Double? = null
    private var n = 0
    private var longRun = 0

    fun observe(tNs: Long) {
        val l = last
        if (l != null && tNs < l - 1_000_000_000L) {   // sensor clock went back: start afresh
            first = tNs; last = tNs; ewma = null; n = 1; longRun = 0
            return
        }
        if (l != null && tNs > l) {
            val dt = (tNs - l) / 1e9
            val e = ewma
            if (e != null && dt > 5.0 * e) {
                longRun++
                if (longRun < 3) { last = tNs; n++; return }
                ewma = dt
            } else longRun = 0
            ewma = if (ewma == null) dt else 0.98 * ewma!! + 0.02 * dt
        }
        if (first == null) first = tNs
        last = maxOf(tNs, last ?: tNs)
        n++
    }

    fun fs(): Double? {
        val e = ewma ?: return null
        val f = first ?: return null
        val l = last ?: return null
        if (n < 10 || (l - f) / 1e9 < 1.0) return null
        return 1.0 / e
    }
}
