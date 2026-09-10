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


@dataclass
class SensorStat:
    received: int = 0
    lost: int = 0
    reordered: int = 0
    last_seq: Optional[int] = None
    last_t: Optional[int] = None
    accuracy: int = 3


class SyncTracker:
    def __init__(self, latency_window: int = 1024):
        self._sensors: Dict[Key, SensorStat] = {}
        self._deltas: Deque[int] = deque(maxlen=latency_window)  # t_recv_ns - t_sensor_ns
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

            if st.last_seq is not None:
                gap = (r.seq - st.last_seq - 1) & 0xFFFFFFFF
                if 0 < gap < 1_000_000:  # ignore counter resets / wrap
                    st.lost += gap
                    self.total_lost += gap
            st.last_seq = r.seq

            if st.last_t is not None and r.t_sensor_ns < st.last_t:
                st.reordered += 1
                self.total_reordered += 1
            else:
                st.last_t = r.t_sensor_ns

            self._deltas.append(t_recv_ns - r.t_sensor_ns)

    def _latency_ms(self) -> Tuple[float, float, float]:
        if not self._deltas:
            return (0.0, 0.0, 0.0)
        offset = min(self._deltas)
        lat = sorted((d - offset) / 1e6 for d in self._deltas)
        n = len(lat)
        p50 = lat[n // 2]
        p95 = lat[min(n - 1, int(0.95 * n))]
        return (round(p50, 2), round(p95, 2), round(p95 - p50, 2))

    def snapshot(self) -> dict:
        p50, p95, jitter = self._latency_ms()
        total = self.total_received + self.total_lost
        return {
            "kind": "debug",
            "active_sensors": len(self._sensors),
            "received": self.total_received,
            "lost": self.total_lost,
            "loss_pct": round(100.0 * self.total_lost / total, 3) if total else 0.0,
            "reordered": self.total_reordered,
            "latency_ms_p50": p50,
            "latency_ms_p95": p95,
            "jitter_ms": jitter,
            "offset_ns": min(self._deltas) if self._deltas else 0,
            "samples": len(self._deltas),
        }
