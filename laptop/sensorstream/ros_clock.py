"""Phone event time -> laptop clock, for ROS 2 stamps (pure; no rclpy).

The phone stamps every sample with its own monotonic clock (``t_sensor_ns``); the receiver records
the laptop's ``time.monotonic_ns()`` on arrival (``t_recv_ns``). ``d = t_recv - t_sensor`` is the
clock offset plus that sample's transit delay, so the minimum ``d`` over a short window is the offset
plus the fastest transit (~1 ms on a LAN): a constant bias, but no Wi-Fi jitter. ClockMapper tracks
that minimum and slews toward it, so mapped stamps never jump. A phone clock reset (reboot) is
accepted only after it is seen consistently for 0.5 s, and at most once per 10 s.
"""

from __future__ import annotations

from collections import deque
from typing import Deque, Dict, Optional, Tuple

WINDOW_NS = 10_000_000_000
WARMUP_NS = 2_000_000_000
SLEW = 0.001                   # offset change per unit of receive time: 1 ms per s
OUTLIER_NS = 1_000_000_000
CONFIRM_NS = 500_000_000
CONFIRM_MIN = 10
HOLDOFF_NS = 10_000_000_000


class ClockMapper:
    def __init__(self) -> None:
        self._win: Deque[Tuple[int, int]] = deque()  # (t_recv, d), d increasing: sliding minimum
        self._applied: Optional[int] = None
        self._warm_until = 0
        self._last_recv = 0
        self._run_start: Optional[int] = None   # first receive time of the current outlier run
        self._run_first = 0
        self._run_min = 0
        self._run_count = 0
        self._last_reset: Optional[int] = None
        self.resets = 0

    @property
    def offset_ns(self) -> Optional[int]:
        return self._applied

    def to_mono(self, t_sensor_ns: int) -> Optional[int]:
        return None if self._applied is None else t_sensor_ns + self._applied

    def observe(self, t_sensor_ns: int, t_recv_ns: int) -> None:
        d = t_recv_ns - t_sensor_ns
        if self._applied is None:
            self._start(t_recv_ns, d)
            return
        if abs(d - self._applied) > OUTLIER_NS:
            self._outlier(t_recv_ns, d)
            return
        self._run_start = None
        self._push(t_recv_ns, d)
        target = self._target(t_recv_ns)
        if t_recv_ns <= self._warm_until:
            self._applied = target
        else:
            step = int(max(0, t_recv_ns - self._last_recv) * SLEW)
            self._applied += max(-step, min(step, target - self._applied))
        self._last_recv = max(self._last_recv, t_recv_ns)

    def _start(self, t_recv: int, d: int) -> None:
        self._win.clear()
        self._push(t_recv, d)
        self._applied = d
        self._warm_until = t_recv + WARMUP_NS
        self._last_recv = t_recv
        self._run_start = None

    def _push(self, t_recv: int, d: int) -> None:
        while self._win and self._win[-1][1] >= d:
            self._win.pop()
        self._win.append((t_recv, d))

    def _target(self, now: int) -> int:
        while len(self._win) > 1 and self._win[0][0] < now - WINDOW_NS:
            self._win.popleft()
        return self._win[0][1]

    def _outlier(self, t_recv: int, d: int) -> None:
        if self._run_start is None or abs(d - self._run_first) > OUTLIER_NS:
            self._run_start, self._run_first, self._run_min, self._run_count = t_recv, d, d, 0
        self._run_count += 1
        self._run_min = min(self._run_min, d)
        confirmed = t_recv - self._run_start >= CONFIRM_NS and self._run_count >= CONFIRM_MIN
        held_off = self._last_reset is not None and t_recv - self._last_reset < HOLDOFF_NS
        if confirmed and not held_off:
            self.resets += 1
            self._last_reset = t_recv
            self._start(t_recv, self._run_min)


class StampGuard:
    """Per topic, a stamp never repeats or goes backwards: it becomes the previous one + 1 ns."""

    def __init__(self) -> None:
        self._last: Dict[str, int] = {}

    def __call__(self, topic: str, ns: int) -> int:
        prev = self._last.get(topic)
        if prev is not None and ns <= prev:
            ns = prev + 1
        self._last[topic] = ns
        return ns
