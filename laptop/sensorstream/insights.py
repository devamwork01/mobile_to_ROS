"""Live insights: full-rate rolling statistics and on-demand spectra for the dashboard.

Sits beside DashboardSink on the raw stream (which the browser only sees decimated), keeps a
bounded ring per sensor, and on each tick() emits compact messages: per-axis mean/sigma/p2p and
rate/jitter for every live sensor (~1 Hz), plus a Welch PSD every other tick for the handles a
browser panel has subscribed to (subscriptions expire after 10 s unless refreshed).
"""

from __future__ import annotations

import time
from typing import Callable, Dict, Iterable, List

import numpy as np

from . import analysis as an
from .sinks import OutputSink

RING_CAP = 30000   # 60 s at 500 Hz
MAX_VALUES = 4
SUB_TTL_S = 10.0
STALE_S = 2.0


class _Ring:
    __slots__ = ("type", "t", "seq", "v", "vf", "has_f", "k", "head", "n", "last_seen")

    def __init__(self, type_: int):
        self.type = type_
        self.t = np.zeros(RING_CAP, dtype=np.int64)
        self.seq = np.zeros(RING_CAP, dtype=np.int64)
        self.v = np.full((RING_CAP, MAX_VALUES), np.nan)
        self.vf = np.full((RING_CAP, MAX_VALUES), np.nan)  # filtered twin (NaN = not filtered)
        self.has_f = False
        self.k = 1
        self.head = 0
        self.n = 0
        self.last_seen = 0.0

    def push(self, t_ns: int, seq: int, values, now: float, fvals=None) -> None:
        i = self.head
        self.t[i] = t_ns
        self.seq[i] = seq
        k = min(len(values), MAX_VALUES)
        self.v[i, :] = np.nan
        self.v[i, :k] = values[:k]
        self.vf[i, :] = np.nan
        if fvals:
            kf = min(len(fvals), MAX_VALUES)
            self.vf[i, :kf] = [np.nan if x is None else x for x in fvals[:kf]]
            self.has_f = True
        self.k = max(self.k, k)
        self.head = (i + 1) % RING_CAP
        self.n = min(self.n + 1, RING_CAP)
        self.last_seen = now

    def window(self, seconds: float, filtered: bool = False):
        """Samples of the last `seconds` (by sensor time), time-sorted, true duplicates removed.

        A duplicate is the same (seq, t) pair; seq alone repeats after the phone restarts its
        counter (sensor re-toggled, reconnect), and those samples are new data."""
        if self.n == 0:
            return np.array([], dtype=np.int64), np.empty((0, self.k))
        idx = (self.head - self.n + np.arange(self.n)) % RING_CAP
        t, seq = self.t[idx], self.seq[idx]
        v = (self.vf if filtered else self.v)[idx, : self.k]
        _, first = np.unique(np.stack([seq, t], axis=1), axis=0, return_index=True)
        t, v = t[first], v[first]
        order = np.argsort(t, kind="stable")
        t, v = t[order], v[order]
        keep = t >= t[-1] - int(seconds * 1e9)
        return t[keep], v[keep]


class InsightsSink(OutputSink):
    def __init__(self, clock: Callable[[], float] = time.monotonic, window_s: float = 10.0):
        self._clock = clock
        self._window = window_s
        self._rings: Dict[int, _Ring] = {}
        self._subs: Dict[int, float] = {}
        self._ticks = 0

    def on_datagram(self, dg, addr, t_recv_ns, filtered=None) -> None:
        now = self._clock()
        for r in dg.records:
            ring = self._rings.get(r.sensor_handle)
            if ring is None or ring.type != r.sensor_type:
                ring = self._rings[r.sensor_handle] = _Ring(r.sensor_type)
            ring.push(r.t_sensor_ns, r.seq, r.values, now,
                      filtered.get((r.sensor_handle, r.seq)) if filtered else None)

    def subscribe_psd(self, handles: Iterable[int]) -> None:
        until = self._clock() + SUB_TTL_S
        for h in handles:
            self._subs[int(h)] = until

    def ring_size(self, handle: int) -> int:
        ring = self._rings.get(handle)
        return ring.n if ring else 0

    def tick(self) -> List[dict]:
        now = self._clock()
        self._ticks += 1
        self._subs = {h: u for h, u in self._subs.items() if u > now}
        stats, out = [], []
        do_psd = self._ticks % 2 == 0
        for h, ring in self._rings.items():
            if now - ring.last_seen > STALE_S:
                continue
            t, v = ring.window(self._window)
            if t.size < 2:
                continue
            rate = an.rate_stats(t)
            vf = ring.window(self._window, filtered=True)[1] if ring.has_f else None
            axes = [an.axis_stats(v[:, i]) for i in range(v.shape[1])]
            if vf is not None:
                for i, ax in enumerate(axes):
                    col = vf[:, i]
                    if np.isfinite(col).sum() >= 2:
                        ax["fstd"] = an.axis_stats(col)["std"]
            stats.append({"handle": h, "type": ring.type, **rate, "axes": axes})
            fs = rate["rate_hz"] or 0.0
            if (do_psd and h in self._subs and ring.type not in an.ON_CHANGE_TYPES
                    and fs >= an.PSD_MIN_RATE):
                f = None
                psds = []
                for i in range(v.shape[1]):
                    ff, pp = an.welch_psd(v[:, i], fs)
                    pts = an.psd_points(ff, pp)
                    f = pts["f"]
                    psds.append(pts["psd"])
                if f:
                    msg = {"kind": "insights_psd", "handle": h, "f": f, "psd": psds}
                    if vf is not None and all(np.isfinite(vf[:, i]).sum() >= 16 for i in range(vf.shape[1])):
                        after = []
                        for i in range(vf.shape[1]):
                            col = vf[:, i]
                            ff, pp = an.welch_psd(col[np.isfinite(col)], fs)
                            after.append([float(x) for x in np.interp(f, ff, pp)] if ff.size else [])
                        msg["psd_f"] = after
                    out.append(msg)
        out.insert(0, {"kind": "insights", "stats": stats})
        return out

    def suggest(self, handle: int):
        """(f, mean raw PSD across axes, fs, [PSD per axis]) over the live window, as input for
        filters.suggest(): the mean for an all-axes filter, one per axis for per-axis filters."""
        ring = self._rings.get(handle)
        if ring is None:
            return None
        t, v = ring.window(self._window)
        if t.size < 64:
            return None
        fs = an.rate_stats(t)["rate_hz"]
        if not fs:
            return None
        acc, f, per = None, None, []
        for i in range(v.shape[1]):
            ff, pp = an.welch_psd(v[:, i], fs)
            if ff.size == 0:
                return None
            f, acc = ff, (pp if acc is None else acc + pp)
            per.append(pp)
        return f, acc / v.shape[1], fs, per
