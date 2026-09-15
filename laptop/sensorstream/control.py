"""Control channel server (WebSocket JSON, path ``/phone``).

Reliable, low-rate side channel that carries everything the high-rate UDP path
must not: the phone's device info + **full sensor catalog**, device-id assignment,
heartbeat/latency, per-phone stats, effective config, and (laptop→phone) sensor
configuration. Telemetry references the compact ``sensor_handle`` from the catalog
sent here, so hot packets never carry strings.

Connection lifecycle:
    phone → hello (model, android, app_version, sensors[])   # full catalog
    laptop → hello_ack (device_id, udp_port)                 # id used in telemetry
    phone → heartbeat            laptop → heartbeat_ack
    phone → clock_ping           laptop → clock_pong          # NTP-like offset
    phone → stats / config_state (forwarded to the dashboard)

Every phone event is mirrored to the dashboard via ``on_event`` so the browser can
show the sensor list, connection state and per-phone stats.
"""

from __future__ import annotations

import itertools
import json
import time
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Dict, List, Optional

from . import protocol as p


def _peer(ws) -> str:
    try:
        host, port = ws.remote_address[:2]
        return f"{host}:{port}"
    except Exception:
        return "?"


async def _send(ws, obj: dict) -> None:
    await ws.send(json.dumps(obj, separators=(",", ":")))


@dataclass
class PhoneSession:
    ws: object
    device_id: int
    addr: str
    model: str = ""
    android: str = ""
    app_version: str = ""
    client_id: str = ""
    catalog: List[dict] = field(default_factory=list)
    active: List[int] = field(default_factory=list)
    connected_at: float = field(default_factory=time.monotonic)
    last_heartbeat: float = field(default_factory=time.monotonic)
    last_stats: dict = field(default_factory=dict)

    def sensor_name(self, handle: int) -> Optional[str]:
        for s in self.catalog:
            if s.get("handle") == handle:
                return s.get("name")
        return None


class ControlServer:
    """Owns connected phone sessions. ``on_event`` receives dashboard-bound dicts."""

    def __init__(self, udp_port: int, on_event: Callable[[dict], None]):
        self._udp_port = udp_port
        self._on_event = on_event
        self._sessions: Dict[int, PhoneSession] = {}
        self._ids = itertools.count(1)

    @property
    def sessions(self) -> Dict[int, PhoneSession]:
        return self._sessions

    def catalog_for(self, device_id: int) -> List[dict]:
        s = self._sessions.get(device_id)
        return s.catalog if s else []

    async def handle(self, ws) -> None:
        """Per-connection coroutine for a ``/phone`` WebSocket."""
        addr = _peer(ws)
        session: Optional[PhoneSession] = None
        try:
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                except (ValueError, TypeError):
                    continue
                if not isinstance(msg, dict):
                    continue
                mtype = msg.get("type")

                if mtype == p.MSG_HELLO:
                    session = await self._on_hello(ws, addr, msg)
                elif mtype == p.MSG_HEARTBEAT:
                    if session:
                        session.last_heartbeat = time.monotonic()
                    await _send(ws, {"type": p.MSG_HEARTBEAT_ACK, "seq": msg.get("seq"), "t_server_ns": time.time_ns()})
                elif mtype == p.MSG_CLOCK_PING:
                    # NTP-like: echo t0, add server receive/transmit stamp.
                    await _send(ws, {"type": p.MSG_CLOCK_PONG, "t0": msg.get("t0"), "t_server_ns": time.time_ns()})
                elif mtype == p.MSG_STATS:
                    if session:
                        session.last_stats = msg
                        self._on_event({"kind": "phone_stats", "device_id": session.device_id, "stats": msg})
                elif mtype == p.MSG_ACTIVE:
                    if session:
                        handles = [int(h) for h in (msg.get("handles") or [])]
                        session.active = handles
                        self._on_event({"kind": "active_set", "device_id": session.device_id, "handles": handles})
                elif mtype == p.MSG_CONFIG_STATE:
                    if session:
                        self._on_event({"kind": "config_state", "device_id": session.device_id, "config": msg.get("config")})
                elif mtype == p.MSG_BACKFILL:
                    if session:
                        self._on_event({"kind": "backfill", "device_id": session.device_id,
                                        "handle": int(msg.get("handle", -1)),
                                        "frames": list(msg.get("frames") or [])})
                elif mtype == p.MSG_BACKFILL_UNAVAILABLE:
                    if session:
                        self._on_event({"kind": "backfill_unavailable", "device_id": session.device_id,
                                        "handle": int(msg.get("handle", -1)),
                                        "from": int(msg.get("from", 0)), "to": int(msg.get("to", 0))})
                # unknown types ignored (forward-compatible)
        except Exception:
            pass
        finally:
            if session is not None:
                self._sessions.pop(session.device_id, None)
                self._on_event({"kind": "phone_disconnected", "device_id": session.device_id})

    async def _on_hello(self, ws, addr: str, msg: dict) -> PhoneSession:
        device_id = next(self._ids) & 0xFFFFFFFF
        session = PhoneSession(
            ws=ws,
            device_id=device_id,
            addr=addr,
            model=str(msg.get("model", "")),
            android=str(msg.get("android", "")),
            app_version=str(msg.get("app_version", "")),
            client_id=str(msg.get("client_id", "")),
            catalog=list(msg.get("sensors", []) or []),
        )
        self._sessions[device_id] = session
        await _send(ws, {"type": p.MSG_HELLO_ACK, "device_id": device_id, "udp_port": self._udp_port})
        self._on_event(
            {
                "kind": "phone_connected",
                "device_id": device_id,
                "addr": addr,
                "model": session.model,
                "android": session.android,
                "app_version": session.app_version,
                "client_id": session.client_id,
                "sensors": session.catalog,
            }
        )
        return session

    async def configure(self, device_id: int, config: dict) -> bool:
        """Push a laptop→phone configuration message (e.g. enable/disable, rate)."""
        s = self._sessions.get(device_id)
        if s is None:
            return False
        await _send(s.ws, {"type": p.MSG_CONFIGURE, **config})
        return True

    async def send_resend(self, device_id: int, handle: int, from_seq: int, to_seq: int) -> bool:
        """Ask the phone to resend a missing seq range from its on-phone recording (backfill)."""
        s = self._sessions.get(device_id)
        if s is None:
            return False
        await _send(s.ws, {"type": p.MSG_RESEND, "device_id": device_id,
                           "handle": handle, "from": from_seq, "to": to_seq})
        return True

    async def send_backfill_ack(self, device_id: int, upto: list) -> bool:
        """Tell the phone the highest contiguous seq per handle it may prune. ``upto`` = [(handle, seq)]."""
        s = self._sessions.get(device_id)
        if s is None:
            return False
        await _send(s.ws, {"type": p.MSG_BACKFILL_ACK, "device_id": device_id,
                           "upto": [{"handle": h, "seq": seq} for (h, seq) in upto]})
        return True
