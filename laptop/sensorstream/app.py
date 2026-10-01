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
from typing import Optional

from . import protocol as p
from . import recordings
from . import reports
from .backfill import GapTracker
from .control import ControlServer
from .reconcile import Reconciler
from .dashboard import DashboardServer
from .filters import FilterBank, suggest as suggest_filter
from .insights import InsightsSink
from .discovery import Advertiser, BEACON_PORT
from .logging_sink import Recorder
from .receiver import start_receiver
from .sinks import DashboardSink
from .sync import SyncTracker
from .testrun import TestRunManager

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


def handle_filter_command(msg: dict, bank, insights, broadcast, on_change=None) -> bool:
    """filter_set / filter_clear / filter_suggest from the dashboard. Returns False for other commands."""
    cmd = msg.get("cmd")
    key = str(msg.get("key") or "")
    handle = msg.get("handle")
    try:
        handle = int(handle) if handle is not None else None
    except (TypeError, ValueError):
        handle = None
    if cmd == "filter_set":
        ok, why = bank.set(key, msg.get("config"), handle=handle)
        # running: a refused change leaves the previous filter (if any) in place
        broadcast(bank.snapshot() if ok else {"kind": "filter_error", "key": key, "message": why,
                                               "running": key in bank.configs})
        if ok and on_change:
            on_change()  # e.g. push the new settings to the phone
        return True
    if cmd == "filter_clear":
        bank.clear(key)
        broadcast(bank.snapshot())
        if on_change:
            on_change()
        return True
    if cmd == "filter_suggest":
        src = insights.suggest(handle) if handle is not None else None
        if src is None:
            broadcast({"kind": "filter_error", "key": key, "message": "Not enough live data yet to suggest a filter.",
                       "running": key in bank.configs})
        else:
            f, psd, fs = src
            broadcast({"kind": "filter_suggestion", "key": key, "config": suggest_filter(f, psd, fs)})
        return True
    return False


async def insights_step(insights, testruns, broadcast) -> None:
    """One pass of the 1 Hz insights loop. Each part is guarded on its own: an error in the live
    statistics must not stop the test-run state machine (or vice versa) - a dead loop would leave
    a run stuck and Record / Test run disabled until the server restarts."""
    try:
        for m in insights.tick():
            broadcast(m)
    except Exception as exc:  # logged; the loop continues
        print(f"[insights] live stats failed: {exc!r}")
    try:
        await testruns.tick()
    except Exception as exc:
        print(f"[insights] test run tick failed: {exc!r}")


class AnalyseQueue:
    """Recording ids with an analysis in flight, so repeated clicks (or several browsers) on a
    long recording don't start the same multi-second analysis more than once."""

    def __init__(self) -> None:
        self._busy: set = set()

    def claim(self, rid: str) -> bool:
        if rid in self._busy:
            return False
        self._busy.add(rid)
        return True

    def release(self, rid: str) -> None:
        self._busy.discard(rid)


def make_api_get(log_dir: str):
    """Read-only HTTP API: recordings (ADR-001) and Insights reports. Filesystem-backed."""

    def api_get(path: str, query: dict):
        """Read-only recordings API (ADR-001). Filesystem-backed; safe off the event loop."""
        parts = [x for x in path.split("/") if x]  # e.g. ['api','recordings', <id>, 'signals', <handle>]
        if parts == ["api", "recordings"]:
            # Paged (newest first): ?limit=50&cursor=<next_cursor from the previous page>
            try:
                limit = int(query.get("limit", 50))
            except (TypeError, ValueError):
                limit = 50
            return 200, recordings.list_recordings_page(log_dir, limit, query.get("cursor"))
        if len(parts) == 5 and parts[:2] == ["api", "recordings"] and parts[3] == "signals":
            rec_id, handle = parts[2], parts[4]
            start = query.get("start")
            end = query.get("end")
            res = recordings.query_signal(
                log_dir, rec_id, int(handle),
                int(start) if start else None,
                int(end) if end else None,
                int(query.get("buckets", 1000)),
            )
            return (200, res) if res is not None else (404, {"error": "recording or signal not found"})
        if parts == ["api", "reports"]:
            return 200, {"items": reports.list_reports(log_dir)}
        if len(parts) == 3 and parts[:2] == ["api", "reports"]:
            rep = reports.load_report(log_dir, parts[2])
            return (200, rep) if rep is not None else (404, {"error": "report not found"})
        return 404, {"error": "unknown endpoint"}

    return api_get


async def run(args: argparse.Namespace) -> None:
    dash = DashboardServer(WEB_DIR, args.ws_host, args.ws_port, args.http_host, args.http_port)
    await dash.start()
    sink = DashboardSink(dash.broadcast, max_ui_hz=args.ui_hz)

    ros_sink = None
    if args.ros:
        try:
            from .ros_sink import Ros2Sink
            ros_sink = Ros2Sink()
        except Exception as exc:
            print(f"   (--ros requested but ROS unavailable, continuing without it: {exc})")

    recorder = Recorder()
    sync = SyncTracker()
    insights = InsightsSink()
    filterbank = FilterBank(args.filters_file)
    testruns: Optional[TestRunManager] = None  # created once the control channel exists (below)

    # Backfill (Phase 2B): detect per-(client,handle) seq gaps on the live stream and, after a grace
    # window, request the missing datagrams from the phone over the reliable control channel. Merged
    # datagrams are appended to the session recording (deduped on read). `control` is referenced late
    # by send_resend (assigned below, before any tick fires).
    tracker = GapTracker()

    def _append_backfill(dg, t_recv_ns):
        if recorder.is_recording:
            recorder.on_datagram(dg, t_recv_ns)  # backfill arrives late/out of order: never filtered

    reconciler = Reconciler(
        tracker,
        send_resend=lambda d, h, a, b: asyncio.create_task(control.send_resend(d, h, a, b)),
        on_backfilled=_append_backfill,
    )

    def on_dg(dg, addr, t_recv_ns):
        fv = filterbank.process(dg)  # once per raw sample, before any decimation
        sink.on_datagram(dg, addr, t_recv_ns, filtered=fv)
        if ros_sink is not None:
            ros_sink.on_datagram(dg, addr, t_recv_ns, filtered=fv)
        sync.observe(dg, t_recv_ns)
        insights.on_datagram(dg, addr, t_recv_ns, filtered=fv)
        if testruns is not None:  # UDP can arrive before the run manager is wired
            testruns.on_datagram(dg)
        reconciler.on_live(dg.device_id, dg, t_recv_ns)
        if recorder.is_recording:
            recorder.on_datagram(dg, t_recv_ns, filtered=fv)

    transport, proto = await start_receiver(args.udp_host, args.udp_port, on_dg)
    udp_port = transport.get_extra_info("socket").getsockname()[1]

    # Control channel: phones connect to ws://host:ws_port/phone. Backfill replies and hellos are
    # routed to the reconciler; everything else is forwarded to the browser.
    def on_control_event(ev: dict) -> None:
        kind = ev.get("kind")
        if kind == "phone_connected":
            reconciler.set_device_client(ev["device_id"], ev.get("client_id", ""))
            filterbank.set_catalog(ev.get("sensors") or [])
            push_filters()  # the phone filters its own preview with the laptop's settings
        elif kind == "backfill":
            reconciler.on_backfill(ev["device_id"], ev["handle"], ev["frames"])
            return   # raw frames are not browser-bound
        elif kind == "backfill_unavailable":
            reconciler.on_unavailable(ev["device_id"], ev["handle"], ev["from"], ev["to"])
            return
        dash.broadcast(ev)

    control = ControlServer(udp_port, on_event=on_control_event)

    def push_filters() -> None:
        asyncio.get_running_loop().create_task(control.send_filters(filterbank.configs))
    dash.control_handler = control.handle

    def record_meta() -> Optional[dict]:
        """Device info + sensor catalog for the recording's .meta.json (handle -> name mapping)."""
        for s in control.sessions.values():
            return {"model": s.model, "android": s.android, "app_version": s.app_version,
                    "device_id": s.device_id, "sensors": s.catalog}
        return None

    def streaming() -> bool:
        return any(s.active for s in control.sessions.values())

    testruns = TestRunManager(recorder, args.log_dir, record_meta, streaming, dash.broadcast)

    rec_base = recorder.start(args.log_dir, record_meta()) if args.record else None

    def ui_command(msg: dict) -> None:
        if handle_filter_command(msg, filterbank, insights, dash.broadcast, push_filters):
            return
        cmd = msg.get("cmd")
        if cmd == "record_start" and not recorder.is_recording and not testruns.active:
            print("[rec] start:", recorder.start(args.log_dir, record_meta()) + ".ssbin")
        elif cmd == "record_stop" and recorder.is_recording and not testruns.active:
            print("[rec] stop:", recorder.stop())
        elif cmd == "testrun_start":
            try:
                seconds = float(msg.get("seconds") or 0)
                handles = [int(h) for h in (msg.get("handles") or [])]
            except (TypeError, ValueError):
                seconds, handles = 0.0, []
            ok, why = testruns.start(str(msg.get("preset", "still")), seconds, handles)
            if not ok:
                dash.broadcast({"kind": "testrun", "phase": "refused", "message": why})
        elif cmd == "testrun_stop":
            testruns.stop_early()
        elif cmd == "psd_subscribe":
            try:
                insights.subscribe_psd([int(h) for h in (msg.get("handles") or [])])
            except (TypeError, ValueError):
                pass
            return
        elif cmd == "analyse":
            rid = str(msg.get("id", ""))
            if reports.valid_id(rid) and analyses.claim(rid):
                asyncio.get_running_loop().create_task(_analyse(rid))
            return
        dash.broadcast({"kind": "recording", "active": recorder.is_recording, "rows": recorder.rows})

    analyses = AnalyseQueue()

    async def _analyse(rid: str) -> None:
        path = os.path.join(args.log_dir, rid + ".ssbin")
        try:
            await asyncio.to_thread(reports.analyse_recording, path, "capture")
            dash.broadcast({"kind": "report_ready", "id": rid})
        except Exception as exc:  # unreadable/missing recording: tell the browser, keep serving
            dash.broadcast({"kind": "report_error", "id": rid, "message": str(exc)})
        finally:
            analyses.release(rid)

    dash.on_ui_command = ui_command

    dash.on_api_get = make_api_get(args.log_dir)

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
        msgs.append(filterbank.snapshot())
        run_status = testruns.status()
        if run_status is not None:
            msgs.append(run_status)
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
    if ros_sink is not None:
        print("   ROS       : publishing /phone/accelerometer, /phone/gyroscope, /phone/magnetic_field, /phone/orientation")
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
                    "ui_hz": args.ui_hz,
                    "ui_records_in": sink.records_in,
                    "ui_records_out": sink.records_out,
                    "backfilled": reconciler.stats()["backfilled"],
                    "permanent_gaps": reconciler.stats()["permanent"],
                }
            )
            dash.broadcast(sync.snapshot())

    async def beacon_task() -> None:
        while True:
            if advertiser is not None:
                advertiser.send_beacon()
            await asyncio.sleep(1.0)

    async def reconcile_task() -> None:
        # Tick often enough to request gaps promptly once past the grace window; ACK periodically
        # so the phone can prune what the laptop has durably received.
        n = 0
        while True:
            await asyncio.sleep(0.25)
            await reconciler.tick(time.monotonic_ns())
            n += 1
            if n % 8 == 0:  # ~every 2s
                for dev, upto in reconciler.acks():
                    await control.send_backfill_ack(dev, upto)

    async def insights_task() -> None:
        while True:
            await asyncio.sleep(1.0)
            await insights_step(insights, testruns, dash.broadcast)
            for key, message in filterbank.errors():
                dash.broadcast({"kind": "filter_error", "key": key, "message": message, "running": False})

    tasks = [asyncio.create_task(stats_task()), asyncio.create_task(reconcile_task()),
             asyncio.create_task(insights_task())]
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
        if ros_sink is not None:
            ros_sink.close()
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
    ap.add_argument("--ui-hz", type=float, default=60.0, help="max per-sensor update rate to the browser (raw logging is always full-rate)")
    ap.add_argument("--selftest", action="store_true", help="emit a synthetic stream (no phone needed)")
    ap.add_argument("--selftest-hz", type=float, default=100.0)
    ap.add_argument("--log-dir", default="./recordings", help="directory for recordings")
    ap.add_argument("--filters-file", default="./filters.json", help="where per-sensor filter settings are saved")
    ap.add_argument("--record", action="store_true", help="record the session from start")
    ap.add_argument("--no-discovery", action="store_true", help="disable mDNS + UDP beacon advertising")
    ap.add_argument("--ros", action="store_true", help="publish decoded sensor data to ROS 2 topics (requires a sourced ROS environment)")
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
