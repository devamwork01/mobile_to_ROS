"""A recording started before any phone connected gets its metadata when one does."""

import json
import os

from sensorstream.logging_sink import Recorder


def test_meta_written_when_the_phone_connects_after_the_recording_started(tmp_path):
    rec = Recorder()
    base = rec.start(str(tmp_path), None)            # --record at startup: no phone yet
    assert not os.path.exists(base + ".meta.json")
    rec.ensure_meta({"model": "SM-S938B", "sensors": [{"handle": 0, "name": "Accel"}]})
    with open(base + ".meta.json", encoding="utf-8") as fh:
        assert json.load(fh)["model"] == "SM-S938B"


def test_existing_meta_is_kept(tmp_path):
    rec = Recorder()
    base = rec.start(str(tmp_path), {"model": "first"})
    rec.ensure_meta({"model": "second"})             # e.g. a reconnect mid-recording
    with open(base + ".meta.json", encoding="utf-8") as fh:
        assert json.load(fh)["model"] == "first"


def test_no_op_when_not_recording(tmp_path):
    rec = Recorder()
    rec.ensure_meta({"model": "x"})
    assert os.listdir(tmp_path) == []
