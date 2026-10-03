from sensorstream.app import handle_filter_command
from sensorstream.filters import FilterBank

LP = {"lowpass": {"hz": 5.0, "order": 2}, "notches": []}


class FakeInsights:
    def __init__(self, ans):
        self.ans = ans

    def suggest(self, handle):
        return self.ans


def test_set_clear_and_broadcast():
    sent, bank = [], FilterBank()
    assert handle_filter_command({"cmd": "filter_set", "key": "1:Acc", "handle": 3, "config": LP}, bank, FakeInsights(None), sent.append)
    assert sent[-1] == {"kind": "filters", "configs": {"1:Acc": LP}}
    handle_filter_command({"cmd": "filter_clear", "key": "1:Acc"}, bank, FakeInsights(None), sent.append)
    assert sent[-1] == {"kind": "filters", "configs": {}}


def test_invalid_set_reports_error():
    sent, bank = [], FilterBank()
    handle_filter_command({"cmd": "filter_set", "key": "11:Rot", "handle": 7, "config": LP}, bank, FakeInsights(None), sent.append)
    assert sent[-1]["kind"] == "filter_error" and sent[-1]["key"] == "11:Rot"


def test_suggest_uses_live_spectrum():
    import numpy as np
    from sensorstream import analysis as an
    fs = 100.0
    f, psd = an.welch_psd(np.random.default_rng(1).normal(0, 1, 5000), fs)
    sent, bank = [], FilterBank()
    handle_filter_command({"cmd": "filter_suggest", "key": "1:Acc", "handle": 3}, bank, FakeInsights((f, psd, fs)), sent.append)
    assert sent[-1]["kind"] == "filter_suggestion" and sent[-1]["config"]["lowpass"]["order"] == 4
    handle_filter_command({"cmd": "filter_suggest", "key": "1:Acc", "handle": 3}, bank, FakeInsights(None), sent.append)
    assert sent[-1]["kind"] == "filter_error"


def test_non_filter_commands_are_ignored():
    assert handle_filter_command({"cmd": "record_start"}, FilterBank(), FakeInsights(None), [].append) is False


def test_errors_say_whether_a_filter_is_still_running():
    sent, bank = [], FilterBank()
    handle_filter_command({"cmd": "filter_set", "key": "1:Acc", "handle": 3, "config": LP}, bank, FakeInsights(None), sent.append)
    handle_filter_command({"cmd": "filter_set", "key": "1:Acc", "handle": 3, "config": {"lowpass": 5}}, bank, FakeInsights(None), sent.append)
    assert sent[-1]["kind"] == "filter_error" and sent[-1]["running"] is True    # the previous filter stays
    handle_filter_command({"cmd": "filter_set", "key": "4:G", "handle": 4, "config": {"lowpass": 5}}, bank, FakeInsights(None), sent.append)
    assert sent[-1]["running"] is False


def test_on_change_fires_only_after_successful_changes():
    calls, bank = [], FilterBank()
    on_change = lambda: calls.append(1)
    handle_filter_command({"cmd": "filter_set", "key": "1:Acc", "handle": 3, "config": LP}, bank, FakeInsights(None), [].append, on_change)
    handle_filter_command({"cmd": "filter_set", "key": "1:Acc", "handle": 3, "config": {"lowpass": 5}}, bank, FakeInsights(None), [].append, on_change)
    handle_filter_command({"cmd": "filter_clear", "key": "1:Acc"}, bank, FakeInsights(None), [].append, on_change)
    handle_filter_command({"cmd": "filter_suggest", "key": "1:Acc", "handle": 3}, bank, FakeInsights(None), [].append, on_change)
    assert calls == [1, 1]


def _spectra():
    import numpy as np
    f = np.linspace(0.5, 50, 100)
    flat = np.full_like(f, 1e-6)
    peak = np.where(f < 20, 1e-5, 1e-6)       # Z: real content up to 20 Hz ...
    peak[np.argmin(abs(f - 8))] = 1e-3          # ... plus a vibration line at 8 Hz
    return f, flat, peak


def test_suggest_returns_combined_and_per_axis():
    f, flat, peak = _spectra()
    sent = []
    handle_filter_command({"cmd": "filter_suggest", "key": "1:Acc", "handle": 3}, FilterBank(),
                          FakeInsights((f, (flat + flat + peak) / 3, 100.0, [flat, flat, peak])), sent.append)
    m = sent[-1]
    assert m["kind"] == "filter_suggestion" and len(m["axes"]) == 3
    assert m["axes"][2]["notches"] and not m["axes"][0]["notches"]


def test_suggest_with_fewer_than_three_axes_has_no_per_axis():
    f, flat, _ = _spectra()
    sent = []
    handle_filter_command({"cmd": "filter_suggest", "key": "6:P", "handle": 4}, FilterBank(),
                          FakeInsights((f, flat, 12.5, [flat])), sent.append)
    assert sent[-1]["kind"] == "filter_suggestion" and sent[-1]["axes"] is None


def test_suggest_per_axis_for_six_value_sensors_uses_first_three():
    # Review: uncalibrated sensors (6 values, 4 stored columns) got no per-axis suggestion.
    f, flat, peak = _spectra()
    sent = []
    handle_filter_command({"cmd": "filter_suggest", "key": "16:GU", "handle": 5}, FilterBank(),
                          FakeInsights((f, flat, 100.0, [flat, flat, peak, flat])), sent.append)
    assert len(sent[-1]["axes"]) == 3 and sent[-1]["axes"][2]["notches"]


def test_suggest_also_reports_per_axis_findings(tmp_path):
    import numpy as np
    from sensorstream import analysis as an
    from sensorstream.autofilter import ReportStore
    fs = 100.0
    f, psd = an.welch_psd(np.random.default_rng(1).normal(0, 1, 5000), fs)
    store = ReportStore(str(tmp_path / "filter_report.json"))
    sent, bank = [], FilterBank()
    handle_filter_command({"cmd": "filter_suggest", "key": "1:Acc", "handle": 3}, bank,
                          FakeInsights((f, psd, fs, [psd, psd, psd])), sent.append, reports=store)
    rep = [m for m in sent if m["kind"] == "filter_report"]
    assert len(rep) == 1 and rep[0]["key"] == "1:Acc" and len(rep[0]["report"]["axes"]) == 3
    assert store.records["1:Acc"]["handle"] == 3
    assert not (tmp_path / "filter_report.json").exists()  # a suggestion is not an applied filter


def test_cli_filter_flags_and_report_path():
    import os
    from sensorstream.app import build_parser, report_path
    a = build_parser().parse_args([])
    assert a.filter is False and a.filter_report is None
    assert report_path(a) == os.path.join(".", "filter_report.json")
    a = build_parser().parse_args(["--filter", "--filters-file", os.path.join("cfg", "f.json")])
    assert a.filter is True and report_path(a) == os.path.join("cfg", "filter_report.json")
    a = build_parser().parse_args(["--filter-report", "x.json"])
    assert report_path(a) == "x.json"


def test_phone_sender_targets_the_current_phone():
    import asyncio
    from sensorstream.app import make_phone_sender

    class Ctl:
        def __init__(self):
            self.sent = []

        async def send_json(self, device_id, obj):
            self.sent.append((device_id, obj))
            return True

    ctl, current = Ctl(), {"id": None}

    async def go():
        send = make_phone_sender(ctl, current)
        send({"type": "tune_prompt"})      # no phone yet: dropped
        current["id"] = 7
        send({"type": "tune_prompt", "id": 1})
        await asyncio.sleep(0)
    asyncio.run(go())
    assert ctl.sent == [(7, {"type": "tune_prompt", "id": 1})]



def test_cli_filter_keep():
    import pytest as _pytest
    from sensorstream.app import build_parser
    assert build_parser().parse_args([]).filter_keep == 0.99
    assert build_parser().parse_args(["--filter-keep", "0.95"]).filter_keep == 0.95
    for bad in ("0.3", "1.0", "x"):
        with _pytest.raises(SystemExit):
            build_parser().parse_args(["--filter-keep", bad])
