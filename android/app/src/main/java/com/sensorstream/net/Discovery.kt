package com.sensorstream.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Finds the laptop on the LAN and returns the first hit (host + control port),
 * via two independent paths so a filtered network still works:
 *   - mDNS `_sensorstream._tcp` (NsdManager)
 *   - a UDP broadcast beacon on [beaconPort] (the laptop broadcasts every second)
 *
 * A Wi-Fi MulticastLock is held while listening (needed for multicast/broadcast
 * reception on many chipsets). Call [stop] when done.
 */
class Discovery(context: Context, private val beaconPort: Int = 5006) {

    fun interface Listener {
        fun onFound(host: String, controlPort: Int)
    }

    private val appCtx = context.applicationContext
    private val nsd = appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val active = AtomicBoolean(false)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile private var listener: Listener? = null

    fun start(listener: Listener) {
        if (!active.compareAndSet(false, true)) return
        this.listener = listener
        multicastLock = wifi.createMulticastLock("sensorstream-disc").apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }
        startNsd()
        startBeaconListener()
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        listener = null
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
        // the beacon thread exits on its own via active flag + socket timeout
    }

    private fun emit(host: String, port: Int) {
        val l = listener
        if (port in 1..65535 && l != null && active.get()) l.onFound(host, port)
    }

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
                    val host = serviceInfo.host?.hostAddress ?: return
                    val txt = serviceInfo.attributes?.get("control_port")
                    val port = txt?.let { String(it).trim().toIntOrNull() } ?: serviceInfo.port
                    emit(host, port)
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
                    if (msg.optString("service") == "sensorstream") {
                        val host = pkt.address?.hostAddress ?: continue
                        emit(host, msg.optInt("control_port", 0))
                    }
                }
            } catch (_: Exception) {
                // port busy / no network — mDNS path still active
            } finally {
                runCatching { sock?.close() }
            }
        }
    }
}
