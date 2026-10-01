import os

import numpy as np

from sensorstream import protocol as p
from sensorstream import reports
from sensorstream.app import make_api_get
from sensorstream.logging_sink import Recorder


def test_reports_api(tmp_path):
    rec = Recorder()
    base = rec.start(str(tmp_path), {"model": "P"})
    for i in range(200):
        rec.on_datagram(p.Datagram(device_id=1, records=[p.Record(4, 4, i, 10**9 + i * 10**7, 3, [0.0, 0.1, 0.2])]), i)
    rec.stop()
    reports.analyse_recording(base + ".ssbin", preset="still")
    api = make_api_get(str(tmp_path))
    status, body = api("/api/reports", {})
    assert status == 200 and body["items"][0]["id"] == os.path.basename(base)
    status, body = api("/api/reports/" + os.path.basename(base), {})
    assert status == 200 and body["preset"] == "still"
    assert api("/api/reports/..", {})[0] == 404
    status, body = api("/api/recordings", {})
    assert status == 200 and body["items"][0]["hasReport"] is True


def test_insights_step_survives_errors():
    import asyncio
    from sensorstream.app import insights_step

    sent, ticked = [], []

    class BadInsights:
        def tick(self):
            raise RuntimeError("numpy exploded")

    class Runs:
        async def tick(self):
            ticked.append(1)

    asyncio.run(insights_step(BadInsights(), Runs(), sent.append))
    assert ticked == [1]          # the test-run state machine still advances

    class Insights:
        def tick(self):
            return [{"kind": "insights", "stats": []}]

    class BadRuns:
        async def tick(self):
            raise OSError("disk full")

    asyncio.run(insights_step(Insights(), BadRuns(), sent.append))
    assert sent == [{"kind": "insights", "stats": []}]


def test_analyse_requests_are_deduplicated():
    from sensorstream.app import AnalyseQueue

    q = AnalyseQueue()
    assert q.claim("rec1") is True
    assert q.claim("rec1") is False   # already running: a second click / browser is ignored
    q.release("rec1")
    assert q.claim("rec1") is True
