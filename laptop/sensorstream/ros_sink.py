"""ROS 2 output sink (the "ROS-ready hook" described in sinks.py).

Publish-only: this never subscribes to or serves anything, so it never needs an rclpy spin loop.
`on_datagram` is called synchronously from the asyncio receiver callback and just calls
`publisher.publish(...)`, which is a non-blocking DDS write. What to publish (topics, values, stamps,
covariances) is decided by the pure RosPlanner; this file only builds the messages.
"""

from __future__ import annotations

import time
from typing import Tuple

import rclpy
from builtin_interfaces.msg import Time
from geometry_msgs.msg import QuaternionStamped
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import Imu, MagneticField

from .protocol import Datagram
from .ros_plan import IMU_TOPICS, Out, RosPlanner
from .sinks import OutputSink

FRAME_ID = "phone"


class Ros2Sink(OutputSink):
    def __init__(self, node_name: str = "sensorstream_ros_sink", stamp: str = "sensor"):
        self._plan = RosPlanner(stamp)
        # Only shut rclpy down on close() if this sink started it (a host process may own it).
        self._owns_rclpy = not rclpy.ok()
        if self._owns_rclpy:
            rclpy.init()
        self._node: Node = rclpy.create_node(node_name)
        # Raw topics, *_filtered twins that only carry data while the laptop filters a sensor,
        # and the combined IMU topics (imu_tools naming).
        types = {
            "/phone/accelerometer": Imu, "/phone/gyroscope": Imu, "/phone/magnetic_field": MagneticField,
            "/phone/orientation": QuaternionStamped,
            "/phone/accelerometer_filtered": Imu, "/phone/gyroscope_filtered": Imu,
            "/phone/magnetic_field_filtered": MagneticField,
            **{t: Imu for t in IMU_TOPICS},
        }
        self._pubs = {t: self._node.create_publisher(m, t, qos_profile_sensor_data) for t, m in types.items()}

    def set_covariance(self, cov) -> None:
        self._plan.set_covariance(cov)

    def on_datagram(self, dg: Datagram, addr: Tuple[str, int], t_recv_ns: int, filtered=None) -> None:
        ros_now = self._node.get_clock().now().nanoseconds
        mono_now = time.monotonic_ns()
        for o in self._plan.plan(dg, t_recv_ns, filtered, ros_now, mono_now):
            self._pubs[o.topic].publish(self._build(o))

    def _build(self, o: Out):
        if o.kind == "quat":
            msg = QuaternionStamped()
            msg.quaternion.x, msg.quaternion.y, msg.quaternion.z, msg.quaternion.w = o.vals
        elif o.kind == "mag":
            msg = MagneticField()
            msg.magnetic_field.x, msg.magnetic_field.y, msg.magnetic_field.z = o.vals
            msg.magnetic_field_covariance = o.cov["magnetic_field"]
        else:
            msg = Imu()
            if o.kind == "imu":
                s = o.vals
                msg.angular_velocity.x, msg.angular_velocity.y, msg.angular_velocity.z = s.gyro
                msg.linear_acceleration.x, msg.linear_acceleration.y, msg.linear_acceleration.z = s.accel
                if s.quat is not None:
                    msg.orientation.x, msg.orientation.y, msg.orientation.z, msg.orientation.w = s.quat
            elif o.kind == "imu_accel":
                msg.linear_acceleration.x, msg.linear_acceleration.y, msg.linear_acceleration.z = o.vals
            else:
                msg.angular_velocity.x, msg.angular_velocity.y, msg.angular_velocity.z = o.vals
            msg.orientation_covariance = o.cov["orientation"]
            msg.angular_velocity_covariance = o.cov["angular_velocity"]
            msg.linear_acceleration_covariance = o.cov["linear_acceleration"]
        msg.header.stamp = Time(sec=o.stamp_ns // 1_000_000_000, nanosec=o.stamp_ns % 1_000_000_000)
        msg.header.frame_id = FRAME_ID
        return msg

    def close(self) -> None:
        self._node.destroy_node()
        if self._owns_rclpy and rclpy.ok():
            rclpy.shutdown()
