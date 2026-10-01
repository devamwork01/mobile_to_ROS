package com.sensorstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerListTest {
    private fun beacon(name: String? = "lab-pc", id: String? = "s1", kind: String? = "laptop") =
        ServerList.Announce(name = name, kind = kind, os = "Windows", session = id, probePort = 8081)

    @Test fun `beacons from two servers give two rows, restart keeps one row`() {
        val l = ServerList()
        l.onBeacon("10.0.0.2", 8081, beacon(), 0)
        l.onBeacon("10.0.0.3", 8081, beacon(name = "pi", id = "s2", kind = "raspberry_pi"), 0)
        l.onBeacon("10.0.0.2", 8081, beacon(id = "s9"), 1000) // restarted: new session, same address
        val rows = l.rows(1000, lastUsed = null)
        assertEquals(2, rows.size)
        assertEquals(ServerKind.RASPBERRY_PI, rows.first { it.host == "10.0.0.3" }.kind)
    }

    @Test fun `bars use the worse of round trip and announcements received`() {
        val l = ServerList()
        for (s in 0 until 10) l.onBeacon("10.0.0.2", 8081, beacon(), s * 1000L)
        repeat(10) { l.onProbeReply("10.0.0.2", 8081, 6f) }
        assertEquals(4, l.rows(9500, null).single().bars)
        assertEquals(6f, l.rows(9500, null).single().rttMs!!, 1e-3f)
        repeat(10) { l.onProbeReply("10.0.0.2", 8081, 45f) }
        assertEquals(2, l.rows(9500, null).single().bars)

        val lossy = ServerList()
        for (s in 0 until 10 step 2) lossy.onBeacon("10.0.0.4", 8081, beacon(), s * 1000L) // 5 of 10
        repeat(10) { lossy.onProbeReply("10.0.0.4", 8081, 5f) }
        assertEquals(2, lossy.rows(9500, null).single().bars)
    }

    @Test fun `older server without identity still listed, bars from announcements only`() {
        val l = ServerList()
        for (s in 0 until 10) l.onBeacon("10.0.0.5", 8081, ServerList.Announce(null, null, null, null, null), s * 1000L)
        val r = l.rows(9500, null).single()
        assertEquals("SensorStream server", r.name)
        assertEquals(ServerKind.UNKNOWN, r.kind)
        assertNull(r.rttMs)
        assertEquals(4, r.bars)
        assertNull(l.probeTargets(9500).firstOrNull())
    }

    @Test fun `quiet server greys out then drops`() {
        val l = ServerList()
        l.onBeacon("10.0.0.2", 8081, beacon(), 0)
        assertFalse(l.rows(4000, null).single().stale)
        assertTrue(l.rows(6000, null).single().stale)
        assertTrue(l.rows(16000, null).isEmpty())
    }

    @Test fun `last used first, then signal, then name`() {
        val l = ServerList()
        for (s in 0 until 10) {
            l.onBeacon("10.0.0.2", 8081, beacon(name = "b-strong", id = "a"), s * 1000L)
            l.onBeacon("10.0.0.3", 8081, beacon(name = "a-weak", id = "b"), if (s % 2 == 0) s * 1000L else 0)
            l.onBeacon("10.0.0.4", 8081, beacon(name = "c-used", id = "c"), if (s % 3 == 0) s * 1000L else 0)
        }
        val names = l.rows(9500, lastUsed = "10.0.0.4:8081").map { it.name }
        assertEquals(listOf("c-used", "b-strong", "a-weak"), names)
        assertTrue(l.rows(9500, lastUsed = "10.0.0.4:8081").first().lastUsed)
    }

    @Test fun `mdns hit is listed until a beacon fills in details`() {
        val l = ServerList()
        l.onMdns("10.0.0.7", 8081, "lab-pc-1a2b", 0)
        assertEquals("lab-pc-1a2b", l.rows(100, null).single().name)
        l.onBeacon("10.0.0.7", 8081, beacon(name = "lab-pc"), 200)
        assertEquals("lab-pc", l.rows(300, null).single().name)
    }

    @Test fun `loopback is ignored`() {
        val l = ServerList()
        l.onBeacon("127.0.0.1", 8081, beacon(), 0)
        l.onMdns("localhost", 8081, "x", 0)
        assertTrue(l.rows(0, null).isEmpty())
    }

    @Test fun `beacon json parses, old beacons and other services handled`() {
        val v2 = ServerList.announceOf(org.json.JSONObject(
            """{"service":"sensorstream","v":2,"control_port":8081,"udp_port":5005,"name":"lab-pc","kind":"laptop","os":"Windows","id":"ab12cd34","probe_port":8081}"""))!!
        assertEquals(ServerList.Announce("lab-pc", "laptop", "Windows", "ab12cd34", 8081), v2)
        val v1 = ServerList.announceOf(org.json.JSONObject("""{"service":"sensorstream","v":1,"control_port":8081,"udp_port":5005}"""))!!
        assertEquals(ServerList.Announce(null, null, null, null, null), v1)
        assertNull(ServerList.announceOf(org.json.JSONObject("""{"service":"other"}""")))
    }
}
