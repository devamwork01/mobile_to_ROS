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
    assert msg.magnetic_field.x == pytest.approx(28.0e-6)    # tesla (the phone sends microtesla)
    assert msg.magnetic_field.y == pytest.approx(-5.0e-6)
    assert msg.magnetic_field.z == pytest.approx(-40.0e-6)


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


def test_filtered_accelerometer_goes_to_the_filtered_topic(ros_env):
    sink, node = ros_env
    dg = p.Datagram(device_id=1, records=[p.Record(1, 0, 7, 123, 3, [0.1, 9.8, 0.2])])
    received = _collect(node, "/phone/accelerometer_filtered", Imu,
                        lambda: sink.on_datagram(dg, ("x", 0), 0, filtered={(0, 7): [0.05, 9.81, 0.1]}))
    assert len(received) == 1
    assert received[0].linear_acceleration.y == pytest.approx(9.81)


def test_no_filter_means_no_filtered_topic_traffic(ros_env):
    sink, node = ros_env
    dg = p.Datagram(device_id=1, records=[p.Record(1, 0, 8, 123, 3, [0.1, 9.8, 0.2])])
    received = _collect(node, "/phone/accelerometer_filtered", Imu, lambda: sink.on_datagram(dg, ("x", 0), 0), timeout=0.5)
    assert received == []


def test_combined_imu_data_raw_and_data(ros_env):
    sink, node = ros_env
    sink.set_covariance({1: {"var": [0.01, 0.04, 0.09], "report": "r"}})  # tilt known -> orientation filled
    dg = p.Datagram(device_id=1, records=[
        p.Record(1, 0, 0, 1_000_000, 3, [0.0, 0.0, 9.8]),
        p.Record(11, 1, 0, 1_000_000, 3, [0.0, 0.0, 0.6, 0.8, 0.1]),
        p.Record(4, 2, 0, 2_000_000, 3, [0.1, 0.2, 0.3])])
    raw = _collect(node, "/phone/imu/data_raw", Imu, lambda: sink.on_datagram(dg, ("x", 0), 0))
    assert len(raw) == 1
    assert raw[0].angular_velocity.z == pytest.approx(0.3) and raw[0].linear_acceleration.z == pytest.approx(9.8)
    assert raw[0].orientation_covariance[0] == -1.0
    data = _collect(node, "/phone/imu/data", Imu, lambda: sink.on_datagram(dg, ("x", 0), 0))
    assert data and data[0].orientation.w == pytest.approx(0.8)
    assert data[0].orientation_covariance[8] == pytest.approx(0.01)   # heading accuracy 0.1 rad


def test_orientation_covariance_unknown_without_a_still_run(ros_env):
    sink, node = ros_env
    dg = p.Datagram(device_id=1, records=[
        p.Record(1, 0, 0, 1_000_000, 3, [0.0, 0.0, 9.8]),
        p.Record(11, 1, 0, 1_000_000, 3, [0.0, 0.0, 0.6, 0.8, 0.1]),
        p.Record(4, 2, 0, 2_000_000, 3, [0.1, 0.2, 0.3])])
    data = _collect(node, "/phone/imu/data", Imu, lambda: sink.on_datagram(dg, ("x", 0), 0))
    assert data and list(data[0].orientation_covariance) == [0.0] * 9   # unknown, never "exact"


def test_covariance_is_published(ros_env):
    sink, node = ros_env
    sink.set_covariance({1: {"var": [0.01, 0.04, 0.09], "report": "r"}})
    dg = p.Datagram(device_id=1, records=[p.Record(1, 0, 0, 123, 3, [0.1, 9.8, 0.2])])
    got = _collect(node, "/phone/accelerometer", Imu, lambda: sink.on_datagram(dg, ("x", 0), 0))
    assert list(got[0].linear_acceleration_covariance) == pytest.approx([0.01, 0, 0, 0, 0.04, 0, 0, 0, 0.09])


def test_sensor_stamps_follow_phone_spacing(ros_env):
    sink, node = ros_env
    # review: warm the clock-read path first, so a cold first read cannot shift the held offset
    # mid-test (gyroscope records from another device: nothing reaches the accelerometer topic)
    warm = p.Datagram(device_id=99, records=[p.Record(4, 9, 0, 0, 3, [0.0, 0.0, 0.0])])
    for _ in range(20):
        sink.on_datagram(warm, ("x", 0), time.monotonic_ns())
    stamps = []
    sub = node.create_subscription(Imu, "/phone/accelerometer", lambda m: stamps.append(
        m.header.stamp.sec * 1_000_000_000 + m.header.stamp.nanosec), qos_profile_sensor_data)
    t0 = time.monotonic_ns()
    for k, late_ms in enumerate([1, 9, 2, 15, 1]):   # 2 ms apart on the phone, jittery arrival
        dg = p.Datagram(device_id=1, records=[p.Record(1, 0, k, k * 2_000_000, 3, [0.0, 0.0, 9.8])])
        sink.on_datagram(dg, ("x", 0), t0 + k * 2_000_000 + late_ms * 1_000_000)
    deadline = time.monotonic() + 2.0
    while len(stamps) < 5 and time.monotonic() < deadline:
        rclpy.spin_once(node, timeout_sec=0.05)
    node.destroy_subscription(sub)
    assert [b - a for a, b in zip(stamps, stamps[1:])] == [2_000_000] * 4
