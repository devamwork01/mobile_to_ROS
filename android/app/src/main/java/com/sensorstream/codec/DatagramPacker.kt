package com.sensorstream.codec

import com.sensorstream.core.SensorSample

/**
 * Size bookkeeping for packing several records into one telemetry datagram. The wire format
 * already carries a record count, so batching changes only how many records ride in each
 * datagram — never the layout. Datagrams stay under [MAX_DATAGRAM_BYTES] so they are never
 * IP-fragmented on a standard 1500-byte MTU (Wi-Fi/Ethernet).
 */
object DatagramPacker {
    const val MAX_DATAGRAM_BYTES = 1200
    const val MAX_RECORDS = 24

    fun recordSize(s: SensorSample, stage: Boolean): Int =
        BinaryPacketCodec.REC_FIXED_SIZE + 4 * s.valueCount + if (stage) BinaryPacketCodec.STAGE_SIZE else 0

    /** Size of a datagram that would hold [count] records totalling [recordBytes]. */
    fun datagramSize(recordBytes: Int): Int = BinaryPacketCodec.HEADER_SIZE + recordBytes

    /** Whether one more record of [next] fits a datagram currently holding [count] records
     *  totalling [recordBytes]. The first record always fits. */
    fun fits(count: Int, recordBytes: Int, next: SensorSample, stage: Boolean): Boolean =
        count == 0 || (count < MAX_RECORDS &&
            datagramSize(recordBytes + recordSize(next, stage)) <= MAX_DATAGRAM_BYTES)
}
