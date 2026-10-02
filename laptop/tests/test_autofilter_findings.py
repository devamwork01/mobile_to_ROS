"""Per-axis spectrum findings: what the spectrum showed and what the chosen filter does to it."""

import math

import numpy as np
import pytest

from sensorstream import analysis as an
from sensorstream.autofilter import axis_findings, format_report, sensor_report
from sensorstream.filters import suggest, suggest_details

FS = 100.0


def psd_of(x):
    return an.welch_psd(np.asarray(x, dtype=float), FS)


def noise(n=6000, sigma=1.0, seed=1):
    return np.random.default_rng(seed).normal(0.0, sigma, n)


def band(n, hz, seed=2):
    """Unit-sigma noise with no content above `hz` (a signal band, so the low-pass lands above it)."""
    X = np.fft.rfft(np.random.default_rng(seed).normal(0.0, 1.0, n))
    X[np.fft.rfftfreq(n, 1.0 / FS) > hz] = 0
    y = np.fft.irfft(X, n)
    return y / y.std()


def test_suggest_is_unchanged_and_details_carry_floor_and_prominence():
    n = 20000
    x = band(n, 15.0) + noise(n, 0.01) + 3 * np.sin(2 * math.pi * 8.0 * np.arange(n) / FS)
    f, p = psd_of(x)
    cfg, det = suggest_details(f, p, FS)
    assert cfg == suggest(f, p, FS)
    assert [round(n["hz"]) for n in cfg["notches"]] == [8]
    assert len(det["prominence"]) == 1 and det["prominence"][0] > 10
    f, p = psd_of(noise())
    _, det = suggest_details(f, p, FS)
    assert det["floor"] == pytest.approx(2.0 / FS, rel=0.3)   # white noise: PSD = 2 sigma^2 / fs


def test_too_short_spectrum_has_no_floor():
    _, det = suggest_details([1.0, 2.0], [1.0, 1.0], FS)
    assert det == {"floor": None, "prominence": []}


def test_lowpass_at_quarter_rate_halves_white_noise_variance():
    f, p = psd_of(noise())
    cfg = {"lowpass": {"hz": 25.0, "order": 4}, "notches": []}
    a = axis_findings(f, p, FS, cfg, {"floor": 0.02, "prominence": []})
    assert a["sigma_raw"] == pytest.approx(1.0, rel=0.1)
    assert (a["sigma_filtered"] / a["sigma_raw"]) ** 2 == pytest.approx(0.5, abs=0.08)
    assert a["noise_density"] == pytest.approx(math.sqrt(0.02))
    assert a["cutoff_hz"] == 25.0 and a["order"] == 4 and a["notches"] == []


def test_notch_removes_a_sine():
    t = np.arange(6000) / FS
    f0 = 20 * FS / 256   # on a Welch bin centre
    f, p = psd_of(noise(sigma=0.02) + np.sin(2 * math.pi * f0 * t))
    cfg = {"lowpass": None, "notches": [{"hz": f0, "q": 2.0}]}
    a = axis_findings(f, p, FS, cfg, {"floor": None, "prominence": [40.0]})
    assert a["sigma_filtered"] < 0.3 * a["sigma_raw"]
    assert a["notches"] == [{"hz": f0, "q": 2.0, "prominence": 40.0}]
    assert a["cutoff_hz"] is None and a["delay_ms"] == 0.0 and a["noise_density"] is None


def test_lowpass_delay():
    f, p = psd_of(noise())
    d4 = axis_findings(f, p, FS, {"lowpass": {"hz": 5.0, "order": 4}, "notches": []}, {"floor": None, "prominence": []})
    d2 = axis_findings(f, p, FS, {"lowpass": {"hz": 5.0, "order": 2}, "notches": []}, {"floor": None, "prominence": []})
    assert d4["delay_ms"] == pytest.approx(83.2, abs=0.1)
    assert d2["delay_ms"] == pytest.approx(45.0, abs=0.1)


def test_sensor_report_and_console_table():
    f, p = psd_of(noise())
    cfgs = [{"lowpass": {"hz": 4.8, "order": 4}, "notches": []}] * 2 + [
        {"lowpass": {"hz": 12.0, "order": 4}, "notches": [{"hz": 8.0, "q": 10.0}]}]
    dets = [{"floor": 1e-6, "prominence": []}] * 2 + [{"floor": 2e-6, "prominence": [35.2]}]
    rec = sensor_report("1:lsm6dsv_0 Accelerometer Non-wakeup", 0, 1, 116.53, f, [p, p, p], cfgs, dets, "2026-10-02T10:00:00+00:00")
    assert rec["key"] == "1:lsm6dsv_0 Accelerometer Non-wakeup" and rec["name"] == "lsm6dsv_0 Accelerometer Non-wakeup"
    assert rec["type"] == 1 and rec["unit"] == "m/s^2" and rec["fs"] == 116.53 and rec["handle"] == 0
    assert len(rec["axes"]) == 3 and rec["axes"][2]["notches"][0]["prominence"] == 35.2
    text = format_report(rec)
    assert text.isascii()
    lines = text.splitlines()
    assert lines[0].startswith("[filter] Acceleration (lsm6dsv_0 Accelerometer Non-wakeup) @ 116.5 Hz")
    assert lines[2].split()[0] == "X" and lines[4].split()[0] == "Z"
    assert "8 Hz (x35)" in lines[4] and "->" in lines[4]
