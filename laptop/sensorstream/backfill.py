"""Laptop-side backfill: gap detection + reconciliation state.

Keyed by the phone's stable ``client_id`` (Phase 2A) so tracked gaps survive the
``device_id`` rotation on reconnect. Pure/state-only — no I/O — so it unit-tests cleanly
and can be driven from the asyncio loop without blocking it.
"""
from __future__ import annotations

from typing import Dict, List, Tuple

Key = Tuple[str, int]  # (client_id, handle)

# Per-handle sequence numbers are uint32 and restart at 0 when a sensor is re-toggled
# (SensorEventSource) or wrap after 2**32. These bound what counts as reordering vs. a restart,
# so a rogue/huge seq can never materialize a giant `missing` set and a re-toggle is not read as
# millions of lost samples. (Mirrors the loss accounting in sync.py.)
_REORDER_WINDOW = 4096      # a seq this far *below* max_seq is still just a late/reordered arrival
_RESET_GAP = 100_000        # a forward jump this large is a counter reset/wrap, not real loss


class _PerSensor:
    __slots__ = ("missing", "permanent", "backfilled", "permanent_evicted", "max_seq")

    def __init__(self) -> None:
        self.missing: Dict[int, int] = {}     # seq -> t_recv_ns when first noticed missing
        self.permanent: set[int] = set()      # irrecoverable seqs in the current epoch
        self.backfilled = 0                   # lifetime (carried across resets)
        self.permanent_evicted = 0            # permanent seqs from prior epochs (lifetime stat)
        self.max_seq = -1

    def _restart(self, seq: int) -> None:
        """Counter reset (sensor re-toggle) or wrap: abandon the old seq epoch, keep lifetime
        totals. The old open holes live in a seq space the phone no longer serves, so requesting
        them is pointless; starting fresh lets gap detection track the new epoch immediately."""
        self.permanent_evicted += len(self.permanent)
        self.permanent.clear()
        self.missing.clear()
        self.max_seq = seq

    def observe(self, seq: int, t_recv_ns: int) -> None:
        if self.max_seq < 0:
            self.max_seq = seq
            return
        if seq > self.max_seq:
            if seq - self.max_seq >= _RESET_GAP:        # implausible forward jump -> reset/wrap
                self._restart(seq)
                return
            for s in range(self.max_seq + 1, seq):      # newly-missing interior seqs
                if s not in self.permanent:
                    self.missing.setdefault(s, t_recv_ns)
            self.max_seq = seq
        elif self.max_seq - seq > _REORDER_WINDOW:      # implausible backward jump -> restart
            self._restart(seq)
            return
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
        permanent = sum(len(ps.permanent) + ps.permanent_evicted for ps in self._sensors.values())
        backfilled = sum(ps.backfilled for ps in self._sensors.values())
        return {"open_gaps": open_gaps, "permanent": permanent, "backfilled": backfilled}
