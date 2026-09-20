"""Tests for the synthetic-phone signal generation (tools/fake_phone.py).

Only the pure sample() logic is exercised -- no network, no websockets. It imports
lazily, so this runs without the websockets package.
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent / "tools"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import fake_phone  # noqa: E402


def test_sample_shapes():
    s = fake_phone.sample(1.234)
    assert set(s) == {1, 4, 2, 11}
    assert len(s[1]) == 3 and len(s[4]) == 3 and len(s[2]) == 3
    assert len(s[11]) == 4  # quaternion x, y, z, w


def test_accelerometer_is_gravity_magnitude():
    # accel is gravity rotated into the device frame, so |a| == g at every instant
    for t in (0.0, 0.37, 1.9, 5.5):
        ax, ay, az = fake_phone.sample(t)[1]
        assert math.isclose(math.hypot(ax, ay, az), fake_phone.GRAVITY, rel_tol=1e-9)


def test_rotation_vector_is_unit_quaternion():
    for t in (0.0, 0.8, 3.3):
        qx, qy, qz, qw = fake_phone.sample(t)[11]
        assert math.isclose(math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw), 1.0, rel_tol=1e-9)


def test_orientation_actually_changes():
    assert fake_phone.sample(0.0)[11] != fake_phone.sample(2.0)[11]
