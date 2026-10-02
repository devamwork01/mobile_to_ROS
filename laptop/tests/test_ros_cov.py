"""IMU / magnetometer covariance from the newest Still test run of the same phone model."""

import json
import math

import pytest

from sensorstream import ros_cov


def axes(sx, sy, sz):
    return {"x": {"std": sx}, "y": {"std": sy}, "z": {"std": sz}}


def sensor(type_, sx=0.1, sy=0.2, sz=0.3, samples=1000, insufficient=False):
    return {"type": type_, "samples": samples, "insufficient": insufficient, "axes": axes(sx, sy, sz)}


def report(tmp_path, rid, created, sensors, model="SM-S938B", preset="still"):
    (tmp_path / f"{rid}.report.json").write_text(json.dumps(
        {"id": rid, "created": created, "preset": preset, "device": {"model": model, "android": "16"},
         "sensors": sensors}), encoding="utf-8")


def test_newest_matching_still_report_wins(tmp_path):
    report(tmp_path, "old", "2026-10-01T10:00:00+00:00", [sensor(1, 1, 1, 1)])
    report(tmp_path, "new", "2026-10-02T10:00:00+00:00", [sensor(1, 0.1, 0.2, 0.3)])
    cov = ros_cov.find_covariance(str(tmp_path), "SM-S938B")
    assert cov[1]["report"] == "new"
    assert cov[1]["var"] == pytest.approx([0.01, 0.04, 0.09])


def test_each_type_falls_back_to_an_older_report(tmp_path):
    report(tmp_path, "old", "2026-10-01T10:00:00+00:00", [sensor(4, 0.001, 0.002, 0.003), sensor(2, 1, 1, 1)])
    report(tmp_path, "new", "2026-10-02T10:00:00+00:00", [sensor(1)])
    cov = ros_cov.find_covariance(str(tmp_path), "SM-S938B")
    assert cov[1]["report"] == "new" and cov[4]["report"] == "old" and cov[2]["report"] == "old"


def test_other_models_and_capture_runs_are_ignored(tmp_path):
    report(tmp_path, "m30", "2026-10-02T10:00:00+00:00", [sensor(1)], model="SM-M307F")
    report(tmp_path, "cap", "2026-10-02T11:00:00+00:00", [sensor(1)], preset="capture")
    assert ros_cov.find_covariance(str(tmp_path), "SM-S938B") == {}
    assert ros_cov.find_covariance(str(tmp_path), "") == {}


def test_unusable_entries_are_skipped(tmp_path):
    report(tmp_path, "a", "2026-10-02T10:00:00+00:00",
           [sensor(1, insufficient=True), sensor(4, None, 0.1, 0.1), sensor(2, math.nan, 1, 1)])
    assert ros_cov.find_covariance(str(tmp_path), "SM-S938B") == {}


def test_most_sampled_entry_of_a_type_wins(tmp_path):
    report(tmp_path, "a", "2026-10-02T10:00:00+00:00",
           [sensor(1, 9, 9, 9, samples=10), sensor(1, 0.1, 0.1, 0.1, samples=5000)])
    assert ros_cov.find_covariance(str(tmp_path), "SM-S938B")[1]["var"] == pytest.approx([0.01] * 3)


def test_malformed_reports_are_skipped(tmp_path):
    (tmp_path / "bad.report.json").write_text("{not json", encoding="utf-8")
    (tmp_path / "list.report.json").write_text("[1, 2]", encoding="utf-8")
    (tmp_path / "nodev.report.json").write_text(json.dumps({"preset": "still", "sensors": [sensor(1)]}), encoding="utf-8")
    report(tmp_path, "none", "2026-10-02T10:00:00+00:00", [sensor(1)], model=None)
    report(tmp_path, "ok", "2026-10-01T10:00:00+00:00", [sensor(1)])
    assert ros_cov.find_covariance(str(tmp_path), "SM-S938B")[1]["report"] == "ok"


def test_matrices():
    assert ros_cov.diag([1.0, 2.0, 3.0]) == [1.0, 0, 0, 0, 2.0, 0, 0, 0, 3.0]
    assert ros_cov.UNKNOWN == [0.0] * 9
    assert ros_cov.NOT_PROVIDED == [-1.0] + [0.0] * 8


def test_orientation_covariance():
    full = ros_cov.orientation_cov([0.01, 0.04, 0.09], 0.1)
    rp = ((0.1 + 0.2) / 2 / 9.81) ** 2
    assert full == pytest.approx(ros_cov.diag([rp, rp, 0.01]))
    assert ros_cov.orientation_cov(None, 0.1) == pytest.approx(ros_cov.diag([0.0, 0.0, 0.01]))
    assert ros_cov.orientation_cov([0.01, 0.04, 0.09], None) == pytest.approx(ros_cov.diag([rp, rp, 0.0]))
    assert ros_cov.orientation_cov(None, None) == ros_cov.UNKNOWN


def test_describe_is_ascii():
    none = ros_cov.describe({}, "SM-S938B")
    assert none == "ROS covariance: none for SM-S938B - run a Still test run to fill it"
    some = ros_cov.describe({1: {"var": [0] * 3, "report": "r1"}, 4: {"var": [0] * 3, "report": "r2"}}, "SM-S938B")
    assert some == "ROS covariance: SM-S938B - accel from r1, gyro from r2"
    assert some.isascii() and none.isascii()
