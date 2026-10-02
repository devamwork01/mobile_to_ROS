import math

import pytest

from sensorstream import protocol as p
from sensorstream.ros_mapping import ImuPairer, ros_targets


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



MS = 1_000_000


def r(type_, t_ms, values, seq=0):
    return p.Record(type_, type_, seq, int(t_ms * MS), 3, values)


def accel_every(pr, t0_ms, n, period_ms, v=(0.0, 0.0, 9.8)):
    for k in range(n):
        pr.on_record(r(1, t0_ms + k * period_ms, list(v)))
    return t0_ms + (n - 1) * period_ms


def test_gyro_is_paired_with_the_newest_accel():
    pr = ImuPairer()
    pr.on_record(r(1, 0, [0.0, 0.0, 9.0]))
    pr.on_record(r(1, 2, [0.0, 0.0, 9.8]))
    out = pr.on_record(r(4, 3, [0.1, 0.2, 0.3]))
    assert len(out) == 1
    s = out[0]
    assert s.t_ns == 3 * MS and s.gyro == [0.1, 0.2, 0.3] and s.accel == [0.0, 0.0, 9.8]
    assert s.quat is None and s.filtered is False


def test_no_accel_means_no_imu():
    pr = ImuPairer()
    assert pr.on_record(r(4, 0, [0.1, 0.2, 0.3])) == []
    assert pr.on_record(r(1, 1, [0.0, 0.0, 9.8])) == []   # accel never triggers a message


def test_stale_accel_is_skipped():
    pr = ImuPairer()
    last = accel_every(pr, 0, 5, 2)                       # interval 2 ms -> stale after 6 ms
    assert pr.on_record(r(4, last + 7, [0.0] * 3)) == []
    assert len(pr.on_record(r(4, last + 5, [0.0] * 3))) == 1


def test_unknown_interval_uses_100ms():
    pr = ImuPairer()
    pr.on_record(r(1, 0, [0.0, 0.0, 9.8]))
    assert len(pr.on_record(r(4, 90, [0.0] * 3))) == 1
    assert pr.on_record(r(4, 110, [0.0] * 3)) == []


def test_future_dated_accel_counts_as_fresh():
    pr = ImuPairer()
    pr.on_record(r(1, 10, [0.0, 0.0, 9.8]))
    assert len(pr.on_record(r(4, 9, [0.0] * 3))) == 1


def test_pairing_resumes_after_accel_rate_drop():
    pr = ImuPairer()
    last = accel_every(pr, 0, 20, 2)                      # 500 Hz
    last = accel_every(pr, last + 20, 16, 20)             # now 50 Hz: 16 new intervals
    assert len(pr.on_record(r(4, last + 15, [0.0] * 3))) == 1


def test_fresh_rotation_vector_adds_orientation():
    pr = ImuPairer()
    pr.on_record(r(1, 0, [0.0, 0.0, 9.8]))
    pr.on_record(r(11, 0, [0.0, 0.0, 0.6, 0.8, 0.05]))
    s = pr.on_record(r(4, 1, [0.0] * 3))[0]
    assert s.quat == [0.0, 0.0, 0.6, 0.8] and s.heading_acc == 0.05


def test_stale_rotation_vector_is_left_out():
    pr = ImuPairer()
    pr.on_record(r(11, 0, [0.0, 0.0, 0.6, 0.8, 0.05]))
    pr.on_record(r(1, 200, [0.0, 0.0, 9.8]))
    s = pr.on_record(r(4, 200, [0.0] * 3))[0]
    assert s.quat is None and s.heading_acc is None


def test_rotation_vector_without_heading_accuracy():
    pr = ImuPairer()
    pr.on_record(r(1, 0, [0.0, 0.0, 9.8]))
    pr.on_record(r(11, 0, [0.0, 0.0, 0.6, 0.8, -1.0]))
    s = pr.on_record(r(4, 1, [0.0] * 3))[0]
    assert s.quat == [0.0, 0.0, 0.6, 0.8] and s.heading_acc is None
    pr.on_record(r(11, 1, [0.0, 0.0, 0.6]))               # 3 values: w derived
    s = pr.on_record(r(4, 2, [0.0] * 3))[0]
    assert s.quat == pytest.approx([0.0, 0.0, 0.6, 0.8]) and s.heading_acc is None


def test_filtered_twin_mixes_filtered_and_raw():
    pr = ImuPairer()
    pr.on_record(r(1, 0, [0.0, 0.0, 9.8]), [0.0, 0.0, 9.7])
    out = pr.on_record(r(4, 1, [0.1, 0.2, 0.3]))
    assert [s.filtered for s in out] == [False, True]
    assert out[1].accel == [0.0, 0.0, 9.7] and out[1].gyro == [0.1, 0.2, 0.3]
    pr.on_record(r(1, 2, [0.0, 0.0, 9.8]))                 # accel no longer filtered
    out = pr.on_record(r(4, 3, [0.1, 0.2, 0.3]), [0.01, 0.02, 0.03])
    assert out[1].accel == [0.0, 0.0, 9.8] and out[1].gyro == [0.01, 0.02, 0.03]
    pr.on_record(r(1, 4, [0.0, 0.0, 9.8]))
    assert len(pr.on_record(r(4, 5, [0.1, 0.2, 0.3]), [math.nan, 0.0, 0.0])) == 1


def test_non_finite_samples_are_not_used():
    pr = ImuPairer()
    pr.on_record(r(1, 0, [0.0, 0.0, 9.8]))
    pr.on_record(r(1, 1, [math.nan, 0.0, 9.8]))            # not held: the previous accel stays
    s = pr.on_record(r(4, 2, [0.0] * 3))[0]
    assert s.accel == [0.0, 0.0, 9.8]
    assert pr.on_record(r(4, 3, [math.inf, 0.0, 0.0])) == []
