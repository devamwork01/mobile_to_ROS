"""AutoFilter (--filter): tune accel / gyro / mag per axis 10 s into each sensor's unbroken run."""

import json

import numpy as np

from sensorstream import analysis as an
from sensorstream import protocol as p
from sensorstream.autofilter import AutoFilter, ReportStore
from sensorstream.filters import FilterBank

S = 1_000_000_000
FS = 100.0
ACC = "1:lsm6dsv_0 Accelerometer Non-wakeup"
CATALOG = [{"handle": 0, "type": 1, "name": "lsm6dsv_0 Accelerometer Non-wakeup"},
           {"handle": 2, "type": 4, "name": "lsm6dsv_0 Gyroscope Non-wakeup"},
           {"handle": 5, "type": 10, "name": "Linear Acceleration"}]


def band(n, hz, seed=2):
    X = np.fft.rfft(np.random.default_rng(seed).normal(0.0, 1.0, n))
    X[np.fft.rfftfreq(n, 1.0 / FS) > hz] = 0
    y = np.fft.irfft(X, n)
    return y / y.std()


class FakeInsights:
    """X/Y: white noise (low cutoff, no notch). Z: a 15 Hz signal band with an 8 Hz vibration line."""
    def __init__(self, ok=True):
        rng = np.random.default_rng(1)
        n = 20000
        self.f, px = an.welch_psd(rng.normal(0, 1, n), FS)
        z = band(n, 15.0) + 0.01 * rng.normal(0, 1, n) + 3 * np.sin(2 * np.pi * 8.0 * np.arange(n) / FS)
        _, pz = an.welch_psd(z, FS)
        self.src = (self.f, (px + px + pz) / 3, FS, [px, px, pz])
        self.ok = ok

    def suggest(self, handle):
        return self.src if self.ok else None


class Harness:
    def __init__(self, tmp_path, bank=None, insights=None, catalog=True):
        self.bank = bank or FilterBank()
        if catalog and hasattr(self.bank, "set_catalog"):
            self.bank.set_catalog(CATALOG)
        self.sent, self.changes, self.printed = [], [], []
        self.reports = ReportStore(str(tmp_path / "filter_report.json"))
        self.af = AutoFilter(self.bank, insights or FakeInsights(), self.reports, self.sent.append,
                             on_change=lambda: self.changes.append(1), print_fn=self.printed.append,
                             now=lambda: "2026-10-02T10:00:00+00:00")
        self.seq = {}

    def stream(self, h, ty, t0_s, secs, hz=FS):
        t, end = int(t0_s * S), int((t0_s + secs) * S)
        while t < end:
            k = self.seq.get(h, 0)
            self.seq[h] = k + 1
            self.af.on_datagram(p.Datagram(device_id=1, records=[p.Record(ty, h, k, t, 3, [0.0, 0.0, 9.8])]))
            t += int(S / hz)

    def kinds(self):
        return [m["kind"] for m in self.sent]


def test_tunes_after_10s_not_before(tmp_path):
    h = Harness(tmp_path)
    h.stream(0, 1, 0, 9)
    assert h.af.tick() == 0 and ACC not in h.bank.configs
    h.stream(0, 1, 9, 1.5)
    assert h.af.tick() == 1
    cfg = h.bank.configs[ACC]
    assert len(cfg["axes"]) == 3 and cfg["axes"][2]["notches"] and not cfg["axes"][0]["notches"]
    assert h.changes == [1]
    assert "filters" in h.kinds() and "filter_report" in h.kinds()
    assert h.printed and h.printed[-1].startswith("[filter] Acceleration")
    doc = json.loads((tmp_path / "filter_report.json").read_text(encoding="utf-8"))
    assert list(doc["sensors"]) == [ACC] and len(doc["sensors"][ACC]["axes"]) == 3


def test_once_per_connection_and_reconnect_retunes_from_fresh_data(tmp_path):
    h = Harness(tmp_path)
    h.stream(0, 1, 0, 11)
    assert h.af.tick() == 1
    h.stream(0, 1, 11, 5)
    assert h.af.tick() == 0 and h.changes == [1]
    h.af.on_phone_connected("SM-S938B", "16")
    h.stream(0, 1, 16, 5)
    assert h.af.tick() == 0
    h.stream(0, 1, 21, 6)
    assert h.af.tick() == 1 and h.changes == [1, 1]
    assert h.reports.device == {"model": "SM-S938B", "android": "16"}


def test_overwrites_a_saved_filter(tmp_path):
    h = Harness(tmp_path)
    h.bank.set(ACC, {"lowpass": {"hz": 3.0, "order": 2}, "notches": []}, handle=0)
    h.stream(0, 1, 0, 11)
    h.af.tick()
    assert "axes" in h.bank.configs[ACC]


def test_only_accel_gyro_and_mag_are_tuned(tmp_path):
    h = Harness(tmp_path)
    h.stream(5, 10, 0, 12)
    h.stream(2, 4, 0, 12)
    assert h.af.tick() == 1
    assert list(h.bank.configs) == ["4:lsm6dsv_0 Gyroscope Non-wakeup"]


def test_sensor_starting_midway_is_tuned_10s_after_its_start(tmp_path):
    h = Harness(tmp_path)
    h.stream(0, 1, 0, 20)
    h.af.tick()
    h.stream(2, 4, 20, 5)
    assert h.af.tick() == 0
    h.stream(2, 4, 25, 5.5)
    assert h.af.tick() == 1


def test_short_run_then_gap_waits_for_a_full_new_run(tmp_path):
    h = Harness(tmp_path)
    h.stream(0, 1, 0, 3)
    h.stream(0, 1, 6, 8)            # 3 s gap, then 8 s: 11 s of samples, but the run is 8 s
    assert h.af.tick() == 0
    h.stream(0, 1, 14, 2.5)
    assert h.af.tick() == 1


def test_rate_change_restarts_the_wait(tmp_path):
    h = Harness(tmp_path)
    h.stream(0, 1, 0, 6, hz=100)
    h.stream(0, 1, 6, 6, hz=50)
    assert h.af.tick() == 0
    h.stream(0, 1, 12, 6, hz=50)
    assert h.af.tick() == 1


def test_tuned_sensor_that_restarts_is_not_retuned(tmp_path):
    h = Harness(tmp_path)
    h.stream(0, 1, 0, 11)
    h.af.tick()
    h.stream(0, 1, 20, 12)          # switched off for 9 s, then on again
    assert h.af.tick() == 0 and h.changes == [1]


def test_refused_config_is_skipped_until_the_next_connection(tmp_path):
    class RefusingBank(FilterBank):
        def set(self, key, config, handle=None):
            return False, "nope"
    h = Harness(tmp_path, bank=RefusingBank())
    h.stream(0, 1, 0, 11)
    assert h.af.tick() == 0
    assert h.sent[-1] == {"kind": "filter_error", "key": ACC, "message": "nope", "running": False}
    assert "nope" in h.printed[-1]
    h.stream(0, 1, 11, 5)
    assert h.af.tick() == 0 and len([m for m in h.sent if m["kind"] == "filter_error"]) == 1
    assert not (tmp_path / "filter_report.json").exists()


def test_not_enough_live_data_is_retried(tmp_path):
    ins = FakeInsights(ok=False)
    h = Harness(tmp_path, insights=ins)
    h.stream(0, 1, 0, 11)
    assert h.af.tick() == 0
    ins.ok = True
    assert h.af.tick() == 1


def test_selftest_uses_generic_keys_and_binds_the_handle(tmp_path):
    h = Harness(tmp_path, catalog=False)
    h.stream(0, 1, 0, 11)
    h.af.tick()
    assert list(h.bank.configs) == ["1:Acceleration"]
    assert h.bank.key_of(0) == "1:Acceleration"


def test_report_store_messages_load_and_atomic_write(tmp_path):
    path = tmp_path / "r.json"
    path.write_text(json.dumps({"updated": "x", "device": None, "sensors": {"2:Mag": {"key": "2:Mag", "axes": []}}}), encoding="utf-8")
    st = ReportStore(str(path))
    st.add({"key": "1:Acc", "axes": []}, persist=False)
    assert st.messages() == [{"kind": "filter_report", "key": "1:Acc", "report": {"key": "1:Acc", "axes": []}}]
    assert json.loads(path.read_text(encoding="utf-8"))["sensors"] == {"2:Mag": {"key": "2:Mag", "axes": []}}
    st.device = {"model": "SM-S938B", "android": "16"}
    st.add({"key": "4:Gyro", "axes": []}, persist=True)
    doc = json.loads(path.read_text(encoding="utf-8"))
    assert set(doc["sensors"]) == {"2:Mag", "4:Gyro"} and doc["device"]["model"] == "SM-S938B" and doc["updated"]
    assert not (tmp_path / "r.json.tmp").exists()


def test_report_store_ignores_a_broken_file(tmp_path):
    for i, text in enumerate(["{not json", "[1, 2]", json.dumps({"sensors": 7})]):
        path = tmp_path / f"b{i}.json"
        path.write_text(text, encoding="utf-8")
        st = ReportStore(str(path))
        assert st.applied == {}
        st.add({"key": "1:Acc", "axes": []}, persist=True)
        assert list(json.loads(path.read_text(encoding="utf-8"))["sensors"]) == ["1:Acc"]


def test_waits_while_the_filter_rate_and_the_spectrum_rate_disagree(tmp_path):
    # smoke test on Windows: the spectrum said one rate, FilterBank another, and the per-axis config
    # was refused for good; disagreement now means "wait", not "fail"
    h = Harness(tmp_path)
    t = 0
    for k in range(1200):  # FilterBank sees 50 Hz; the fake spectrum says 100 Hz
        h.bank.process(p.Datagram(device_id=1, records=[p.Record(1, 0, k, t, 3, [0.0, 0.0, 9.8])]))
        t += S // 50
    h.stream(0, 1, 0, 11)
    assert h.af.tick() == 0 and ACC not in h.bank.configs
    assert not [m for m in h.sent if m["kind"] == "filter_error"]
    for k in range(1200, 3000):  # FilterBank's estimate converges to 100 Hz
        t += S // 100
        h.bank.process(p.Datagram(device_id=1, records=[p.Record(1, 0, k, t, 3, [0.0, 0.0, 9.8])]))
    assert h.af.tick() == 1


def test_a_phone_clock_reset_never_tunes_from_the_previous_session():
    # review I1: the insights ring kept the old session's samples (higher sensor times) after a
    # reboot, so the 10 s window and the tuned filter came from the previous phone session
    from sensorstream.insights import InsightsSink
    ins = InsightsSink(clock=lambda: 0.0)
    sent, printed = [], []
    bank = FilterBank()
    bank.set_catalog(CATALOG)
    af = AutoFilter(bank, ins, ReportStore(None), sent.append, print_fn=printed.append, now=lambda: "x")
    rng = np.random.default_rng(5)

    def feed(t0_s, secs, line_hz=None, seq0=0):
        n = int(secs * FS)
        for k in range(n):
            t = int((t0_s + k / FS) * S)
            z = rng.normal(0, 0.01) + (np.sin(2 * np.pi * line_hz * k / FS) if line_hz else 0.0)
            dg = p.Datagram(device_id=1, records=[p.Record(1, 0, seq0 + k, t, 3, [rng.normal(0, 0.01), rng.normal(0, 0.01), 9.8 + z])])
            ins.on_datagram(dg, None, 0)
            af.on_datagram(dg)

    feed(5000, 60, line_hz=20.0)          # old session: phone up for 5000 s, a strong 20 Hz line on Z
    af.on_phone_connected("SM-S938B", "16")
    feed(100, 12)                          # rebooted phone: clock starts low, quiet data
    assert af.tick() == 1
    z = bank.configs[ACC]["axes"][2]
    assert z["notches"] == []              # nothing from the old 20 Hz line


def test_says_once_why_a_sensor_is_still_waiting(tmp_path):
    # review M1: --filter could wait forever without a word
    h = Harness(tmp_path, insights=FakeInsights(ok=False))
    h.stream(0, 1, 0, 25)
    h.af.tick()
    assert not [m for m in h.printed if "still waiting" in m]
    h.stream(0, 1, 25, 20)
    h.af.tick()
    h.af.tick()
    waits = [m for m in h.printed if "still waiting" in m]
    assert len(waits) == 1 and ACC in waits[0] and "not enough live data" in waits[0]
