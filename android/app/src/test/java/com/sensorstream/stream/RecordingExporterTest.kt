package com.sensorstream.stream

import com.sensorstream.core.SensorSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecordingExporterTest {
    private fun tempDir(): File = File.createTempFile("exp", "").let { it.delete(); it.mkdirs(); it }
    private fun sample(seq: Long) = SensorSample(1, 0, seq, 1_000L + seq, 3, floatArrayOf(1f, 2f, 3f), tAcquireNs = 5_000L + seq)

    /** Parse an exported file into (frameTs, seq) pairs, asserting the laptop's layout. */
    private fun parse(f: File): List<Pair<Long, Long>> {
        val b = f.readBytes()
        assertArrayEquals(LocalRecorder.MAGIC, b.copyOfRange(0, LocalRecorder.MAGIC.size))
        val out = mutableListOf<Pair<Long, Long>>()
        var off = LocalRecorder.MAGIC.size
        while (off < b.size) {
            val h = ByteBuffer.wrap(b, off, 12).order(ByteOrder.LITTLE_ENDIAN)
            val ts = h.long; val len = h.int
            val seq = ByteBuffer.wrap(b, off + 12 + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            out += ts to seq
            off += 12 + len
        }
        return out
    }

    @Test
    fun mergesSegmentsInOrderWithOneHeader() {
        val dir = tempDir()
        var t = 0L
        val rec = LocalRecorder(dir, deviceId = 1, maxBytes = 10_000_000, maxAgeMs = 600_000, segmentMs = 100, now = { t })
        for (i in 0L until 30L) { t = i * 10; rec.write(sample(i)) } // ~3 segments
        rec.close()
        val segs = RecordingExporter.segmentsIn(dir)
        assertEquals(3, segs.size)

        val out = File(dir, "export/merged.ssbin")
        val r = RecordingExporter.merge(segs, out)
        assertEquals(30L, r.frames)
        assertEquals(3, r.segments)
        assertEquals(out.length(), r.bytes)
        assertEquals((0L until 30L).toList(), parse(out).map { it.second })
    }

    @Test
    fun dropsTruncatedTrailingFrame() {
        val dir = tempDir()
        val rec = LocalRecorder(dir, deviceId = 1, maxBytes = 10_000_000, maxAgeMs = 600_000, segmentMs = 60_000, now = { 0L })
        for (i in 0L until 5L) rec.write(sample(i))
        rec.close()
        val seg = RecordingExporter.segmentsIn(dir).single()
        seg.writeBytes(seg.readBytes().let { it.copyOf(it.size - 7) }) // chop the last frame mid-way

        val out = File(dir, "merged.ssbin")
        val r = RecordingExporter.merge(listOf(seg), out)
        assertEquals(4L, r.frames)
        assertEquals((0L until 4L).toList(), parse(out).map { it.second })
    }

    @Test
    fun segmentsInIgnoresOtherFilesAndSortsBySalt() {
        val dir = tempDir()
        listOf("seg_100_2.ssbin", "seg_100_10.ssbin", "seg_050_0.ssbin", "notes.txt", "seg_x_1.ssbin")
            .forEach { File(dir, it).writeText("") }
        assertEquals(listOf("seg_050_0.ssbin", "seg_100_2.ssbin", "seg_100_10.ssbin"),
            RecordingExporter.segmentsIn(dir).map { it.name })
    }
}
