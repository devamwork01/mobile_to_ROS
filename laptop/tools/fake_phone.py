#!/usr/bin/env python3
"""fake_phone.py -- a synthetic "phone" so you can test SensorStream without an Android device.

It speaks the same control + telemetry protocol as the real app: it opens the laptop's
control WebSocket, sends a `hello` with a small sensor catalog, learns the UDP telemetry
port from `hello_ack`, then streams wire-identical UDP datagrams for four sensors. The
dashboard shows it as a connected device with named sensors, a live 3D orientation that
slowly tumbles, and physically-consistent accelerometer / gyroscope / magnetometer values
-- so you can exercise the whole pipeline (receiver, sync, dashboard, recording, ROS sink)
with zero hardware.

Usage (from the ``laptop/`` directory, with the server already running):

    python -m sensorstream.app          # terminal 1: the receiver + dashboard
    python tools/fake_phone.py          # terminal 2: the synthetic phone
    # then open http://localhost:8080

    python tools/fake_phone.py --host 192.168.1.50 --hz 100 --duration 60
"""
from __future__ import annotations

import argparse
import asyncio
import json
import math
import socket
import sys
import time
import uuid
from pathlib import Path

# Allow running as a script (python tools/fake_phone.py) from anywhere: put laptop/ on the path.
LAPTOP_DIR = Path(__file__).resolve().parent.parent
if str(LAPTOP_DIR) not in sys.path:
    sys.path.insert(0, str(LAPTOP_DIR))

from sensorstream.protocol import Datagram, Record, encode_datagram  # noqa: E402

GRAVITY = 9.80665

# (handle, sensor_type, name, android_string_type, value_count) for the synthetic catalog.
# handle == type here purely for simplicity; the real app uses independent handles.
SENSORS = [
    (1, 1, "Synthetic Accelerometer", "android.sensor.accelerometer", 3),
    (4, 4, "Synthetic Gyroscope", "android.sensor.gyroscope", 3),
    (2, 2, "Synthetic Magnetometer", "android.sensor.magnetic_field", 3),
    (11, 11, "Synthetic Rotation Vector", "android.sensor.rotation_vector", 4),
]
MAG_WORLD = (22.0, 5.0, -42.0)  # a plausible geomagnetic field vector (uT)


def _quat_axis_angle(ax: float, ay: float, az: float, angle: float):
    n = math.sqrt(ax * ax + ay * ay + az * az) or 1.0
    s = math.sin(angle / 2.0)
    return (ax / n * s, ay / n * s, az / n * s, math.cos(angle / 2.0))


def _rot_matrix(q):
    """Rotation matrix R (device -> world) from quaternion (x, y, z, w)."""
    x, y, z, w = q
    return (
        (1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)),
        (2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)),
        (2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)),
    )


def _world_to_device(R, v):
    """Apply R^T (world -> device frame) to a world vector v."""
    return (
        R[0][0] * v[0] + R[1][0] * v[1] + R[2][0] * v[2],
        R[0][1] * v[0] + R[1][1] * v[1] + R[2][1] * v[2],
        R[0][2] * v[0] + R[1][2] * v[1] + R[2][2] * v[2],
    )


def sample(t: float) -> dict:
    """Physically-consistent synthetic sensor values at time ``t`` (seconds).

    The phone slowly tumbles about a fixed, slightly tilted axis. Accelerometer,
    magnetometer and gyroscope are all derived from that single orientation, so the 3D
    view and the numbers agree: the accelerometer reports gravity expressed in the device
    frame (|a| ~ 9.81), and the gyroscope reports the corresponding body-frame rate.
    Returned dict is keyed by sensor type -> list of values.
    """
    axis = (0.2, 1.0, 0.1)
    angle = 0.5 * t  # rad; ~0.5 rad/s sweep
    q = _quat_axis_angle(*axis, angle)
    R = _rot_matrix(q)
    accel = _world_to_device(R, (0.0, 0.0, GRAVITY))  # gravity is world +up at rest
    mag = _world_to_device(R, MAG_WORLD)
    n = math.sqrt(sum(c * c for c in axis)) or 1.0
    omega_world = tuple(0.5 * c / n for c in axis)  # matches the 0.5 rad/s sweep
    gyro = _world_to_device(R, omega_world)
    return {
        1: list(accel),
        4: list(gyro),
        2: list(mag),
        11: [q[0], q[1], q[2], q[3]],
    }


def _catalog() -> list:
    return [
        {"handle": h, "name": name, "type": ty, "stringType": st,
         "vendor": "SensorStream (synthetic)", "valueCount": vc, "units": ""}
        for (h, ty, name, st, vc) in SENSORS
    ]


async def stream(host: str, ws_port: int, hz: float, duration: float) -> None:
    import websockets  # lazy so the pure sample() logic imports without this dependency

    ws_url = f"ws://{host}:{ws_port}/phone"
    async with websockets.connect(ws_url) as ws:
        await ws.send(json.dumps({
            "type": "hello",
            "model": "Fake Phone (fake_phone.py)",
            "android": "synthetic",
            "app_version": "fake_phone",
            "client_id": str(uuid.uuid4()),
            "sensors": _catalog(),
        }))

        device_id = udp_port = None
        while device_id is None:
            msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=10.0))
            if msg.get("type") == "hello_ack":
                device_id = int(msg["device_id"])
                udp_port = int(msg["udp_port"])

        await ws.send(json.dumps({"type": "active", "handles": [h for h, *_ in SENSORS]}))
        print(f"fake_phone: connected as device 0x{device_id:08X}; streaming {len(SENSORS)} sensors "
              f"@ {hz:.0f} Hz to UDP {host}:{udp_port}  (Ctrl+C to stop)")

        # Drain control replies (heartbeat_ack, any resend requests) so the buffer stays small.
        async def _drain():
            try:
                async for _ in ws:
                    pass
            except Exception:
                pass
        drain = asyncio.create_task(_drain())

        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        addr = (host, udp_port)
        seqs = {h: 0 for h, *_ in SENSORS}
        period = 1.0 / hz
        t0 = time.perf_counter()
        next_t = t0
        last_hb = t0
        hb = 0
        try:
            while duration <= 0 or (time.perf_counter() - t0) < duration:
                now = time.perf_counter()
                vals = sample(now - t0)
                now_ns = time.monotonic_ns()
                for h, ty, *_ in SENSORS:
                    rec = Record(ty, h, seqs[h], now_ns, 3, vals[ty])
                    sock.sendto(encode_datagram(Datagram(device_id=device_id, records=[rec])), addr)
                    seqs[h] += 1
                if now - last_hb >= 1.0:  # ~1 Hz heartbeat keeps the control session healthy
                    last_hb = now
                    hb += 1
                    await ws.send(json.dumps({"type": "heartbeat", "seq": hb}))
                next_t += period
                delay = next_t - time.perf_counter()
                if delay > 0:
                    await asyncio.sleep(delay)
                else:
                    next_t = time.perf_counter()  # fell behind; resync without a burst
        finally:
            sock.close()
            drain.cancel()


def main() -> None:
    ap = argparse.ArgumentParser(description="Synthetic phone for testing SensorStream without hardware")
    ap.add_argument("--host", default="127.0.0.1", help="host running sensorstream.app (default: 127.0.0.1)")
    ap.add_argument("--ws-port", type=int, default=8081, help="control WebSocket port (default: 8081)")
    ap.add_argument("--hz", type=float, default=100.0, help="per-sensor sample rate (default: 100)")
    ap.add_argument("--duration", type=float, default=0.0, help="seconds to stream (0 = until Ctrl+C)")
    args = ap.parse_args()
    try:
        asyncio.run(stream(args.host, args.ws_port, args.hz, args.duration))
    except KeyboardInterrupt:
        print("\nfake_phone: stopped")


if __name__ == "__main__":
    main()
