from sensorstream import protocol as p
from sensorstream.logging_sink import LOG_MAGIC, FRAME
from sensorstream import recordings


def _write_ssbin(path, frames):
    with open(path, "wb") as fh:
        fh.write(LOG_MAGIC)
        for t_recv, dg in frames:
            raw = p.encode_datagram(dg)
            fh.write(FRAME.pack(t_recv, len(raw)))
            fh.write(raw)


def _dg(handle, seq, t_sensor, val):
    return p.Datagram(device_id=1, records=[p.Record(1, handle, seq, t_sensor, 3, [val])])


def test_query_signal_dedups_and_orders(tmp_path):
    # frames written OUT OF ORDER with a duplicate (seq 1 appears twice: late-live + backfill)
    frames = [
        (10, _dg(0, 0, 100, 0.0)),
        (12, _dg(0, 2, 300, 2.0)),   # seq 1 missing here (arrived late below)
        (30, _dg(0, 1, 200, 1.0)),   # backfilled, out of order on disk
        (31, _dg(0, 1, 200, 1.0)),   # duplicate of seq 1
    ]
    path = tmp_path / "session_x.ssbin"
    _write_ssbin(str(path), frames)
    res = recordings.query_signal(str(tmp_path), "session_x", handle=0, buckets=1000)
    assert res is not None
    # 3 unique records (seq 0,1,2), ordered by t_sensor_ns, duplicate collapsed
    assert res["raw"] == 3
    assert res["t"] == [round((100 - 100) / 1e9, 6), round((200 - 100) / 1e9, 6), round((300 - 100) / 1e9, 6)]
    assert res["avg"][0] == [0.0, 1.0, 2.0]
