"""Entry point: wire the UDP receiver to the dashboard and run until Ctrl+C.

    python -m sensorstream.app                 # normal: wait for a phone
    python -m sensorstream.app --selftest      # emit a synthetic stream (no phone needed)

The ``--selftest`` generator sends a slowly rotating simulated gravity vector to
the receiver's own UDP port, exercising the full decode -> sink -> WebSocket ->
browser path so the dashboard can be verified without a device.
"""

from __future__ import annotations

import argparse
import asyncio
import math
import os
import socket
import sys
import time

from . import protocol as p
from .control import ControlServer
from .dashboard import DashboardServer
from .discovery import Advertiser, BEACON_PORT
from .logging_sink import Recorder
from .receiver import start_receiver
from .sinks import DashboardSink
from .sync import SyncTracker

_HERE = os.path.dirname(__file__)
_REACT_DIST = os.path.abspath(os.path.join(_HERE, "..", "webapp", "dist"))
_LEGACY_WEB = os.path.abspath(os.path.join(_HERE, "..", "web"))
# Serve the built React dashboard when present; otherwise the legacy static one.
WEB_DIR = _REACT_DIST if os.path.isdir(_REACT_DIST) else _LEGACY_WEB
SELFTEST_DEVICE_ID = 0x53454C46  # "SELF"


def _local_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    ips.discard("127.0.0.1")
    return sorted(ips)


async def _selftest_generator(udp_port: int, hz: float) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    dest = ("127.0.0.1", udp_port)
    period = 1.0 / hz
    seqs = [0, 0, 0, 0]  # accel, gyro, mag, rotation-vector
    t0 = time.monotonic()
    try:
        while True:
            t = time.monotonic() - t0
            yaw = t * 0.6
            pitch = 0.35 * math.sin(t * 0.5)
            cy, sy = math.cos(yaw / 2), math.sin(yaw / 2)
            cp, sp = math.cos(pitch / 2), math.sin(pitch / 2)
            qx, qy, qz, qw = cy * sp, sy * sp, sy * cp, cy * cp  # rotation vector
            ax, ay, az = 9.81 * math.sin(pitch), 0.6 * math.sin(t * 3), 9.81 * math.cos(pitch)
            gx, gy, gz = 0.15 * math.sin(t * 2), 0.35 * math.cos(t * 0.5), 0.6
            mx, my, mz = 28 * math.cos(yaw), 28 * math.sin(yaw), -40 + 3 * math.sin(t)
            tn = time.monotonic_ns()

            def rec(handle, stype, vals):
                r = p.Record(stype, handle, seqs[handle], tn, 3, vals, t_acquire_ns=tn, t_serialize_ns=tn + 250_000)
                seqs[handle] += 1
                return r

            dg = p.Datagram(
                device_id=SELFTEST_DEVICE_ID,
                flags=p.FLAG_STAGE_TS,
                records=[
                    rec(0, 1, [ax, ay, az]),     # Acceleration
                    rec(1, 4, [gx, gy, gz]),     # Angular Velocity
                    rec(2, 2, [mx, my, mz]),     # Magnetic Field
                    rec(3, 11, [qx, qy, qz, qw]),  # Orientation (rotation vector)
                ],
            )
            sock.sendto(p.encode_datagram(dg), dest)
            await asyncio.sleep(period)
    finally:
        sock.close()


async def run(args: argparse.Namespace) -> None:
    dash = DashboardServer(WEB_DIR, args.ws_host, args.ws_port, args.http_host, args.http_port)
    await dash.start()
    sink = DashboardSink(dash.broadcast, max_ui_hz=args.ui_hz)

    recorder = Recorder()
    sync = SyncTracker()

    def on_dg(dg, addr, t_recv_ns):
        sink.on_datagram(dg, addr, t_recv_ns)
        sync.observe(dg, t_recv_ns)
        if recorder.is_recording:
            recorder.on_datagram(dg, t_recv_ns)

    transport, proto = await start_receiver(args.udp_host, args.udp_port, on_dg)
    udp_port = transport.get_extra_info("socket").getsockname()[1]

    # Control channel: phones connect to ws://host:ws_port/phone
    control = ControlServer(udp_port, on_event=dash.broadcast)
    dash.control_handler = control.handle
    rec_base = recorder.start(args.log_dir) if args.record else None

    def ui_command(msg: dict) -> None:
        cmd = msg.get("cmd")
        if cmd == "record_start" and not recorder.is_recording:
            print("[rec] start:", recorder.start(args.log_dir) + ".ssbin")
        elif cmd == "record_stop" and recorder.is_recording:
            print("[rec] stop:", recorder.stop())
        dash.broadcast({"kind": "recording", "active": recorder.is_recording, "rows": recorder.rows})

    dash.on_ui_command = ui_command

    def ui_snapshot() -> list:
        """Current state replayed to a browser that connects after the phone did."""
        msgs: list = []
        for s in control.sessions.values():
            msgs.append({
                "kind": "phone_connected",
                "device_id": s.device_id,
                "addr": s.addr,
                "model": s.model,
                "android": s.android,
                "app_version": s.app_version,
                "sensors": s.catalog,
            })
            if s.active:
                msgs.append({"kind": "active_set", "device_id": s.device_id, "handles": s.active})
        msgs.append({"kind": "recording", "active": recorder.is_recording, "rows": recorder.rows})
        return msgs

    dash.on_ui_connect = ui_snapshot

    advertiser = None
    if not args.no_discovery:
        adv_ips = _local_ips()
        advertiser = Advertiser(
            adv_ips[0] if adv_ips else "", dash.ws_port, udp_port,
            beacon_port=args.beacon_port, broadcast_addr=args.beacon_addr,
        )
        try:
            advertiser.start_mdns()
        except Exception as exc:  # mDNS may be blocked; the UDP beacon still runs
            print(f"   (mDNS advertise unavailable, beacon still on: {exc})")

    ips = _local_ips()
    bar = "=" * 64
    print(bar)
    print(" Sensor Stream - laptop receiver")
    print(f"   dashboard : http://localhost:{dash.http_port}     (open in a browser)")
    print(f"   control   : ws  :{dash.ws_port}      telemetry : UDP :{udp_port} (auto)")
    print("   -- On the phone, enter Laptop IP + Ctrl port --")
    for ip in ips or ["<this PC's Wi-Fi IP>"]:
        print(f"        Laptop IP  {ip}      Ctrl port  {dash.ws_port}")
    print("   (the phone learns the UDP port from the control channel - don't type it)")
    if advertiser is not None:
        print(f"   discovery : mDNS + UDP beacon :{args.beacon_port}   (phone can auto-find this PC)")
    if args.selftest:
        print("   MODE      : SELF-TEST (synthetic accelerometer)")
    if args.record:
        print(f"   recording : {rec_base}.ssbin (+ .csv)")
    print("   Ctrl+C to stop.")
    print(bar)

    async def stats_task() -> None:
        last_p = last_b = 0
        while True:
            await asyncio.sleep(0.5)
            dp, db = proto.packets - last_p, proto.bytes - last_b
            last_p, last_b = proto.packets, proto.bytes
            dash.broadcast(
                {
                    "kind": "stats",
                    "pps": dp * 2,
                    "bps": db * 2,
                    "packets": proto.packets,
                    "records": proto.records,
                    "decode_errors": proto.decode_errors,
                    "clients": len(dash.clients),
                    "phones": len(control.sessions),
                    "recording": recorder.is_recording,
                    "rec_rows": recorder.rows,
                }
            )
            dash.broadcast(sync.snapshot())

    async def beacon_task() -> None:
        while True:
            if advertiser is not None:
                advertiser.send_beacon()
            await asyncio.sleep(1.0)

    tasks = [asyncio.create_task(stats_task())]
    if advertiser is not None:
        tasks.append(asyncio.create_task(beacon_task()))
    if args.selftest:
        tasks.append(asyncio.create_task(_selftest_generator(udp_port, args.selftest_hz)))

    try:
        await asyncio.gather(*tasks)
    finally:
        for t in tasks:
            t.cancel()
        recorder.stop()
        if advertiser is not None:
            advertiser.stop()
        transport.close()
        await dash.stop()


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description="Laptop-side sensor telemetry receiver + dashboard")
    ap.add_argument("--udp-host", default="0.0.0.0", help="telemetry bind address")
    ap.add_argument("--udp-port", type=int, default=5005, help="telemetry UDP port")
    ap.add_argument("--http-host", default="0.0.0.0")
    ap.add_argument("--http-port", type=int, default=8080)
    ap.add_argument("--ws-host", default="0.0.0.0")
    ap.add_argument("--ws-port", type=int, default=8081)
    ap.add_argument("--ui-hz", type=float, default=30.0, help="max per-sensor update rate to the browser")
    ap.add_argument("--selftest", action="store_true", help="emit a synthetic stream (no phone needed)")
    ap.add_argument("--selftest-hz", type=float, default=100.0)
    ap.add_argument("--log-dir", default="./recordings", help="directory for recordings")
    ap.add_argument("--record", action="store_true", help="record the session from start")
    ap.add_argument("--no-discovery", action="store_true", help="disable mDNS + UDP beacon advertising")
    ap.add_argument("--beacon-port", type=int, default=BEACON_PORT, help="UDP discovery beacon port")
    ap.add_argument("--beacon-addr", default="255.255.255.255", help="UDP beacon destination (broadcast)")
    return ap


def main() -> None:
    # Never let a stray non-ASCII byte crash console output on Windows (cp1252).
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    args = build_parser().parse_args()
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        print("\nstopped.")


if __name__ == "__main__":
    main()
