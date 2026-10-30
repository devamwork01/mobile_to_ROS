package com.sensorstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionErrorsTest {
    private fun say(raw: String) = ConnectionErrors.friendly(raw, "QA-fgs", "8091")

    @Test fun `connect timeout says the server can't be reached`() {
        val m = say("failed to connect to /192.168.31.216 (port 8091) from /192.168.31.134 (port 35730) after 5000ms")
        assertEquals("Can't reach QA-fgs. Check the server is running and this phone is on the same Wi-Fi.", m)
    }

    @Test fun `refused means nothing listens on the port`() {
        val m = say("failed to connect to /192.168.31.216 (port 8091) from /192.168.31.134 (port 4) after 12ms: isConnected failed: ECONNREFUSED (Connection refused)")
        assertTrue(m, m.startsWith("QA-fgs answered, but no SensorStream server is listening on port 8091"))
    }

    @Test fun `no route and no network`() {
        assertTrue(say("isConnected failed: EHOSTUNREACH (No route to host)").startsWith("Can't reach QA-fgs"))
        assertEquals("This phone isn't connected to a network.", say("connect failed: ENETUNREACH (Network is unreachable)"))
    }

    @Test fun `unknown host name`() {
        assertEquals("Can't find QA-fgs. Check the server address.",
            say("Unable to resolve host \"lab-pc\": No address associated with hostname"))
    }

    @Test fun `wrong service on the port`() {
        assertTrue(say("Expected HTTP 101 response but was '404 Not Found'").startsWith("Something other than SensorStream"))
    }

    @Test fun `dropped link`() {
        assertEquals("Lost the connection to QA-fgs. Reconnecting when it's back.", say("Connection reset"))
        assertEquals("Lost the connection to QA-fgs. Reconnecting when it's back.", say("timeout"))
    }

    @Test fun `messages we wrote ourselves pass through`() {
        assertEquals("No sensors selected", say("No sensors selected"))
    }
}
