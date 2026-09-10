package com.sensorstream.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Fire-and-forget UDP sender for the telemetry channel. [connect] resolves the
 * destination (do this off the main thread) and [send] pushes one datagram.
 * There are no acknowledgements — the telemetry path never blocks on the network.
 */
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
