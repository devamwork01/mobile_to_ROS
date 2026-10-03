"""Noise-aware tuning: the cutoff where the motion sinks into the measured noise."""

import math

import numpy as np
import pytest

from sensorstream import analysis as an
from sensorstream.autofilter import axis_findings, format_report, gain_text, motion_cutoff, noise_level, sensor_report, spectra

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



def _rows(x):
    return np.stack([x, x, x], axis=1)


def _times(n, fs=FS):
    return (np.arange(n) * (S / fs)).astype(np.int64)


def test_firm_taps_do_not_push_the_cutoff_up():
    # review I1: taps of 4-8x the motion sd pushed the 99 % point to 28-41 Hz
    for taps in ([(100, 5.0), (300, 5.0), (500, 5.0), (700, 5.0), (900, 5.0)], [(450, 8.0)]):
        x = band(1000, 5.0) + noise(1000)
        for i, a in taps:
            x[i] += a
        f, fs, per = spectra(_times(1000), _rows(x), median=True)
        cfg, _ = motion_cutoff(f, per[0], N, fs)
        assert cfg["lowpass"]["hz"] < 10.0, (taps, cfg)


def test_noise_level_ignores_a_put_down_transient():
    # review I2: a put-down bump inside the still window inflated N 8-400x
    x = noise(250, seed=9)
    for k in range(5):
        x[60 + k] += 2.0 * (0.5 ** k)
    lv = noise_level(_times(250), _rows(x))
    assert lv is not None and lv[0] < 1.6 * N


def test_zero_variance_still_window_is_unusable():
    assert noise_level(_times(300), np.zeros((300, 3))) is None


def _lowpass(x, fc, fs):
    a = math.exp(-2 * math.pi * fc / fs)
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = a * acc + (1 - a) * v
        y[i] = acc
    return y


def test_noise_alone_is_never_motion():
    # review I3: one bin above 2N counted as "moved" - random filters for a phone that never moved
    false = 0
    for fs in (25.0, 50.0, 100.0):
        n = int(10 * fs)
        for seed in range(60):
            rng = np.random.default_rng(seed)
            still = rng.normal(0, 0.01, int(3 * fs))
            lv = noise_level(_times(still.size, fs), _rows(still))
            cap = rng.normal(0, 0.01, n)
            f, fsx, per = spectra(_times(n, fs), _rows(cap), median=True)
            _, det = motion_cutoff(f, per[0], lv[0], fsx)
            false += det["moved"]
    assert false == 0


def test_drifty_1_over_f_noise_is_not_motion():
    rng = np.random.default_rng(3)
    fs, n = 50.0, 500
    # 1/f-like drift: flat at 10x the white level below 1.33 Hz, meeting the white noise at ~4 Hz
    drift = _lowpass(rng.normal(0, 1, n + int(3 * fs)), 1.33, fs)
    drift *= 0.009 / drift.std()
    x = rng.normal(0, 0.01, n + int(3 * fs)) + drift
    lv = noise_level(_times(int(3 * fs), fs), _rows(x[: int(3 * fs)]))
    f, fsx, per = spectra(_times(n, fs), _rows(x[int(3 * fs):]), median=True)
    _, det = motion_cutoff(f, per[0], lv[0], fsx)
    assert det["moved"] is False


def test_real_motion_is_still_motion():
    x = band(1000, 5.0) * 0.2 + noise(1000)
    f, fs, per = spectra(_times(1000), _rows(x), median=True)
    _, det = motion_cutoff(f, per[0], N, fs)
    assert det["moved"] is True
