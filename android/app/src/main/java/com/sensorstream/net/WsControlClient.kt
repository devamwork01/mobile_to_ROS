package com.sensorstream.net

import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Control-channel client (WebSocket JSON) to `ws://host:port/phone`.
 *
 * Sends `hello` (device info + sensor catalog) on open, learns `device_id` +
 * telemetry `udp_port` from `hello_ack`, and measures RTT from
 * `heartbeat`/`heartbeat_ack`. App-level heartbeat is used (not WS ping) so the
 * round trip is measured against our own protocol.
 */
class WsControlClient {

    interface Listener {
        fun onConnected()
        fun onHelloAck(deviceId: Int, udpPort: Int)
        fun onConfigure(msg: JSONObject)
        fun onResend(msg: JSONObject)
        fun onRtt(ms: Float)
        fun onClosed(reason: String?)
        fun onFailure(t: Throwable)
    }

    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private val pendingPings = ConcurrentHashMap<Int, Long>()

    fun connect(host: String, port: Int, hello: JSONObject, listener: Listener) {
        close()
        val http = OkHttpClient.Builder().build()
        client = http
        // OkHttp's HttpUrl only accepts http/https; it performs the WebSocket
        // upgrade itself, so use http:// (not ws://).
        val request = Request.Builder().url("http://$host:$port/phone").build()
        val helloText = hello.toString()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(helloText)
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (e: Exception) { return }
                when (msg.optString("type")) {
                    "hello_ack" -> listener.onHelloAck(msg.optInt("device_id"), msg.optInt("udp_port"))
                    "configure" -> listener.onConfigure(msg)
                    "resend" -> listener.onResend(msg)
                    "heartbeat_ack" -> {
                        val t0 = pendingPings.remove(msg.optInt("seq", -1))
                        if (t0 != null) listener.onRtt((SystemClock.elapsedRealtime() - t0).toFloat())
                    }
                    "clock_pong" -> { /* offset estimation lands in Phase 5 */ }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t)
            }
        })
    }

    fun sendHeartbeat(seq: Int) {
        pendingPings[seq] = SystemClock.elapsedRealtime()
        ws?.send(JSONObject().put("type", "heartbeat").put("seq", seq).toString())
    }

    fun sendStats(stats: JSONObject) {
        ws?.send(stats.put("type", "stats").toString())
    }

    /** Announce the currently-streaming sensor handles so the dashboard can add/remove live. */
    fun sendActive(handles: List<Int>) {
        val arr = org.json.JSONArray()
        for (h in handles) arr.put(h)
        ws?.send(JSONObject().put("type", "active").put("handles", arr).toString())
    }

    /** Serve one batch of backfill datagrams (raw wire bytes, base64-encoded) for a resend range. */
    fun sendBackfill(deviceId: Int, clientId: String, handle: Int, frames: List<ByteArray>) {
        val arr = org.json.JSONArray()
        for (f in frames) arr.put(android.util.Base64.encodeToString(f, android.util.Base64.NO_WRAP))
        ws?.send(
            JSONObject().put("type", "backfill").put("device_id", deviceId).put("client_id", clientId)
                .put("handle", handle).put("frames", arr).toString()
        )
    }

    /** Tell the laptop a requested range is no longer in the on-phone ring (permanent gap). */
    fun sendBackfillUnavailable(deviceId: Int, clientId: String, handle: Int, fromSeq: Long, toSeq: Long) {
        ws?.send(
            JSONObject().put("type", "backfill_unavailable").put("device_id", deviceId)
                .put("client_id", clientId).put("handle", handle).put("from", fromSeq).put("to", toSeq).toString()
        )
    }

    fun close() {
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        pendingPings.clear()
    }
}
