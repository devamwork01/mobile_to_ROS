"""Laptop self-advertisement so the phone can auto-discover it (no manual IP).

Two mechanisms, both pointing at the WebSocket **control port**:
  * **mDNS** (`_sensorstream._tcp`) via zeroconf — Android discovers it with NsdManager.
  * **UDP broadcast beacon** — a tiny JSON packet broadcast on ``BEACON_PORT`` every
    second; a fallback for networks where mDNS is filtered. The phone reads the
    beacon's sender IP + advertised control port and connects.

The phone still learns the telemetry UDP port from ``hello_ack``; discovery only
needs to hand it the control endpoint.
"""

from __future__ import annotations

import json
import socket
from typing import Optional

from zeroconf import ServiceInfo, Zeroconf

SERVICE_TYPE = "_sensorstream._tcp.local."
BEACON_PORT = 5006
BEACON_MAGIC = "sensorstream"
BEACON_VERSION = 1


def beacon_payload(control_port: int, udp_port: int) -> bytes:
    return json.dumps(
        {"service": BEACON_MAGIC, "v": BEACON_VERSION, "control_port": control_port, "udp_port": udp_port},
        separators=(",", ":"),
    ).encode("utf-8")


def parse_beacon(data: bytes) -> Optional[dict]:
    try:
        m = json.loads(data.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    if isinstance(m, dict) and m.get("service") == BEACON_MAGIC:
        return m
    return None


class Advertiser:
    def __init__(
        self,
        ip: str,
        control_port: int,
        udp_port: int,
        instance: str = "SensorStream",
        beacon_port: int = BEACON_PORT,
        broadcast_addr: str = "255.255.255.255",
    ):
        self.ip = ip
        self.control_port = control_port
        self.udp_port = udp_port
        self.instance = instance
        self.beacon_port = beacon_port
        self.broadcast_addr = broadcast_addr
        self._zc: Optional[Zeroconf] = None
        self._info: Optional[ServiceInfo] = None
        self._sock: Optional[socket.socket] = None

    def start_mdns(self) -> None:
        self._zc = Zeroconf()
        self._info = ServiceInfo(
            SERVICE_TYPE,
            f"{self.instance}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(self.ip)] if self.ip else [],
            port=self.control_port,
            properties={
                b"control_port": str(self.control_port).encode(),
                b"udp_port": str(self.udp_port).encode(),
                b"path": b"/phone",
                b"v": str(BEACON_VERSION).encode(),
            },
        )
        self._zc.register_service(self._info)

    def _beacon_socket(self) -> socket.socket:
        if self._sock is None:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self._sock = s
        return self._sock

    def send_beacon(self) -> None:
        payload = beacon_payload(self.control_port, self.udp_port)
        try:
            self._beacon_socket().sendto(payload, (self.broadcast_addr, self.beacon_port))
        except OSError:
            pass  # e.g. no route while the network is down

    def stop(self) -> None:
        if self._zc is not None:
            try:
                if self._info is not None:
                    self._zc.unregister_service(self._info)
            except Exception:
                pass
            self._zc.close()
        self._zc = None
        self._info = None
        if self._sock is not None:
            self._sock.close()
            self._sock = None
