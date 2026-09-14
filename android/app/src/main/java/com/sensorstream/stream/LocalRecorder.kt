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
    }

    private var current: Segment? = null

    init {
        dir.mkdirs()
    }

    fun write(sample: SensorSample) {
        val seg = current ?: openSegment()
        val datagram = BinaryPacketCodec.encode(deviceId, listOf(sample), flags)
        val header = ByteBuffer.allocate(FRAME_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        header.putLong(sample.tAcquireNs)
        header.putInt(datagram.size)
        seg.out.write(header.array())
        seg.out.write(datagram)
        seg.bytes += FRAME_HEADER + datagram.size
    }

    private fun openSegment(): Segment {
        val ts = now()
        val seg = Segment(File(dir, "seg_$ts.ssbin"), ts)
        seg.out.write(MAGIC)
        seg.bytes = MAGIC.size.toLong()
        current = seg
        return seg
    }

    fun close() {
        current?.out?.flush()
        current?.out?.close()
        current = null
    }
}
