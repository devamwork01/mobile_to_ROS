"""Loopback test for the UDP receiver: send real datagrams to a bound socket and
assert they decode and surface through the callback. No pytest-asyncio needed —
we drive the loop with asyncio.run.
"""

from __future__ import annotations

import asyncio
import socket

from sensorstream import protocol as p
from sensorstream.receiver import start_receiver


def _send(port: int, dg: p.Datagram) -> None:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.sendto(p.encode_datagram(dg), ("127.0.0.1", port))
    finally:
        s.close()


def test_receiver_loopback_delivers_records():
    async def run():
        got = []
        ev = asyncio.Event()

        def on_dg(dg, addr, t_recv_ns):
            got.append((dg, t_recv_ns))
            ev.set()

        transport, proto = await start_receiver("127.0.0.1", 0, on_dg)
        port = transport.get_extra_info("socket").getsockname()[1]
        try:
            _send(port, p.Datagram(device_id=42, records=[p.Record(1, 0, 0, 111, 3, [0.1, 9.8, 0.2])]))
            await asyncio.wait_for(ev.wait(), timeout=3.0)
        finally:
            transport.close()
        return got, proto

    got, proto = asyncio.run(run())
    assert len(got) == 1
    dg, t_recv_ns = got[0]
    assert dg.device_id == 42
    assert dg.records[0].values[1] == 0.2 or abs(dg.records[0].values[1] - 9.8) < 1e-6
    assert t_recv_ns > 0
    assert proto.packets == 1 and proto.records == 1 and proto.decode_errors == 0


def test_receiver_counts_decode_errors():
    async def run():
        errs = []
        ev = asyncio.Event()
        transport, proto = await start_receiver(
            "127.0.0.1", 0, lambda *_: None, on_error=lambda e, a, d: (errs.append(e), ev.set())
        )
        port = transport.get_extra_info("socket").getsockname()[1]
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.sendto(b"\x00\x00garbage", ("127.0.0.1", port))  # bad magic
            s.close()
            await asyncio.wait_for(ev.wait(), timeout=3.0)
        finally:
            transport.close()
        return proto

    proto = asyncio.run(run())
    assert proto.decode_errors == 1
