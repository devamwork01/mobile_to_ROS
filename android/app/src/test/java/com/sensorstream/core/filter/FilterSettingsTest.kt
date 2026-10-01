package com.sensorstream.core.filter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterSettingsTest {
    private val key = "1:acc"
    private val msg = JSONObject("""{"type":"filters","configs":{"1:acc":{"lowpass":{"hz":5.0,"order":4},"notches":[]}}}""")

    @Test fun `apply updates configs and persists without any UI attached`() {
        var saved: String? = null
        val s = FilterSettings(null) { saved = it }
        assertTrue(s.configs.value.isEmpty())
        assertTrue(s.apply(msg))
        assertEquals(5.0, s.configs.value[key]!!.lowpassHz!!, 0.0)
        // what was saved restores the same settings (e.g. after the app is reopened)
        assertEquals(s.configs.value, FilterSettings(saved) {}.configs.value)
    }

    @Test fun `clear from the laptop empties and persists`() {
        var saved: String? = null
        val s = FilterSettings(FilterConfigCodec.encode(FilterConfigCodec.parseConfigs(msg.getJSONObject("configs")))) { saved = it }
        assertEquals(1, s.configs.value.size)
        assertTrue(s.apply(JSONObject("""{"type":"filters","configs":{}}""")))
        assertTrue(s.configs.value.isEmpty())
        assertTrue(FilterSettings(saved) {}.configs.value.isEmpty())
    }

    @Test fun `message without configs keeps the previous settings`() {
        var saved: String? = null
        val s = FilterSettings(null) { saved = it }
        s.apply(msg)
        saved = null
        assertFalse(s.apply(JSONObject("""{"type":"filters"}""")))
        assertEquals(1, s.configs.value.size)
        assertNull(saved)
    }
}
