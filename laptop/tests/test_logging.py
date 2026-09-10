"""Record -> read-back (binary lossless + CSV) and replay-over-UDP tests."""

from __future__ import annotations

import socket

import pytest

from sensorstream import protocol as p
from sensorstream.logging_sink import CSV_HEADER, Recorder, read_frames
from sensorstream.replay import replay


def _datagrams():
    return [
        p.Datagram(device_id=7, records=[
            p.Record(1, 0, 0, 1000, 3, [0.1, 9.8, 0.2]),
            p.Record(4, 1, 0, 1000, 3, [0.01, 0.02, -0.03]),
        ]),
        p.Datagram(device_id=7, records=[p.Record(1, 0, 1, 2000, 3, [0.11, 9.79, 0.19])]),
        p.Datagram(device_id=7, records=[p.Record(6, 2, 0, 3000, 3, [1013.25])]),
    ]


def test_record_binary_lossless(tmp_path):
    rec = Recorder()
    base = rec.start(str(tmp_path))
    for i, dg in enumerate(_datagrams()):
        rec.on_datagram(dg, t_recv_ns=100 + i * 10)
    assert rec.is_recording
    assert rec.frames == 3 and rec.rows == 4
    rec.stop()
    assert not rec.is_recording

    frames = list(read_frames(base + ".ssbin"))
    assert [t for t, _ in frames] == [100, 110, 120]
    out0 = p.decode_datagram(frames[0][1])
    assert out0.device_id == 7
    assert len(out0.records) == 2
    assert out0.records[1].values == pytest.approx([0.01, 0.02, -0.03], rel=1e-6)
    assert out0.records[0].t_sensor_ns == 1000
    out2 = p.decode_datagram(frames[2][1])
    assert out2.records[0].sensor_type == 6
    assert out2.records[0].values == pytest.approx([1013.25], rel=1e-6)


def test_record_csv(tmp_path):
    rec = Recorder()
    base = rec.start(str(tmp_path))
    for i, dg in enumerate(_datagrams()):
        rec.on_datagram(dg, t_recv_ns=100 + i * 10)
    rec.stop()

    lines = open(base + ".csv", encoding="utf-8").read().splitlines()
    assert lines[0] == CSV_HEADER.strip()
    assert len(lines) == 1 + 4  # header + 4 records
    cols = lines[1].split(",")
    assert cols[1] == "1" and cols[2] == "0" and cols[3] == "0" and cols[4] == "3"
    assert cols[8] == "" and cols[10] == ""  # v3..v5 blank for a 3-value record


def test_replay_over_udp(tmp_path):
    rec = Recorder()
    base = rec.start(str(tmp_path))
    for i, dg in enumerate(_datagrams()):
        rec.on_datagram(dg, t_recv_ns=100 + i * 1_000_000)
    rec.stop()

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    s.settimeout(2.0)
    port = s.getsockname()[1]
    try:
        n = replay(base + ".ssbin", "127.0.0.1", port, speed=1000.0)
        assert n == 3
        got = [p.decode_datagram(s.recvfrom(65535)[0]) for _ in range(3)]
    finally:
        s.close()
    assert got[0].device_id == 7
    assert got[2].records[0].sensor_type == 6
