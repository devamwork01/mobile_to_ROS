"""Noise-aware tuning: the cutoff where the motion sinks into the measured noise."""

import math

import numpy as np
import pytest

from sensorstream import analysis as an
from sensorstream.autofilter import axis_findings, format_report, gain_text, motion_cutoff, noise_level, sensor_report

FS = 100.0
S = 1_000_000_000
N = 2 * 0.01 ** 2 / FS   # PSD level of white noise with sigma 0.01


def band(n, hz, seed=2):
    X = np.fft.rfft(np.random.default_rng(seed).normal(0.0, 1.0, n))
    X[np.fft.rfftfreq(n, 1.0 / FS) > hz] = 0
    y = np.fft.irfft(X, n)
    return y / y.std()


def noise(n, seed=1):
    return np.random.default_rng(seed).normal(0.0, 0.01, n)


def psd(x):
    return an.welch_psd(x, FS)


def test_cutoff_follows_the_motion_band():
    for hz, lo, hi in ((5.0, 4.0, 5.6), (20.0, 17.0, 21.0)):
        f, p = psd(band(1000, hz) + noise(1000))
        cfg, det = motion_cutoff(f, p, N, FS)
        assert lo <= cfg["lowpass"]["hz"] <= hi and det["moved"] is True and cfg["lowpass"]["order"] == 4


def test_pure_noise_is_no_motion():
    f, p = psd(noise(1000))
    cfg, det = motion_cutoff(f, p, N, FS)
    assert det["moved"] is False and cfg["lowpass"]["hz"] == 0.5


def test_light_handling_bursts_do_not_push_the_cutoff_up():
    x = band(1000, 5.0) + noise(1000)
    x[[100, 300, 500, 700, 900]] += 1.0          # taps / grip changes
    f, p = psd(x)
    cfg, _ = motion_cutoff(f, p, N, FS)
    assert cfg["lowpass"]["hz"] < 15.0


def test_noise_level_needs_a_clean_still_window():
    t = np.arange(0, 3 * S, S // 100, dtype=np.int64)
    v = np.random.default_rng(4).normal(0, 0.01, (t.size, 3))
    lv = noise_level(t, v)
    assert lv is not None and len(lv) == 3 and lv[0] == pytest.approx(N, rel=0.35)
    assert noise_level(t[:150], v[:150]) is None                       # 1.5 s: too short
    gap = np.concatenate([np.arange(0, S, S // 100), np.arange(int(2.5 * S), 4 * S, S // 100)]).astype(np.int64)
    assert noise_level(gap, np.zeros((gap.size, 3))) is None            # 1.5 s gap


def test_findings_noise_before_after():
    f, p = psd(noise(6000))
    a = axis_findings(f, p, FS, {"lowpass": {"hz": 25.0, "order": 4}, "notches": []},
                      {"floor": N, "prominence": []}, noise=N)
    assert a["noise_raw"] == pytest.approx(0.01, rel=0.01)              # sigma of the noise, sensor units
    assert a["noise_filtered"] / a["noise_raw"] == pytest.approx(math.sqrt(0.5), abs=0.05)
    b = axis_findings(f, p, FS, {"lowpass": {"hz": 25.0, "order": 4}, "notches": []}, {"floor": None, "prominence": []})
    assert b["noise_raw"] is None and b["noise_filtered"] is None


def test_report_table_and_gain():
    f, p = psd(noise(2000))
    cfg = {"lowpass": {"hz": 5.0, "order": 4}, "notches": []}
    rec = sensor_report("1:Acc", 0, 1, FS, f, [p, p, p], [cfg] * 3, [{"floor": N, "prominence": []}] * 3, "t",
                        noise=[N, N, N], noise_source="still")
    assert rec["noise_source"] == "still"
    text = format_report(rec)
    assert text.isascii() and "noise sd raw -> filtered" in text.splitlines()[1]
    g = gain_text(rec)
    assert g.startswith("noise ") and g.endswith("x lower") and float(g.split()[1][:-1]) > 2.5
    assert gain_text(sensor_report("1:Acc", 0, 1, FS, f, [p] * 3, [cfg] * 3, [{"floor": None, "prominence": []}] * 3, "t")) == ""
