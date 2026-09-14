package com.sensorstream.stream

import com.sensorstream.codec.BinaryPacketCodec
import com.sensorstream.core.SensorSample
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bounded, lossless on-phone recorder. Writes segmented .ssbin files byte-compatible with the
 * laptop (logging_sink.py) and indexes each record's raw datagram bytes by (handle, seq) so a
 * later backfill can resend them verbatim. Synchronous and Context-free for JVM testability;
 * callers must drive write() from an IO thread, never the sensor callback.
 */
class LocalRecorder(
    private val dir: File,
    private val deviceId: Int,
    private val maxBytes: Long,
    private val maxAgeMs: Long,
    private val segmentMs: Long,
    private val flags: Int = BinaryPacketCodec.FLAG_STAGE_TS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        val MAGIC: ByteArray = "SSLOG1\n".toByteArray(Charsets.US_ASCII)
        private const val FRAME_HEADER = 12 // i64 + u32
    }

    private class Segment(val file: File, val startMs: Long) {
        val out = BufferedOutputStream(FileOutputStream(file))
        var bytes = 0L
        // (handle -> list of (seq, datagramOffset, datagramLen)) for readRawRange
        val index = HashMap<Int, ArrayList<Triple<Long, Long, Int>>>()
    }

    private var current: Segment? = null
    private val segments = ArrayList<Segment>()

    data class Stats(
        val recorded: Long, val bytesOnDisk: Long, val oldestAgeMs: Long,
        val droppedOldest: Long, val writeErrors: Long,
    )

    private var recorded = 0L
    private var droppedOldest = 0L
    private var writeErrors = 0L

    init {
        dir.mkdirs()
    }

    fun write(sample: SensorSample) {
        runCatching {
            var seg = current ?: openSegment()
            if (now() - seg.startMs >= segmentMs) { rotate(); seg = current!! }
            val datagram = BinaryPacketCodec.encode(deviceId, listOf(sample), flags)
            val header = ByteBuffer.allocate(FRAME_HEADER).order(ByteOrder.LITTLE_ENDIAN)
            header.putLong(sample.tAcquireNs); header.putInt(datagram.size)
            val datagramOffset = seg.bytes + FRAME_HEADER
            seg.out.write(header.array()); seg.out.write(datagram)
            seg.bytes += FRAME_HEADER + datagram.size
            seg.index.getOrPut(sample.handle) { ArrayList() }
                .add(Triple(sample.seq, datagramOffset, datagram.size))
            recorded++
            prune()
        }.onFailure { writeErrors++ }
    }

    private fun totalBytes(): Long = segments.sumOf { it.bytes }

    private fun prune() {
        while (segments.size > 1) {
            val oldest = segments.first()
            val overSize = totalBytes() > maxBytes
            val overAge = now() - oldest.startMs > maxAgeMs
            if (!overSize && !overAge) break
            // count records in the segment as dropped, then delete it
            droppedOldest += oldest.index.values.sumOf { it.size.toLong() }
            runCatching { oldest.out.close() }
            oldest.file.delete()
            segments.removeAt(0)
        }
    }

    fun stats(): Stats {
        val oldestAge = segments.firstOrNull()?.let { now() - it.startMs } ?: 0L
        return Stats(recorded, totalBytes(), oldestAge, droppedOldest, writeErrors)
    }

    private fun rotate() {
        current?.out?.flush(); current?.out?.close()
        openSegment()
    }

    private fun openSegment(): Segment {
        val ts = now()
        val seg = Segment(File(dir, "seg_$ts.ssbin"), ts)
        seg.out.write(MAGIC)
        seg.bytes = MAGIC.size.toLong()
        current = seg
        segments.add(seg)
        return seg
    }

    fun close() {
        current?.out?.flush()
        current?.out?.close()
        current = null
    }
}
