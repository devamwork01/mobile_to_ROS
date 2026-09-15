import pytest
from sensorstream import protocol as p


def test_message_constants_present():
    assert p.MSG_RESEND == "resend"
    assert p.MSG_BACKFILL == "backfill"
    assert p.MSG_BACKFILL_UNAVAILABLE == "backfill_unavailable"
    assert p.MSG_BACKFILL_ACK == "backfill_ack"


def test_frame_b64_roundtrip():
    raw = p.encode_datagram(p.Datagram(device_id=7, records=[
        p.Record(1, 0, 42, 1234, 3, [1.0, 2.0, 3.0])]))
    s = p.encode_frame_b64(raw)
    assert isinstance(s, str)
    assert p.decode_frame_b64(s) == raw


def test_decode_frame_b64_rejects_garbage():
    with pytest.raises(p.ProtocolError):
        p.decode_frame_b64("not!base64!!")
