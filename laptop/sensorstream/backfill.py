"""Laptop-side backfill: gap detection + reconciliation state.

Keyed by the phone's stable ``client_id`` (Phase 2A) so tracked gaps survive the
``device_id`` rotation on reconnect. Pure/state-only — no I/O — so it unit-tests cleanly
and can be driven from the asyncio loop without blocking it.
"""
from __future__ import annotations

from typing import Dict, List, Tuple

Key = Tuple[str, int]  # (client_id, handle)


class _PerSensor:
    __slots__ = ("missing", "permanent", "backfilled", "max_seq")

    def __init__(self) -> None:
        self.missing: Dict[int, int] = {}     # seq -> t_recv_ns when first noticed missing
        self.permanent: set[int] = set()
        self.backfilled = 0
        self.max_seq = -1

    def observe(self, seq: int, t_recv_ns: int) -> None:
        if self.max_seq < 0:
            self.max_seq = seq
            return
        if seq > self.max_seq:
            for s in range(self.max_seq + 1, seq):      # newly-missing interior seqs
                if s not in self.permanent:
                    self.missing.setdefault(s, t_recv_ns)
            self.max_seq = seq
        self.missing.pop(seq, None)                     # a late/backfilled arrival fills a hole

    def contiguous_upto(self) -> int:
        holes = self.missing.keys() | self.permanent
        if not holes:
            return self.max_seq
        return min(holes) - 1


def _coalesce(seqs: List[int]) -> List[Tuple[int, int]]:
    out: List[Tuple[int, int]] = []
    for s in sorted(seqs):
        if out and s == out[-1][1] + 1:
            out[-1] = (out[-1][0], s)
        else:
            out.append((s, s))
    return out


class GapTracker:
    def __init__(self, grace_ns: int = 750_000_000) -> None:
        self._grace = grace_ns
        self._sensors: Dict[Key, _PerSensor] = {}

    def _get(self, client_id: str, handle: int) -> _PerSensor:
        return self._sensors.setdefault((client_id, handle), _PerSensor())

    def observe(self, client_id: str, handle: int, seq: int, t_recv_ns: int) -> None:
        self._get(client_id, handle).observe(seq, t_recv_ns)

    def due_ranges(self, now_ns: int) -> List[Tuple[str, int, int, int]]:
        out: List[Tuple[str, int, int, int]] = []
        for (cid, h), ps in self._sensors.items():
            due = [s for s, t in ps.missing.items() if now_ns - t >= self._grace]
            for a, b in _coalesce(due):
                out.append((cid, h, a, b))
        out.sort()
        return out

    def resolve(self, client_id: str, handle: int, seqs: List[int]) -> None:
        ps = self._get(client_id, handle)
        for s in seqs:
            if ps.missing.pop(s, None) is not None:
                ps.backfilled += 1

    def mark_permanent(self, client_id: str, handle: int, from_seq: int, to_seq: int) -> None:
        ps = self._get(client_id, handle)
        for s in range(from_seq, to_seq + 1):
            ps.missing.pop(s, None)
            ps.permanent.add(s)

    def contiguous_upto(self, client_id: str, handle: int) -> int:
        return self._get(client_id, handle).contiguous_upto()

    def stats(self) -> dict:
        open_gaps = sum(len(ps.missing) for ps in self._sensors.values())
        permanent = sum(len(ps.permanent) for ps in self._sensors.values())
        backfilled = sum(ps.backfilled for ps in self._sensors.values())
        return {"open_gaps": open_gaps, "permanent": permanent, "backfilled": backfilled}
