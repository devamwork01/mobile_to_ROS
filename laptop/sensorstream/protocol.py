"""Wire protocol for the sensor telemetry system.

Two channels share this module's definitions:

* **Telemetry** (UDP): a compact little-endian binary datagram carrying one or
  more sensor *records*. Encoded/decoded by :func:`encode_datagram` /
  :func:`decode_datagram`. This is the hot path.
* **Control** (WebSocket): JSON messages. Only message-type constants and a few
  helpers live here; the schema is documented in ``docs/protocol.md``.

The binary layout is mirrored byte-for-byte by the Kotlin ``BinaryPacketCodec``
on the phone. A committed golden vector (``tests/golden_packet.bin``) pins the
layout so both implementations cannot silently drift.

Datagram layout (all little-endian, standard sizes, no padding)::

    Header (10 bytes)
        u16  magic          = 0x5353  ("SS")
        u8   version        = 1
        u8   flags          bit0 = per-record stage timestamps present
        u32  device_id      assigned by the laptop at control handshake
        u16  record_count

    Record (20 bytes fixed + payload)
        i32  sensor_type    Android Sensor.getType()
        u16  sensor_handle  stable per-session index (metadata sent via control)
        u32  seq            per-sensor sequence number (loss detection)
        i64  t_sensor_ns    Android SensorEvent.timestamp (monotonic; verbatim)
        i8   accuracy       Android accuracy status (-1..3)
        u8   n_values       number of float32 values that follow
        f32  values[n_values]
        i64  t_acquire_ns   only if flags bit0 set
        i64  t_serialize_ns only if flags bit0 set
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field
from typing import List, Optional

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

MAGIC = 0x5353  # "SS"
VERSION = 1

FLAG_STAGE_TS = 0x01  # per-record t_acquire_ns / t_serialize_ns present

_HEADER = struct.Struct("<HBBIH")          # magic, version, flags, device_id, count
_REC_FIXED = struct.Struct("<iHIqbB")      # type, handle, seq, t_sensor_ns, acc, n
_STAGE = struct.Struct("<qq")              # t_acquire_ns, t_serialize_ns

HEADER_SIZE = _HEADER.size                 # 10
REC_FIXED_SIZE = _REC_FIXED.size           # 20
STAGE_SIZE = _STAGE.size                   # 16

# Control-channel message types (WebSocket JSON, "type" field).
MSG_HELLO = "hello"
MSG_HELLO_ACK = "hello_ack"
MSG_CLOCK_PING = "clock_ping"
MSG_CLOCK_PONG = "clock_pong"
MSG_CONFIGURE = "configure"
MSG_CONFIG_STATE = "config_state"
MSG_HEARTBEAT = "heartbeat"
MSG_HEARTBEAT_ACK = "heartbeat_ack"
MSG_STATS = "stats"
MSG_ERROR = "error"
MSG_START = "start"
MSG_STOP = "stop"
MSG_ACTIVE = "active"  # phone→laptop: currently-streaming sensor handles (live reconfig)


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------


class ProtocolError(ValueError):
    """Raised when a telemetry datagram cannot be decoded."""


# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------


@dataclass(slots=True)
class Record:
    """One sensor measurement as carried on the wire.

    ``t_sensor_ns`` is the phone's original monotonic timestamp and is never
    rewritten anywhere in the pipeline.
    """

    sensor_type: int
    sensor_handle: int
    seq: int
    t_sensor_ns: int
    accuracy: int
    values: List[float]
    t_acquire_ns: Optional[int] = None
    t_serialize_ns: Optional[int] = None


@dataclass(slots=True)
class Datagram:
    device_id: int
    records: List[Record] = field(default_factory=list)
    flags: int = 0

    @property
    def has_stage_ts(self) -> bool:
        return bool(self.flags & FLAG_STAGE_TS)


# ---------------------------------------------------------------------------
# Encode / decode
# ---------------------------------------------------------------------------


def encode_datagram(dg: Datagram) -> bytes:
    """Serialize a :class:`Datagram` to bytes.

    If ``dg.flags`` has :data:`FLAG_STAGE_TS`, every record must carry
    ``t_acquire_ns`` and ``t_serialize_ns``.
    """
    stage = bool(dg.flags & FLAG_STAGE_TS)
    parts = [_HEADER.pack(MAGIC, VERSION, dg.flags, dg.device_id & 0xFFFFFFFF, len(dg.records))]
    for r in dg.records:
        n = len(r.values)
        if n > 255:
            raise ProtocolError(f"record has {n} values (max 255)")
        parts.append(
            _REC_FIXED.pack(
                r.sensor_type,
                r.sensor_handle & 0xFFFF,
                r.seq & 0xFFFFFFFF,
                r.t_sensor_ns,
                r.accuracy,
                n,
            )
        )
        parts.append(struct.pack("<%df" % n, *r.values))
        if stage:
            parts.append(_STAGE.pack(r.t_acquire_ns or 0, r.t_serialize_ns or 0))
    return b"".join(parts)


def decode_datagram(buf: bytes) -> Datagram:
    """Parse bytes into a :class:`Datagram`.

    Raises :class:`ProtocolError` on a bad magic, wrong version, truncation, or
    trailing garbage.
    """
    if len(buf) < HEADER_SIZE:
        raise ProtocolError("buffer shorter than header")
    magic, version, flags, device_id, count = _HEADER.unpack_from(buf, 0)
    if magic != MAGIC:
        raise ProtocolError(f"bad magic 0x{magic:04x}")
    if version != VERSION:
        raise ProtocolError(f"unsupported version {version}")
    stage = bool(flags & FLAG_STAGE_TS)

    off = HEADER_SIZE
    records: List[Record] = []
    for _ in range(count):
        if off + REC_FIXED_SIZE > len(buf):
            raise ProtocolError("truncated record header")
        sensor_type, handle, seq, t_sensor_ns, accuracy, n = _REC_FIXED.unpack_from(buf, off)
        off += REC_FIXED_SIZE
        vbytes = 4 * n
        if off + vbytes > len(buf):
            raise ProtocolError("truncated record values")
        values = list(struct.unpack_from("<%df" % n, buf, off))
        off += vbytes
        t_acq = t_ser = None
        if stage:
            if off + STAGE_SIZE > len(buf):
                raise ProtocolError("truncated stage timestamps")
            t_acq, t_ser = _STAGE.unpack_from(buf, off)
            off += STAGE_SIZE
        records.append(
            Record(sensor_type, handle, seq, t_sensor_ns, accuracy, values, t_acq, t_ser)
        )

    if off != len(buf):
        raise ProtocolError(f"{len(buf) - off} trailing bytes after {count} records")
    return Datagram(device_id=device_id, records=records, flags=flags)


def iter_records(buf: bytes):
    """Yield records from a datagram without building intermediate lists.

    Convenience for the receiver hot path; validation matches
    :func:`decode_datagram`.
    """
    yield from decode_datagram(buf).records
