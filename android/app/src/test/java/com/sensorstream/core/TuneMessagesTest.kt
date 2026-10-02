package com.sensorstream.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneMessagesTest {
    @Test fun `parses a prompt`() {
        val m = JSONObject("""{"type":"tune_prompt","id":4,"reason":"new_sensor","attention":true,"lead_s":3,"capture_s":10,
            "sensors":[{"handle":4,"name":"Mag"}]}""")
        val p = TuneMessages.parsePrompt(m)!!
        assertEquals(TunePrompt(4, "new_sensor", true, 3000, 10000, listOf(TuneSensor(4, "Mag"))), p)
    }

    @Test fun `rejects prompts it cannot run`() {
        assertNull(TuneMessages.parsePrompt(JSONObject("""{"type":"filters"}""")))
        assertNull(TuneMessages.parsePrompt(JSONObject("""{"type":"tune_prompt","id":1,"sensors":[]}""")))
        assertNull(TuneMessages.parsePrompt(JSONObject("""{"type":"tune_prompt","id":1,"sensors":[{"name":"x"}, 7]}""")))
    }

    @Test fun `parses a result with nulls`() {
        val m = JSONObject("""{"type":"tune_result","id":4,"sensors":[
            {"handle":0,"name":"Acc","ok":true,"summary":"X/Y/Z LP 5 Hz","message":null},
            {"handle":2,"name":"Gyro","ok":false,"summary":null,"message":"data gap during the capture"}]}""")
        val (id, rs) = TuneMessages.parseResult(m)!!
        assertEquals(4, id)
        assertEquals(TuneSensorResult(0, "Acc", true, "X/Y/Z LP 5 Hz", null), rs[0])
        assertEquals(TuneSensorResult(2, "Gyro", false, null, "data gap during the capture"), rs[1])
        assertNull(TuneMessages.parseResult(JSONObject("""{"type":"tune_prompt"}""")))
    }

    @Test fun `builds window, cancelled and request messages`() {
        val w = TuneMessages.window(4, listOf(Triple(0, 10L, 20L), Triple(2, 11L, 21L)))
        assertEquals("tune_window", w.getString("type"))
        assertEquals(4, w.getInt("id"))
        val arr: JSONArray = w.getJSONArray("windows")
        assertEquals(0, arr.getJSONObject(0).getInt("handle"))
        assertEquals(10L, arr.getJSONObject(0).getLong("from_ns"))
        assertEquals(21L, arr.getJSONObject(1).getLong("to_ns"))
        val c = TuneMessages.cancelled(4)
        assertTrue(c.getBoolean("cancelled") && c.getInt("id") == 4 && c.getString("type") == "tune_window")
        assertEquals("tune_request", TuneMessages.request().getString("type"))
        assertEquals("tune", TuneMessages.CAP)
    }
}
