package com.sensorstream.codec

import com.sensorstream.core.SensorSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes telemetry datagrams in the little-endian binary layout defined in
 * `docs/protocol.md` and mirrored by the Python `sensorstream.protocol`.
 *
 * The layout is pinned by a cross-language golden vector (see the unit test and
 * `laptop/tests/golden_packet.bin`): both codecs must produce identical bytes.
 */
object BinaryPacketCodec {

    const val MAGIC: Int = 0x5353          // "SS"
    const val VERSION: Int = 1
    const val FLAG_STAGE_TS: Int = 0x01

    const val HEADER_SIZE: Int = 10
    const val REC_FIXED_SIZE: Int = 20
    const val STAGE_SIZE: Int = 16

    fun encodedSize(records: List<SensorSample>, stage: Boolean): Int {
        var n = HEADER_SIZE
        for (r in records) {
            n += REC_FIXED_SIZE + 4 * r.valueCount + if (stage) STAGE_SIZE else 0
        }
        return n
    }

    /** Encode into a caller-provided buffer (little-endian). Used with a pooled
     *  buffer on the hot path. The buffer must have at least [encodedSize] bytes
     *  remaining; on return it is flipped to the written region. */
    fun encodeInto(buf: ByteBuffer, deviceId: Int, flags: Int, records: List<SensorSample>) {
        val stage = (flags and FLAG_STAGE_TS) != 0
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(MAGIC.toShort())
        buf.put(VERSION.toByte())
        buf.put(flags.toByte())
        buf.putInt(deviceId)
        buf.putShort(records.size.toShort())
        for (r in records) {
            buf.putInt(r.sensorType)
            buf.putShort(r.handle.toShort())
            buf.putInt((r.seq and 0xFFFFFFFFL).toInt())
            buf.putLong(r.timestampNs)
            buf.put(r.accuracy.toByte())
            val n = r.valueCount
            buf.put(n.toByte())
            for (i in 0 until n) buf.putFloat(r.values[i])
            if (stage) {
                buf.putLong(r.tAcquireNs)
                buf.putLong(r.tSerializeNs)
            }
        }
    }

    /** Convenience allocator variant. */
    fun encode(deviceId: Int, records: List<SensorSample>, flags: Int = 0): ByteArray {
        val stage = (flags and FLAG_STAGE_TS) != 0
        val out = ByteArray(encodedSize(records, stage))
        encodeInto(ByteBuffer.wrap(out), deviceId, flags, records)
        return out
    }
}
