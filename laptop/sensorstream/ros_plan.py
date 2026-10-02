"""What the ROS 2 sink publishes for one datagram (pure; no rclpy, so it is testable anywhere).

For every record: the existing per-sensor topics (ros_mapping.ros_targets) and, from the device's
ImuPairer, the combined /phone/imu/* topics. Stamps: "sensor" maps the phone's event time through the
device's ClockMapper to ROS time; "receive" uses ROS time now (the pre-0.1.11 behaviour). Covariances
come from the newest Still test run (ros_cov), unknown = zeros, "not provided" = -1 first.
Magnetometer values go out in tesla (sensor_msgs/MagneticField); Android reports microtesla.
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
UT_TO_T = 1e-6
# ROS time and the monotonic clock are read one after the other per datagram (ROS first), so their
# difference wobbles by ~1 us and a slow read only ever makes it smaller. It is held; it moves up when a
# read beats it by more than ROS_READ_SLACK_NS (the held one was a slow read, or the clock stepped
# forward). A drop of more than ROS_CLOCK_STEP_NS (the clock stepped back) is taken only when the next
# read agrees - one slow read (a GIL stall) is never a step. A real backward step under 1 ms is not
# followed (bounded: the stamps stay < 1 ms late).
ROS_READ_SLACK_NS = 10_000
ROS_CLOCK_STEP_NS = 1_000_000
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
        self._ros_off: Optional[int] = None
        self._ros_drop: Optional[int] = None  # a > 1 ms drop seen once, waiting for confirmation

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
            return {"magnetic_field": [x * UT_TO_T * UT_TO_T for x in self._measured(2)]}
        return {}

    def _stamp(self, topic: str, clock: ClockMapper, t_sensor_ns: int, ros_now_ns: int, mono_now_ns: int) -> int:
        mono = clock.to_mono(t_sensor_ns) if self.stamp == "sensor" else None
        ns = ros_now_ns if mono is None else mono + self._ros_offset(ros_now_ns, mono_now_ns)
        return self._guard(topic, max(0, ns))

    def _ros_offset(self, ros_now_ns: int, mono_now_ns: int) -> int:
        sample = ros_now_ns - mono_now_ns
        if self._ros_off is None or sample - self._ros_off > ROS_READ_SLACK_NS:
            self._ros_off, self._ros_drop = sample, None
        elif self._ros_off - sample > ROS_CLOCK_STEP_NS:
            if self._ros_drop is not None and abs(sample - self._ros_drop) <= ROS_CLOCK_STEP_NS:
                self._ros_off, self._ros_drop = max(sample, self._ros_drop), None
            else:
                self._ros_drop = sample
        else:
            self._ros_drop = None
        return self._ros_off

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
                if kind == "mag":
                    vals = [x * UT_TO_T for x in vals]
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
