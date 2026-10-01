"""Laptop self-advertisement so the phone can auto-discover it (no manual IP).

Two mechanisms, both pointing at the WebSocket **control port**:
  * **mDNS** (`_sensorstream._tcp`) via zeroconf — Android discovers it with NsdManager.
  * **UDP broadcast beacon** — a tiny JSON packet broadcast on ``BEACON_PORT`` every
    second; a fallback for networks where mDNS is filtered. The phone reads the
    beacon's sender IP + advertised control port and connects.

The phone still learns the telemetry UDP port from ``hello_ack``; discovery only
needs to hand it the control endpoint.

The beacon (v2) also identifies the server (name, kind, OS, per-run session id) so the phone can
list every server on the network, and each server answers a small UDP ping on its control port
number so the phone can measure the link to each one without connecting.
"""

from __future__ import annotations

import asyncio
import json
import os
import platform
import socket
import uuid
from dataclasses import dataclass
from typing import Optional

from zeroconf import ServiceInfo, Zeroconf

SERVICE_TYPE = "_sensorstream._tcp.local."
BEACON_PORT = 5006
BEACON_MAGIC = "sensorstream"
BEACON_VERSION = 2
PROBE_MAGIC = BEACON_MAGIC


def _board_model() -> Optional[str]:
    try:
        with open("/proc/device-tree/model", "rb") as f:
            return f.read().decode("utf-8", "ignore").strip("\x00 \n")
    except OSError:
        return None


def _has_battery() -> Optional[bool]:
    try:
        import psutil  # optional

        return psutil.sensors_battery() is not None
    except Exception:
        pass
    base = "/sys/class/power_supply"
    try:
        return any(n.startswith("BAT") for n in os.listdir(base))
    except OSError:
        return None


def detect_kind(board_model: Optional[str], has_battery: Optional[bool]) -> str:
    """'raspberry_pi' | 'laptop' | 'desktop' (a battery means a laptop)."""
    if board_model and "raspberry pi" in board_model.lower():
        return "raspberry_pi"
    return "laptop" if has_battery else "desktop"


@dataclass(frozen=True)
class ServerIdentity:
    name: str
    kind: str
    os: str
    session: str  # random per run: a restarted server is recognised as the same row, not a duplicate

    @classmethod
    def create(cls, name: Optional[str] = None) -> "ServerIdentity":
        return cls(
            name=(name or socket.gethostname() or "SensorStream server")[:40],
            kind=detect_kind(_board_model(), _has_battery()),
            os={"Darwin": "macOS"}.get(platform.system(), platform.system() or "unknown"),
            session=uuid.uuid4().hex[:8],
        )


def beacon_payload(control_port: int, udp_port: int, ident: Optional[ServerIdentity] = None) -> bytes:
    m = {"service": BEACON_MAGIC, "v": BEACON_VERSION, "control_port": control_port, "udp_port": udp_port}
    if ident is not None:
        m.update(name=ident.name, kind=ident.kind, os=ident.os, id=ident.session, probe_port=control_port)
    return json.dumps(m, separators=(",", ":")).encode("utf-8")


def probe_reply(data: bytes, session: str) -> Optional[bytes]:
    """Answer to a phone's link ping ({"service","probe":n}); None for anything else."""
    try:
        m = json.loads(data.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    if not isinstance(m, dict) or m.get("service") != PROBE_MAGIC or not isinstance(m.get("probe"), int):
        return None
    return json.dumps({"service": PROBE_MAGIC, "probe": m["probe"], "id": session}, separators=(",", ":")).encode()


class _ProbeProtocol(asyncio.DatagramProtocol):
    def __init__(self, session: str):
        self.session = session
        self.transport: Optional[asyncio.DatagramTransport] = None

    def connection_made(self, transport) -> None:
        self.transport = transport

    def datagram_received(self, data: bytes, addr) -> None:
        out = probe_reply(data[:512], self.session)
        if out is not None and self.transport is not None:
            self.transport.sendto(out, addr)


async def start_probe_responder(host: str, port: int, session: str) -> asyncio.DatagramTransport:
    """Echo phone pings on UDP host:port (the control port number; TCP and UDP don't clash)."""
    loop = asyncio.get_running_loop()
    transport, _ = await loop.create_datagram_endpoint(lambda: _ProbeProtocol(session), local_addr=(host, port))
    return transport


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
        ident: Optional[ServerIdentity] = None,
    ):
        self.ip = ip
        self.control_port = control_port
        self.udp_port = udp_port
        self.instance = instance
        self.beacon_port = beacon_port
        self.broadcast_addr = broadcast_addr
        self.ident = ident
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

    async def start_mdns_async(self) -> None:
        """From inside the server's event loop: zeroconf's blocking calls raise EventLoopBlocked there."""
        await asyncio.to_thread(self.start_mdns)

    async def stop_async(self) -> None:
        await asyncio.to_thread(self.stop)

    def _beacon_socket(self) -> socket.socket:
        if self._sock is None:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self._sock = s
        return self._sock

    def send_beacon(self) -> None:
        payload = beacon_payload(self.control_port, self.udp_port, self.ident)
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
