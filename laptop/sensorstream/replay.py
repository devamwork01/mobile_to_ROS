"""Replay a recorded ``.ssbin`` session back into a running receiver over UDP,
reproducing the original timing. Because it re-sends the exact datagrams, the
whole live pipeline (decode -> sync -> dashboard: values, 3D, graphs) replays
identically.

    python -m sensorstream.replay recordings/session_*.ssbin
    python -m sensorstream.replay recordings/session_*.ssbin --speed 2.0
"""

from __future__ import annotations

import argparse
import socket
import time

from .logging_sink import read_frames


def replay(path: str, host: str, port: int, speed: float = 1.0) -> int:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    dest = (host, port)
    prev_t = None
    sent = 0
    try:
        for t_recv_ns, data in read_frames(path):
            if prev_t is not None and speed > 0:
                dt = (t_recv_ns - prev_t) / 1e9 / speed
                if dt > 0:
                    time.sleep(dt)
            sock.sendto(data, dest)
            prev_t = t_recv_ns
            sent += 1
    finally:
        sock.close()
    return sent


def main() -> None:
    ap = argparse.ArgumentParser(description="Replay a recorded session into a running receiver")
    ap.add_argument("file", help="path to a .ssbin recording")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=5005, help="receiver telemetry UDP port")
    ap.add_argument("--speed", type=float, default=1.0, help="playback speed multiplier")
    args = ap.parse_args()
    print(f"replaying {args.file} -> {args.host}:{args.port} at {args.speed}x")
    n = replay(args.file, args.host, args.port, args.speed)
    print(f"sent {n} datagrams")


if __name__ == "__main__":
    main()
