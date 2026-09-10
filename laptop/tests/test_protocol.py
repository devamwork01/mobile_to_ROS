"""Round-trip, edge-case and golden-vector tests for the binary telemetry
protocol. These pin the wire layout so the Python and Kotlin codecs cannot
drift apart.
"""

from __future__ import annotations

import os
import struct

import pytest

from sensorstream import protocol as p
from sensorstream.protocol import Datagram, Record, ProtocolError

GOLDEN_PATH = os.path.join(os.path.dirname(__file__), "golden_packet.bin")


def _sample_datagram() -> Datagram:
    return Datagram(
        device_id=0x0A0B0C0D,
        flags=0,
        records=[
            Record(
                sensor_type=1,          # TYPE_ACCELEROMETER
                sensor_handle=0,
                seq=12345,
                t_sensor_ns=123456789012345,
                accuracy=3,
                values=[0.12, -9.81, 0.42],
            ),
            Record(
                sensor_type=4,          # TYPE_GYROSCOPE
                sensor_handle=1,
                seq=7,
                t_sensor_ns=123456789099999,
                accuracy=2,
                values=[0.01, 0.02, -0.03],
            ),
        ],
    )


def test_roundtrip_simple():
    dg = _sample_datagram()
    out = p.decode_datagram(p.encode_datagram(dg))
    assert out.device_id == dg.device_id
    assert len(out.records) == 2
    r = out.records[0]
    assert r.sensor_type == 1
    assert r.seq == 12345
    assert r.t_sensor_ns == 123456789012345
    assert r.accuracy == 3
    assert r.values == pytest.approx([0.12, -9.81, 0.42], rel=1e-6)


def test_roundtrip_with_stage_timestamps():
    dg = _sample_datagram()
    dg.flags = p.FLAG_STAGE_TS
    for i, r in enumerate(dg.records):
        r.t_acquire_ns = 1000 + i
        r.t_serialize_ns = 2000 + i
    out = p.decode_datagram(p.encode_datagram(dg))
    assert out.has_stage_ts
    assert out.records[0].t_acquire_ns == 1000
    assert out.records[1].t_serialize_ns == 2001


def test_empty_datagram():
    dg = Datagram(device_id=1, records=[])
    out = p.decode_datagram(p.encode_datagram(dg))
    assert out.records == []


def test_variable_value_counts():
    # e.g. pressure (1 value), rotation vector (up to 5), quaternion (4)
    dg = Datagram(
        device_id=2,
        records=[
            Record(6, 3, 1, 111, 3, [1013.25]),                       # pressure
            Record(11, 4, 1, 222, 3, [0.1, 0.2, 0.3, 0.9, 0.05]),     # rotation vector
        ],
    )
    out = p.decode_datagram(p.encode_datagram(dg))
    assert len(out.records[0].values) == 1
    assert len(out.records[1].values) == 5


def test_header_size_constants():
    assert p.HEADER_SIZE == 10
    assert p.REC_FIXED_SIZE == 20
    assert p.STAGE_SIZE == 16


def test_bad_magic():
    buf = bytearray(p.encode_datagram(_sample_datagram()))
    buf[0] = 0x00
    with pytest.raises(ProtocolError):
        p.decode_datagram(bytes(buf))


def test_truncated_buffer_raises():
    buf = p.encode_datagram(_sample_datagram())
    for cut in range(1, len(buf)):
        with pytest.raises(ProtocolError):
            p.decode_datagram(buf[:cut])


def test_trailing_garbage_raises():
    buf = p.encode_datagram(_sample_datagram()) + b"\x00\x01\x02"
    with pytest.raises(ProtocolError):
        p.decode_datagram(buf)


def test_too_many_values_raises():
    dg = Datagram(device_id=1, records=[Record(1, 0, 0, 0, 3, [0.0] * 256)])
    with pytest.raises(ProtocolError):
        p.encode_datagram(dg)


def test_golden_vector_matches():
    """The committed golden packet must decode to the canonical datagram and
    re-encode to identical bytes. The Kotlin unit test asserts the same bytes,
    guaranteeing cross-language parity.
    """
    golden = p.encode_datagram(_sample_datagram())
    if not os.path.exists(GOLDEN_PATH):
        # First run bootstraps the fixture; commit it to the repo afterwards.
        with open(GOLDEN_PATH, "wb") as fh:
            fh.write(golden)
    with open(GOLDEN_PATH, "rb") as fh:
        on_disk = fh.read()
    assert golden == on_disk, "encoder output drifted from committed golden_packet.bin"
    out = p.decode_datagram(on_disk)
    assert out.records[0].values == pytest.approx([0.12, -9.81, 0.42], rel=1e-6)


def test_golden_first_bytes_are_stable():
    """Explicit byte-level assertions so the Kotlin side has exact expected
    values to hard-code (magic, version, flags, device_id, count)."""
    buf = p.encode_datagram(_sample_datagram())
    magic, version, flags, device_id, count = struct.unpack_from("<HBBIH", buf, 0)
    assert magic == 0x5353
    assert version == 1
    assert flags == 0
    assert device_id == 0x0A0B0C0D
    assert count == 2
