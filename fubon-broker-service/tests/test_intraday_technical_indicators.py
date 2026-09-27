from __future__ import annotations

from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient

from fubon_broker_service.app import create_app
from fubon_broker_service.intraday_technical_indicators import (
    IntradayTechnicalGatewayError,
    IntradayTechnicalIndicatorError,
    IntradayTechnicalIndicatorService,
)
from fubon_broker_service.sdk_gateway import SdkCallError

from helpers import TOKEN, ready_config


NOW = datetime(2026, 9, 25, 2, 0, tzinfo=UTC)
TODAY = "2026-09-25"
QUERY_FROM = "2026-08-27"
QUERY_TO = TODAY
LATEST_TIMESTAMP = "2026-09-25T01:59:00Z"
PROFILE = {
    "kdj": {"rPeriod": 9, "kPeriod": 3, "dPeriod": 3},
    "macd": {"fast": 12, "slow": 26, "signal": 9},
    "bb": {"period": 20},
}
VALUES = {
    "kdj": {"k": "50.20", "d": "45", "j": "60"},
    "macd": {"macdLine": "1.25", "signalLine": "0.75"},
    "bb": {"upper": "102", "middle": "100", "lower": "98"},
}


def vendor_response(kind: str, timeframe: str, *, latest: str = LATEST_TIMESTAMP) -> dict[str, object]:
    return {
        "symbol": "2330",
        "from": QUERY_FROM,
        "to": QUERY_TO,
        "timeframe": timeframe,
        **PROFILE[kind],
        "data": [
            {"date": "2026-09-25T01:58:00Z", **VALUES[kind]},
            {"date": latest, **VALUES[kind]},
        ],
    }


class FakeTechnicalGateway:
    def __init__(self, *, mutate=None, failure=None):
        self.calls = []
        self.mutate = mutate
        self.failure = failure

    def read_intraday_technical_indicator(self, kind, symbol, start, end, timeframe, *, deadline=None):
        self.calls.append((kind, symbol, start, end, timeframe, deadline))
        if self.failure is not None:
            raise self.failure
        result = vendor_response(kind, timeframe)
        if self.mutate is not None:
            self.mutate(kind, timeframe, result)
        return result


def test_fixed_six_reads_return_latest_points_and_common_source_timestamp():
    gateway = FakeTechnicalGateway()
    service = IntradayTechnicalIndicatorService(
        gateway, now=lambda: NOW, monotonic=lambda: 100.0,
    )

    result = service.read("2330")

    assert [(call[0], call[4]) for call in gateway.calls] == [
        ("kdj", "1"), ("macd", "1"), ("bb", "1"),
        ("kdj", "5"), ("macd", "5"), ("bb", "5"),
    ]
    assert all(call[2:4] == (QUERY_FROM, QUERY_TO) for call in gateway.calls)
    assert result == {
        "schemaVersion": 1,
        "symbol": "2330",
        "market": "台股",
        "provider": "FUBON_SDK",
        "observedAt": "2026-09-25T02:00:00Z",
        "oneMinute": {
            "timeframe": "1", "sourceDate": TODAY, "sourceTimestamp": LATEST_TIMESTAMP,
            "observedAt": "2026-09-25T02:00:00Z",
            "kdj": {"k": "50.2", "d": "45", "j": "60"},
            "macd": {"macdLine": "1.25", "signalLine": "0.75"},
            "bollinger": {"upper": "102", "middle": "100", "lower": "98"},
        },
        "fiveMinute": {
            "timeframe": "5", "sourceDate": TODAY, "sourceTimestamp": LATEST_TIMESTAMP,
            "observedAt": "2026-09-25T02:00:00Z",
            "kdj": {"k": "50.2", "d": "45", "j": "60"},
            "macd": {"macdLine": "1.25", "signalLine": "0.75"},
            "bollinger": {"upper": "102", "middle": "100", "lower": "98"},
        },
    }
    assert "data" not in result["oneMinute"] and "histogram" not in result["oneMinute"]["macd"]


def test_date_only_provider_values_keep_source_timestamp_null():
    def date_only(_kind, _timeframe, result):
        result["data"] = [{"date": TODAY, **VALUES[_kind]}]

    gateway = FakeTechnicalGateway(mutate=date_only)
    result = IntradayTechnicalIndicatorService(gateway, now=lambda: NOW).read("2330")

    assert result["oneMinute"]["sourceTimestamp"] is None
    assert result["fiveMinute"]["sourceTimestamp"] is None


@pytest.mark.parametrize("mutation", ["parameters", "timestamp", "future", "wrong_date", "missing"])
def test_inconsistent_or_invalid_provider_response_fails_whole_bundle(mutation):
    def mutate(kind, timeframe, result):
        if mutation == "parameters" and kind == "kdj" and timeframe == "1":
            result["rPeriod"] = 10
        elif mutation == "timestamp" and kind == "macd" and timeframe == "1":
            result["data"][-1]["date"] = "2026-09-25T01:59:01Z"
        elif mutation == "future" and kind == "bb" and timeframe == "1":
            result["data"][-1]["date"] = "2026-09-25T02:01:00Z"
        elif mutation == "wrong_date" and kind == "bb" and timeframe == "1":
            result["data"][-1]["date"] = "2026-09-24T15:59:00Z"
        elif mutation == "missing" and kind == "macd" and timeframe == "1":
            result["data"][-1].pop("signalLine")

    gateway = FakeTechnicalGateway(mutate=mutate)
    with pytest.raises(IntradayTechnicalIndicatorError, match="TECHNICAL_SCHEMA_INVALID"):
        IntradayTechnicalIndicatorService(gateway, now=lambda: NOW).read("2330")
    assert gateway.calls and all(call[4] == "1" for call in gateway.calls)


def test_mixed_date_precision_between_indicator_responses_is_rejected():
    def date_only_macd(kind, timeframe, result):
        if kind == "macd":
            result["data"] = [{"date": TODAY, **VALUES[kind]}]

    with pytest.raises(IntradayTechnicalIndicatorError, match="TECHNICAL_SCHEMA_INVALID"):
        IntradayTechnicalIndicatorService(
            FakeTechnicalGateway(mutate=date_only_macd), now=lambda: NOW,
        ).read("2330")


class StubService:
    def __init__(self):
        self.calls = []

    def read(self, symbol):
        self.calls.append(symbol)
        return {"schemaVersion": 1, "symbol": symbol, "oneMinute": {}, "fiveMinute": {}}


class AppGateway:
    runtime_misconfigured = False

    def shutdown(self):
        pass

    def runtime_misconfigured_for(self, _config):
        return False


def test_exact_token_protected_route_rejects_queries_duplicates_and_unlisted_fields(tmp_path):
    service = StubService()
    app = create_app(
        config_loader=ready_config(tmp_path), gateway=AppGateway(),
        intraday_technical_indicator_service=service,
    )
    with TestClient(app) as client:
        path = "/internal/market-data/intraday-technical-indicators/read"
        headers = {"X-Internal-Service-Token": TOKEN}

        assert client.post(path, content="{", headers={"content-type": "application/json"}).status_code == 401
        assert client.post(path + "?symbol=2330", json={"symbol": "2330"}, headers=headers).status_code == 400
        assert client.post(path, content='{"symbol":"2330","symbol":"0050"}', headers=headers).status_code == 400
        assert client.post(path, json={"symbol": "2330", "timeframe": "1"}, headers=headers).status_code == 400
        assert client.post(path, json={"symbol": "0000"}, headers=headers).status_code == 400

        response = client.post(path, json={"symbol": "2330"}, headers=headers)
        assert response.status_code == 200
        assert response.json()["symbol"] == "2330"
        assert service.calls == ["2330"]


def test_port_failure_does_not_return_a_partial_intraday_bundle():
    gateway = FakeTechnicalGateway(failure=IntradayTechnicalGatewayError("RATE_LIMITED"))
    with pytest.raises(IntradayTechnicalGatewayError, match="RATE_LIMITED"):
        IntradayTechnicalIndicatorService(gateway, now=lambda: NOW).read("2330")
    assert len(gateway.calls) == 1


class FailingSdkGateway(AppGateway):
    def __init__(self, reason: str, *, misconfigured: bool = False):
        self.reason = reason
        self.misconfigured = misconfigured

    def read_intraday_technical_indicator(self, *_args, **_kwargs):
        raise SdkCallError(self.reason, misconfigured=self.misconfigured)


@pytest.mark.parametrize(
    ("reason", "misconfigured", "expected"),
    [
        ("RATE_LIMITED", False, "RATE_LIMITED"),
        ("HISTORY_BUDGET_EXHAUSTED", False, "HISTORY_BUDGET_EXHAUSTED"),
        ("SDK_SESSION_FAILED", False, "UPSTREAM_UNAVAILABLE"),
        ("SDK_SESSION_FAILED", True, "MISCONFIGURED"),
    ],
)
def test_outer_adapter_translates_sdk_errors_into_exact_route_reasons(
    tmp_path, reason, misconfigured, expected,
):
    gateway = FailingSdkGateway(reason, misconfigured=misconfigured)
    app = create_app(config_loader=ready_config(tmp_path), gateway=gateway)

    with TestClient(app) as client:
        response = client.post(
            "/internal/market-data/intraday-technical-indicators/read",
            json={"symbol": "2330"},
            headers={"X-Internal-Service-Token": TOKEN},
        )

    assert response.status_code == 503
    assert response.json() == {"reason": expected}
