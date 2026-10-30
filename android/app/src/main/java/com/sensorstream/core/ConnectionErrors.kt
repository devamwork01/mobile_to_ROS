package com.sensorstream.core

/**
 * Plain-language text for a connection error. The engine keeps the raw exception message (shown
 * as-is on the Diagnostics screen); the Home and Connection screens show this instead. Pure string
 * matching on the messages Android's sockets and OkHttp produce, so it unit-tests on the JVM.
 */
object ConnectionErrors {
    /** [server] is the server's name, or host:port when it has none; [port] is the control port. */
    fun friendly(raw: String, server: String, port: String): String {
        val m = raw.lowercase()
        return when {
            "econnrefused" in m || "connection refused" in m ->
                "$server answered, but no SensorStream server is listening on port $port. Check the server is running and the port matches."
            "enetunreach" in m || "network is unreachable" in m -> "This phone isn't connected to a network."
            "unable to resolve host" in m || "no address associated" in m -> "Can't find $server. Check the server address."
            "expected http 101" in m -> "Something other than SensorStream answered on port $port. Check the port."
            "failed to connect" in m || "ehostunreach" in m || "no route to host" in m ->
                "Can't reach $server. Check the server is running and this phone is on the same Wi-Fi."
            "reset" in m || "timeout" in m || "timed out" in m || "closed" in m || "eof" in m || "broken pipe" in m ->
                "Lost the connection to $server. Reconnecting when it's back."
            else -> raw
        }
    }
}
