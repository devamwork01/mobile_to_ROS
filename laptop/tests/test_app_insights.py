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
