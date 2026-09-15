package com.sensorstream.core

import java.util.UUID

/** Stable per-install identity, persisted by the caller. Context-free for JVM testability:
 *  the caller supplies the read/write to SharedPreferences. Used so the laptop can key a phone
 *  across reconnects even though the telemetry device_id is reassigned each control handshake. */
object ClientId {
    fun getOrCreate(read: () -> String?, write: (String) -> Unit): String {
        val existing = read()
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        write(created)
        return created
    }
}
