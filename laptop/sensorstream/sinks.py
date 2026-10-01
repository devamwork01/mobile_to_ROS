"""Output sinks.

`OutputSink` is the seam that keeps the receiver/sync layers independent of what
consumes the data. Today there is a dashboard sink (and, from Phase 6, CSV +
binary loggers). A future `Ros2Sink` publishing sensor_msgs can be added here
without touching acquisition or the receiver — this is the "ROS-ready" hook.
"""

from __future__ import annotations

import time
from abc import ABC, abstractmethod
from typing import Callable, Dict, Tuple

from .protocol import Datagram
from .seqloss import SeqLoss

Key = Tuple[int, int]  # (sensor_type, sensor_handle)


class OutputSink(ABC):
    @abstractmethod
    def on_datagram(self, dg: Datagram, addr: Tuple[str, int], t_recv_ns: int) -> None: ...

    def close(self) -> None:  # pragma: no cover - default no-op
        pass


class DashboardSink(OutputSink):
    """Turns decoded datagrams into compact JSON for the browser.

    * Measures the true per-sensor output rate from the phone's own
      ``t_sensor_ns`` deltas (an EWMA), so the dashboard shows the *actual*
      frequency, not the requested one.
    * Tracks per-sensor packet loss from sequence numbers (reorder/reset tolerant; see seqloss).
    * Decimates to ``max_ui_hz`` per sensor so a 200 Hz stream does not flood
      the browser (the raw stream still flows unthrottled to loggers).
    """

    def __init__(self, broadcast: Callable[[dict], None], max_ui_hz: float = 30.0):
        self._broadcast = broadcast
        self._min_emit_dt = 1.0 / max_ui_hz if max_ui_hz > 0 else 0.0
        self._last_emit: Dict[Key, float] = {}
        self._last_t: Dict[Key, int] = {}
        self._hz: Dict[Key, float] = {}
        self._loss: Dict[Key, SeqLoss] = {}
        # Health: records seen vs records forwarded to the browser. The gap is *coalesced*
        # presentation updates (intentional decimation) — NOT lost raw data.
        self.records_in = 0
        self.records_out = 0

    def on_datagram(self, dg: Datagram, addr: Tuple[str, int], t_recv_ns: int, filtered=None) -> None:
        now = time.monotonic()
        out = []
        self.records_in += len(dg.records)
        for r in dg.records:
            key = (r.sensor_type, r.sensor_handle)

            last_t = self._last_t.get(key)
            if last_t is not None and r.t_sensor_ns > last_t:
                dt = (r.t_sensor_ns - last_t) / 1e9
                if dt > 0:
                    inst = 1.0 / dt
                    prev = self._hz.get(key)
                    self._hz[key] = inst if prev is None else 0.9 * prev + 0.1 * inst
            self._last_t[key] = r.t_sensor_ns

            # Same reorder/reset-tolerant accounting as the diagnostics tracker (a naive gap
            # counter reported false, ever-growing loss under ordinary UDP reordering).
            loss = self._loss.get(key)
            if loss is None:
                loss = self._loss[key] = SeqLoss()
            loss.observe(r.seq)

            if now - self._last_emit.get(key, 0.0) >= self._min_emit_dt:
                self._last_emit[key] = now
                self.records_out += 1
                out.append(
                    {
                        "type": r.sensor_type,
                        "handle": r.sensor_handle,
                        "seq": r.seq,
                        "t": r.t_sensor_ns,
                        "acc": r.accuracy,
                        "v": [round(x, 6) for x in r.values],
                        "hz": round(self._hz.get(key, 0.0), 1),
                        "lost": loss.lost,
                    }
                )
                # Filtered value of this exact sample (FilterBank runs on every raw sample, before
                # this decimation), present only while the sensor has an active filter.
                fv = filtered.get((r.sensor_handle, r.seq)) if filtered else None
                if fv is not None:
                    out[-1]["vf"] = [None if x != x else round(x, 6) for x in fv]
        if out:
            self._broadcast({"kind": "data", "device_id": dg.device_id, "t_recv_ns": t_recv_ns, "records": out})
