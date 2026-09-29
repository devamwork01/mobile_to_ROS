package com.sensorstream.ui.components

import com.sensorstream.stream.EngineState
import org.junit.Assert.assertEquals
import org.junit.Test

class PhaseTest {
    @Test
    fun streamingWithLiveLinkIsStreaming() =
        assertEquals(StreamPhase.STREAMING, EngineState(connected = true, streaming = true).phase())

    @Test
    fun streamingWithDroppedLinkIsReconnecting() =
        assertEquals(StreamPhase.RECONNECTING, EngineState(connecting = true, connected = false, streaming = true).phase())

    @Test
    fun idleIsReady() = assertEquals(StreamPhase.READY, EngineState().phase())

    @Test
    fun failedFirstConnectIsError() =
        assertEquals(StreamPhase.ERROR, EngineState(error = "refused").phase())
}
