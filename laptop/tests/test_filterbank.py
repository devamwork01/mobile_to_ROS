import pytest
import json

from sensorstream import protocol as p
from sensorstream.filters import FilterBank

LP = {"lowpass": {"hz": 5.0, "order": 2}, "notches": []}


def dg(handle, type_, i, fs=100.0, t0=1_000_000_000, vals=(1.0, 2.0, 3.0)):
    return p.Datagram(device_id=1, records=[p.Record(type_, handle, i, t0 + int(i * 1e9 / fs), 3, list(vals))])


def run(bank, handle, type_, n, fs=100.0, start=0, t0=1_000_000_000):
    out = {}
    for i in range(start, start + n):
        out.update(bank.process(dg(handle, type_, i, fs, t0)))
    return out


def test_pass_through_until_rate_known_then_filters():
    b = FilterBank()
    b.set_catalog([{"handle": 3, "type": 1, "name": "Acc"}])
    assert b.set("1:Acc", LP)[0]
    early = run(b, 3, 1, 50)          # 0.5 s: rate not yet known
    assert early == {}
    late = run(b, 3, 1, 100, start=50)
    assert (3, 149) in late and len(late[(3, 149)]) == 3


def test_no_config_or_excluded_type_gives_nothing():
    b = FilterBank()
    b.set_catalog([{"handle": 7, "type": 11, "name": "Rot"}, {"handle": 3, "type": 1, "name": "Acc"}])
    assert not b.set("11:Rot", LP)[0]           # quaternions are not filterable
    assert run(b, 3, 1, 300) == {}               # no config for Acc
    assert run(b, 7, 11, 300) == {}


def test_bind_handle_without_catalog():
    b = FilterBank()
    b.set("1:Acceleration", LP, handle=4)
    assert run(b, 4, 1, 300)


def test_rebuild_on_config_change_and_rate_drift():
    b = FilterBank()
    b.set_catalog([{"handle": 3, "type": 1, "name": "Acc"}])
    b.set("1:Acc", LP)
    run(b, 3, 1, 300, fs=100.0)
    assert abs(b.fs(3) - 100.0) < 1
    chain1 = b._chains[3]
    b.set("1:Acc", {"lowpass": {"hz": 3.0, "order": 4}, "notches": []})
    run(b, 3, 1, 10, start=300)
    assert b._chains[3] is not chain1
    chain2 = b._chains[3]
    run(b, 3, 1, 600, fs=200.0, start=400, t0=10_000_000_000)
    assert abs(b.fs(3) - 200.0) < 5 and b._chains[3] is not chain2


def test_config_invalid_for_new_rate_reports_error():
    b = FilterBank()
    b.set_catalog([{"handle": 3, "type": 1, "name": "Acc"}])
    b.set("1:Acc", {"lowpass": {"hz": 40.0, "order": 4}, "notches": []})   # fine before rate is known
    out = run(b, 3, 1, 300, fs=50.0)                                     # 0.45*50 = 22.5 Hz < 40 Hz
    assert out == {}
    errs = b.errors()
    assert errs and errs[0][0] == "1:Acc"
    assert b.errors() == []                                              # drained, reported once


def test_persistence_round_trip_and_corrupt_file(tmp_path):
    path = tmp_path / "filters.json"
    b = FilterBank(str(path))
    b.set("1:Acc", LP)
    assert json.loads(path.read_text())["1:Acc"] == LP
    assert FilterBank(str(path)).configs == {"1:Acc": LP}
    b.clear("1:Acc")
    assert FilterBank(str(path)).configs == {}
    path.write_text("{not json")
    assert FilterBank(str(path)).configs == {}
    path.write_text(json.dumps({"1:Acc": {"lowpass": {"hz": "bad"}}, "4:Gyro": LP}))
    assert FilterBank(str(path)).configs == {"4:Gyro": LP}


def test_snapshot_message():
    b = FilterBank()
    b.set("1:Acc", LP)
    assert b.snapshot() == {"kind": "filters", "configs": {"1:Acc": LP}}


def test_a_single_stream_pause_does_not_disturb_the_rate():
    # Ruling (pre-flight): one long interval (a pause) must not yank the estimate; a sustained
    # change of interval (the user changed the sampling period) is adopted.
    b = FilterBank()
    b.set_catalog([{"handle": 3, "type": 1, "name": "Acc"}])
    run(b, 3, 1, 300, fs=100.0)
    run(b, 3, 1, 100, fs=100.0, start=300, t0=1_000_000_000 + 2_000_000_000)  # 2 s pause, then 100 Hz
    assert abs(b.fs(3) - 100.0) < 2


def test_timestamp_jitter_does_not_rebuild_the_filter():
    # Browser check found the filtered spectrum 25-45 dB ABOVE raw: jittery timestamps (~3 ms on
    # 10 ms) pushed the rate estimate across the 5 % threshold, rebuilding (and zeroing) the chain.
    import numpy as np
    rng = np.random.default_rng(0)
    b = FilterBank()
    b.set_catalog([{"handle": 1, "type": 1, "name": "Acc"}])
    b.set("1:Acc", {"lowpass": {"hz": 3.0, "order": 4}, "notches": []})
    t, chains = 1_000_000_000, set()
    for i in range(6000):
        t += int(max(1e6, rng.normal(10e6, 2.8e6)))
        b.process(p.Datagram(device_id=1, records=[p.Record(1, 1, i, t, 3, [9.0, 0.0, 1.0])]))
        if 1 in b._chains:
            chains.add(id(b._chains[1]))
    assert len(chains) == 1


def test_a_new_chain_starts_in_steady_state_no_step():
    b = FilterBank()
    b.set_catalog([{"handle": 3, "type": 1, "name": "Acc"}])
    b.set("1:Acc", {"lowpass": {"hz": 3.0, "order": 4}, "notches": [{"hz": 10.0, "q": 5.0}]})
    out = run(b, 3, 1, 300)  # constant input (1, 2, 3)
    first = min(k[1] for k in out)
    assert out[(3, first)] == pytest.approx([1.0, 2.0, 3.0], abs=1e-9)


def test_wrong_typed_entries_in_file_do_not_stop_startup(tmp_path):
    path = tmp_path / "filters.json"
    path.write_text(json.dumps({"1:A": {"lowpass": 5}, "1:B": {"notches": [5]}, "4:G": LP}))
    assert FilterBank(str(path)).configs == {"4:G": LP}


def test_reconnect_resets_the_rate_estimate():
    b = FilterBank()
    b.set_catalog([{"handle": 3, "type": 1, "name": "Acc"}])
    run(b, 3, 1, 1000, fs=400.0, t0=500_000_000_000)
    assert abs(b.fs(3) - 400) < 5
    b.set_catalog([{"handle": 3, "type": 1, "name": "Acc"}])        # phone reconnected / rebooted
    run(b, 3, 1, 300, fs=50.0, t0=1_000_000_000)                     # clock restarted lower
    assert abs(b.fs(3) - 50) < 2


def test_clock_jump_back_resets_the_rate_without_a_reconnect():
    b = FilterBank()
    b.set_catalog([{"handle": 3, "type": 1, "name": "Acc"}])
    run(b, 3, 1, 1000, fs=400.0, t0=500_000_000_000)
    run(b, 3, 1, 300, fs=50.0, t0=1_000_000_000)
    assert abs(b.fs(3) - 50) < 2


def test_invalid_config_error_is_reported_once_while_rate_drifts():
    b = FilterBank()
    b.set_catalog([{"handle": 3, "type": 1, "name": "Acc"}])
    b.set("1:Acc", {"lowpass": {"hz": 40.0, "order": 4}, "notches": []})
    t = 1_000_000_000
    for i in range(3000):                       # 200 Hz drifting smoothly down to ~50 Hz
        dt = 5_000_000 + (15_000_000 * i) // 3000
        t += dt
        b.process(p.Datagram(device_id=1, records=[p.Record(1, 3, i, t, 3, [1.0, 2.0, 3.0])]))
    assert len(b.errors()) == 1
