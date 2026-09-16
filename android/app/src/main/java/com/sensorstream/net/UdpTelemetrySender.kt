package com.sensorstream.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Fire-and-forget UDP sender for the telemetry channel. [connect] resolves the
 * destination (do this off the main thread) and [send] pushes one datagram.
 * There are no acknowledgements — the telemetry path never blocks on the network.
 */
/** DSCP Expedited Forwarding (46) shifted into the 8-bit IP ToS field (46 << 2 = 0xB8). */
const val DSCP_EF = 0xB8

class UdpTelemetrySender {

    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var port: Int = 0

    @Volatile var packetsSent: Long = 0L; private set
    @Volatile var bytesSent: Long = 0L; private set

    fun connect(host: String, port: Int) {
        close()
        address = InetAddress.getByName(host)
        this.port = port
        socket = DatagramSocket().apply {
            try { sendBufferSize = 1 shl 20 } catch (_: Exception) { /* best effort */ }
            // Best-effort QoS: tag the high-rate telemetry as Expedited Forwarding (DSCP 46 ->
            // ToS 0xB8) so Wi-Fi maps it to the top WMM access category on access points that
            // honor DSCP. This only sets the IP header's ToS/DSCP field — the datagram payload
            // and wire format are unchanged. Helps under contention; never a guarantee.
            try { trafficClass = DSCP_EF } catch (_: Exception) { /* best effort */ }
        }
        packetsSent = 0L
        bytesSent = 0L
    }

    fun send(bytes: ByteArray, length: Int = bytes.size) {
        val s = socket ?: return
        val a = address ?: return
        s.send(DatagramPacket(bytes, length, a, port))
        packetsSent++
        bytesSent += length
    }

    fun close() {
        socket?.close()
        socket = null
        address = null
    }
}
