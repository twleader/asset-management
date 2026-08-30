from copy import deepcopy
from dataclasses import FrozenInstanceError
from datetime import timedelta
from decimal import Decimal
import json

import pytest

from fubon_broker_service.stock_push_events import decode_stock_message, normalize_stock_message
from fubon_broker_service.normalization import canonical_number

from stock_push_fixtures import MICROS, STOCK_NOW, TRADE_AT, packet


def normalized(source=None, *, now=STOCK_NOW, confirmed=None):
    return normalize_stock_message(source or packet(), confirmed=confirmed or {"2330": "channel:2330"}, now=now)


def test_complete_immutable_actual_trade_event_ignores_trial_prices_books_and_volume():
    event = normalized()
    assert event is not None
    assert event.price == "123.5" and event.tradeSize == 3 and event.tradeTimeMicros == MICROS
    assert event.name == "測試股票" and event.previousClose == "122"
    assert event.openPrice == "122.5" and event.highPrice == "124" and event.lowPrice == "121.5"
    assert event.sourceDate == "2026-08-28" and event.source == "FUBON_WS_AGGREGATES"
    assert event.buyPrice is event.sellPrice is event.volume is None
    frame = event.sse_bytes().decode()
    assert frame.startswith(f"event: stock-price\nid: 2330:{MICROS}\ndata: ") and frame.endswith("\n\n")
    data = json.loads(frame.split("data: ", 1)[1])
    assert set(data) == {"symbol", "exchange", "type", "market", "source", "sourceDate", "tradeTimeMicros", "tradeSize",
                         "price", "name", "previousClose", "openPrice", "highPrice", "lowPrice", "buyPrice", "sellPrice", "volume"}
    with pytest.raises(FrozenInstanceError):
        event.price = "9000"


@pytest.mark.parametrize("key,value", [("isTrial", True), ("isTrial", "false"), ("isTrial", 0),
                                       ("isTrial", None), ("intradayOddLot", True), ("symbol", "0050"),
                                       ("symbol", "0000"), ("exchange", "NYSE"), ("type", "INDEX"),
                                       ("date", "20260828"), ("date", "2026/08/28"), ("date", "2026-02-30"),
                                       ("date", "2026-08-27"), ("date", "2026-08-29"), ("lastTrade", None)])
def test_required_actual_identity_or_boolean_rejections(key, value):
    source = packet()
    source["data"][key] = value
    assert normalized(source) is None


@pytest.mark.parametrize("field,value", [
    ("price", 0), ("price", -1), ("price", True), ("price", float("nan")), ("price", float("inf")),
    ("price", "NaN"), ("price", "1e2"), ("price", "0.00000000001"), ("price", "1" * 21),
    ("size", 0), ("size", -1), ("size", 1.0), ("size", True), ("size", None), ("size", "01"),
    ("size", 9_223_372_036_854_775_808), ("time", 0), ("time", -1), ("time", True), ("time", None),
    ("time", MICROS // 1000), ("time", MICROS // 1000000), ("time", MICROS * 1000),
    ("time", float(MICROS)), ("time", MICROS + 1000001), ("time", MICROS - 86400000000),
])
def test_trade_evidence_rejects_bad_numbers_wrong_epoch_units_and_non_today(field, value):
    source = packet()
    source["data"]["lastTrade"][field] = value
    source["data"].pop("closePrice")
    source["data"].pop("closeTime")
    assert normalized(source) is None


@pytest.mark.parametrize("field,value", [
    ("previousClose", 0), ("previousClose", "bad"), ("openPrice", "125"),
    ("highPrice", "123"), ("lowPrice", "124"), ("lowPrice", -1),
    ("highPrice", Decimal("NaN")), ("closePrice", "9000"), ("closePrice", None),
    ("closeTime", MICROS - 1), ("closeTime", None), ("name", ""), ("name", "   "),
    ("name", True), ("name", "A" * 101), ("name", "bad\nname"), ("name", "bad\x85name"),
])
def test_bad_optional_same_packet_metadata_rejects_whole_packet(field, value):
    source = packet()
    source["data"][field] = value
    assert normalized(source) is None


def test_missing_optional_fields_stay_null_without_rest_or_old_packet_merge():
    source = packet()
    for field in ("previousClose", "openPrice", "highPrice", "lowPrice", "closePrice", "closeTime", "name"):
        source["data"].pop(field)
    source["data"]["isTrial"] = False
    event = normalized(source)
    assert event is not None
    assert event.previousClose is event.openPrice is event.highPrice is event.lowPrice is event.name is None


@pytest.mark.parametrize("event_kind,channel", [("snapshot", "aggregates"), ("heartbeat", "aggregates"),
                                                ("subscribed", "aggregates"), ("data", "indices"), ("data", "books")])
def test_heartbeat_snapshot_ack_and_other_channels_never_prove_a_trade(event_kind, channel):
    source = packet()
    source.update(event=event_kind, channel=channel)
    assert normalized(source) is None
    source = packet()
    source["id"] = "other-current-session-id"
    assert normalized(source) is None


def test_confirmed_subscription_is_required_not_just_desired_symbol():
    assert normalize_stock_message(packet(), confirmed={}, now=STOCK_NOW) is None


def test_true_receive_and_trade_time_boundaries_are_0900_inclusive_and_1330_exclusive():
    opened = STOCK_NOW.replace(hour=1, minute=0, second=0)
    before_close = STOCK_NOW.replace(hour=5, minute=29, second=59, microsecond=999999)
    closed = STOCK_NOW.replace(hour=5, minute=30, second=0)
    assert normalized(packet(at=opened), now=opened) is not None
    assert normalized(packet(at=opened - timedelta(microseconds=1)), now=opened) is None
    assert normalized(packet(at=before_close), now=before_close) is not None
    assert normalized(packet(at=before_close), now=closed) is None
    assert normalized(packet(at=closed), now=closed) is None


def test_raw_json_decoding_preserves_decimal_rejects_duplicate_nonfinite_and_bounded_frames():
    raw = json.dumps(packet()).replace('"123.5"', '123.5')
    decoded = decode_stock_message(raw)
    assert decoded is not None and isinstance(decoded["data"]["lastTrade"]["price"], Decimal)
    assert normalized(decoded) is not None
    assert decode_stock_message('{"event":"data","event":"snapshot"}') is None
    assert decode_stock_message('{"value":NaN}') is None
    assert decode_stock_message('{"value":Infinity}') is None
    assert decode_stock_message('{"value":1e999999999999999999999999999999}') is None
    assert decode_stock_message('{"data":"' + "X" * 262144 + '"}') is None
    assert decode_stock_message({"data": "X" * 262144}) is None


@pytest.mark.parametrize("literal", ["1e100000", "1e-100000", "-1e100000", "-1e-100000"])
def test_short_json_large_exponents_are_rejected_before_fixed_point_expansion(literal):
    raw = json.dumps(packet()).replace('"123.5"', literal)
    decoded = decode_stock_message(raw)
    assert decoded is not None and normalized(decoded) is None
    with pytest.raises(ValueError):
        canonical_number(Decimal(literal), precision=38, scale=18)


@pytest.mark.parametrize("literal", ["0e100000", "0e-100000", "-0e100000", "-0e-100000"])
def test_signed_zero_with_large_exponents_is_canonical_without_expansion(literal):
    assert canonical_number(Decimal(literal), precision=38, scale=18) == "0"
    raw = json.dumps(packet()).replace('"123.5"', literal)
    assert normalized(decode_stock_message(raw)) is None  # prices still have to be positive
