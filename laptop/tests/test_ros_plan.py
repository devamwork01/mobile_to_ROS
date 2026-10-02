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
    # var in uT^2 from the report -> T^2 on the wire
    assert by["/phone/magnetic_field"].cov["magnetic_field"] == pytest.approx(ros_cov.diag([1e-12, 2e-12, 3e-12]), rel=1e-9, abs=0)


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


def test_cli_has_ros_stamp():
    from sensorstream.app import build_parser
    assert build_parser().parse_args([]).ros_stamp == "sensor"
    assert build_parser().parse_args(["--ros-stamp", "receive"]).ros_stamp == "receive"
    with pytest.raises(SystemExit):
        build_parser().parse_args(["--ros-stamp", "wall"])


def test_covariances_are_floats():
    pl = RosPlanner()
    pl.set_covariance({1: {"var": [1, 4, 9], "report": "r"}, 4: {"var": [1, 1, 1], "report": "r"},
                       2: {"var": [2, 2, 2], "report": "r"}})
    out = pl.plan(dg(rec(1, 0, [0.0, 0.0, 9.8]), rec(11, 0, [0.0, 0.0, 0.6, 0.8, 0.1]), rec(4, 1, [0.1, 0.2, 0.3]),
                     rec(2, 1, [1.0, 2.0, 3.0])), R0, None, NOW, R0)
    assert {o.topic for o in out} >= {"/phone/imu/data", "/phone/magnetic_field"}
    for o in out:
        for name, m in o.cov.items():
            assert all(type(x) is float for x in m), (o.topic, name)


def test_on_change_records_do_not_seed_the_clock():
    # review: a step counter is stamped with the last step's time (maybe an hour ago); seeding the
    # mapping from it put stamps an hour in the future
    pl = RosPlanner()
    hour = 3_600_000
    out = pl.plan(dg(rec(19, 0, [5.0]), rec(1, hour, [0.0, 0.0, 9.8])), R0, None, NOW, R0)
    assert [o.stamp_ns for o in out if o.topic == "/phone/accelerometer"] == [NOW]


def test_magnetometer_is_published_in_tesla():
    # Linux check: sensor_msgs/MagneticField is tesla; Android reports microtesla
    pl = RosPlanner()
    out = pl.plan(dg(rec(2, 0, [28.0, -5.0, -40.0])), R0, {(2, 0): [27.0, -4.0, -41.0]}, NOW, R0)
    by = {o.topic: o for o in out}
    assert by["/phone/magnetic_field"].vals == pytest.approx([28e-6, -5e-6, -40e-6], rel=1e-12, abs=0)
    assert by["/phone/magnetic_field_filtered"].vals == pytest.approx([27e-6, -4e-6, -41e-6], rel=1e-12, abs=0)


def test_ros_clock_read_jitter_does_not_reach_stamps():
    # Linux check: ROS time and the monotonic clock are read one after the other per datagram, so
    # their difference wobbles by ~1 us; stamps must still follow the phone's spacing exactly
    pl = RosPlanner()
    stamps = []
    for k, (late_ms, wobble_ns) in enumerate([(1, 0), (9, -1115), (2, -471), (15, -2000), (1, -42)]):
        recv = R0 + k * 2 * MS + late_ms * MS
        out = pl.plan(dg(rec(1, k * 2, [0.0, 0.0, 9.8], seq=k)), recv, None, NOW + (recv - R0) + wobble_ns, recv)
        stamps.append(out[0].stamp_ns)
    assert [b - a for a, b in zip(stamps, stamps[1:])] == [2 * MS] * 4


def test_ros_clock_step_is_followed():
    # a real change of ROS time against the monotonic clock (NTP step, > 1 ms) is taken at once
    pl = RosPlanner()
    a = pl.plan(dg(rec(1, 0, [0.0, 0.0, 9.8])), R0, None, NOW, R0)[0]
    b = pl.plan(dg(rec(1, 2, [0.0, 0.0, 9.8], seq=1)), R0 + 2 * MS, None, NOW + 2 * MS + 5 * MS, R0 + 2 * MS)[0]
    assert b.stamp_ns - a.stamp_ns == 7 * MS


def test_a_slow_first_clock_read_is_corrected():
    # ROS time is read before the monotonic clock, so a slow read makes the difference too small;
    # a first read delayed 500 us must not leave every later stamp 500 us early
    pl = RosPlanner()
    pl.plan(dg(rec(1, 0, [0.0, 0.0, 9.8])), R0, None, NOW, R0 + 500_000)
    b = pl.plan(dg(rec(1, 2, [0.0, 0.0, 9.8], seq=1)), R0 + 2 * MS, None, NOW + 2 * MS, R0 + 2 * MS)[0]
    assert b.stamp_ns == NOW + 2 * MS
