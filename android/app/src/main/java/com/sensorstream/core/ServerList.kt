package com.sensorstream.core

enum class ServerKind { LAPTOP, DESKTOP, RASPBERRY_PI, UNKNOWN;
    companion object {
        fun of(s: String?): ServerKind = when (s) {
            "laptop" -> LAPTOP
            "desktop" -> DESKTOP
            "raspberry_pi" -> RASPBERRY_PI
            else -> UNKNOWN
        }
    }
}

/**
 * Every SensorStream server heard on the network (UDP beacons + mDNS), with a link quality per
 * server measured from the phone: the median round trip of our pings and the share of the
 * once-a-second announcements that arrive. Pure (clock passed in) so it unit-tests on the JVM;
 * synchronized because beacons, mDNS and ping replies arrive on different threads.
 */
class ServerList {
    /** What a beacon says about the server; all null for servers older than beacon v2. */
    data class Announce(val name: String?, val kind: String?, val os: String?, val session: String?, val probePort: Int?)

    data class Row(
        val key: String,
        val host: String,
        val port: Int,
        val name: String,
        val kind: ServerKind,
        val os: String?,
        /** Median round trip of recent pings, null when the server doesn't answer them. */
        val rttMs: Float?,
        /** 1..4 */
        val bars: Int,
        /** Not heard for a few seconds (shown greyed out). */
        val stale: Boolean,
        val lastUsed: Boolean,
    )

    /** A server to ping: send to [host]:[probePort], report the reply for [host]:[port]. */
    data class ProbeTarget(val host: String, val port: Int, val probePort: Int)

    private class Entry(val host: String, val port: Int) {
        var name: String? = null
        var mdnsName: String? = null
        var kind: ServerKind = ServerKind.UNKNOWN
        var os: String? = null
        var session: String? = null
        var probePort: Int? = null
        var firstBeaconMs: Long = -1
        var lastSeenMs: Long = 0
        val beaconSeconds = HashSet<Long>()
        val rtts = ArrayDeque<Float>()
    }

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized fun onBeacon(host: String, port: Int, a: Announce, nowMs: Long) {
        val e = entry(host, port) ?: return
        if (a.session != null && e.session != null && a.session != e.session) e.rtts.clear() // restarted
        a.name?.takeIf { it.isNotBlank() }?.let { e.name = it }
        if (a.kind != null) e.kind = ServerKind.of(a.kind)
        if (a.os != null) e.os = a.os
        if (a.session != null) e.session = a.session
        e.probePort = a.probePort
        if (e.firstBeaconMs < 0 || nowMs < e.firstBeaconMs) e.firstBeaconMs = nowMs
        e.beaconSeconds.add(nowMs / 1000)
        e.lastSeenMs = maxOf(e.lastSeenMs, nowMs)
    }

    @Synchronized fun onMdns(host: String, port: Int, instanceName: String?, nowMs: Long) {
        val e = entry(host, port) ?: return
        if (!instanceName.isNullOrBlank()) e.mdnsName = instanceName
        // Current servers answer pings on their control port; an older one simply won't reply.
        if (e.firstBeaconMs < 0 && e.probePort == null) e.probePort = port
        e.lastSeenMs = maxOf(e.lastSeenMs, nowMs)
    }

    @Synchronized fun onProbeReply(host: String, port: Int, rttMs: Float) {
        val e = entries[key(host, port)] ?: return
        e.rtts.addLast(rttMs)
        while (e.rtts.size > WINDOW) e.rtts.removeFirst()
    }

    @Synchronized fun probeTargets(nowMs: Long): List<ProbeTarget> {
        prune(nowMs)
        return entries.values.filter { it.probePort != null && nowMs - it.lastSeenMs <= STALE_MS }
            .map { ProbeTarget(it.host, it.port, it.probePort!!) }
    }

    @Synchronized fun rows(nowMs: Long, lastUsed: String?): List<Row> {
        prune(nowMs)
        return entries.values.map { e ->
            val rtt = median(e.rtts)
            val k = key(e.host, e.port)
            Row(
                key = k, host = e.host, port = e.port,
                name = e.name ?: e.mdnsName ?: "SensorStream server",
                kind = e.kind, os = e.os, rttMs = rtt,
                bars = bars(rtt, arrival(e, nowMs)),
                stale = nowMs - e.lastSeenMs > STALE_MS,
                lastUsed = k == lastUsed,
            )
        }.sortedWith(compareByDescending<Row> { it.lastUsed }.thenBy { it.stale }.thenByDescending { it.bars }.thenBy { it.name.lowercase() })
    }

    private fun entry(host: String, port: Int): Entry? {
        val h = host.lowercase()
        if (host.isBlank() || h == "localhost" || host.startsWith("127.") || host == "::1" || host == "0.0.0.0") return null
        if (port !in 1..65535) return null
        return entries.getOrPut(key(host, port)) { Entry(host, port) }
    }

    private fun prune(nowMs: Long) {
        entries.values.removeAll { nowMs - it.lastSeenMs > DROP_MS }
        for (e in entries.values) e.beaconSeconds.removeAll { it < nowMs / 1000 - (WINDOW - 1) }
    }

    /** Share of the last (up to) 10 seconds with a beacon; null if this server never beaconed. */
    private fun arrival(e: Entry, nowMs: Long): Float? {
        if (e.firstBeaconMs < 0) return null
        val expected = minOf(WINDOW.toLong(), nowMs / 1000 - e.firstBeaconMs / 1000 + 1).coerceAtLeast(1)
        return (e.beaconSeconds.size.toFloat() / expected).coerceAtMost(1f)
    }

    companion object {
        const val WINDOW = 10
        const val STALE_MS = 5_000L
        const val DROP_MS = 15_000L

        fun key(host: String, port: Int) = "$host:$port"

        /** A beacon's identity fields; null if it isn't a SensorStream beacon. Older (v1) beacons give all nulls. */
        fun announceOf(m: org.json.JSONObject): Announce? {
            if (m.optString("service") != "sensorstream") return null
            fun str(k: String) = m.optString(k, "").takeIf { it.isNotBlank() }
            return Announce(
                name = str("name")?.take(40), kind = str("kind"), os = str("os"), session = str("id"),
                probePort = m.optInt("probe_port", 0).takeIf { it in 1..65535 },
            )
        }

        fun bars(rttMs: Float?, arrival: Float?): Int {
            val byRtt = when {
                rttMs == null -> 4
                rttMs < 10f -> 4
                rttMs < 30f -> 3
                rttMs < 80f -> 2
                else -> 1
            }
            val byArrival = when {
                arrival == null -> if (rttMs == null) 1 else 4
                arrival >= 0.9f -> 4
                arrival >= 0.7f -> 3
                arrival >= 0.4f -> 2
                else -> 1
            }
            return minOf(byRtt, byArrival)
        }

        private fun median(d: Collection<Float>): Float? {
            if (d.isEmpty()) return null
            val s = d.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
        }
    }
}
