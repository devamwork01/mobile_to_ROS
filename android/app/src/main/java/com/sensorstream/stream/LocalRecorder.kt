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
 * later backfill can resend them verbatim. Context-free for JVM testability; callers must drive
 * write() from an IO thread, never the sensor callback.
 *
 * Thread-safety: write() runs on the single IO drain coroutine, but stats() is polled from the
 * heartbeat coroutine (another thread) and close() runs on the stop() caller's thread. The public
 * methods are therefore [Synchronized] on the instance monitor so a stats() poll never iterates
 * [segments] while write()/prune() structurally mutate it, and close() never overlaps an in-flight
 * write(). There is only one writer, so it never contends with itself; the 1 s stats() poll (and,
 * later, readRawRange for backfill) contend only briefly.
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
    private var segmentSeq = 0L

    data class Stats(
        val recorded: Long, val bytesOnDisk: Long, val oldestAgeMs: Long,
        val droppedOldest: Long, val writeErrors: Long, val lastError: String?,
    )

    private var recorded = 0L
    private var droppedOldest = 0L
    private var writeErrors = 0L
    @Volatile private var lastError: String? = null

    init {
        dir.mkdirs()
    }

    @Synchronized
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
        }.onFailure { e -> writeErrors++; lastError = e.message ?: e.javaClass.simpleName }
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

    @Synchronized
    fun stats(): Stats {
        val oldestAge = segments.firstOrNull()?.let { now() - it.startMs } ?: 0L
        return Stats(recorded, totalBytes(), oldestAge, droppedOldest, writeErrors, lastError)
    }

    @Synchronized
    fun readRawRange(handle: Int, fromSeq: Long, toSeq: Long): List<ByteArray> {
        current?.out?.flush() // ensure current segment's bytes are on disk before reading
        val out = ArrayList<Pair<Long, ByteArray>>()
        for (seg in segments) {
            val entries = seg.index[handle] ?: continue
            for ((seq, offset, len) in entries) {
                if (seq < fromSeq || seq > toSeq) continue
                java.io.RandomAccessFile(seg.file, "r").use { raf ->
                    raf.seek(offset)
                    val buf = ByteArray(len)
                    raf.readFully(buf)
                    out.add(seq to buf)
                }
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    private fun rotate() {
        current?.out?.flush(); current?.out?.close()
        openSegment()
    }

    private fun openSegment(): Segment {
        val ts = now()
        // salt with a monotonic counter: a constant/coarse clock can otherwise produce the
        // same filename across rotations, causing a later segment's FileOutputStream to
        // truncate a still-referenced earlier segment's file.
        val seg = Segment(File(dir, "seg_${ts}_${segmentSeq++}.ssbin"), ts)
        seg.out.write(MAGIC)
        seg.bytes = MAGIC.size.toLong()
        current = seg
        segments.add(seg)
        return seg
    }

    @Synchronized
    fun close() {
        current?.out?.flush()
        current?.out?.close()
        current = null
    }
}
