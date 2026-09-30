import time

import numpy as np
import pytest

from sensorstream import analysis as a

RNG = np.random.default_rng(7)


def _t(n, fs, t0=1_000_000_000):
    return (t0 + np.arange(n) * (1e9 / fs)).astype(np.int64)


def test_axis_stats_white_noise():
    v = RNG.normal(0.5, 0.01, 20000)
    s = a.axis_stats(v)
    assert s["mean"] == pytest.approx(0.5, abs=0.001)
    assert s["std"] == pytest.approx(0.01, rel=0.05)
    assert s["p2p"] > 0.05


def test_ignores_non_finite():
    s = a.axis_stats(np.array([1.0, np.nan, 3.0, np.inf]))
    assert s["mean"] == pytest.approx(2.0)


def test_rate_stats_regular_and_gap():
    t = _t(2000, 200.0)
    r = a.rate_stats(t)
    assert r["rate_hz"] == pytest.approx(200.0, rel=1e-3)
    assert r["gaps"] == 0
    t2 = np.concatenate([t[:1000], t[1000:] + 45_000_000])  # one 50 ms hole
    r2 = a.rate_stats(t2)
    assert r2["gaps"] == 1
    assert r2["jitter_ms"] > r["jitter_ms"]


def test_noise_density_of_white_noise():
    fs, sigma = 200.0, 0.01
    v = RNG.normal(0, sigma, 60000)
    f, psd = a.welch_psd(v, fs)
    nd = a.noise_density(f, psd, fs)
    assert nd == pytest.approx(sigma / np.sqrt(fs / 2), rel=0.15)


def test_psd_points_are_log_spaced_and_bounded():
    f, psd = a.welch_psd(RNG.normal(0, 1, 8000), 100.0)
    pts = a.psd_points(f, psd, n=200)
    assert 10 <= len(pts["f"]) <= 200
    assert pts["f"][0] >= 0.1
    assert all(x < y for x, y in zip(pts["f"], pts["f"][1:]))


def test_drift_slope():
    fs = 100.0
    n = 12000  # 2 min
    t = _t(n, fs)
    v = 0.3 * (t - t[0]) / 60e9 + RNG.normal(0, 0.01, n)
    assert a.drift_per_min(t, v) == pytest.approx(0.3, rel=0.02)


def test_allan_white_noise_slope_and_points():
    fs, sigma = 100.0, 0.01
    v = RNG.normal(0, sigma, 30000)
    adev = a.allan_deviation(v, fs)
    taus = np.array([p[0] for p in adev])
    sig = np.array([p[1] for p in adev])
    short = taus < 5
    slope = np.polyfit(np.log10(taus[short]), np.log10(sig[short]), 1)[0]
    assert slope == pytest.approx(-0.5, abs=0.08)
    pts = a.adev_points(adev)
    assert pts["random_walk"] == pytest.approx(sigma / np.sqrt(fs), rel=0.15)
    assert pts["bias_instability"] > 0 and pts["bi_tau"] > 0


def test_allan_random_walk_bias_turns_up():
    fs = 100.0
    n = 60000
    v = RNG.normal(0, 0.01, n) + np.cumsum(RNG.normal(0, 0.0002, n))
    adev = a.allan_deviation(v, fs)
    sig = [p[1] for p in adev]
    assert sig[-1] > min(sig)  # curve rises again at long tau


def _sensor(type_, v, fs=100.0, seq=None):
    v = np.asarray(v, dtype=np.float64)
    if v.ndim == 1:
        v = v[:, None]
    n = len(v)
    return {"handle": 3, "type": type_, "name": "S", "unit": "u", "t": _t(n, fs),
            "v": v, "seq": np.arange(n, dtype=np.int64) if seq is None else seq}


def test_analyse_sensor_still_accel_uses_gravity_on_magnitude():
    n = 13000
    v = np.column_stack([RNG.normal(0, 0.01, n), RNG.normal(0, 0.01, n), RNG.normal(9.81, 0.01, n)])
    rep = a.analyse_sensor(_sensor(1, v), "still", 130.0)
    assert rep["magnitude"]["bias"] == pytest.approx(9.81 - a.GRAVITY, abs=0.002)
    assert rep["axes"]["x"]["bias"] is None
    assert rep["rate"]["rate_hz"] == pytest.approx(100.0, rel=1e-3)
    assert "x" in rep["adev"] and rep["adev_points"]["x"]["random_walk"] > 0


def test_analyse_sensor_still_gyro_bias_is_mean_and_capture_has_none():
    n = 3000
    v = np.column_stack([RNG.normal(0.002, 0.001, n)] * 3)
    still = a.analyse_sensor(_sensor(4, v), "still", 30.0)
    assert still["axes"]["x"]["bias"] == pytest.approx(0.002, abs=2e-4)
    assert "adev" not in still  # run shorter than 120 s
    cap = a.analyse_sensor(_sensor(4, v), "capture", 30.0)
    assert cap["axes"]["x"]["bias"] is None


def test_analyse_sensor_scalar_on_change_and_uncalibrated_axes():
    light = a.analyse_sensor(_sensor(5, RNG.normal(300, 5, 500)), "still", 5.0)
    assert set(light["axes"]) == {"x"} and "magnitude" not in light
    assert light["axes"]["x"]["noise_density"] is None
    uncal = a.analyse_sensor(_sensor(16, RNG.normal(0, 0.01, (1000, 6))), "capture", 10.0)
    assert set(uncal["axes"]) == {"x", "y", "z"}


def test_insufficient_data():
    rep = a.analyse_sensor(_sensor(1, RNG.normal(0, 1, (10, 3))), "still", 1.0)
    assert rep["insufficient"] is True
    assert rep["axes"]["x"]["std"] is None


def test_lost_from_sequence_numbers():
    seq = np.array([i for i in range(200) if i not in (50, 51, 52)], dtype=np.int64)
    rep = a.analyse_sensor(_sensor(4, RNG.normal(0, 1, (len(seq), 3)), seq=seq), "capture", 2.0)
    assert rep["rate"]["lost"] == 3


def test_large_input_is_fast():
    n = 30 * 60 * 200  # 30 min at 200 Hz
    v = RNG.normal(0, 0.01, (n, 3))
    t0 = time.perf_counter()
    a.analyse_sensor(_sensor(4, v, fs=200.0), "still", 1800.0)
    assert time.perf_counter() - t0 < 10.0
