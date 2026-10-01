import math

from sensorstream import protocol as p
from sensorstream.ros_mapping import ros_targets


def rec(type_, values):
    return p.Record(type_, 3, 0, 1, 3, values)


def test_raw_targets_unchanged():
    assert ros_targets(rec(1, [1.0, 2.0, 3.0])) == [("/phone/accelerometer", "imu_accel", [1.0, 2.0, 3.0])]
    assert ros_targets(rec(4, [0.1, 0.2, 0.3])) == [("/phone/gyroscope", "imu_gyro", [0.1, 0.2, 0.3])]
    assert ros_targets(rec(2, [10.0, 20.0, 30.0])) == [("/phone/magnetic_field", "mag", [10.0, 20.0, 30.0])]
    q = ros_targets(rec(11, [0.0, 0.0, 0.0]))
    assert q == [("/phone/orientation", "quat", [0.0, 0.0, 0.0, 1.0])]


def test_filtered_targets_only_when_filtered():
    out = ros_targets(rec(1, [1.0, 2.0, 3.0]), [0.9, 2.1, 3.0])
    assert out == [("/phone/accelerometer", "imu_accel", [1.0, 2.0, 3.0]),
                   ("/phone/accelerometer_filtered", "imu_accel", [0.9, 2.1, 3.0])]
    assert ros_targets(rec(4, [0.0] * 3), [0.1] * 3)[1][0] == "/phone/gyroscope_filtered"
    assert ros_targets(rec(2, [0.0] * 3), [1.0] * 3)[1][0] == "/phone/magnetic_field_filtered"


def test_nan_filtered_gives_no_filtered_target():
    assert len(ros_targets(rec(1, [1.0, 2.0, 3.0]), [1.0, math.nan, 3.0])) == 1
    assert len(ros_targets(rec(1, [1.0, 2.0, 3.0]), [1.0, None, 3.0])) == 1


def test_orientation_never_filtered_and_unknown_types_ignored():
    assert len(ros_targets(rec(11, [0.0, 0.0, 0.0, 1.0]), [0.0, 0.0, 0.0])) == 1
    assert ros_targets(rec(5, [300.0])) == []


def test_short_records_are_skipped():
    assert ros_targets(rec(1, [1.0, 2.0])) == []
