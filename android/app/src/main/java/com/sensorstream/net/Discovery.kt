package com.sensorstream.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.sensorstream.core.ServerList
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Scans the LAN for SensorStream servers while started and records them in a [ServerList]:
 *   - mDNS `_sensorstream._tcp` (NsdManager)
 *   - the UDP broadcast beacon on [beaconPort] (each server broadcasts every second, with its
 *     name / kind / OS since beacon v2)
 *   - a UDP ping to each server once a second (answered on its control port number), timing the
 *     round trip so the list can show the link quality to every server.
 *
 * A Wi-Fi MulticastLock is held while scanning (needed for multicast/broadcast reception on many
 * chipsets). Call [stop] when the list is no longer shown.
 */
class Discovery(context: Context, private val beaconPort: Int = 5006) {

    private val appCtx = context.applicationContext
    private val nsd = appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val active = AtomicBoolean(false)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile private var servers: ServerList? = null

    fun start(list: ServerList) {
        if (!active.compareAndSet(false, true)) return
        servers = list
        multicastLock = wifi.createMulticastLock("sensorstream-disc").apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }
        startNsd()
        startBeaconListener()
        startProber()
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
        // the beacon and ping threads exit on their own via the active flag + socket timeouts
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun startNsd() {
        val dl = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("_sensorstream")) resolve(serviceInfo)
            }
        }
        discoveryListener = dl
        runCatching { nsd.discoverServices("_sensorstream._tcp.", NsdManager.PROTOCOL_DNS_SD, dl) }
    }

    @Suppress("DEPRECATION") // resolveService/host are fine for minSdk 24
    private fun resolve(info: NsdServiceInfo) {
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (!active.get()) return
                    val host = serviceInfo.host?.hostAddress ?: return
                    val txt = serviceInfo.attributes?.get("control_port")
                    val port = txt?.let { String(it).trim().toIntOrNull() } ?: serviceInfo.port
                    servers?.onMdns(host, port, serviceInfo.serviceName, now())
                }
            })
        }
    }

    private fun startBeaconListener() {
        thread(isDaemon = true, name = "disc-beacon") {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1500
                    bind(InetSocketAddress(beaconPort))
                }
                val buf = ByteArray(2048)
                while (active.get()) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (e: Exception) {
                        if (!active.get()) break else continue // socket timeout -> re-check active
                    }
                    val msg = runCatching { JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                    val a = ServerList.announceOf(msg) ?: continue
                    val host = pkt.address?.hostAddress ?: continue
                    servers?.onBeacon(host, msg.optInt("control_port", 0), a, now())
                }
            } catch (_: Exception) {
                // port busy / no network — mDNS path still active
            } finally {
                runCatching { sock?.close() }
            }
        }
    }

    /** One socket: send a ping to every known server each second, match replies by sequence. */
    private fun startProber() {
        thread(isDaemon = true, name = "disc-ping") {
            var sock: DatagramSocket? = null
            val sent = ConcurrentHashMap<Int, Triple<String, Int, Long>>() // seq -> (host, port, t0)
            try {
                sock = DatagramSocket().apply { soTimeout = 250 }
                val buf = ByteArray(512)
                var seq = 0
                var nextSend = 0L
                while (active.get()) {
                    val t = now()
                    if (t >= nextSend) {
                        nextSend = t + 1000
                        sent.entries.removeAll { t - it.value.third > 3000 } // unanswered
                        for (target in servers?.probeTargets(t).orEmpty()) {
                            val s = ++seq
                            val payload = JSONObject().put("service", "sensorstream").put("probe", s).toString().toByteArray()
                            runCatching {
                                val addr = InetAddress.getByName(target.host)
                                sent[s] = Triple(target.host, target.port, now())
                                sock.send(DatagramPacket(payload, payload.size, addr, target.probePort))
                            }
                        }
                    }
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (_: Exception) {
                        continue // timeout -> maybe time to send again
                    }
                    val m = runCatching { JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                    val s = m.optInt("probe", -1)
                    val (host, port, t0) = sent.remove(s) ?: continue
                    servers?.onProbeReply(host, port, (now() - t0).toFloat())
                }
            } catch (_: Exception) {
                // no network: the list just shows bars from beacons
            } finally {
                runCatching { sock?.close() }
            }
        }
    }
}
