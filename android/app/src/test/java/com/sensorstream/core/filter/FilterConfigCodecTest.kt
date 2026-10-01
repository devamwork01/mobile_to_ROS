package com.sensorstream.core.filter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterConfigCodecTest {
    @Test fun parsesTheLaptopMessageAndSkipsBadEntries() {
        val msg = JSONObject("""{"type":"filters","configs":{
            "1:Acc":{"lowpass":{"hz":5.0,"order":4},"notches":[{"hz":8.0,"q":10.0}]},
            "4:Gyro":{"lowpass":null,"notches":[{"hz":3.0,"q":5.0}]},
            "2:Bad":{"lowpass":5},
            "9:Worse":"x"}}""")
        val m = FilterConfigCodec.parseConfigs(msg.getJSONObject("configs"))
        assertEquals(setOf("1:Acc", "4:Gyro"), m.keys)
        assertEquals(FilterConfig(5.0, 4, listOf(Notch(8.0, 10.0))), m["1:Acc"])
        assertEquals(FilterConfig(null, 4, listOf(Notch(3.0, 5.0))), m["4:Gyro"])
    }

    @Test fun roundTripsForStorage() {
        val m = mapOf("1:Acc" to FilterConfig(5.0, 2, listOf(Notch(8.0, 10.0))), "2:Mag" to FilterConfig(null, 4, listOf(Notch(3.0, 5.0))))
        assertEquals(m, FilterConfigCodec.decode(FilterConfigCodec.encode(m)))
        assertEquals(emptyMap<String, FilterConfig>(), FilterConfigCodec.decode("{not json"))
        assertEquals(emptyMap<String, FilterConfig>(), FilterConfigCodec.decode(null))
    }

    @Test fun summaries() {
        assertEquals("LP 5 Hz · 4th + notch 8 Hz", FilterConfigCodec.summary(FilterConfig(5.0, 4, listOf(Notch(8.0, 10.0)))))
        assertEquals("LP 2.5 Hz · 2nd", FilterConfigCodec.summary(FilterConfig(2.5, 2)))
        assertEquals("notch 3, 12 Hz", FilterConfigCodec.summary(FilterConfig(null, 4, listOf(Notch(3.0, 5.0), Notch(12.0, 9.0)))))
    }
}
