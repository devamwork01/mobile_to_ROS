"""Guided tuning (--filter + a phone with the "tune" capability): prompt, capture window, result."""

import numpy as np

from sensorstream import protocol as p
from sensorstream.autofilter import AutoFilter, ReportStore
from sensorstream.filters import FilterBank
from sensorstream.insights import InsightsSink

S = 1_000_000_000
FS = 100.0
CATALOG = [{"handle": 0, "type": 1, "name": "Acc"}, {"handle": 2, "type": 4, "name": "Gyro"},
           {"handle": 4, "type": 2, "name": "Mag"}]


class G:
    """A guided phone session: real InsightsSink and FilterBank, fake clock = newest sensor time."""

    def __init__(self, caps=("tune",)):
        self.now = 0.0
        self.ins = InsightsSink(clock=lambda: self.now)
        self.bank = FilterBank()
        self.bank.set_catalog(CATALOG)
        self.sent, self.phone, self.printed, self.changes = [], [], [], []
        self.af = AutoFilter(self.bank, self.ins, ReportStore(None), self.sent.append,
                             on_change=lambda: self.changes.append(1), print_fn=self.printed.append,
                             now=lambda: "t", send_phone=self.phone.append, clock=lambda: self.now)
        self.af.on_phone_connected("SM-S938B", "16", list(caps))
        self.rng = np.random.default_rng(3)
        self.seq = {}

    def stream(self, handles, t0, secs, line=None):
        """Stream the handles from t0 for secs at FS; `line` = (hz, until_s) adds a strong Z line."""
        for k in range(int(secs * FS)):
            t = t0 + k / FS
            recs = []
            for h in handles:
                ty = {0: 1, 2: 4, 4: 2}[h]
                z = 9.8 + self.rng.normal(0, 0.01)
                if line and t < line[1]:
                    z += 3 * np.sin(2 * np.pi * line[0] * t)
                n = self.seq.get(h, 0)
                self.seq[h] = n + 1
                recs.append(p.Record(ty, h, n, int(t * S), 3, [self.rng.normal(0, 0.01), self.rng.normal(0, 0.01), z]))
            dg = p.Datagram(device_id=1, records=recs)
            self.now = t
            self.ins.on_datagram(dg, None, 0)
            self.bank.process(dg)
            self.af.on_datagram(dg)
        return t0 + secs

    def prompts(self):
        return [m for m in self.phone if m["type"] == "tune_prompt"]

    def window(self, pid, frm, to, handles=(0, 2)):
        self.af.on_tune_window({"type": "tune_window", "id": pid,
                                "windows": [{"handle": h, "from_ns": int(frm * S), "to_ns": int(to * S)} for h in handles]})
        return [m for m in self.phone if m["type"] == "tune_result"][-1]


def test_prompt_after_2s_listing_streaming_untuned_sensors():
    g = G()
    g.stream([0, 2], 0, 1.5)
    g.af.tick()
    assert g.prompts() == []
    g.stream([0, 2], 1.5, 1.0)
    g.af.tick()
    [pr] = g.prompts()
    assert pr["reason"] == "connect" and pr["attention"] is False and pr["lead_s"] == 3 and pr["capture_s"] == 10
    assert [s["handle"] for s in pr["sensors"]] == [0, 2] and pr["sensors"][0]["name"] == "Acc"
    g.af.tick()
    assert len(g.prompts()) == 1  # one prompt in flight


def test_tunes_only_from_the_capture_window():
    g = G()
    g.stream([0, 2], 0, 2.5)
    g.af.tick()
    pid = g.prompts()[0]["id"]
    g.stream([0, 2], 2.5, 15, line=(20.0, 6.0))      # a strong 20 Hz line on Z, but only before t=6 s
    res = g.window(pid, 6.0, 16.5)
    assert [s["ok"] for s in res["sensors"]] == [True, True] and res["id"] == pid
    z = g.bank.configs["1:Acc"]["axes"][2]
    assert z["notches"] == []                          # nothing from outside the window
    assert res["sensors"][0]["summary"].startswith("X") and g.changes == [1, 1]


def test_short_or_gappy_windows_are_reported():
    g = G()
    g.stream([0, 2], 0, 2.5)
    g.af.tick()
    pid = g.prompts()[0]["id"]
    g.stream([0, 2], 2.5, 5)
    g.stream([0, 2], 9.0, 9)                           # 1.5 s gap
    res = g.window(pid, 3.0, 7.0)
    assert {s["message"] for s in res["sensors"]} == {"not enough data in the capture"}
    g.af.on_tune_request()
    g.af.tick()
    pid2 = g.prompts()[-1]["id"]
    res = g.window(pid2, 3.0, 17.9)
    assert {s["message"] for s in res["sensors"]} == {"data gap during the capture"}
    assert not g.bank.configs


def test_later_sensor_gets_its_own_attention_prompt_after_the_pending_one():
    g = G()
    g.stream([0, 2], 0, 2.5)
    g.af.tick()
    pid = g.prompts()[0]["id"]
    g.stream([0, 2, 4], 2.5, 13)                        # magnetometer switched on during the capture
    g.af.tick()
    assert len(g.prompts()) == 1                        # queued behind the pending prompt
    g.window(pid, 3.0, 15.4)
    g.af.tick()
    pr = g.prompts()[-1]
    assert pr["reason"] == "new_sensor" and pr["attention"] is True and [s["handle"] for s in pr["sensors"]] == [4]


def test_timeout_abandons_and_falls_back_at_60s():
    g = G()
    g.stream([0, 2], 0, 2.5)
    g.af.tick()
    g.stream([0, 2], 2.5, 34)                           # no tune_window within 3 + 0.5 + 10 + 20 s
    g.af.tick()
    assert any("no capture" in m for m in g.printed)
    g.stream([0, 2], 36.5, 20)
    g.af.tick()
    assert not g.bank.configs and len(g.prompts()) == 1  # not re-prompted, not yet 60 s
    g.stream([0, 2], 56.5, 5)
    g.af.tick()
    assert set(g.bank.configs) == {"1:Acc", "4:Gyro"}


def test_cancelled_window_falls_back_at_60s():
    g = G()
    g.stream([0, 2], 0, 2.5)
    g.af.tick()
    g.af.on_tune_window({"type": "tune_window", "id": g.prompts()[0]["id"], "cancelled": True})
    g.stream([0, 2], 2.5, 59)
    g.af.tick()
    assert set(g.bank.configs) == {"1:Acc", "4:Gyro"} and len(g.prompts()) == 1


def test_retune_prompts_all_streaming_sensors_and_retunes():
    g = G()
    g.stream([0, 2], 0, 2.5)
    g.af.tick()
    g.stream([0, 2], 2.5, 13)
    g.window(g.prompts()[0]["id"], 3.0, 15.4)
    assert g.changes == [1, 1]
    g.af.on_tune_request()
    g.af.tick()
    pr = g.prompts()[-1]
    assert pr["reason"] == "retune" and [s["handle"] for s in pr["sensors"]] == [0, 2]
    g.stream([0, 2], 15.5, 13)
    g.window(pr["id"], 16.0, 28.4)
    assert g.changes == [1, 1, 1, 1]


def test_window_for_another_prompt_is_ignored():
    g = G()
    g.stream([0, 2], 0, 2.5)
    g.af.tick()
    g.af.on_tune_window({"type": "tune_window", "id": 999, "windows": []})
    assert [m for m in g.phone if m["type"] == "tune_result"] == []


def test_malformed_window_entries_are_reported_not_raised():
    g = G()
    g.stream([0, 2], 0, 2.5)
    g.af.tick()
    g.af.on_tune_window({"type": "tune_window", "id": g.prompts()[0]["id"],
                         "windows": [{"handle": "x"}, {"handle": 0, "from_ns": "a", "to_ns": None}, 7]})
    res = [m for m in g.phone if m["type"] == "tune_result"][-1]
    assert all(s["ok"] is False for s in res["sensors"])


def test_old_phone_gets_no_prompt_and_falls_back_at_60s():
    g = G(caps=())
    g.stream([0, 2], 0, 30)
    g.af.tick()
    assert g.prompts() == [] and not g.bank.configs
    g.stream([0, 2], 30, 31)
    g.af.tick()
    assert set(g.bank.configs) == {"1:Acc", "4:Gyro"}


def test_reconnect_drops_a_pending_prompt():
    g = G()
    g.stream([0, 2], 0, 2.5)
    g.af.tick()
    pid = g.prompts()[0]["id"]
    g.af.on_phone_connected("SM-S938B", "16", ["tune"])
    g.af.on_tune_window({"type": "tune_window", "id": pid, "windows": []})
    assert [m for m in g.phone if m["type"] == "tune_result"] == []


def test_malformed_windows_never_raise_and_still_answer():
    # review I1: "windows": 5 and Infinity times raised out of the control handler (link dropped)
    for bad in (5, [{"handle": 0, "from_ns": float("inf"), "to_ns": 1}], None):
        g = G()
        g.stream([0, 2], 0, 2.5)
        g.af.tick()
        g.af.on_tune_window({"type": "tune_window", "id": g.prompts()[0]["id"], "windows": bad})
        res = [m for m in g.phone if m["type"] == "tune_result"][-1]
        assert all(s["ok"] is False for s in res["sensors"])


def test_a_report_write_failure_still_answers_the_phone(tmp_path):
    # review I1: an unwritable filter_report.json raised after the filter was set; no tune_result
    g = G()
    g.af._reports = ReportStore(str(tmp_path))      # a directory: the atomic replace fails
    g.stream([0, 2], 0, 2.5)
    g.af.tick()
    pid = g.prompts()[0]["id"]
    g.stream([0, 2], 2.5, 13)
    res = g.window(pid, 3.0, 15.4)
    assert [s["ok"] for s in res["sensors"]] == [True, True]
    assert any("report" in m.lower() and "not written" in m.lower() for m in g.printed)


def test_failed_capture_is_explained_and_falls_back_at_60s():
    # review I2: a failed capture left the sensor unfiltered for good, without a word on the console
    g = G()
    g.stream([0, 2], 0, 2.5)
    g.af.tick()
    g.window(g.prompts()[0]["id"], 0.0, 1.0)            # too short -> ok:false
    assert any("capture received" in m for m in g.printed)
    assert any("Acc" in m and "not enough data in the capture" in m for m in g.printed)
    g.stream([0, 2], 2.5, 59)
    g.af.tick()
    assert set(g.bank.configs) == {"1:Acc", "4:Gyro"} and len(g.prompts()) == 1


def test_connect_prompt_waits_for_every_streaming_sensor():
    # review M1: gyro starting 1 s after accel gave two captures back to back
    g = G()
    g.stream([0], 0, 1.0)
    g.stream([0, 2], 1.0, 1.5)                          # accel run 2.5 s, gyro run 1.5 s
    g.af.tick()
    assert g.prompts() == []
    g.stream([0, 2], 2.5, 1.0)
    g.af.tick()
    assert [s["handle"] for s in g.prompts()[0]["sensors"]] == [0, 2]


def test_old_phone_waiting_notice_is_meaningful():
    # review I3: every non-guided app printed "still waiting - None" at 40 s
    g = G(caps=())
    g.stream([0, 2], 0, 45)
    g.af.tick()
    assert not [m for m in g.printed if "None" in m]
