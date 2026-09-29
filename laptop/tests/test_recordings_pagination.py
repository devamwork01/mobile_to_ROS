"""Recordings list pagination: newest-first by file time, stable cursors, and only the requested
page is scanned (the list stays fast as the recordings folder grows)."""

import os

from sensorstream import protocol as p
from sensorstream import recordings
from sensorstream.logging_sink import FRAME, LOG_MAGIC


def _write(path, n_frames=3):
    with open(path, "wb") as fh:
        fh.write(LOG_MAGIC)
        for i in range(n_frames):
            raw = p.encode_datagram(p.Datagram(device_id=1, records=[p.Record(1, 0, i, 100 + i, 3, [0.0])]))
            fh.write(FRAME.pack(1000 + i, len(raw)))
            fh.write(raw)


def _make(tmp_path, n):
    """n recordings; rec_00 oldest ... rec_{n-1} newest (by mtime, deliberately NOT name order)."""
    names = [f"rec_{i:02d}" for i in range(n)]
    for i, name in enumerate(names):
        path = tmp_path / f"{name}.ssbin"
        _write(str(path))
        os.utime(path, (1_700_000_000 + i, 1_700_000_000 + i))
    return names


def _all_pages(d, limit):
    ids, cursor, pages = [], None, 0
    while True:
        page = recordings.list_recordings_page(d, limit=limit, cursor=cursor)
        ids += [r["id"] for r in page["items"]]
        pages += 1
        cursor = page["next_cursor"]
        if cursor is None:
            return ids, pages, page["total"]


def test_pages_cover_everything_newest_first_without_duplicates(tmp_path):
    names = _make(tmp_path, 23)
    ids, pages, total = _all_pages(str(tmp_path), limit=10)
    assert total == 23
    assert pages == 3
    assert ids == list(reversed(names))  # newest first, each exactly once


def test_first_page_size_and_cursor(tmp_path):
    _make(tmp_path, 5)
    page = recordings.list_recordings_page(str(tmp_path), limit=2)
    assert [r["id"] for r in page["items"]] == ["rec_04", "rec_03"]
    assert page["next_cursor"] is not None
    last = recordings.list_recordings_page(str(tmp_path), limit=10)
    assert last["next_cursor"] is None and len(last["items"]) == 5


def test_cursor_survives_deleting_the_boundary_file(tmp_path):
    _make(tmp_path, 6)
    first = recordings.list_recordings_page(str(tmp_path), limit=3)  # rec_05, rec_04, rec_03
    os.remove(tmp_path / "rec_03.ssbin")                               # boundary file vanishes
    second = recordings.list_recordings_page(str(tmp_path), limit=3, cursor=first["next_cursor"])
    assert [r["id"] for r in second["items"]] == ["rec_02", "rec_01", "rec_00"]


def test_only_the_page_is_scanned(tmp_path, monkeypatch):
    _make(tmp_path, 30)
    calls = []
    real = recordings._scan_frames
    monkeypatch.setattr(recordings, "_scan_frames", lambda path: calls.append(path) or real(path))
    recordings.list_recordings_page(str(tmp_path), limit=5)
    assert len(calls) == 5


def test_legacy_full_list_still_works(tmp_path):
    names = _make(tmp_path, 4)
    assert [r["id"] for r in recordings.list_recordings(str(tmp_path))] == list(reversed(names))


def test_missing_dir_is_empty(tmp_path):
    page = recordings.list_recordings_page(str(tmp_path / "nope"), limit=5)
    assert page == {"items": [], "next_cursor": None, "total": 0}
