package com.sensorstream.core

/**
 * Persists the user's sensor selection (which sensors are enabled + each one's requested period)
 * as a compact string. Each entry also records the sensor TYPE: handles are indices into the
 * device's sensor list, so if that list ever changes (OS update, different device restoring a
 * backup) an entry whose type no longer matches is dropped instead of silently selecting a
 * different sensor.
 *
 * Format: `handle:type:periodUs:enabled` joined by `;`, e.g. `0:1:10000:1;3:4:5000:0`.
 */
object SelectionCodec {

    data class Decoded(val enabled: Set<Int>, val periods: Map<Int, Int>)

    fun encode(enabled: Set<Int>, periods: Map<Int, Int>, typeOf: (Int) -> Int?): String =
        (enabled + periods.keys).sorted().mapNotNull { h ->
            val type = typeOf(h) ?: return@mapNotNull null
            val period = periods[h] ?: return@mapNotNull null
            "$h:$type:$period:${if (h in enabled) 1 else 0}"
        }.joinToString(";")

    /** [catalogTypes] maps handle -> sensor type for the current device. */
    fun decode(text: String?, catalogTypes: Map<Int, Int>): Decoded? {
        if (text.isNullOrBlank()) return null
        val enabled = mutableSetOf<Int>()
        val periods = mutableMapOf<Int, Int>()
        for (entry in text.split(';')) {
            val f = entry.split(':')
            if (f.size != 4) continue
            val h = f[0].toIntOrNull() ?: continue
            val type = f[1].toIntOrNull() ?: continue
            val period = f[2].toIntOrNull() ?: continue
            if (catalogTypes[h] != type || period < 0) continue // sensor list changed: skip
            periods[h] = period
            if (f[3] == "1") enabled += h
        }
        return Decoded(enabled, periods)
    }
}

/** Validates a laptop address before connecting, returning a user-facing error or null if OK. */
object TargetValidator {
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val HOSTNAME = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*\.?$""")

    fun validate(host: String, port: String): String? {
        val h = host.trim()
        if (h.isEmpty()) return "Pick a server under Servers, or enter its IP address."
        val lower = h.lowercase()
        if (lower == "localhost" || h.startsWith("127.") || h == "0.0.0.0" || h == "::1") {
            return "That address points at the phone itself — use the server's LAN IP."
        }
        val m = IPV4.matchEntire(h)
        if (m != null) {
            if (m.groupValues.drop(1).any { it.toInt() > 255 }) return "“$h” isn't a valid IP address."
        } else if (h.all { it.isDigit() || it == '.' } || !HOSTNAME.matches(h)) {
            return "“$h” isn't a valid IP address or hostname."
        }
        val p = port.trim().toIntOrNull()
        if (p == null || p !in 1..65535) return "Port must be a number from 1 to 65535."
        return null
    }
}
