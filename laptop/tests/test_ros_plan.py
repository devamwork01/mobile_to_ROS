"""RosPlanner: datagram -> what the ROS 2 sink publishes (topics, values, stamps, covariances)."""

import pytest

from sensorstream import protocol as p
from sensorstream import ros_cov
from sensorstream.ros_plan import IMU_TOPICS, RosPlanner

MS = 1_000_000
R0 = 50_000_000_000          # laptop monotonic receive time of the first datagram
NOW = 1_700_000_000_000_000_000  # ROS time


def rec(type_, t_ms, values, seq=0):
    return p.Record(type_, type_, seq, int(t_ms * MS), 3, values)


def dg(*records, dev=1):
    return p.Datagram(device_id=dev, records=list(records))


def test_imu_topic_names():
    assert IMU_TOPICS == ("/phone/imu/data_raw", "/phone/imu/data",
                          "/phone/imu/data_raw_filtered", "/phone/imu/data_filtered")


def test_unknown_stamp_mode_is_refused():
    with pytest.raises(ValueError):
        RosPlanner("wall")


def test_receive_mode_stamps_with_ros_now():
    pl = RosPlanner("receive")
    out = pl.plan(dg(rec(1, 5, [0.0, 0.0, 9.8])), R0, None, NOW, R0 + 3 * MS)
    assert [o.stamp_ns for o in out] == [NOW]


def test_sensor_mode_removes_arrival_jitter():
    pl = RosPlanner()
    a = pl.plan(dg(rec(1, 0, [0.0, 0.0, 9.8])), R0, None, NOW, R0)[0]
    # 2 ms later on the phone, but it arrived 7 ms late; the planner runs 2 ms after arrival
    b = pl.plan(dg(rec(1, 2, [0.0, 0.0, 9.8], seq=1)), R0 + 9 * MS, None, NOW + 11 * MS, R0 + 11 * MS)[0]
    assert a.stamp_ns == NOW
    assert b.stamp_ns - a.stamp_ns == 2 * MS


def test_existing_topics_carry_covariance():
    pl = RosPlanner()
    pl.set_covariance({1: {"var": [0.01, 0.04, 0.09], "report": "r"}, 2: {"var": [1.0, 2.0, 3.0], "report": "r"}})
    out = pl.plan(dg(rec(1, 0, [0.0, 0.0, 9.8]), rec(4, 0, [0.1, 0.2, 0.3]), rec(2, 0, [20.0, 0.0, -40.0])),
                  R0, None, NOW, R0)
    by = {o.topic: o for o in out}
    acc = by["/phone/accelerometer"]
    assert acc.kind == "imu_accel" and acc.vals == [0.0, 0.0, 9.8]
    assert acc.cov["linear_acceleration"] == pytest.approx(ros_cov.diag([0.01, 0.04, 0.09]))
    assert acc.cov["angular_velocity"] == ros_cov.NOT_PROVIDED
    assert acc.cov["orientation"] == ros_cov.NOT_PROVIDED
    gyr = by["/phone/gyroscope"]
    assert gyr.cov["angular_velocity"] == ros_cov.UNKNOWN          # no gyro report
    assert gyr.cov["linear_acceleration"] == ros_cov.NOT_PROVIDED
    assert by["/phone/magnetic_field"].cov["magnetic_field"] == pytest.approx(ros_cov.diag([1.0, 2.0, 3.0]))


def test_combined_imu_topics_and_their_covariance():
    pl = RosPlanner()
    pl.set_covariance({1: {"var": [0.01, 0.04, 0.09], "report": "r"}, 4: {"var": [1e-6, 4e-6, 9e-6], "report": "r"}})
    out = pl.plan(dg(rec(1, 0, [0.0, 0.0, 9.8]), rec(11, 0, [0.0, 0.0, 0.6, 0.8, 0.1]), rec(4, 1, [0.1, 0.2, 0.3])),
                  R0, None, NOW, R0)
    by = {o.topic: o for o in out}
    raw, data = by["/phone/imu/data_raw"], by["/phone/imu/data"]
    assert raw.kind == data.kind == "imu"
    assert raw.vals.gyro == [0.1, 0.2, 0.3] and raw.vals.accel == [0.0, 0.0, 9.8]
    assert raw.cov["orientation"] == ros_cov.NOT_PROVIDED
    assert raw.cov["angular_velocity"] == pytest.approx(ros_cov.diag([1e-6, 4e-6, 9e-6]))
    assert raw.cov["linear_acceleration"] == pytest.approx(ros_cov.diag([0.01, 0.04, 0.09]))
    assert data.vals.quat == [0.0, 0.0, 0.6, 0.8]
    assert data.cov["orientation"] == pytest.approx(ros_cov.orientation_cov([0.01, 0.04, 0.09], 0.1))
    assert raw.stamp_ns == data.stamp_ns == by["/phone/gyroscope"].stamp_ns
    assert "/phone/imu/data_raw_filtered" not in by


def test_no_rotation_vector_means_data_raw_only():
    pl = RosPlanner()
    topics = [o.topic for o in pl.plan(dg(rec(1, 0, [0.0, 0.0, 9.8]), rec(4, 1, [0.0] * 3)), R0, None, NOW, R0)]
    assert "/phone/imu/data_raw" in topics and "/phone/imu/data" not in topics


def test_filtered_twins():
    pl = RosPlanner()
    filt = {(1, 0): [0.0, 0.0, 9.7]}
    out = pl.plan(dg(rec(1, 0, [0.0, 0.0, 9.8]), rec(11, 0, [0.0, 0.0, 0.6, 0.8]), rec(4, 1, [0.1, 0.2, 0.3])),
                  R0, filt, NOW, R0)
    by = {o.topic: o for o in out}
    assert by["/phone/imu/data_raw_filtered"].vals.accel == [0.0, 0.0, 9.7]
    assert by["/phone/imu/data_filtered"].vals.quat == [0.0, 0.0, 0.6, 0.8]
    assert by["/phone/accelerometer_filtered"].vals == [0.0, 0.0, 9.7]


def test_devices_have_separate_clocks_and_pairers():
    pl = RosPlanner()
    pl.plan(dg(rec(1, 0, [0.0, 0.0, 9.8]), dev=1), R0, None, NOW, R0)
    # device 2: a different phone clock (offset 5 s larger); its stamp maps from its own clock
    out = pl.plan(dg(rec(4, 0, [0.1, 0.2, 0.3]), dev=2), R0 + 5_000 * MS, None, NOW + 9 * MS, R0 + 5_000 * MS + 9 * MS)
    assert [o.topic for o in out] == ["/phone/gyroscope"]    # dev 1's accel is not paired with dev 2's gyro
    assert out[0].stamp_ns == NOW
