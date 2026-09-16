#!/usr/bin/env python3
"""SensorStream soak test — long-run stability of the laptop pipeline.

Self-contained: launches the server on isolated ports, blasts a realistic
multi-sensor UDP load (reusing sensorstream.protocol so it is wire-identical to
the phone), subscribes to the dashboard WS for live stats + latency, samples the
server process RSS/CPU, and after N minutes writes a CSV time-series plus a
PASS/FAIL summary against stability thresholds.

Usage:
    python tools/soak.py --minutes 30 --hz 100
    python tools/soak.py --minutes 2            # quick smoke

Run from the ``laptop/`` directory (same place you run ``python -m sensorstream.app``).
"""
from __future__ import annotations

import argparse
import asyncio
import csv
import json
import math
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import websockets

# Allow running as a script (python tools/soak.py) from anywhere: put laptop/ on the path.
LAPTOP_DIR = Path(__file__).resolve().parent.parent
if str(LAPTOP_DIR) not in sys.path:
    sys.path.insert(0, str(LAPTOP_DIR))

from sensorstream.protocol import Datagram, Record, encode_datagram

try:
    import psutil
except ImportError:  # pragma: no cover - guarded at runtime
    psutil = None

# (sensor_type, handle, value_count) — a typical IMU + orientation load.
SENSORS = [
    (1, 1, 3),    # accelerometer
    (4, 4, 3),    # gyroscope
    (2, 2, 3),    # magnetic field
    (11, 11, 4),  # rotation vector
]
DEVICE_ID = 0x51AC_AB01


# ---------------------------------------------------------------------------
# Load generator
# ---------------------------------------------------------------------------
async def load_generator(udp_port: int, hz: float, stop: asyncio.Event, counters: dict) -> None:
    """Send one datagram per sensor each tick at ``hz`` Hz (pps = hz * len(SENSORS))."""
    import socket

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    addr = ("127.0.0.1", udp_port)
    seqs = {h: 0 for _, h, _ in SENSORS}
    period = 1.0 / hz
    next_t = time.perf_counter()
    t0 = time.perf_counter()
    try:
        while not stop.is_set():
            now_ns = time.monotonic_ns()
            phase = time.perf_counter() - t0
            for stype, handle, n in SENSORS:
                vals = [math.sin(phase + i) for i in range(n)]
                rec = Record(stype, handle, seqs[handle], now_ns, 3, vals)
                sock.sendto(encode_datagram(Datagram(device_id=DEVICE_ID, records=[rec])), addr)
                seqs[handle] += 1
                counters["sent"] += 1
            next_t += period
            delay = next_t - time.perf_counter()
            if delay > 0:
                await asyncio.sleep(delay)
            else:
                next_t = time.perf_counter()  # fell behind; resync without a burst
    finally:
        sock.close()


# ---------------------------------------------------------------------------
# Stats collector (dashboard WS client)
# ---------------------------------------------------------------------------
async def collector(ws_url: str, stop: asyncio.Event, latest: dict) -> None:
    async with websockets.connect(ws_url, max_queue=64) as ws:
        while not stop.is_set():
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=1.0)
            except asyncio.TimeoutError:
                continue
            except websockets.ConnectionClosed:
                break
            try:
                msg = json.loads(raw)
            except (ValueError, TypeError):
                continue
            kind = msg.get("kind")
            if kind == "stats":
                latest["stats"] = msg
            elif kind == "debug":  # sync.snapshot()
                latest["sync"] = msg


# ---------------------------------------------------------------------------
# Readiness
# ---------------------------------------------------------------------------
async def wait_ready(ws_url: str, proc: subprocess.Popen, timeout: float = 20.0) -> None:
    deadline = time.monotonic() + timeout
    last_err: Exception | None = None
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"server exited during startup (code {proc.returncode})")
        try:
            async with websockets.connect(ws_url):
                return
        except Exception as e:  # noqa: BLE001 - retry until ready
            last_err = e
            await asyncio.sleep(0.5)
    raise RuntimeError(f"server WS not ready within {timeout}s: {last_err}")


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------
def analyze(rows: list[dict], target_pps: float, minutes: float, had_psutil: bool) -> tuple[bool, list[str]]:
    lines: list[str] = []
    ok = True

    def check(name: str, passed: bool | None, detail: str) -> None:
        nonlocal ok
        if passed is None:
            mark = "SKIP"
        elif passed:
            mark = "PASS"
        else:
            mark = "FAIL"
            ok = False
        lines.append(f"  [{mark}] {name}: {detail}")

    if not rows:
        return False, ["  [FAIL] no samples collected"]

    warm = [r for r in rows if r["elapsed"] >= min(30.0, minutes * 60 * 0.2)]
    if not warm:
        warm = rows

    last = rows[-1]

    # Correctness counters (cumulative)
    check("decode errors", last["decode_errors"] == 0, f"{last['decode_errors']}")
    check("permanent gaps", last["permanent_gaps"] == 0, f"{last['permanent_gaps']}")

    # Throughput stability
    pps_vals = [r["pps"] for r in warm if r["pps"] > 0]
    if pps_vals:
        mean_pps = sum(pps_vals) / len(pps_vals)
        drift = abs(mean_pps - target_pps) / target_pps
        check("throughput stable", drift <= 0.10,
              f"mean {mean_pps:.0f} pps vs target {target_pps:.0f} ({drift*100:.1f}% off)")
    else:
        check("throughput stable", False, "no packets observed")

    # Memory leak: first-window vs last-window mean RSS
    if had_psutil:
        win = max(1, len(warm) // 5)
        first = warm[:win]
        lastw = warm[-win:]
        rss0 = sum(r["rss_mb"] for r in first) / len(first)
        rss1 = sum(r["rss_mb"] for r in lastw) / len(lastw)
        growth = rss1 - rss0
        pct = (growth / rss0 * 100) if rss0 else 0.0
        leak_ok = growth < 50.0 and pct < 15.0
        check("no memory leak", leak_ok,
              f"RSS {rss0:.1f} -> {rss1:.1f} MB (+{growth:.1f} MB, {pct:+.1f}%)")
        peak = max(r["rss_mb"] for r in rows)
        lines.append(f"  [info] peak RSS: {peak:.1f} MB")
    else:
        check("no memory leak", None, "psutil not installed")

    # Recording grew (records were persisted) and stayed bounded
    rec_first = rows[0]["rec_rows"]
    rec_last = last["rec_rows"]
    check("recording active", rec_last > rec_first, f"rows {rec_first} -> {rec_last}")

    # Latency sanity (near-zero on loopback, but must not blow up)
    lat = [r["lat_p95"] for r in warm if r["lat_p95"] is not None]
    if lat:
        maxp95 = max(lat)
        check("latency bounded", maxp95 < 100.0, f"max p95 {maxp95:.2f} ms")

    lines.append(f"  [info] samples: {len(rows)} | duration: {last['elapsed']:.0f}s | "
                 f"packets: {last['packets']} | records: {last['records']}")
    return ok, lines


async def run(args: argparse.Namespace) -> int:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    csv_path = out / f"soak-{stamp}.csv"
    log_path = out / f"soak-{stamp}.server.log"
    rec_dir = out / "recordings"

    ws_url = f"ws://127.0.0.1:{args.ws_port}/ui"
    target_pps = args.hz * len(SENSORS)

    cmd = [
        sys.executable, "-m", "sensorstream.app",
        "--record", "--no-discovery",
        "--http-port", str(args.http_port),
        "--ws-port", str(args.ws_port),
        "--udp-port", str(args.udp_port),
        "--log-dir", str(rec_dir),
    ]
    print(f"soak: launching server -> {' '.join(cmd)}")
    log_f = open(log_path, "w", encoding="utf-8")
    creationflags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    proc = subprocess.Popen(cmd, stdout=log_f, stderr=subprocess.STDOUT,
                            creationflags=creationflags, cwd=str(LAPTOP_DIR))

    stop = asyncio.Event()
    latest: dict = {}
    counters = {"sent": 0}
    rows: list[dict] = []
    had_psutil = psutil is not None
    if not had_psutil:
        print("soak: WARNING psutil not installed - memory-leak check will be skipped "
              "(pip install psutil)")

    try:
        await wait_ready(ws_url, proc)
        print(f"soak: server ready. streaming {target_pps:.0f} pps for {args.minutes} min "
              f"({len(SENSORS)} sensors @ {args.hz} Hz). CSV -> {csv_path}")

        ps_proc = psutil.Process(proc.pid) if had_psutil else None
        if ps_proc is not None:
            ps_proc.cpu_percent(None)  # prime

        tasks = [
            asyncio.create_task(load_generator(args.udp_port, args.hz, stop, counters)),
            asyncio.create_task(collector(ws_url, stop, latest)),
        ]

        t0 = time.monotonic()
        end = t0 + args.minutes * 60
        next_sample = t0
        while time.monotonic() < end:
            if proc.poll() is not None:
                raise RuntimeError(f"server crashed mid-run (exit {proc.returncode}) - see {log_path}")
            await asyncio.sleep(max(0.0, next_sample - time.monotonic()))
            next_sample += args.sample_interval
            elapsed = time.monotonic() - t0

            rss_mb = cpu = 0.0
            if ps_proc is not None:
                try:
                    rss = ps_proc.memory_info().rss
                    for ch in ps_proc.children(recursive=True):
                        rss += ch.memory_info().rss
                    rss_mb = rss / (1024 * 1024)
                    cpu = ps_proc.cpu_percent(None)
                except psutil.Error:
                    pass

            st = latest.get("stats", {})
            sy = latest.get("sync", {})
            row = {
                "elapsed": round(elapsed, 1),
                "rss_mb": round(rss_mb, 1),
                "cpu": round(cpu, 1),
                "sent": counters["sent"],
                "pps": st.get("pps", 0),
                "packets": st.get("packets", 0),
                "records": st.get("records", 0),
                "rec_rows": st.get("rec_rows", 0),
                "decode_errors": st.get("decode_errors", 0),
                "backfilled": st.get("backfilled", 0),
                "permanent_gaps": st.get("permanent_gaps", 0),
                "lat_p50": sy.get("latency_ms_p50"),
                "lat_p95": sy.get("latency_ms_p95"),
                "jitter": sy.get("jitter_ms"),
            }
            rows.append(row)
            if len(rows) % 10 == 0 or elapsed < args.sample_interval * 2:
                print(f"  t={row['elapsed']:6.0f}s rss={row['rss_mb']:6.1f}MB cpu={row['cpu']:4.0f}% "
                      f"pps={row['pps']:4d} rec={row['rec_rows']:>8} "
                      f"lat_p95={row['lat_p95']} decErr={row['decode_errors']} gaps={row['permanent_gaps']}")

        stop.set()
        await asyncio.gather(*tasks, return_exceptions=True)
    finally:
        stop.set()
        _shutdown_server(proc)
        log_f.close()

    # Write CSV
    if rows:
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)

    ok, lines = analyze(rows, target_pps, args.minutes, had_psutil)
    print("\n" + "=" * 60)
    print(f"SOAK {'PASS' if ok else 'FAIL'} - {args.minutes} min @ {target_pps:.0f} pps target")
    print("=" * 60)
    for ln in lines:
        print(ln)
    print(f"\n  CSV:        {csv_path}")
    print(f"  server log: {log_path}")
    return 0 if ok else 1


def _shutdown_server(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    print("soak: stopping server...")
    try:
        if os.name == "nt":
            proc.send_signal(signal.CTRL_BREAK_EVENT)
        else:
            proc.send_signal(signal.SIGINT)
        proc.wait(timeout=10)
    except (subprocess.TimeoutExpired, OSError):
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


def main() -> int:
    ap = argparse.ArgumentParser(description="SensorStream pipeline soak test")
    ap.add_argument("--minutes", type=float, default=30.0, help="soak duration in minutes")
    ap.add_argument("--hz", type=float, default=100.0, help="per-sensor datagram rate")
    ap.add_argument("--sample-interval", type=float, default=2.0, help="metric sampling period (s)")
    ap.add_argument("--http-port", type=int, default=8090)
    ap.add_argument("--ws-port", type=int, default=8091)
    ap.add_argument("--udp-port", type=int, default=5015)
    ap.add_argument("--out", default="soak-out", help="output directory (CSV + server log)")
    args = ap.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        print("\nsoak: interrupted")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
