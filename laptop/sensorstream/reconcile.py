"""Reconciler: drives backfill from detected gaps and merges the results.

Transport-free: it calls injected ``send_resend`` / ``on_backfilled`` callbacks, so it is
unit-testable and the asyncio app wires it to the control server + recorder. Gaps are tracked
through the GapTracker by ``client_id``; the ``device_id`` <-> ``client_id`` map is maintained
here from control hellos, so a reconnect (new device_id, same client) still requests the old
client's gaps from the current device.
"""
from __future__ import annotations

from typing import Callable, Dict, List, Optional, Tuple

from . import protocol as p
from .backfill import GapTracker


class Reconciler:
    def __init__(self, tracker: GapTracker,
                 send_resend: Callable[[int, int, int, int], None],
                 on_backfilled: Callable[[p.Datagram, int], None]) -> None:
        self._t = tracker
        self._send_resend = send_resend
        self._on_backfilled = on_backfilled
        self._dev2client: Dict[int, str] = {}
        self._client2dev: Dict[str, int] = {}
        self._inflight: set = set()   # (client_id, handle, from, to) requested, awaiting reply

    def set_device_client(self, device_id: int, client_id: str) -> None:
        self._dev2client[device_id] = client_id
        if client_id:
            self._client2dev[client_id] = device_id   # latest device_id for this client

    def client_for(self, device_id: int) -> Optional[str]:
        return self._dev2client.get(device_id)

    def _client(self, device_id: int) -> str:
        cid = self._dev2client.get(device_id)
        if cid is None:
            cid = f"dev:{device_id}"          # fallback until a hello maps this device
            self.set_device_client(device_id, cid)
        return cid

    def on_live(self, device_id: int, dg: p.Datagram, t_recv_ns: int) -> None:
        cid = self._client(device_id)
        for r in dg.records:
            self._t.observe(cid, r.sensor_handle, r.seq, t_recv_ns)

    async def tick(self, now_ns: int) -> None:
        for (cid, handle, a, b) in self._t.due_ranges(now_ns):
            key = (cid, handle, a, b)
            if key in self._inflight:
                continue
            dev = self._client2dev.get(cid)
            if dev is None:
                continue
            self._inflight.add(key)
            self._send_resend(dev, handle, a, b)

    def on_backfill(self, device_id: int, handle: int, frames_b64: List[str]) -> None:
        cid = self._client(device_id)
        got: List[int] = []
        for s in frames_b64:
            try:
                dg = p.decode_datagram(p.decode_frame_b64(s))
            except p.ProtocolError:
                continue
            for r in dg.records:
                got.append(r.seq)
            self._on_backfilled(dg, 0)       # append to recorder; dedup on read is by (handle, seq)
        self._t.resolve(cid, handle, got)
        got_set = set(got)
        self._inflight = {
            k for k in self._inflight
            if not (k[0] == cid and k[1] == handle and set(range(k[2], k[3] + 1)) <= got_set)
        }

    def on_unavailable(self, device_id: int, handle: int, from_seq: int, to_seq: int) -> None:
        cid = self._client(device_id)
        self._t.mark_permanent(cid, handle, from_seq, to_seq)
        self._inflight.discard((cid, handle, from_seq, to_seq))

    def acks(self) -> List[Tuple[int, List[Tuple[int, int]]]]:
        out: List[Tuple[int, List[Tuple[int, int]]]] = []
        for cid, dev in self._client2dev.items():
            per = [(h, self._t.contiguous_upto(cid, h))
                   for (c, h) in list(self._t._sensors.keys()) if c == cid]
            if per:
                out.append((dev, per))
        return out

    def stats(self) -> dict:
        return self._t.stats()
