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
 * Lifecycle: construct ONCE per streaming session and keep it across WS reconnects (a fresh
 * instance per reconnect would orphan the previous instance's segment files — uncounted and never
 * pruned — growing storage without bound). On construction it ADOPTS any pre-existing seg_*.ssbin
 * in [dir] (from earlier reconnects this session, or a prior process) into [segments] + the
 * (handle,seq) index, so they count toward the cap, get pruned, and can be served by readRawRange.
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
        // Fixed byte offsets of (handle, seq) inside a datagram, so segments can be adopted
        // without a decoder: packet header is HEADER_SIZE bytes, then the record's fixed fields
        // start with sensorType(i32); handle(u16) and seq(u32) follow. Stable for flags=0 and
        // FLAG_STAGE_TS alike (stage timestamps are appended at the record end).
        private const val OFF_HANDLE = BinaryPacketCodec.HEADER_SIZE + 4       // 14
        private const val OFF_SEQ = BinaryPacketCodec.HEADER_SIZE + 4 + 2      // 16
        private const val MIN_DATAGRAM = BinaryPacketCodec.HEADER_SIZE + BinaryPacketCodec.REC_FIXED_SIZE // 30
    }

    private class Segment(val file: File, val startMs: Long) {
        // Non-null only for the writable current segment; adopted/closed segments carry null.
        var out: BufferedOutputStream? = null
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
        adopt()
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
            val o = seg.out!!
            o.write(header.array()); o.write(datagram)
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
            runCatching { oldest.out?.close() }
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
        current?.let { it.out?.flush(); it.out?.close(); it.out = null }
        openSegment()
    }

    private fun openSegment(): Segment {
        val ts = now()
        // salt with a monotonic counter: a constant/coarse clock can otherwise produce the
        // same filename across rotations, causing a later segment's FileOutputStream to
        // truncate a still-referenced earlier segment's file. Also advanced past adopted salts.
        val seg = Segment(File(dir, "seg_${ts}_${segmentSeq++}.ssbin"), ts)
        seg.out = BufferedOutputStream(FileOutputStream(seg.file))
        seg.out!!.write(MAGIC)
        seg.bytes = MAGIC.size.toLong()
        current = seg
        segments.add(seg)
        return seg
    }

    /**
     * Adopt pre-existing seg_*.ssbin so their records count toward the cap and can be served.
     * They are read-only (out == null); the next write() opens a fresh segment rather than
     * appending. [segmentSeq] is advanced past the highest adopted salt so new filenames can't
     * collide with (and truncate) an adopted file.
     */
    private fun adopt() {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("seg_") && f.name.endsWith(".ssbin") }
            ?: return
        // filename shape: seg_<ts>_<salt>.ssbin — order oldest-first (by ts then salt) for prune.
        val parsed = files.mapNotNull { f ->
            val core = f.name.removePrefix("seg_").removeSuffix(".ssbin")
            val us = core.lastIndexOf('_')
            if (us <= 0) return@mapNotNull null
            val ts = core.substring(0, us).toLongOrNull() ?: return@mapNotNull null
            val salt = core.substring(us + 1).toLongOrNull() ?: return@mapNotNull null
            Triple(f, ts, salt)
        }.sortedWith(compareBy({ it.second }, { it.third }))

        var maxSalt = -1L
        for ((file, ts, salt) in parsed) {
            val seg = scanSegment(file, ts) ?: continue
            segments.add(seg)
            if (salt > maxSalt) maxSalt = salt
        }
        if (maxSalt >= 0) segmentSeq = maxSalt + 1
    }

    /** Rebuild a Segment's index from an on-disk .ssbin by reading frame headers + (handle,seq). */
    private fun scanSegment(file: File, startMs: Long): Segment? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size < MAGIC.size) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null // not our file
        val seg = Segment(file, startMs) // out stays null: adopted segments are read-only
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = MAGIC.size
        while (pos + FRAME_HEADER <= bytes.size) {
            val dlen = bb.getInt(pos + 8) // datagramLen u32 (frame header = tAcquireNs i64, dlen u32)
            val end = pos + FRAME_HEADER + dlen
            if (dlen < MIN_DATAGRAM || end > bytes.size) break // torn/incomplete final frame: stop
            val datagramStart = pos + FRAME_HEADER
            val handle = bb.getShort(datagramStart + OFF_HANDLE).toInt() and 0xFFFF
            val seq = bb.getInt(datagramStart + OFF_SEQ).toLong() and 0xFFFFFFFFL
            seg.index.getOrPut(handle) { ArrayList() }.add(Triple(seq, datagramStart.toLong(), dlen))
            pos = end
        }
        seg.bytes = pos.toLong() // valid bytes consumed (excludes any torn tail)
        return seg
    }

    @Synchronized
    fun close() {
        current?.let { it.out?.flush(); it.out?.close(); it.out = null }
        current = null
    }
}
