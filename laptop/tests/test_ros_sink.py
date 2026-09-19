"""Ros2Sink -> ROS topic mapping tests.

Each test feeds a synthetic Datagram straight into the sink (no UDP involved,
matching how app.py calls it from the receiver callback) and verifies what a
real ROS subscriber sees on the resulting topic. Real rclpy nodes throughout,
no mocking, matching this repo's existing integration-test style.
"""

from __future__ import annotations

import math
import time

import pytest

rclpy = pytest.importorskip("rclpy")
from geometry_msgs.msg import QuaternionStamped
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import Imu, MagneticField

from sensorstream import protocol as p
from sensorstream.ros_sink import Ros2Sink


def _collect(node, topic, msg_type, trigger, timeout=2.0):
    received = []
    sub = node.create_subscription(msg_type, topic, received.append, qos_profile_sensor_data)
    try:
        trigger()
        deadline = time.monotonic() + timeout
        while not received and time.monotonic() < deadline:
            rclpy.spin_once(node, timeout_sec=0.05)
        return received
    finally:
        node.destroy_subscription(sub)


@pytest.fixture
def ros_env():
    rclpy.init()
    sink = Ros2Sink()
    node = rclpy.create_node("test_ros_sink_subscriber")
    try:
        yield sink, node
    finally:
        node.destroy_node()
        sink.close()
        rclpy.shutdown()


def test_accelerometer_maps_to_imu_linear_acceleration(ros_env):
    sink, node = ros_env
    dg = p.Datagram(device_id=1, records=[p.Record(1, 0, 0, 123, 3, [0.1, 9.8, 0.2])])

    received = _collect(node, "/phone/accelerometer", Imu, lambda: sink.on_datagram(dg, ("x", 0), 0))

    assert len(received) == 1
    msg = received[0]
    assert msg.linear_acceleration.x == pytest.approx(0.1)
    assert msg.linear_acceleration.y == pytest.approx(9.8)
    assert msg.linear_acceleration.z == pytest.approx(0.2)
    assert msg.orientation_covariance[0] == -1.0
    assert msg.angular_velocity_covariance[0] == -1.0


def test_gyroscope_maps_to_imu_angular_velocity(ros_env):
    sink, node = ros_env
    dg = p.Datagram(device_id=1, records=[p.Record(4, 0, 0, 123, 3, [0.01, -0.02, 0.03])])

    received = _collect(node, "/phone/gyroscope", Imu, lambda: sink.on_datagram(dg, ("x", 0), 0))

    assert len(received) == 1
    msg = received[0]
    assert msg.angular_velocity.x == pytest.approx(0.01)
    assert msg.angular_velocity.y == pytest.approx(-0.02)
    assert msg.angular_velocity.z == pytest.approx(0.03)
    assert msg.orientation_covariance[0] == -1.0
    assert msg.linear_acceleration_covariance[0] == -1.0


def test_magnetic_field_maps_to_magnetic_field_msg(ros_env):
    sink, node = ros_env
    dg = p.Datagram(device_id=1, records=[p.Record(2, 0, 0, 123, 3, [28.0, -5.0, -40.0])])

    received = _collect(
        node, "/phone/magnetic_field", MagneticField, lambda: sink.on_datagram(dg, ("x", 0), 0)
    )

    assert len(received) == 1
    msg = received[0]
    assert msg.magnetic_field.x == pytest.approx(28.0)
    assert msg.magnetic_field.y == pytest.approx(-5.0)
    assert msg.magnetic_field.z == pytest.approx(-40.0)


def test_rotation_vector_with_explicit_w_maps_to_quaternion(ros_env):
    sink, node = ros_env
    dg = p.Datagram(device_id=1, records=[p.Record(11, 0, 0, 123, 3, [0.1, 0.2, 0.3, 0.9])])

    received = _collect(
        node, "/phone/orientation", QuaternionStamped, lambda: sink.on_datagram(dg, ("x", 0), 0)
    )

    assert len(received) == 1
    q = received[0].quaternion
    assert (q.x, q.y, q.z, q.w) == pytest.approx((0.1, 0.2, 0.3, 0.9))


def test_rotation_vector_without_w_derives_it(ros_env):
    sink, node = ros_env
    x, y, z = 0.1, 0.2, 0.3
    expected_w = math.sqrt(1 - x * x - y * y - z * z)
    dg = p.Datagram(device_id=1, records=[p.Record(11, 0, 0, 123, 3, [x, y, z])])

    received = _collect(
        node, "/phone/orientation", QuaternionStamped, lambda: sink.on_datagram(dg, ("x", 0), 0)
    )

    assert len(received) == 1
    q = received[0].quaternion
    assert (q.x, q.y, q.z, q.w) == pytest.approx((x, y, z, expected_w))


def test_unmapped_sensor_type_is_dropped(ros_env):
    sink, node = ros_env
    STEP_COUNTER = 19  # Android TYPE_STEP_COUNTER, not one of our four mapped types
    dg = p.Datagram(device_id=1, records=[p.Record(STEP_COUNTER, 0, 0, 123, 3, [42.0])])

    # No mapped topic exists for this sensor type, so there's nothing to subscribe to;
    # instead prove the call doesn't raise and doesn't publish on any known topic by
    # racing it against a real accelerometer record on the same call.
    accel_dg = p.Datagram(device_id=1, records=[p.Record(1, 0, 0, 123, 3, [1.0, 2.0, 3.0])])

    def trigger():
        sink.on_datagram(dg, ("x", 0), 0)
        sink.on_datagram(accel_dg, ("x", 0), 0)

    received = _collect(node, "/phone/accelerometer", Imu, trigger)

    assert len(received) == 1  # only the accelerometer record produced a message
