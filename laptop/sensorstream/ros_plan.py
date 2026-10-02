"""What the ROS 2 sink publishes for one datagram (pure; no rclpy, so it is testable anywhere).

For every record: the existing per-sensor topics (ros_mapping.ros_targets) and, from the device's
ImuPairer, the combined /phone/imu/* topics. Stamps: "sensor" maps the phone's event time through the
device's ClockMapper to ROS time; "receive" uses ROS time now (the pre-0.1.11 behaviour). Covariances
come from the newest Still test run (ros_cov), unknown = zeros, "not provided" = -1 first.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

from .ros_clock import ClockMapper, StampGuard
from .ros_cov import NOT_PROVIDED, UNKNOWN, diag, orientation_cov
from .ros_mapping import ImuPairer, ros_targets

STAMP_MODES = ("sensor", "receive")
# Only continuously reporting sensors feed the clock: an on-change sensor (e.g. the step counter) is
# stamped with when its value last changed, which can be an hour before it arrives.
CLOCK_TYPES = frozenset({1, 2, 4, 11})
IMU_TOPICS = ("/phone/imu/data_raw", "/phone/imu/data",
              "/phone/imu/data_raw_filtered", "/phone/imu/data_filtered")


@dataclass
class Out:
    topic: str
    kind: str
    stamp_ns: int
    vals: object
    cov: Dict[str, List[float]] = field(default_factory=dict)


class RosPlanner:
    def __init__(self, stamp: str = "sensor") -> None:
        if stamp not in STAMP_MODES:
            raise ValueError(f"stamp must be one of {STAMP_MODES}")
        self.stamp = stamp
        self._clocks: Dict[int, ClockMapper] = {}
        self._pairers: Dict[int, ImuPairer] = {}
        self._guard = StampGuard()
        self._cov: Dict[int, dict] = {}

    def set_covariance(self, cov: Optional[Dict[int, dict]]) -> None:
        self._cov = dict(cov or {})

    def _var(self, sensor_type: int) -> Optional[List[float]]:
        e = self._cov.get(sensor_type)
        return e["var"] if e else None

    def _measured(self, sensor_type: int) -> List[float]:
        v = self._var(sensor_type)
        return diag(v) if v else list(UNKNOWN)

    def _sensor_cov(self, kind: str) -> Dict[str, List[float]]:
        if kind == "imu_accel":
            return {"orientation": list(NOT_PROVIDED), "angular_velocity": list(NOT_PROVIDED),
                    "linear_acceleration": self._measured(1)}
        if kind == "imu_gyro":
            return {"orientation": list(NOT_PROVIDED), "angular_velocity": self._measured(4),
                    "linear_acceleration": list(NOT_PROVIDED)}
        if kind == "mag":
            return {"magnetic_field": self._measured(2)}
        return {}

    def _stamp(self, topic: str, clock: ClockMapper, t_sensor_ns: int, ros_now_ns: int, mono_now_ns: int) -> int:
        mono = clock.to_mono(t_sensor_ns) if self.stamp == "sensor" else None
        ns = ros_now_ns if mono is None else ros_now_ns - (mono_now_ns - mono)
        return self._guard(topic, max(0, ns))

    def plan(self, dg, t_recv_ns: int, filtered, ros_now_ns: int, mono_now_ns: int) -> List[Out]:
        clock = self._clocks.setdefault(dg.device_id, ClockMapper())
        pairer = self._pairers.setdefault(dg.device_id, ImuPairer())
        for r in dg.records:
            if r.sensor_type in CLOCK_TYPES:
                clock.observe(r.t_sensor_ns, t_recv_ns)
        out: List[Out] = []
        for r in dg.records:
            fv = filtered.get((r.sensor_handle, r.seq)) if filtered else None
            for topic, kind, vals in ros_targets(r, fv):
                out.append(Out(topic, kind, self._stamp(topic, clock, r.t_sensor_ns, ros_now_ns, mono_now_ns),
                               vals, self._sensor_cov(kind)))
            for s in pairer.on_record(r, fv):
                suffix = "_filtered" if s.filtered else ""
                cov = {"angular_velocity": self._measured(4), "linear_acceleration": self._measured(1)}
                topic = "/phone/imu/data_raw" + suffix
                out.append(Out(topic, "imu", self._stamp(topic, clock, s.t_ns, ros_now_ns, mono_now_ns), s,
                               {**cov, "orientation": list(NOT_PROVIDED)}))
                if s.quat is not None:
                    topic = "/phone/imu/data" + suffix
                    out.append(Out(topic, "imu", self._stamp(topic, clock, s.t_ns, ros_now_ns, mono_now_ns), s,
                                   {**cov, "orientation": orientation_cov(self._var(1), s.heading_acc)}))
        return out
