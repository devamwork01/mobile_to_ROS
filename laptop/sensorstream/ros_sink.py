"""ROS 2 output sink (the "ROS-ready hook" described in sinks.py).

Publish-only: this never subscribes to or serves anything, so it never needs
an rclpy spin loop. `on_datagram` is called synchronously from the asyncio
receiver callback and just calls `publisher.publish(...)`, which is a
non-blocking DDS write.
"""

from __future__ import annotations

from typing import Tuple

import rclpy
from geometry_msgs.msg import QuaternionStamped
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import Imu, MagneticField

from .protocol import Datagram
from .ros_mapping import ros_targets
from .sinks import OutputSink

FRAME_ID = "phone"


class Ros2Sink(OutputSink):
    def __init__(self, node_name: str = "sensorstream_ros_sink"):
        # Only shut rclpy down on close() if this sink started it (a host process may own it).
        self._owns_rclpy = not rclpy.ok()
        if self._owns_rclpy:
            rclpy.init()
        self._node: Node = rclpy.create_node(node_name)
        # Raw topics, plus *_filtered twins that only carry data while the laptop filters a sensor.
        types = {
            "/phone/accelerometer": Imu, "/phone/gyroscope": Imu, "/phone/magnetic_field": MagneticField,
            "/phone/orientation": QuaternionStamped,
            "/phone/accelerometer_filtered": Imu, "/phone/gyroscope_filtered": Imu,
            "/phone/magnetic_field_filtered": MagneticField,
        }
        self._pubs = {t: self._node.create_publisher(m, t, qos_profile_sensor_data) for t, m in types.items()}

    def _stamp(self, msg) -> None:
        msg.header.stamp = self._node.get_clock().now().to_msg()
        msg.header.frame_id = FRAME_ID

    def on_datagram(self, dg: Datagram, addr: Tuple[str, int], t_recv_ns: int, filtered=None) -> None:
        for r in dg.records:
            fv = filtered.get((r.sensor_handle, r.seq)) if filtered else None
            for topic, kind, vals in ros_targets(r, fv):
                self._pubs[topic].publish(self._build(kind, vals))

    def _build(self, kind: str, vals):
        if kind == "quat":
            msg = QuaternionStamped()
            self._stamp(msg)
            msg.quaternion.x, msg.quaternion.y, msg.quaternion.z, msg.quaternion.w = vals
            return msg
        if kind == "mag":
            msg = MagneticField()
            self._stamp(msg)
            msg.magnetic_field.x, msg.magnetic_field.y, msg.magnetic_field.z = vals
            return msg
        msg = Imu()
        self._stamp(msg)
        msg.orientation_covariance[0] = -1.0
        if kind == "imu_accel":
            msg.linear_acceleration.x, msg.linear_acceleration.y, msg.linear_acceleration.z = vals
            msg.angular_velocity_covariance[0] = -1.0
        else:
            msg.angular_velocity.x, msg.angular_velocity.y, msg.angular_velocity.z = vals
            msg.linear_acceleration_covariance[0] = -1.0
        return msg

    def close(self) -> None:
        self._node.destroy_node()
        if self._owns_rclpy and rclpy.ok():
            rclpy.shutdown()
