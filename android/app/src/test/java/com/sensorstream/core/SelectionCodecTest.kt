package com.sensorstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionCodecTest {

    private val catalog = mapOf(0 to 1, 1 to 2, 2 to 4, 3 to 11) // handle -> type

    @Test
    fun roundTripsEnabledAndPeriods() {
        val enabled = setOf(0, 3)
        val periods = mapOf(0 to 10_000, 1 to 20_000, 2 to 5_000, 3 to 0)
        val text = SelectionCodec.encode(enabled, periods) { catalog[it] }
        val d = SelectionCodec.decode(text, catalog)!!
        assertEquals(enabled, d.enabled)
        assertEquals(periods, d.periods)
    }

    @Test
    fun dropsEntriesWhoseSensorTypeChanged() {
        val text = SelectionCodec.encode(setOf(0, 2), mapOf(0 to 10_000, 2 to 5_000)) { catalog[it] }
        // Same handles, but handle 2 is now a different sensor type on this device.
        val d = SelectionCodec.decode(text, mapOf(0 to 1, 2 to 9))!!
        assertEquals(setOf(0), d.enabled)
        assertEquals(mapOf(0 to 10_000), d.periods)
    }

    @Test
    fun blankOrGarbageDecodesSafely() {
        assertNull(SelectionCodec.decode(null, catalog))
        assertNull(SelectionCodec.decode("", catalog))
        val d = SelectionCodec.decode("junk;1:2;x:y:z:w;0:1:10000:1", catalog)!!
        assertEquals(setOf(0), d.enabled)
    }
}

class TargetValidatorTest {

    @Test
    fun acceptsLanIpsAndHostnames() {
        assertNull(TargetValidator.validate("192.168.1.23", "8081"))
        assertNull(TargetValidator.validate(" 10.0.0.5 ", " 8081 "))
        assertNull(TargetValidator.validate("my-laptop.local", "8081"))
    }

    @Test
    fun rejectsBlankLoopbackAndMalformed() {
        assertNotNull(TargetValidator.validate("", "8081"))
        assertNotNull(TargetValidator.validate("   ", "8081"))
        assertNotNull(TargetValidator.validate("127.0.0.1", "8081"))
        assertNotNull(TargetValidator.validate("localhost", "8081"))
        assertNotNull(TargetValidator.validate("192.168.1.300", "8081"))
        assertNotNull(TargetValidator.validate("192.168.1", "8081"))
        assertNotNull(TargetValidator.validate("bad host", "8081"))
    }

    @Test
    fun rejectsBadPorts() {
        assertNotNull(TargetValidator.validate("192.168.1.23", ""))
        assertNotNull(TargetValidator.validate("192.168.1.23", "0"))
        assertNotNull(TargetValidator.validate("192.168.1.23", "70000"))
        assertNotNull(TargetValidator.validate("192.168.1.23", "abc"))
    }
}
