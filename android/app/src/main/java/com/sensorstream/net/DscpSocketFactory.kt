package com.sensorstream.net

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Wraps the default [SocketFactory] to tag every socket it creates with a DSCP/ToS traffic class.
 * A best-effort QoS hint (honored only where the network maps DSCP -> WMM); it sets the IP header's
 * ToS field and changes nothing about the bytes sent. Used for the control WebSocket so it rides a
 * high — but below telemetry — priority. See [DSCP_EF] for the telemetry class.
 */
class DscpSocketFactory(private val trafficClass: Int) : SocketFactory() {
    private val delegate: SocketFactory = getDefault()

    private fun tag(s: Socket): Socket {
        try { s.trafficClass = trafficClass } catch (_: Exception) { /* best effort */ }
        return s
    }

    override fun createSocket(): Socket = tag(delegate.createSocket())
    override fun createSocket(host: String?, port: Int): Socket = tag(delegate.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        tag(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket = tag(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        tag(delegate.createSocket(address, port, localAddress, localPort))
}

/** DSCP AF41 (34) shifted into the 8-bit IP ToS field (34 << 2 = 0x88) — "video" WMM class. */
const val DSCP_AF41 = 0x88
