"""ROS 2 output sink (the "ROS-ready hook" described in sinks.py).

Publish-only: this never subscribes to or serves anything, so it never needs
an rclpy spin loop. `on_datagram` is called synchronously from the asyncio
receiver callback and just calls `publisher.publish(...)`, which is a
non-blocking DDS write.
"""

from __future__ import annotations

import math
from typing import Tuple

import rclpy
from geometry_msgs.msg import QuaternionStamped
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import Imu, MagneticField

from .protocol import Datagram
from .sinks import OutputSink

FRAME_ID = "phone"

SENSOR_TYPE_ACCELEROMETER = 1
SENSOR_TYPE_MAGNETIC_FIELD = 2
SENSOR_TYPE_GYROSCOPE = 4
SENSOR_TYPE_ROTATION_VECTOR = 11


class Ros2Sink(OutputSink):
    def __init__(self, node_name: str = "sensorstream_ros_sink"):
        if not rclpy.ok():
            rclpy.init()
        self._node: Node = rclpy.create_node(node_name)
        self._accel_pub = self._node.create_publisher(Imu, "/phone/accelerometer", qos_profile_sensor_data)
        self._gyro_pub = self._node.create_publisher(Imu, "/phone/gyroscope", qos_profile_sensor_data)
        self._mag_pub = self._node.create_publisher(
            MagneticField, "/phone/magnetic_field", qos_profile_sensor_data
        )
        self._orientation_pub = self._node.create_publisher(
            QuaternionStamped, "/phone/orientation", qos_profile_sensor_data
        )

    def _stamp(self, msg) -> None:
        msg.header.stamp = self._node.get_clock().now().to_msg()
        msg.header.frame_id = FRAME_ID

    def on_datagram(self, dg: Datagram, addr: Tuple[str, int], t_recv_ns: int) -> None:
        for r in dg.records:
            if r.sensor_type == SENSOR_TYPE_ACCELEROMETER:
                msg = Imu()
                self._stamp(msg)
                msg.linear_acceleration.x, msg.linear_acceleration.y, msg.linear_acceleration.z = r.values
                msg.orientation_covariance[0] = -1.0
                msg.angular_velocity_covariance[0] = -1.0
                self._accel_pub.publish(msg)
            elif r.sensor_type == SENSOR_TYPE_GYROSCOPE:
                msg = Imu()
                self._stamp(msg)
                msg.angular_velocity.x, msg.angular_velocity.y, msg.angular_velocity.z = r.values
                msg.orientation_covariance[0] = -1.0
                msg.linear_acceleration_covariance[0] = -1.0
                self._gyro_pub.publish(msg)
            elif r.sensor_type == SENSOR_TYPE_MAGNETIC_FIELD:
                msg = MagneticField()
                self._stamp(msg)
                msg.magnetic_field.x, msg.magnetic_field.y, msg.magnetic_field.z = r.values
                self._mag_pub.publish(msg)
            elif r.sensor_type == SENSOR_TYPE_ROTATION_VECTOR:
                x, y, z = r.values[0], r.values[1], r.values[2]
                w = r.values[3] if len(r.values) >= 4 else math.sqrt(max(0.0, 1.0 - x * x - y * y - z * z))
                msg = QuaternionStamped()
                self._stamp(msg)
                msg.quaternion.x, msg.quaternion.y, msg.quaternion.z, msg.quaternion.w = x, y, z, w
                self._orientation_pub.publish(msg)

    def close(self) -> None:
        self._node.destroy_node()
