"""Synchronization + connection-quality tracking.

Consumes decoded datagrams (with the laptop receive time) and derives the metrics
the spec asks for (§18/§25/§29/§32): per-sensor packet loss, out-of-order
detection, and end-to-end latency.

Latency without clock sync: the phone timestamps (``t_sensor_ns``, monotonic from
boot) and the laptop receive time (``t_recv_ns``, monotonic) live in different
epochs, so their raw difference is ``true_clock_offset + transit_delay``. The
**minimum** of that difference over a rolling window approximates
``offset + min_transit`` (the packet that was queued least), so subtracting the
rolling min yields per-packet *added* latency (≥ 0) and jitter — an honest,
handshake-free measurement. ``offset_ns`` is that rolling minimum.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from typing import Deque, Dict, Optional, Tuple

from .protocol import Datagram

Key = Tuple[int, int]  # (sensor_type, sensor_handle)

# Loss accounting tolerances (per-sensor sequence numbers are uint32, monotonic from 0).
_REORDER_WINDOW = 4096      # a seq this far *below* the epoch max is still just reordering
_RESET_GAP = 1_000_000      # a jump this large means a counter reset (sensor re-toggle) or wrap


@dataclass
class SensorStat:
    received: int = 0
    lost: int = 0            # committed epochs + current epoch; refreshed by SyncTracker.snapshot()
    reordered: int = 0
    last_t: Optional[int] = None
    accuracy: int = 3
    # Reordering/reset-tolerant loss accounting (RTP-style), scoped to one contiguous seq epoch.
    epoch_base: Optional[int] = None  # first seq of the current epoch
    epoch_max: int = 0                # highest seq seen in the current epoch
    epoch_recv: int = 0               # records attributed to the current epoch
    committed_lost: int = 0           # loss from prior (closed) epochs


class SyncTracker:
    def __init__(self, latency_window: int = 1024):
        self._sensors: Dict[Key, SensorStat] = {}
        self._deltas: Deque[int] = deque(maxlen=latency_window)  # t_recv_ns - t_sensor_ns
        self._phone_lat: Deque[int] = deque(maxlen=latency_window)  # t_serialize_ns - t_acquire_ns (phone)
        self.total_received = 0
        self.total_lost = 0
        self.total_reordered = 0

    def observe(self, dg: Datagram, t_recv_ns: int) -> None:
        for r in dg.records:
            key = (r.sensor_type, r.sensor_handle)
            st = self._sensors.get(key)
            if st is None:
                st = SensorStat()
                self._sensors[key] = st
            st.received += 1
            self.total_received += 1
            st.accuracy = r.accuracy

            self._account_seq(st, r.seq)

            if st.last_t is not None and r.t_sensor_ns < st.last_t:
                st.reordered += 1
                self.total_reordered += 1
            else:
                st.last_t = r.t_sensor_ns

            self._deltas.append(t_recv_ns - r.t_sensor_ns)
            if r.t_acquire_ns is not None and r.t_serialize_ns is not None:
                on_phone = r.t_serialize_ns - r.t_acquire_ns
                if 0 <= on_phone < 5_000_000_000:  # sane bound (< 5 s)
                    self._phone_lat.append(on_phone)

    def _account_seq(self, st: SensorStat, seq: int) -> None:
        """Reordering- and reset-tolerant per-sensor loss accounting.

        Within one contiguous sequence *epoch* the loss is ``(max - base + 1) - received``
        (RTP-style). That is immune to UDP reordering: a late packet still increments
        ``received`` and cancels the gap it appeared to open, so loss never accrues when
        every seq eventually arrives. A jump too large to be reordering -- the phone
        re-toggles a sensor (its per-handle seq restarts at 0) or a 32-bit wrap -- closes
        the epoch and opens a new one, so a restart is not mistaken for a huge loss.
        """
        if st.epoch_base is None:
            st.epoch_base = st.epoch_max = seq
            st.epoch_recv = 1
        elif seq > st.epoch_max:
            if seq - st.epoch_max >= _RESET_GAP:      # implausible forward jump -> reset/wrap
                self._close_epoch(st, seq)
            else:
                st.epoch_max = seq
                st.epoch_recv += 1
        else:  # seq <= epoch_max: reordered, duplicate, or a counter restart
            if st.epoch_max - seq > _REORDER_WINDOW:  # too far back to be reordering -> restart
                self._close_epoch(st, seq)
            else:
                st.epoch_recv += 1
                if seq < st.epoch_base:               # a late straggler below the epoch base
                    st.epoch_base = seq

    @staticmethod
    def _close_epoch(st: SensorStat, seq: int) -> None:
        st.committed_lost += max(0, (st.epoch_max - st.epoch_base + 1) - st.epoch_recv)
        st.epoch_base = st.epoch_max = seq
        st.epoch_recv = 1

    def _recompute_loss(self) -> int:
        """Sum committed + open-epoch loss across sensors; refresh cached per-sensor/total loss."""
        total = 0
        for st in self._sensors.values():
            cur = 0
            if st.epoch_base is not None:
                cur = max(0, (st.epoch_max - st.epoch_base + 1) - st.epoch_recv)
            st.lost = st.committed_lost + cur
            total += st.lost
        self.total_lost = total
        return total

    def _latency_ms(self) -> Tuple[float, float, float]:
        if not self._deltas:
            return (0.0, 0.0, 0.0)
        offset = min(self._deltas)
        lat = sorted((d - offset) / 1e6 for d in self._deltas)
        n = len(lat)
        p50 = lat[n // 2]
        p95 = lat[min(n - 1, int(0.95 * n))]
        return (round(p50, 2), round(p95, 2), round(p95 - p50, 2))

    def _phone_latency_ms(self) -> Tuple[float, float]:
        """Median/p95 of on-phone acquisition->serialization latency (ms)."""
        if not self._phone_lat:
            return (0.0, 0.0)
        lat = sorted(d / 1e6 for d in self._phone_lat)
        n = len(lat)
        return (round(lat[n // 2], 3), round(lat[min(n - 1, int(0.95 * n))], 3))

    def snapshot(self) -> dict:
        p50, p95, jitter = self._latency_ms()
        ph50, ph95 = self._phone_latency_ms()
        total_lost = self._recompute_loss()
        total = self.total_received + total_lost
        return {
            "kind": "debug",
            "active_sensors": len(self._sensors),
            "received": self.total_received,
            "lost": total_lost,
            "loss_pct": round(100.0 * total_lost / total, 3) if total else 0.0,
            "reordered": self.total_reordered,
            "latency_ms_p50": p50,
            "latency_ms_p95": p95,
            "jitter_ms": jitter,
            "offset_ns": min(self._deltas) if self._deltas else 0,
            "phone_latency_ms_p50": ph50,
            "phone_latency_ms_p95": ph95,
            "samples": len(self._deltas),
        }
