"""Reordering- and reset-tolerant packet-loss accounting for one sensor's sequence numbers.

Shared by the sync/diagnostics tracker and the per-sensor dashboard feed so both report the same
loss. Within one contiguous sequence *epoch* the loss is ``(max - base + 1) - received``
(RTP-style), which is immune to UDP reordering: a late packet still increments ``received`` and
cancels the gap it appeared to open. A jump too large to be reordering -- the phone re-toggles a
sensor (its per-handle seq restarts at 0) or a uint32 wrap -- closes the epoch and opens a new one,
so a restart is not mistaken for a huge loss.
"""

from __future__ import annotations

from typing import Optional

# Per-sensor sequence numbers are uint32, monotonic from 0.
REORDER_WINDOW = 4096      # a seq this far *below* the epoch max is still just reordering
RESET_GAP = 1_000_000      # a jump this large means a counter reset (sensor re-toggle) or wrap


class SeqLoss:
    __slots__ = ("base", "max", "recv", "committed")

    def __init__(self) -> None:
        self.base: Optional[int] = None  # first seq of the current epoch
        self.max = 0                     # highest seq seen in the current epoch
        self.recv = 0                    # records attributed to the current epoch
        self.committed = 0               # loss from prior (closed) epochs

    def observe(self, seq: int) -> None:
        if self.base is None:
            self.base = self.max = seq
            self.recv = 1
        elif seq > self.max:
            if seq - self.max >= RESET_GAP:        # implausible forward jump -> reset/wrap
                self._close(seq)
            else:
                self.max = seq
                self.recv += 1
        else:  # seq <= max: reordered, duplicate, or a counter restart
            if self.max - seq > REORDER_WINDOW:    # too far back to be reordering -> restart
                self._close(seq)
            else:
                self.recv += 1
                if seq < self.base:                # a late straggler below the epoch base
                    self.base = seq

    def _close(self, seq: int) -> None:
        self.committed += self._open_loss()
        self.base = self.max = seq
        self.recv = 1

    def _open_loss(self) -> int:
        if self.base is None:
            return 0
        return max(0, (self.max - self.base + 1) - self.recv)

    @property
    def lost(self) -> int:
        """Total loss so far: closed epochs + the current one."""
        return self.committed + self._open_loss()
