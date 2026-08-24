from __future__ import annotations

import asyncio
import copy
import threading
import time
from datetime import UTC, datetime

import pytest

from fubon_broker_service.quotes import QuoteError, QuoteService

from helpers import fixed_now, quote_raw


class Gateway:
    def __init__(self, response=None, delay: float = 0):
        self.response = response or quote_raw()
        self.delay = delay
        self.calls = 0
        self.lock = threading.Lock()

    def quote(self, _code):
        with self.lock:
            self.calls += 1
        if self.delay:
            time.sleep(self.delay)
        return copy.deepcopy(self.response)


class PerCodeGateway:
    def __init__(self, delay: float = 0):
        self.delay = delay
        self.calls = 0
        self.active = 0
        self.max_active = 0
        self.lock = threading.Lock()

    def quote(self, code):
        with self.lock:
            self.calls += 1
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        try:
            if self.delay:
                time.sleep(self.delay)
            return quote_raw(code)
        finally:
            with self.lock:
                self.active -= 1


def read_one(raw):
    gateway = Gateway(raw)
    result = asyncio.run(QuoteService(gateway, now=fixed_now).read(["2330"]))
    return result["quotes"][0]


def test_official_raw_quote_is_normalized_with_provider_timestamp():
    result = read_one(
        quote_raw(
            actual=0.1,
            previous="0.09",
            open_price="0.1",
            high_price="0.2",
            low_price="0.05",
            volume=9_223_372_036_854_775_807,
        )
    )
    assert result["status"] == "SUCCESS"
    quote = result["quote"]
    assert quote["actualPrice"] == "0.1"
    assert quote["previousClose"] == "0.09"
    assert quote["openPrice"] == "0.1"
    assert quote["volume"] == 9_223_372_036_854_775_807
    assert quote["tradingDate"] == "2026-08-21"
    assert quote["source"] == "FUBON_INTRADAY"


def test_only_open_high_low_aliases_are_rejected():
    raw = quote_raw()
    raw["open"] = raw.pop("openPrice")
    raw["high"] = raw.pop("highPrice")
    raw["low"] = raw.pop("lowPrice")
    result = read_one(raw)
    assert result == {
        "stockCode": "2330",
        "status": "FAILURE",
        "reason": "UNSUPPORTED_OHLC_ALIAS",
        "quote": None,
    }


@pytest.mark.parametrize(
    ("mutate", "reason"),
    [
        (lambda raw: raw.update(isTrial=True), "TRIAL_QUOTE_REJECTED"),
        (lambda raw: raw.update(market="OTC"), "INVALID_MARKET_PAIR"),
        (lambda raw: raw.update(symbol="2317"), "WRONG_SYMBOL"),
        (lambda raw: raw.update(previousClose="10000000000.0000000000"), "DECIMAL_PRECISION_EXCEEDED"),
        (lambda raw: raw.update(openPrice="0.00000000001"), "DECIMAL_SCALE_EXCEEDED"),
        (lambda raw: raw["total"].update(tradeVolume=9_223_372_036_854_775_808), "INTEGER_RANGE_EXCEEDED"),
        (lambda raw: raw.update(lowPrice="100.2"), "INVALID_OHLC_RELATION"),
    ],
)
def test_quote_validation_matrix(mutate, reason):
    raw = quote_raw()
    mutate(raw)
    assert read_one(raw)["reason"] == reason


def test_stale_source_date_is_rejected():
    timestamp = int(datetime(2026, 8, 20, 5, tzinfo=UTC).timestamp() * 1_000_000)
    assert read_one(quote_raw(timestamp=timestamp))["reason"] == "STALE_PROVIDER_DATE"


def test_future_provider_timestamp_is_rejected():
    timestamp = int(datetime(2026, 8, 21, 5, 0, 31, tzinfo=UTC).timestamp() * 1_000_000)
    assert read_one(quote_raw(timestamp=timestamp))["reason"] == "FUTURE_PROVIDER_TIMESTAMP"


def test_conflicting_last_trade_and_close_pair_is_rejected():
    raw = quote_raw()
    raw["closePrice"] = "100.2"
    raw["closeTime"] = raw["lastTrade"]["time"]
    assert read_one(raw)["reason"] == "CONFLICTING_ACTUAL_TRADE"


def test_same_actual_price_with_different_decimal_scale_is_consistent():
    raw = quote_raw(actual="100.10")
    raw["closePrice"] = "100.1"
    raw["closeTime"] = raw["lastTrade"]["time"]
    result = read_one(raw)
    assert result["status"] == "SUCCESS"
    assert result["quote"]["actualPrice"] == "100.10"


def test_close_pair_is_actual_but_last_price_alone_never_is():
    close_only = quote_raw()
    close_only.pop("lastTrade")
    close_only["closePrice"] = "100.1"
    close_only["closeTime"] = int(fixed_now().timestamp()) * 1_000_000
    assert read_one(close_only)["status"] == "SUCCESS"

    forbidden = quote_raw()
    forbidden.pop("lastTrade")
    forbidden["lastPrice"] = "100.1"
    assert read_one(forbidden)["reason"] == "ACTUAL_TRADE_MISSING"


def test_omitted_trial_flag_still_requires_and_accepts_actual_pair():
    raw = quote_raw()
    raw.pop("isTrial")
    assert read_one(raw)["status"] == "SUCCESS"


def test_timestamp_must_be_16_digit_microseconds():
    raw = quote_raw()
    raw["lastTrade"]["time"] = 1_234_567_890_123
    assert read_one(raw)["reason"] == "INVALID_PROVIDER_TIMESTAMP"


def test_microsecond_timestamp_is_preserved_without_float_rounding():
    timestamp = int(fixed_now().timestamp()) * 1_000_000 + 123_456
    result = read_one(quote_raw(timestamp=timestamp))
    assert result["status"] == "SUCCESS"
    assert result["quote"]["updatedAt"] == "2026-08-21T05:00:00.123456Z"


def test_empty_order_books_are_explicitly_nullable():
    raw = quote_raw()
    raw["bids"] = []
    raw["asks"] = []
    result = read_one(raw)
    assert result["status"] == "SUCCESS"
    assert result["quote"]["buyPrice"] is None
    assert result["quote"]["sellPrice"] is None


def test_precision20_scale10_volume_zero_and_official_54538_are_exact():
    accepted = read_one(
        quote_raw(
            actual="9999999999.9999999999",
            previous="9999999999.0",
            open_price="0.1",
            high_price="9999999999.9999999999",
            low_price="0.1",
            volume=0,
        )
    )
    assert accepted["status"] == "SUCCESS"
    assert accepted["quote"]["actualPrice"] == "9999999999.9999999999"
    assert accepted["quote"]["openPrice"] == "0.1"
    assert accepted["quote"]["volume"] == 0
    assert read_one(quote_raw(volume=54_538))["quote"]["volume"] == 54_538


@pytest.mark.parametrize(
    ("mutate", "reason"),
    [
        (lambda raw: raw.update(previousClose=-1), "NON_POSITIVE_DECIMAL"),
        (lambda raw: raw.update(highPrice="1e2"), "NON_CANONICAL_DECIMAL"),
        (lambda raw: raw["total"].update(tradeVolume=-1), "INTEGER_RANGE_EXCEEDED"),
        (lambda raw: raw["bids"][0].update(price="0"), "NON_POSITIVE_DECIMAL"),
        (lambda raw: raw.update(lastUpdated="not-microseconds"), "INVALID_PROVIDER_TIMESTAMP"),
    ],
)
def test_additional_decimal_volume_book_and_diagnostic_time_rejections(mutate, reason):
    raw = quote_raw()
    mutate(raw)
    assert read_one(raw)["reason"] == reason


def test_live_never_uses_success_cache_but_same_request_is_single_flight():
    async def scenario():
        gateway = Gateway(delay=0.05)
        service = QuoteService(gateway, now=fixed_now)
        first, second = await asyncio.gather(service.read(["2330"], "LIVE"), service.read(["2330"], "LIVE"))
        third = await service.read(["2330"], "LIVE")
        assert first["quotes"] == second["quotes"] == third["quotes"]
        assert gateway.calls == 2

    asyncio.run(scenario())


def test_inventory_alone_keeps_30_second_success_cache_and_never_leaks_into_live():
    async def scenario():
        gateway = Gateway()
        service = QuoteService(gateway, now=fixed_now)
        await service.read(["2330"], "INVENTORY")
        await service.read(["2330"], "INVENTORY")
        await service.read(["2330"], "LIVE")
        assert gateway.calls == 2

    asyncio.run(scenario())


def test_request_code_contract_rejects_duplicate_and_over_100():
    with pytest.raises(QuoteError, match="DUPLICATE_CODE"):
        QuoteService.normalize_codes(["2330", " 2330 "])
    with pytest.raises(QuoteError, match="INVALID_CODE_COUNT"):
        QuoteService.normalize_codes([str(i).zfill(4) for i in range(101)])


def test_provider_429_opens_circuit_before_next_symbol_call():
    async def scenario():
        gateway = Gateway({"is_success": False, "code": 429})
        service = QuoteService(gateway, now=fixed_now)
        first = await service.read(["2330"])
        gateway.response = quote_raw("2317")
        second = await service.read(["2317"])
        assert first["quotes"][0]["reason"] == "RATE_LIMITED"
        assert second["quotes"][0]["reason"] == "RATE_LIMIT_CIRCUIT_OPEN"
        assert gateway.calls == 1

    asyncio.run(scenario())


def test_retry_after_longer_than_default_extends_circuit():
    async def scenario():
        monotonic = [0.0]
        gateway = Gateway({"is_success": False, "code": 429, "retryAfter": "120"})
        service = QuoteService(gateway, now=fixed_now, monotonic=lambda: monotonic[0])
        first = await service.read(["2330"])
        monotonic[0] = 61.0
        second = await service.read(["2317"])
        monotonic[0] = 121.0
        gateway.response = quote_raw("2454")
        third = await service.read(["2454"])
        assert first["quotes"][0]["reason"] == "RATE_LIMITED"
        assert second["quotes"][0]["reason"] == "RATE_LIMIT_CIRCUIT_OPEN"
        assert third["quotes"][0]["status"] == "SUCCESS"
        assert gateway.calls == 2

    asyncio.run(scenario())


def test_process_budget_stops_before_241st_call_without_http(monkeypatch):
    async def scenario():
        monkeypatch.setattr(QuoteService, "MAX_CALLS_PER_MINUTE", 2)
        gateway = PerCodeGateway()
        result = await QuoteService(gateway, now=fixed_now, monotonic=lambda: 0.0).read(
            ["0001", "0002", "0003"]
        )
        reasons = [row["reason"] for row in result["quotes"]]
        assert reasons.count("RATE_LIMIT_BUDGET_EXHAUSTED") == 1
        assert gateway.calls == 2

    asyncio.run(scenario())


def test_concurrency_never_exceeds_20(monkeypatch):
    async def scenario():
        monkeypatch.setattr(QuoteService, "MAX_CONCURRENCY", 20)
        gateway = PerCodeGateway(delay=0.02)
        service = QuoteService(gateway, now=fixed_now)
        result = await service.read([f"{i:04d}" for i in range(25)])
        assert all(row["status"] == "SUCCESS" for row in result["quotes"])
        assert gateway.max_active <= 20

    asyncio.run(scenario())


def test_batch_wall_timeout_returns_stable_failure_for_every_code(monkeypatch):
    monkeypatch.setattr(QuoteService, "ENDPOINT_TIMEOUT_SECONDS", 0.01)
    gateway = PerCodeGateway(delay=0.05)
    result = asyncio.run(QuoteService(gateway, now=fixed_now).read(["2330", "2317"]))
    assert [row["reason"] for row in result["quotes"]] == ["BATCH_TIMEOUT", "BATCH_TIMEOUT"]


def test_per_call_timeout_is_bounded_and_reported(monkeypatch):
    monkeypatch.setattr(QuoteService, "PER_CALL_TIMEOUT_SECONDS", 0.01)
    result = asyncio.run(QuoteService(Gateway(delay=0.05), now=fixed_now).read(["2330"]))
    assert result["quotes"][0]["reason"] == "QUOTE_TIMEOUT"
