import math

import numpy as np
import pytest

from sensorstream import analysis as an
from sensorstream import filters as fl

RNG = np.random.default_rng(11)


def tone(f, fs, n=8000, amp=1.0):
    return amp * np.sin(2 * np.pi * f * np.arange(n) / fs)


def gain_db(cfg, fs, f):
    y = fl.FilterChain(cfg, fs, 1).process_block(tone(f, fs)[:, None])[:, 0]
    tail = y[len(y) // 2:]
    return 20 * math.log10(np.sqrt(np.mean(tail ** 2)) * math.sqrt(2))


@pytest.mark.parametrize("order", [2, 4])
def test_butterworth_is_minus_3db_at_cutoff(order):
    cfg = {"lowpass": {"hz": 10.0, "order": order}, "notches": []}
    assert gain_db(cfg, 200.0, 10.0) == pytest.approx(-3.01, abs=0.3)


def test_fourth_order_lowpass_rejects_and_passes():
    cfg = {"lowpass": {"hz": 5.0, "order": 4}, "notches": []}
    assert gain_db(cfg, 200.0, 50.0) <= -30
    assert gain_db(cfg, 200.0, 1.0) == pytest.approx(0.0, abs=0.5)


def test_notch_removes_its_tone_and_passes_double():
    cfg = {"lowpass": None, "notches": [{"hz": 8.0, "q": 10.0}]}
    assert gain_db(cfg, 100.0, 8.0) <= -30
    assert gain_db(cfg, 100.0, 16.0) == pytest.approx(0.0, abs=1.0)


def test_chunked_equals_one_shot():
    cfg = {"lowpass": {"hz": 7.0, "order": 4}, "notches": [{"hz": 3.0, "q": 5.0}]}
    x = RNG.normal(0, 1, (1000, 3))
    one = fl.FilterChain(cfg, 100.0, 3).process_block(x)
    ch = fl.FilterChain(cfg, 100.0, 3)
    parts = np.vstack([ch.process_block(x[i:i + 137]) for i in range(0, 1000, 137)])
    assert np.allclose(one, parts)


def test_frequency_response_matches_measured_gain():
    cfg = {"lowpass": {"hz": 10.0, "order": 4}, "notches": [{"hz": 25.0, "q": 8.0}]}
    for f in (2.0, 10.0, 18.0, 40.0):
        h = fl.frequency_response(cfg, 200.0, np.array([f]))[0]
        assert 20 * math.log10(h) == pytest.approx(gain_db(cfg, 200.0, f), abs=0.5)


def test_validate_ranges():
    ok = {"lowpass": {"hz": 10.0, "order": 4}, "notches": [{"hz": 5.0, "q": 10.0}]}
    assert fl.validate(ok, 100.0)[0]
    assert not fl.validate({"lowpass": {"hz": 46.0, "order": 4}, "notches": []}, 100.0)[0]
    assert not fl.validate({"lowpass": {"hz": 10.0, "order": 3}, "notches": []}, 100.0)[0]
    assert not fl.validate({"lowpass": None, "notches": [{"hz": 5.0, "q": 0.5}]}, 100.0)[0]
    assert not fl.validate({"lowpass": None, "notches": [{"hz": 5.0, "q": 10}] * 4}, 100.0)[0]
    assert not fl.validate({"lowpass": None, "notches": []}, 100.0)[0]  # nothing to do
    assert not fl.validate({"lowpass": {"hz": "x", "order": 4}, "notches": []}, 100.0)[0]


def test_nan_input_does_not_poison_state():
    ch = fl.FilterChain({"lowpass": {"hz": 5.0, "order": 2}, "notches": []}, 100.0, 1)
    for _ in range(200):
        ch.process([1.0])
    out = ch.process([float("nan")])
    assert math.isnan(out[0])
    assert ch.process([1.0])[0] == pytest.approx(1.0, abs=1e-3)


def _band_signal(fs, n, edge_hz, amp):
    white = RNG.normal(0, 1, (n, 1))
    lp = fl.FilterChain({"lowpass": {"hz": edge_hz, "order": 4}, "notches": []}, fs, 1)
    return amp * lp.process_block(white)[:, 0]


def test_suggest_finds_vibration_peak_inside_the_signal_band():
    fs, n = 100.0, 20000
    x = _band_signal(fs, n, 15.0, 1.0) + 0.01 * RNG.normal(0, 1, n) + 3 * np.sin(2 * np.pi * 8.0 * np.arange(n) / fs)
    f, p = an.welch_psd(x, fs)
    cfg = fl.suggest(f, p, fs)
    assert cfg["lowpass"]["order"] == 4 and cfg["lowpass"]["hz"] > 8.0
    assert any(abs(nt["hz"] - 8.0) <= 0.5 for nt in cfg["notches"])
    assert fl.validate(cfg, fs)[0]


def test_suggest_cutoff_follows_the_noise_knee():
    fs, n = 200.0, 40000
    x = _band_signal(fs, n, 5.0, 10.0) + RNG.normal(0, 1, n)
    f, p = an.welch_psd(x, fs)
    cfg = fl.suggest(f, p, fs)
    assert 4.0 <= cfg["lowpass"]["hz"] <= 12.0
    assert cfg["notches"] == []


def test_suggest_on_pure_noise_is_valid():
    fs = 100.0
    f, p = an.welch_psd(RNG.normal(0, 1, 10000), fs)
    cfg = fl.suggest(f, p, fs)
    assert fl.validate(cfg, fs)[0] and cfg["lowpass"]["hz"] >= 0.5


def test_validate_rejects_wrong_types_without_raising():
    for bad in ({"lowpass": 5}, {"lowpass": None, "notches": [5]}, {"lowpass": None, "notches": "x"},
                {"lowpass": [1, 2], "notches": []}):
        assert fl.validate(bad, 100.0)[0] is False


def test_suggested_cutoff_always_passes_validation_with_rate_margin():
    # Rising spectrum: everything above the floor, so the cutoff lands at the band edge.
    fs = 116.4
    f = np.linspace(0.2, fs / 2, 300)
    cfg = fl.suggest(f, 10.0 ** f, fs)  # steep rise: the cutoff lands exactly on the band edge
    for fs_server in (fs, fs * 0.98):  # the server's EWMA rate can differ slightly
        assert fl.validate(cfg, fs_server)[0]


# --- per-axis filters ------------------------------------------------------------------------
AX = {"lowpass": {"hz": 0.5, "order": 4}, "notches": []}
AZ = {"lowpass": {"hz": 5.0, "order": 4}, "notches": [{"hz": 8.0, "q": 10.0}]}
PER = {"axes": [AX, AX, AZ]}


def test_per_axis_chain_equals_three_single_axis_chains():
    rng = np.random.default_rng(7)
    x = rng.normal(0, 1, (300, 3)) + [0, 0, 9.8]
    got = fl.FilterChain(fl.normalise(PER), 100.0, 3).process_block(x)
    for a, cfg in enumerate([AX, AX, AZ]):
        want = fl.FilterChain(cfg, 100.0, 1).process_block(x[:, a:a + 1])[:, 0]
        assert np.allclose(got[:, a], want, atol=1e-12)


def test_normalise_mirrors_x_and_keeps_plain_configs():
    n = fl.normalise(PER)
    assert n["lowpass"] == AX["lowpass"] and n["notches"] == [] and len(n["axes"]) == 3
    assert "axes" not in fl.normalise({"lowpass": {"hz": 5.0, "order": 4}, "notches": []})


def test_validate_names_the_failing_axis():
    ok, msg = fl.validate({"axes": [AX, AX, {"lowpass": {"hz": 40.0, "order": 4}, "notches": []}]}, 50.0)
    assert not ok and msg.startswith("Z: ")
    assert not fl.validate({"axes": [AX, AX]}, 100.0)[0]                       # must be 3 entries
    assert not fl.validate({"axes": [AX, AX, {"lowpass": None, "notches": []}]}, 100.0)[0]
    assert fl.validate(PER, 100.0)[0]


def test_scalar_stream_uses_axis_x_only():
    ch = fl.FilterChain(fl.normalise(PER), 100.0, 1)
    assert len(ch.process([1013.0])) == 1


def test_bank_stores_and_snapshots_per_axis():
    b = fl.FilterBank(None)
    ok, _ = b.set("1:acc", PER)
    assert ok and b.snapshot()["configs"]["1:acc"]["axes"][2] == AZ
    assert b.snapshot()["configs"]["1:acc"]["lowpass"] == AX["lowpass"]
