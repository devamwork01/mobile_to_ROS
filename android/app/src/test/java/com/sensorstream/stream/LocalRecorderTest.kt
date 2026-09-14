package com.sensorstream.stream

import com.sensorstream.codec.BinaryPacketCodec
import com.sensorstream.core.SensorSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LocalRecorderTest {
    private fun tempDir(): File = File.createTempFile("rec", "").let { it.delete(); it.mkdirs(); it }
    private fun sample(handle: Int, seq: Long) =
        SensorSample(sensorType = handle + 1, handle = handle, seq = seq, timestampNs = 1000L + seq,
            accuracy = 3, values = floatArrayOf(1f, 2f, 3f), tAcquireNs = 5000L + seq)

    @Test
    fun writesMagicAndOneFrameMatchingCodec() {
        val dir = tempDir()
        val rec = LocalRecorder(dir, deviceId = 7, maxBytes = 1_000_000, maxAgeMs = 60_000, segmentMs = 10_000,
            now = { 0L })
        val s = sample(handle = 0, seq = 0)
        rec.write(s)
        rec.close()

        val seg = dir.listFiles { f -> f.name.endsWith(".ssbin") }!!.single()
        val bytes = seg.readBytes()
        // magic
        assertArrayEquals(LocalRecorder.MAGIC, bytes.copyOfRange(0, LocalRecorder.MAGIC.size))
        // frame header + datagram == expected
        val datagram = BinaryPacketCodec.encode(7, listOf(s), BinaryPacketCodec.FLAG_STAGE_TS)
        val hdr = ByteBuffer.wrap(bytes, LocalRecorder.MAGIC.size, 12).order(ByteOrder.LITTLE_ENDIAN)
        val frameTs = hdr.long
        val len = hdr.int
        assertTrue(frameTs == s.tAcquireNs)
        assertTrue(len == datagram.size)
        val payload = bytes.copyOfRange(LocalRecorder.MAGIC.size + 12, LocalRecorder.MAGIC.size + 12 + len)
        assertArrayEquals(datagram, payload)
    }
}
