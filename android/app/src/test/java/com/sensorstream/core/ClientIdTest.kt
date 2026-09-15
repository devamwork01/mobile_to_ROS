package com.sensorstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientIdTest {
    @Test fun createsAndPersistsWhenAbsent() {
        var stored: String? = null
        val id = ClientId.getOrCreate(read = { stored }, write = { stored = it })
        assertTrue("id is non-blank", id.isNotBlank())
        assertEquals("persisted the created id", id, stored)
    }

    @Test fun reusesExistingId() {
        var stored: String? = "existing-abc"
        val id = ClientId.getOrCreate(read = { stored }, write = { stored = it })
        assertEquals("existing-abc", id)
        assertEquals("existing-abc", stored) // unchanged
    }

    @Test fun replacesBlankId() {
        var stored: String? = "   "
        val id = ClientId.getOrCreate(read = { stored }, write = { stored = it })
        assertTrue(id.isNotBlank())
        assertEquals(id, stored)
    }
}
