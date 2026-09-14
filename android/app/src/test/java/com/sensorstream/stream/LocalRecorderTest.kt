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

    @Test
    fun rotatesSegmentsByTime() {
        val dir = tempDir()
        var t = 0L
        val rec = LocalRecorder(dir, deviceId = 1, maxBytes = 10_000_000, maxAgeMs = 600_000,
            segmentMs = 100, now = { t })
        rec.write(sample(0, 0))       // opens segment @ t=0
        t = 50; rec.write(sample(0, 1)) // same segment
        t = 150; rec.write(sample(0, 2)) // >=100ms since start -> new segment
        rec.close()
        val segs = dir.listFiles { f -> f.name.endsWith(".ssbin") }!!
        assertTrue("expected 2 segments, got ${segs.size}", segs.size == 2)
    }

    @Test
    fun prunesOldestBySize() {
        val dir = tempDir()
        var t = 0L
        // tiny size cap so a few records force a prune; small segments so multiple exist
        val rec = LocalRecorder(dir, deviceId = 1, maxBytes = 400, maxAgeMs = 600_000,
            segmentMs = 1, now = { t })
        for (i in 0 until 20) { t = i.toLong(); rec.write(sample(0, i.toLong())) }
        rec.close()
        val st = rec.stats()
        assertTrue("bytes ${st.bytesOnDisk} should be <= cap-ish", st.bytesOnDisk <= 400 + 200)
        assertTrue("expected some dropped, got ${st.droppedOldest}", st.droppedOldest > 0)
        assertTrue("at least current segment remains", dir.listFiles { f -> f.name.endsWith(".ssbin") }!!.isNotEmpty())
    }

    @Test
    fun prunesOldestByAge() {
        val dir = tempDir()
        var t = 0L
        val rec = LocalRecorder(dir, deviceId = 1, maxBytes = 10_000_000, maxAgeMs = 100,
            segmentMs = 10, now = { t })
        rec.write(sample(0, 0))                 // seg @0
        t = 500; rec.write(sample(0, 1))        // seg @500; seg@0 is now >100ms old -> pruned
        rec.close()
        assertTrue(rec.stats().droppedOldest >= 1)
    }

    @Test
    fun readRawRangeReturnsRequestedDatagrams() {
        val dir = tempDir()
        var t = 0L
        val rec = LocalRecorder(dir, deviceId = 3, maxBytes = 10_000_000, maxAgeMs = 600_000,
            segmentMs = 30, now = { t })
        val written = (0L until 6L).map { seq -> sample(handle = 2, seq = seq).also { t = seq * 10; rec.write(it) } }
        rec.close()
        val got = rec.readRawRange(handle = 2, fromSeq = 2, toSeq = 4)
        // expect datagrams for seq 2,3,4 == codec output
        val expected = written.filter { it.seq in 2..4 }
            .map { BinaryPacketCodec.encode(3, listOf(it), BinaryPacketCodec.FLAG_STAGE_TS) }
        assertTrue(got.size == 3)
        got.forEachIndexed { i, b -> assertArrayEquals(expected[i], b) }
    }

    @Test
    fun rotationAtConstantClockDoesNotCollideSegmentFiles() {
        val dir = tempDir()
        // constant clock + segmentMs=0 forces a rotation on every write, exercising the
        // filename-collision risk when now() doesn't change between rotations.
        val rec = LocalRecorder(dir, deviceId = 5, maxBytes = 10_000_000, maxAgeMs = 600_000,
            segmentMs = 0, now = { 100L })
        val n = 5
        val written = (0 until n).map { i -> sample(handle = 9, seq = i.toLong()).also { rec.write(it) } }
        rec.close()

        // every segment file created must be uniquely named (no truncate-on-reuse collision)
        val segFiles = dir.listFiles { f -> f.name.endsWith(".ssbin") }!!
        assertTrue("expected at least $n distinct segment files, got ${segFiles.size}: " +
            segFiles.map { it.name }, segFiles.size >= n)

        // and no data loss/corruption from a collided/truncated filename
        val got = rec.readRawRange(handle = 9, fromSeq = 0, toSeq = (n - 1).toLong())
        val expected = written.map { BinaryPacketCodec.encode(5, listOf(it), BinaryPacketCodec.FLAG_STAGE_TS) }
        assertTrue("expected $n records read back, got ${got.size}", got.size == n)
        got.forEachIndexed { i, b -> assertArrayEquals(expected[i], b) }
    }

    @Test
    fun writeFailureIsCapturedInLastError() {
        val parent = tempDir()
        val notADir = File(parent, "blocked")
        notADir.writeText("this is a regular file, not a directory")
        // dir points at a regular file, so segment creation inside it must fail
        val rec = LocalRecorder(notADir, deviceId = 1, maxBytes = 10_000, maxAgeMs = 10_000,
            segmentMs = 10_000, now = { 0L })
        rec.write(sample(0, 0))
        val st = rec.stats()
        assertTrue("expected at least one write error, got ${st.writeErrors}", st.writeErrors >= 1)
        assertTrue("expected lastError to be captured", st.lastError != null)
    }
}
