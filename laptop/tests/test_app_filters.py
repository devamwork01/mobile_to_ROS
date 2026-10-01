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
