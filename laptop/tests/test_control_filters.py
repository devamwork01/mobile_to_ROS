import asyncio
import json
import types

from sensorstream.control import ControlServer


class FakeWs:
    def __init__(self):
        self.sent = []

    async def send(self, text):
        self.sent.append(json.loads(text))


def test_send_filters_to_every_connected_phone():
    cs = ControlServer(5005, on_event=lambda ev: None)
    a, b = FakeWs(), FakeWs()
    cs._sessions = {1: types.SimpleNamespace(ws=a), 2: types.SimpleNamespace(ws=b)}
    cfg = {"1:Acc": {"lowpass": {"hz": 5.0, "order": 4}, "notches": []}}
    n = asyncio.run(cs.send_filters(cfg))
    assert n == 2
    assert a.sent[-1] == {"type": "filters", "configs": cfg} and b.sent[-1]["type"] == "filters"


def test_send_filters_survives_a_broken_socket():
    class Broken(FakeWs):
        async def send(self, text):
            raise ConnectionError("gone")

    cs = ControlServer(5005, on_event=lambda ev: None)
    ok = FakeWs()
    cs._sessions = {1: types.SimpleNamespace(ws=Broken()), 2: types.SimpleNamespace(ws=ok)}
    assert asyncio.run(cs.send_filters({})) == 1
