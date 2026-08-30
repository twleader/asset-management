from __future__ import annotations

import asyncio
import json
import threading
import time
from copy import deepcopy
from datetime import UTC, datetime
from decimal import Decimal

import pytest

from fubon_broker_service.etf_holdings import EtfHoldingsService
from fubon_broker_service.sdk_gateway import SdkCallError

from helpers import fixed_now


_DEFAULT = object()


def provider_response(code="0050", *, components=None, source_date="2026-08-20"):
    """Official schema fixture, not evidence of a live provider query."""
    return {
        "symbol": code,
        "type": "EQUITY",
        "exchange": "TWSE",
        "market": "TSE",
        "data": [{
            "date": source_date,
            "components": components if components is not None else [
                {"symbol": "2330", "name": "台積電", "quantity": 530358242, "weight": 58.82},
            ],
        }],
    }


class Gateway:
    def __init__(self, response=_DEFAULT, *, fail: SdkCallError | None = None, unexpected: Exception | None = None):
        self.response = response
        self.fail = fail
        self.unexpected = unexpected
        self.calls: list[str] = []
        self.lock = threading.Lock()

    def read_etf_holdings(self, code):
        with self.lock:
            self.calls.append(code)
        if self.fail is not None:
            raise self.fail
        if self.unexpected is not None:
            raise self.unexpected
        return provider_response(code) if self.response is _DEFAULT else self.response


def read(gateway, codes=None, *, now=fixed_now):
    return asyncio.run(EtfHoldingsService(gateway, now=now).read(codes or ["0050"]))


def first_row(gateway, **kwargs):
    return read(gateway, **kwargs)["holdings"][0]


def test_only_normalized_market_fields_leave_the_adapter_and_unknown_fields_are_not_even_serialized():
    raw = provider_response(components=[
        {
            "symbol": "2330", "name": "台積電", "quantity": 530358242, "weight": 58.82,
            "quantityChange": 370562, "weightChange": 0.17, "account": "ACCOUNT_SENTINEL",
        },
        {"symbol": "AAPL UQ", "name": "APPLE INC", "weight": "1.2300", "quantity": Decimal("2.5")},
    ])
    raw["api-key"] = "SECRET_SENTINEL"
    raw["unknownSdkObject"] = object()
    row = first_row(Gateway(raw))

    assert set(row) == {"stockCode", "status", "reason", "rawResponseJson"}
    assert row["status"] == "SUCCESS"
    assert row["reason"] is None
    assert json.loads(row["rawResponseJson"]) == {
        "schemaVersion": 1,
        "stockCode": "0050",
        "sourceDate": "2026-08-20",
        "holdings": [
            {"stockCode": "2330", "stockName": "台積電", "weight": "58.82", "shares": "530358242"},
            {"stockCode": "AAPL UQ", "stockName": "APPLE INC", "weight": "1.2300", "shares": "2.5"},
        ],
    }
    assert "SENTINEL" not in row["rawResponseJson"]
    assert raw["data"][0]["components"][0]["account"] == "ACCOUNT_SENTINEL"


def test_latest_provider_date_wins_without_mixing_days_or_using_fetch_date():
    raw = provider_response(source_date="2026-08-19")
    latest = provider_response(components=[{"symbol": "2454", "name": "聯發科", "weight": 5.76}])
    raw["data"].extend(latest["data"])
    raw["data"].extend(provider_response(source_date="2026-08-18")["data"])
    original = deepcopy(raw)

    payload = json.loads(first_row(Gateway(raw))["rawResponseJson"])
    assert payload["sourceDate"] == "2026-08-20"  # fetched on the 21st
    assert payload["holdings"] == [
        {"stockCode": "2454", "stockName": "聯發科", "weight": "5.76", "shares": None},
    ]
    assert raw == original


def test_empty_data_is_success_without_a_fabricated_source_date():
    row = first_row(Gateway({"symbol": "0050", "data": []}))
    assert row["status"] == "SUCCESS"
    assert json.loads(row["rawResponseJson"]) == {
        "schemaVersion": 1, "stockCode": "0050", "sourceDate": None, "holdings": [],
    }


def test_empty_latest_components_keeps_the_provider_date():
    row = first_row(Gateway(provider_response(components=[])))
    assert row["status"] == "SUCCESS"
    assert json.loads(row["rawResponseJson"])["sourceDate"] == "2026-08-20"
    assert json.loads(row["rawResponseJson"])["holdings"] == []


@pytest.mark.parametrize("raw", [
    None, [], "raw-sdk-text", True, {},
    {"symbol": "0056", "data": []},
    {"symbol": "0050", "data": None},
    {"symbol": "0050", "data": {}},
    {"symbol": "0050", "data": [None]},
    {"symbol": "0050", "data": [{"date": "20260820", "components": []}]},
    {"symbol": "0050", "data": [{"date": "2026-8-20", "components": []}]},
    {"symbol": "0050", "data": [{"date": "2026-02-30", "components": []}]},
    {"symbol": "0050", "data": [{"date": "2026-08-20T00:00:00Z", "components": []}]},
    {"symbol": "0050", "data": [{"date": True, "components": []}]},
    {"symbol": "0050", "data": [{"date": "2026-08-20"}]},
    {"symbol": "0050", "data": [{"date": "2026-08-20", "components": {}}]},
])
def test_malformed_provider_envelope_is_a_sanitized_failure(raw):
    assert first_row(Gateway(raw)) == {
        "stockCode": "0050", "status": "FAILURE", "reason": "ETF_HOLDINGS_INVALID_RESPONSE",
        "rawResponseJson": None,
    }


@pytest.mark.parametrize("bad_latest", [
    {"date": "2026-08-22", "components": []},
    {"date": "2026-08-21", "components": None},
    {"date": "2026-08-21", "components": [{"symbol": "2330", "name": "台積電", "weight": -1}]},
    {"date": "2026-08-20", "components": []},  # even identical duplicate dates are ambiguous
])
def test_invalid_newer_or_duplicate_day_does_not_fall_back_to_older_holdings(bad_latest):
    raw = provider_response()
    raw["data"].append(bad_latest)
    row = first_row(Gateway(raw))
    assert row["status"] == "FAILURE"
    assert row["reason"] == "ETF_HOLDINGS_INVALID_RESPONSE"
    assert row["rawResponseJson"] is None


def test_source_date_validation_and_batch_day_use_taipei_not_utc():
    gateway = Gateway(provider_response(source_date="2026-08-21"))
    in_taipei_today = read(gateway, now=lambda: datetime(2026, 8, 20, 17, tzinfo=UTC))
    same_local_day = read(gateway, now=fixed_now)
    assert in_taipei_today["holdings"][0]["status"] == "SUCCESS"
    assert in_taipei_today["batchId"] == same_local_day["batchId"]


@pytest.mark.parametrize("invalid", [
    None, [], {"symbol": "2330", "name": "台積電"},
    {"symbol": "", "name": "台積電", "weight": 1},
    {"symbol": "2330", "name": "   ", "weight": 1},
    {"symbol": 2330, "name": "台積電", "weight": 1},
    *[{"symbol": "2330", "name": "台積電", "weight": v}
      for v in (True, float("nan"), float("inf"), "-1", "100.01", "0.00000000001", "1e2")],
    *[{"symbol": "2330", "name": "台積電", "weight": "1", "quantity": v}
      for v in (True, float("nan"), float("inf"), "-1", "1e2", "100000000000000000000")],
])
def test_invalid_component_is_skipped_but_cannot_be_misreported_as_an_empty_success(invalid):
    valid = {"symbol": "AAPL UQ", "name": "APPLE INC", "weight": 0, "quantity": 0}
    row = first_row(Gateway(provider_response(components=[invalid, valid])))
    assert row["status"] == "SUCCESS"
    assert json.loads(row["rawResponseJson"])["holdings"] == [
        {"stockCode": "AAPL UQ", "stockName": "APPLE INC", "weight": "0", "shares": "0"},
    ]
    failed = first_row(Gateway(provider_response(components=[invalid])))
    assert failed["status"] == "FAILURE"
    assert failed["rawResponseJson"] is None


def test_weight_percent_is_not_rescaled_and_may_be_less_than_one_hundred_in_total():
    row = first_row(Gateway(provider_response(components=[
        {"symbol": "AAPL UQ", "name": "APPLE INC", "weight": "30", "quantity": None},
    ])))
    assert json.loads(row["rawResponseJson"])["holdings"][0] == {
        "stockCode": "AAPL UQ", "stockName": "APPLE INC", "weight": "30", "shares": None,
    }


@pytest.mark.parametrize("reason", [
    "AUTH_SESSION_INVALID", "ETF_HOLDINGS_TIMEOUT", "ETF_HOLDINGS_TRANSPORT_FAILED", "RATE_LIMITED",
])
def test_known_sdk_call_errors_are_per_code_failures(reason):
    assert first_row(Gateway(fail=SdkCallError(reason))) == {
        "stockCode": "0050", "status": "FAILURE", "reason": reason, "rawResponseJson": None,
    }


@pytest.mark.parametrize("gateway", [
    Gateway(unexpected=RuntimeError("ACCOUNT_SENTINEL SECRET_SENTINEL")),
    Gateway(fail=SdkCallError("ACCOUNT_SENTINEL SECRET_SENTINEL")),
])
def test_unexpected_exception_or_unregistered_reason_never_escapes_or_leaks(gateway, caplog):
    row = first_row(gateway)
    assert row == {
        "stockCode": "0050", "status": "FAILURE", "reason": "ETF_HOLDINGS_FAILED", "rawResponseJson": None,
    }
    assert "SENTINEL" not in caplog.text


def test_one_code_failing_does_not_affect_the_others_in_the_same_batch():
    class PerCodeGateway:
        def read_etf_holdings(self, code):
            if code == "0056":
                raise SdkCallError("ETF_HOLDINGS_CLIENT_UNAVAILABLE", misconfigured=True)
            if code == "00981A":
                return {"symbol": "WRONG_CODE", "data": []}
            return provider_response(code)

    result = read(PerCodeGateway(), ["0050", "0056", "00981A", "006208"])
    by_code = {row["stockCode"]: row for row in result["holdings"]}
    assert by_code["0050"]["status"] == by_code["006208"]["status"] == "SUCCESS"
    assert by_code["0056"]["reason"] == "ETF_HOLDINGS_CLIENT_UNAVAILABLE"
    assert by_code["00981A"]["reason"] == "ETF_HOLDINGS_INVALID_RESPONSE"


def test_concurrency_never_exceeds_four():
    active = {"current": 0, "max": 0}
    lock = threading.Lock()

    class SlowGateway:
        def read_etf_holdings(self, code):
            with lock:
                active["current"] += 1
                active["max"] = max(active["max"], active["current"])
            try:
                time.sleep(0.02)
                return provider_response(code)
            finally:
                with lock:
                    active["current"] -= 1

    result = read(SlowGateway(), [f"00{i:03d}" for i in range(100, 120)])
    assert all(row["status"] == "SUCCESS" for row in result["holdings"])
    assert 1 < active["max"] <= 4


@pytest.mark.parametrize("codes", [[], ["0000"], ["2330"], ["0050", "0050"], [True], ["0050 "]])
def test_invalid_direct_service_input_never_calls_sdk(codes):
    gateway = Gateway()
    with pytest.raises(ValueError):
        asyncio.run(EtfHoldingsService(gateway, now=fixed_now).read(codes))
    assert gateway.calls == []
