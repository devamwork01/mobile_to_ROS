"""--selftest generator: paced at the requested rate with evenly spaced sensor timestamps.

On Windows the monotonic clock ticks every ~15.6 ms and asyncio treats shorter sleeps as already
due, so a naive `sleep(1/hz)` loop spun (~13 000 samples/s) with many samples sharing a timestamp.
"""

import asyncio
import socket

import numpy as np

from sensorstream import protocol as p
from sensorstream.app import _selftest_generator


async def _collect(seconds: float, hz: float):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))
    sock.setblocking(False)
    task = asyncio.create_task(_selftest_generator(sock.getsockname()[1], hz))
    await asyncio.sleep(seconds)
    task.cancel()
    out = []
    while True:
        try:
            out.append(p.decode_datagram(sock.recv(65535)))
        except BlockingIOError:
            break
    sock.close()
    return out


def test_selftest_runs_at_the_requested_rate_with_even_timestamps():
    dgs = asyncio.run(_collect(1.0, 100.0))
    t = np.array([r.t_sensor_ns for dg in dgs for r in dg.records if r.sensor_handle == 0])
    assert 70 <= t.size <= 130                       # ~100 in 1 s (not thousands)
    assert set(np.diff(t).tolist()) == {10_000_000}  # exactly one period apart
