package com.sensorstream.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackfillResponderTest {
    @Test fun chunksFramesByMaxPerMsg() {
        val frames = (0 until 600).map { byteArrayOf(it.toByte(), 1, 2, 3) }
        val chunks = BackfillResponder.chunk(frames)
        // 600 frames / 256 per msg -> 3 chunks (256, 256, 88)
        assertEquals(3, chunks.size)
        assertEquals(256, chunks[0].size)
        assertEquals(256, chunks[1].size)
        assertEquals(88, chunks[2].size)
        // chunking preserves the frames verbatim and in order
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), chunks[0][0])
        assertArrayEquals(byteArrayOf(255.toByte(), 1, 2, 3), chunks[0][255])
    }

    @Test fun emptyFramesProducesNoChunks() {
        assertTrue(BackfillResponder.chunk(emptyList()).isEmpty())
    }

    @Test fun singlePartialChunk() {
        val frames = (0 until 10).map { byteArrayOf(it.toByte()) }
        val chunks = BackfillResponder.chunk(frames)
        assertEquals(1, chunks.size)
        assertEquals(10, chunks[0].size)
    }
}
