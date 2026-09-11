"""Task408 fixed technical, ticker and minute-candle contracts (offline only)."""
from __future__ import annotations

from copy import deepcopy
from datetime import UTC, datetime, timedelta
import hashlib
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from fubon_broker_service.app import create_app
from fubon_broker_service.config import ConfigLoader
from fubon_broker_service.market_data_v1 import MarketDataV1Error, MarketDataV1Service
from fubon_broker_service.sdk_gateway import SdkCallError
from fubon_broker_service.technical_indicators import (
    PROFILE_BY_ID,
    PROFILES,
    TechnicalIndicatorService,
    technical_fact_canonical_bytes,
    technical_fact_sha256,
)

from helpers import TOKEN, ready_config
from market_fixtures import NOW, TODAY, technical_v2_result


def _technical_fact_golden() -> Path:
    """Find the sole repository fixture, including its read-only test mount."""
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "spec" / "fixtures" / "fubon-technical-fact-v1-golden.json"
        if candidate.is_file():
            return candidate
    raise FileNotFoundError("Task408 technical-fact golden fixture is missing")


TECHNICAL_FACT_GOLDEN = _technical_fact_golden()


class FixedGateway:
    runtime_misconfigured = False

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, object]]] = []
        self.ticker = {
            "date": TODAY, "type": "EQUITY", "exchange": "TWSE", "market": "TSE", "symbol": "2330",
            "name": "台積電", "industry": "半導體", "securityType": "COMMON", "limitUpPrice": "1000",
            "limitDownPrice": "900", "securityStatus": "NORMAL", "matchingInterval": 5,
            "boardLot": 1000, "currency": "TWD",
        }
        self.candle_source = {
            "date": TODAY, "type": "EQUITY", "exchange": "TWSE", "market": "TSE", "symbol": "2330",
            "timeframe": 1,
            "data": [{"date": "2026-08-28T01:00:00Z", "open": "950", "high": "960", "low": "945",
                      "close": "955", "volume": "123", "average": "953"}],
        }
        self.volume_source = {
            "date": TODAY, "type": "EQUITY", "exchange": "TWSE", "market": "TSE", "symbol": "2330",
            "data": [{"price": "950", "volume": "123", "bidVolume": "100", "askVolume": None}],
        }
        self.daily_candle_source = {
            "type": "EQUITY", "exchange": "TWSE", "market": "TSE", "symbol": "2330",
            "data": [{"date": TODAY, "open": "950", "high": "960", "low": "945", "close": "955",
                      "volume": "123", "turnover": "117465", "change": "5"}],
        }

    def shutdown(self) -> None:
        pass

    def runtime_misconfigured_for(self, _config) -> bool:
        return self.runtime_misconfigured

    def read_technical_indicator(self, kind, symbol, start, end, *, timeframe="D", parameters=None, deadline=None):
        assert parameters is not None
        self.calls.append((kind, {"symbol": symbol, "from": start, "to": end,
                                  "timeframe": timeframe, **parameters}))
        return technical_v2_result(kind, symbol, start=start, end=end, timeframe=timeframe,
                                   parameters=parameters)

    def read_ticker(self, symbol, *, deadline=None):
        self.calls.append(("ticker", {"symbol": symbol}))
        return deepcopy(self.ticker)

    def read_intraday_candles(self, symbol, *, deadline=None):
        self.calls.append(("candles", {"symbol": symbol, "timeframe": 1, "sort": "asc"}))
        return deepcopy(self.candle_source)

    def read_intraday_volumes(self, symbol, *, deadline=None):
        self.calls.append(("volumes", {"symbol": symbol}))
        return deepcopy(self.volume_source)

    def read_historical_daily_candles(self, symbol, start, end, *, deadline=None):
        self.calls.append(("daily-candles", {"symbol": symbol, "from": start, "to": end, "timeframe": "D",
                                              "adjusted": False, "fields": "open,high,low,close,volume,turnover,change", "sort": "asc"}))
        return deepcopy(self.daily_candle_source)


def test_fixed_17_profile_aggregate_has_exact_root_and_420_day_window():
    gateway = FixedGateway()
    result = TechnicalIndicatorService(gateway, now=lambda: NOW).read("2330")

    assert list(result) == ["schemaVersion", "captureId", "symbol", "market", "provider", "queryFrom", "queryTo", "profiles"]
    assert result["schemaVersion"] == 2
    assert result["queryFrom"] == (NOW.date() - timedelta(days=420)).isoformat()
    assert result["queryTo"] == TODAY
    assert [value["profileId"] for value in result["profiles"]] == [profile.profile_id for profile in PROFILES]
    assert all(value["status"] == "AVAILABLE" and value["reason"] is None
               and len(value["history"]) == 1 for value in result["profiles"])
    assert [(kind, call["timeframe"]) for kind, call in gateway.calls] == [
        (profile.kind, profile.timeframe) for profile in PROFILES
    ]
    assert all(call["from"] == result["queryFrom"] and call["to"] == TODAY for _, call in gateway.calls)


def test_normalized_technical_fact_matches_cross_language_golden_bytes_and_hash():
    """The normalizer, external writer and backend reader consume one fixture."""
    fixture = json.loads(TECHNICAL_FACT_GOLDEN.read_text(encoding="utf-8"))
    profile = PROFILE_BY_ID[fixture["profileId"]]
    source = {
        "symbol": "2330",
        "from": "2026-01-01",
        "to": fixture["sourceDate"],
        **profile.parameters,
        "data": [{"date": fixture["sourceDate"], **fixture["payload"]}],
    }
    normalized = TechnicalIndicatorService._normalize_profile(
        profile, source, "2330", "2026-01-01", fixture["sourceDate"], NOW)
    payload = normalized["history"][0]["payload"]
    assert normalized["parameters"] == fixture["parameters"]
    assert payload == fixture["payload"]

    literal = fixture["canonicalInputUtf8"].encode("utf-8")
    # This is deliberately independent of our helper: the fixture's literal
    # UTF-8 bytes must themselves match its declared hex and SHA-256.
    assert literal.hex() == fixture["canonicalInputUtf8Hex"]
    assert hashlib.sha256(literal).hexdigest() == fixture["sha256"]
    assert technical_fact_canonical_bytes(
        fixture["profileId"], fixture["sourceDate"], normalized["parameters"], payload
    ) == literal
    assert technical_fact_sha256(
        fixture["profileId"], fixture["sourceDate"], normalized["parameters"], payload
    ) == fixture["sha256"]


def test_technical_v2_rejects_noncanonical_profile_payload_without_poisoning_others():
    gateway = FixedGateway()
    original = gateway.read_technical_indicator

    def malformed(kind, symbol, start, end, *, timeframe="D", parameters=None, deadline=None):
        value = original(kind, symbol, start, end, timeframe=timeframe, parameters=parameters, deadline=deadline)
        if kind == "sma" and timeframe == "D" and parameters == {"period": 10}:
            value["data"][0]["sma"] = "0600"
        return value

    gateway.read_technical_indicator = malformed  # type: ignore[method-assign]
    result = TechnicalIndicatorService(gateway, now=lambda: NOW).read("2330")
    rejected = next(value for value in result["profiles"] if value["profileId"] == "sma_d_10")
    assert rejected["status"] == "SCHEMA_INVALID"
    assert rejected["reason"] == "TECHNICAL_SCHEMA_INVALID"
    assert next(value for value in result["profiles"] if value["profileId"] == "sma_d_20")["status"] == "AVAILABLE"


def test_basic_and_candles_only_rebuild_allowed_normalized_fields():
    gateway = FixedGateway()
    service = MarketDataV1Service(gateway, now=lambda: NOW)
    basic = service.basic("2330")
    candles = service.candles("2330")

    assert list(basic) == ["schemaVersion", "symbol", "market", "provider", "sourceDate", "observedAt", "instrumentType", "exchange",
                           "sourceMarket", "sourceName", "industry", "securityType", "limitUpPrice", "limitDownPrice",
                           "tradingEligible", "tradingStatus", "matchingInterval", "boardLot", "currency"]
    assert basic["tradingEligible"] is True and basic["sourceName"] == "台積電"
    assert candles["status"] == "AVAILABLE" and candles["reason"] is None
    assert candles["candles"][0]["candleAt"] == "2026-08-28T01:00:00Z"
    assert gateway.calls[-2:] == [("ticker", {"symbol": "2330"}),
                                  ("candles", {"symbol": "2330", "timeframe": 1, "sort": "asc"})]


def test_task425_volume_and_daily_candle_wire_contracts_are_exact_and_fixed():
    gateway = FixedGateway()
    service = MarketDataV1Service(gateway, now=lambda: NOW)
    volumes = service.volumes("2330")
    daily = service.daily_candles("2330", (NOW.date() - timedelta(days=365)).isoformat(), TODAY)
    assert list(volumes) == ["schemaVersion", "symbol", "market", "provider", "sourceDate", "observedAt", "instrumentType",
                             "exchange", "sourceMarket", "status", "reason", "levels"]
    assert volumes["status"] == "OK" and volumes["levels"] == [{"price": "950", "volume": "123", "bidVolume": "100", "askVolume": None}]
    assert volumes["observedAt"].endswith(".000000Z")
    assert list(daily) == ["schemaVersion", "symbol", "market", "provider", "queryFrom", "queryTo", "observedAt",
                           "instrumentType", "exchange", "sourceMarket", "status", "reason", "candles"]
    assert daily["status"] == "OK" and daily["candles"][0]["tradingDate"] == TODAY
    assert gateway.calls[-1][1] == {"symbol": "2330", "from": (NOW.date() - timedelta(days=365)).isoformat(), "to": TODAY,
                                    "timeframe": "D", "adjusted": False, "fields": "open,high,low,close,volume,turnover,change", "sort": "asc"}


def test_source_name_whitespace_is_rejected_before_preserving_raw_value():
    gateway = FixedGateway()
    gateway.ticker["name"] = " 台積電 "
    with pytest.raises(MarketDataV1Error, match="SCHEMA_INVALID"):
        MarketDataV1Service(gateway, now=lambda: NOW).basic("2330")


def app_client(tmp_path, *, disabled: bool = False, gateway: FixedGateway | None = None):
    loader = ConfigLoader(tmp_path, lambda: "false") if disabled else ready_config(tmp_path)
    source = gateway or FixedGateway()
    application = create_app(
        loader, source,
        technical_indicator_service=TechnicalIndicatorService(source, now=lambda: NOW),
        market_data_v1_service=MarketDataV1Service(source, now=lambda: NOW),
    )
    return TestClient(application), source


@pytest.mark.parametrize("path", ["/internal/market-data/stock-basic/read", "/internal/market-data/intraday-candles/read",
                                  "/internal/market-data/intraday-volumes/read"])
def test_route_local_v1_gate_uses_root_reason_and_does_not_touch_sdk(tmp_path, path):
    client, gateway = app_client(tmp_path)
    with client:
        assert client.post(path, headers={"X-Internal-Service-Token": TOKEN}, json={"symbol": "2330"}).json()["schemaVersion"] == 1
        missing = client.post(path, json={"symbol": "2330"})
        wrong = client.post(path, headers={"X-Internal-Service-Token": "wrong"}, json={"symbol": "2330"})
        duplicate = client.post(path, headers=[("X-Internal-Service-Token", TOKEN),
                                               ("X-Internal-Service-Token", TOKEN)], json={"symbol": "2330"})
        invalid = client.post(path, headers={"X-Internal-Service-Token": TOKEN}, json={"symbol": "2330", "extra": 1})
    assert missing.status_code == 401 and missing.json() == {"reason": "UNAUTHORIZED"}
    assert wrong.status_code == 403 and wrong.json() == {"reason": "FORBIDDEN"}
    assert duplicate.status_code == 401 and duplicate.json() == {"reason": "UNAUTHORIZED"}
    assert invalid.status_code == 400 and invalid.json() == {"reason": "INVALID_REQUEST"}
    # Only the first authenticated success crossed the route-local gate.
    assert len(gateway.calls) == 1


@pytest.mark.parametrize("path", ["/internal/market-data/stock-basic/read", "/internal/market-data/intraday-candles/read",
                                  "/internal/market-data/intraday-volumes/read"])
def test_route_local_v1_disabled_and_unhandled_provider_failure_are_sanitized(tmp_path, path):
    disabled, disabled_gateway = app_client(tmp_path, disabled=True)
    with disabled:
        result = disabled.post(path, headers={"X-Internal-Service-Token": TOKEN}, json={"symbol": "2330"})
    assert result.status_code == 503 and result.json() == {"reason": "MISCONFIGURED"}
    assert disabled_gateway.calls == []

    exploding = FixedGateway()
    if path.endswith("stock-basic/read"):
        exploding.read_ticker = lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("provider"))  # type: ignore[method-assign]
    elif path.endswith("intraday-candles/read"):
        exploding.read_intraday_candles = lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("provider"))  # type: ignore[method-assign]
    else:
        exploding.read_intraday_volumes = lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("provider"))  # type: ignore[method-assign]
    client, _ = app_client(tmp_path, gateway=exploding)
    with client:
        result = client.post(path, headers={"X-Internal-Service-Token": TOKEN}, json={"symbol": "2330"})
    assert result.status_code == 503 and result.json() == {"reason": "UPSTREAM_UNAVAILABLE"}


def test_route_local_v1_maps_sdk_configuration_and_rate_limit_without_detail_envelope(tmp_path):
    gateway = FixedGateway()
    gateway.read_ticker = lambda *_args, **_kwargs: (_ for _ in ()).throw(SdkCallError("RATE_LIMITED"))  # type: ignore[method-assign]
    client, _ = app_client(tmp_path, gateway=gateway)
    with client:
        response = client.post("/internal/market-data/stock-basic/read", headers={"X-Internal-Service-Token": TOKEN}, json={"symbol": "2330"})
    assert response.status_code == 503 and response.json() == {"reason": "RATE_LIMITED"}


def test_task425_daily_route_requires_exact_date_window_and_never_accepts_extra_fields(tmp_path):
    client, gateway = app_client(tmp_path)
    body = {"symbol": "2330", "from": (NOW.date() - timedelta(days=365)).isoformat(), "to": TODAY}
    with client:
        response = client.post("/internal/market-data/historical-daily-candles/read", headers={"X-Internal-Service-Token": TOKEN}, json=body)
        invalid = client.post("/internal/market-data/historical-daily-candles/read", headers={"X-Internal-Service-Token": TOKEN},
                              json={**body, "extra": True})
        too_long = client.post("/internal/market-data/historical-daily-candles/read", headers={"X-Internal-Service-Token": TOKEN},
                               json={"symbol": "2330", "from": "2025-01-01", "to": "2026-08-28"})
    assert response.status_code == 200 and response.json()["status"] == "OK"
    assert invalid.status_code == 400 and invalid.json() == {"reason": "INVALID_REQUEST"}
    assert too_long.status_code == 400 and too_long.json() == {"reason": "INVALID_REQUEST"}
    assert len(gateway.calls) == 1
