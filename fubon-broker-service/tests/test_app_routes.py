from __future__ import annotations

import json
import logging
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import fubon_broker_service.app as app_module
from fubon_broker_service.app import create_app
from fubon_broker_service.config import ConfigLoader
from fubon_broker_service.etf_holdings import EtfHoldingsService
from fubon_broker_service.redaction import redact, redact_mapping
from fubon_broker_service.sdk_gateway import SdkCallError
from fubon_broker_service.trades import TradeReadError

from helpers import TOKEN, fixed_now, ready_config


class Gateway:
    runtime_misconfigured = False

    def __init__(self):
        self.shutdown_calls = 0

    def shutdown(self):
        self.shutdown_calls += 1

    def runtime_misconfigured_for(self, _config):
        return self.runtime_misconfigured


class Portfolio:
    def __init__(self):
        self.calls = 0

    def read(self):
        self.calls += 1
        return {
            "batchId": "batch",
            "queryDate": "2026-08-21",
            "accountFingerprint": "fingerprint",
            "emptyConfirmed": True,
            "positions": [],
            "reason": "EMPTY_CONFIRMED",
        }


class Quotes:
    def __init__(self):
        self.calls = 0

    async def read(self, codes, purpose):
        self.calls += 1
        assert purpose in {"LIVE", "INVENTORY"}
        return {"batchId": "batch", "quotes": [{"stockCode": codes[0], "status": "SUCCESS"}]}


class Trades:
    def __init__(self):
        self.calls = 0

    def read(self, start_date, end_date, internal_token):
        self.calls += 1
        assert internal_token
        return {
            "batchId": "trade-batch",
            "startDate": start_date,
            "endDate": end_date,
            "accountFingerprint": "fingerprint",
            "emptyConfirmed": True,
            "trades": [],
        }


class EtfHoldings:
    def __init__(self):
        self.calls = 0

    async def read(self, codes):
        self.calls += 1
        return {
            "batchId": "etf-holdings-batch",
            "holdings": [
                {"stockCode": code, "status": "SUCCESS", "reason": None, "rawResponseJson": "{}"}
                for code in codes
            ],
        }


class ExplodingPortfolio:
    def read(self):
        raise RuntimeError("TEST_PERSONAL_ID_SENTINEL TEST_API_KEY_SENTINEL")


def client_for(loader):
    gateway = Gateway()
    portfolio = Portfolio()
    quotes = Quotes()
    trades = Trades()
    etf_holdings = EtfHoldings()
    application = create_app(
        loader, gateway, portfolio, quotes, trades, etf_holdings_service=etf_holdings
    )
    return TestClient(application), gateway, portfolio, quotes, trades, etf_holdings


def test_disabled_health_is_up_without_reading_sdk_or_secrets(tmp_path):
    client, gateway, portfolio, quotes, trades, etf_holdings = client_for(
        ConfigLoader(tmp_path, lambda: "false")
    )
    with client:
        response = client.get("/internal/health")
        assert response.status_code == 200
        assert set(response.json()) == {"status", "configState", "sdkVersion", "platform"}
        assert response.json()["status"] == "UP"
        assert response.json()["configState"] == "NOT_CONFIGURED"
        assert client.post("/internal/portfolio/read", json={"dryRun": True}).status_code == 503
    assert portfolio.calls == quotes.calls == trades.calls == etf_holdings.calls == 0
    assert gateway.shutdown_calls == 1


def test_exact_seven_routes_auth_and_methods(tmp_path):
    client, _gateway, portfolio, quotes, trades, etf_holdings = client_for(ready_config(tmp_path))
    headers = {"X-Internal-Service-Token": TOKEN}
    assert {(route.path, frozenset(route.methods or ())) for route in client.app.routes} == {
        ("/internal/health", frozenset({"GET"})),
        ("/internal/config", frozenset({"GET"})),
        ("/internal/portfolio/read", frozenset({"POST"})),
        ("/internal/market-data/tw-quotes", frozenset({"POST"})),
        ("/internal/market-data/etf-holdings", frozenset({"POST"})),
        ("/internal/trades/read", frozenset({"POST"})),
        ("/internal/market-data/taiex-index/stream", frozenset({"GET"})),
    }
    with client:
        assert client.get("/internal/health").status_code == 200
        assert client.get("/internal/config").status_code == 401
        assert client.get("/internal/config", headers={"X-Internal-Service-Token": "wrong"}).status_code == 403
        assert client.get(
            "/internal/config",
            headers=[("X-Internal-Service-Token", TOKEN), ("X-Internal-Service-Token", TOKEN)],
        ).status_code == 401
        assert client.get("/internal/config", headers=headers).status_code == 200
        assert client.post("/internal/portfolio/read", headers=headers, json={"dryRun": True}).status_code == 200
        assert client.post("/internal/market-data/tw-quotes", headers=headers, json={"codes": ["2330"], "purpose": "LIVE"}).status_code == 200
        assert client.post(
            "/internal/market-data/etf-holdings", headers=headers, json={"codes": ["0050"]}
        ).status_code == 200
        assert client.post(
            "/internal/trades/read", headers=headers, json={"startDate": "2026-08-21", "endDate": "2026-08-21"}
        ).status_code == 200
        assert client.post("/internal/trades/read", json={"startDate": "2026-08-21", "endDate": "2026-08-21"}).status_code == 401
        assert client.post("/internal/market-data/etf-holdings", json={"codes": ["0050"]}).status_code == 401
        assert client.get("/docs").status_code == 404
        assert client.get("/redoc").status_code == 404
        assert client.get("/openapi.json").status_code == 404
        assert client.get("/not-allowed").status_code == 404
        assert client.post("/internal/health").status_code == 405
        assert client.get("/internal/portfolio/read", headers=headers).status_code == 405
        assert client.get("/internal/trades/read", headers=headers).status_code == 405
        assert client.get("/internal/market-data/etf-holdings", headers=headers).status_code == 405
        assert client.get("/internal/market-data/taiex-index/stream", headers=headers).status_code == 503
    assert portfolio.calls == quotes.calls == 1
    assert trades.calls == 1
    assert etf_holdings.calls == 1


def test_portfolio_has_no_commit_mode_and_invalid_requests_are_400(tmp_path):
    client, _gateway, portfolio, _quotes, _trades, _etf_holdings = client_for(ready_config(tmp_path))
    headers = {"X-Internal-Service-Token": TOKEN}
    with client:
        assert client.post("/internal/portfolio/read", headers=headers, json={"dryRun": False}).status_code == 400
        assert client.post(
            "/internal/portfolio/read",
            headers=headers,
            json={"dryRun": True, "personalId": "forbidden"},
        ).status_code == 400
    assert portfolio.calls == 0


def test_etf_holdings_request_body_validation_matrix(tmp_path):
    client, _gateway, _portfolio, _quotes, _trades, etf_holdings = client_for(ready_config(tmp_path))
    headers = {"X-Internal-Service-Token": TOKEN}
    with client:
        assert client.post(
            "/internal/market-data/etf-holdings", headers=headers, json={"codes": []}
        ).status_code == 400
        assert client.post(
            "/internal/market-data/etf-holdings",
            headers=headers,
            json={"codes": [f"00{i:03d}" for i in range(100, 151)]},
        ).status_code == 400
        assert client.post(
            "/internal/market-data/etf-holdings",
            headers=headers,
            json={"codes": ["0050"], "purpose": "LIVE"},
        ).status_code == 400
        assert client.post(
            "/internal/market-data/etf-holdings", headers=headers, json={}
        ).status_code == 400
        response = client.post(
            "/internal/market-data/etf-holdings",
            headers=headers,
            json={"codes": [f"00{i:03d}" for i in range(100, 150)]},
        )
        assert response.status_code == 200
        assert len(response.json()["holdings"]) == 50
    assert etf_holdings.calls == 1


@pytest.mark.parametrize("body", [
    {"codes": ["0000"]}, {"codes": ["2330"]}, {"codes": ["AAPL"]},
    {"codes": ["0050", "0050"]}, {"codes": ["0050 "]}, {"codes": [" 0050"]},
    {"codes": ["00981a"]}, {"codes": ["00Ａ１"]}, {"codes": ["0050\n"]},
    {"codes": ["005"]}, {"codes": ["0012345"]}, {"codes": ["0050/secret"]},
    {"codes": [True]}, {"codes": [50]}, {"codes": [None]}, {"codes": "0050"},
    {"codes": ["0050"], "account": "ACCOUNT_SENTINEL"},
])
def test_etf_holdings_rejects_noncanonical_or_duplicate_codes_without_calling_service(tmp_path, body):
    client, _gateway, _portfolio, _quotes, _trades, etf_holdings = client_for(ready_config(tmp_path))
    with client:
        response = client.post(
            "/internal/market-data/etf-holdings", headers={"X-Internal-Service-Token": TOKEN}, json=body,
        )
    assert response.status_code == 400
    assert response.json() == {"reason": "INVALID_REQUEST"}
    assert etf_holdings.calls == 0


def test_etf_holdings_accepts_numeric_and_active_etf_symbols_and_checks_each_auth_form(tmp_path):
    client, _gateway, _portfolio, _quotes, _trades, etf_holdings = client_for(ready_config(tmp_path))
    body = {"codes": ["0050", "006208", "00981A", "00631L"]}
    with client:
        assert client.post("/internal/market-data/etf-holdings", json=body).status_code == 401
        assert client.post(
            "/internal/market-data/etf-holdings", json=body,
            headers={"X-Internal-Service-Token": "wrong"},
        ).status_code == 403
        assert client.post(
            "/internal/market-data/etf-holdings", json=body,
            headers=[("X-Internal-Service-Token", TOKEN), ("X-Internal-Service-Token", TOKEN)],
        ).status_code == 401
        response = client.post(
            "/internal/market-data/etf-holdings", json=body, headers={"X-Internal-Service-Token": TOKEN},
        )
    assert response.status_code == 200
    assert [row["stockCode"] for row in response.json()["holdings"]] == body["codes"]
    assert response.json()["counters"]["SUCCESS"] == 1
    assert response.json()["counters"]["QUOTE_FAILED"] == 0
    assert etf_holdings.calls == 1


def test_etf_holdings_returns_503_when_disabled_or_misconfigured(tmp_path):
    client, _gateway, _portfolio, _quotes, _trades, etf_holdings = client_for(
        ConfigLoader(tmp_path, lambda: "false")
    )
    with client:
        assert client.post(
            "/internal/market-data/etf-holdings", json={"codes": ["0050"]}
        ).status_code == 503

    loader = ready_config(tmp_path)
    (tmp_path / "shared" / "internal-service-token").unlink()
    client, _gateway, _portfolio, _quotes, _trades, etf_holdings = client_for(loader)
    headers = {"X-Internal-Service-Token": TOKEN}
    with client:
        assert client.post(
            "/internal/market-data/etf-holdings", headers=headers, json={"codes": ["0050"]}
        ).status_code == 503
    assert etf_holdings.calls == 0


def test_etf_route_returns_only_normalized_fields_and_counts_mixed_batch_failure(tmp_path):
    class ProviderGateway(Gateway):
        def read_etf_holdings(self, code):
            if code == "0056":
                raise RuntimeError("SECRET_SENTINEL")
            return {
                "symbol": code, "account": "ACCOUNT_SENTINEL", "api-key": "SECRET_SENTINEL",
                "data": [{"date": "2026-08-20", "components": [
                    {"symbol": "2330", "name": "台積電", "weight": 58.82, "quantity": 530358242},
                ]}],
            }

    gateway = ProviderGateway()
    application = create_app(
        ready_config(tmp_path), gateway,
        etf_holdings_service=EtfHoldingsService(gateway, now=fixed_now),
    )
    with TestClient(application) as client:
        response = client.post(
            "/internal/market-data/etf-holdings", json={"codes": ["0050", "0056"]},
            headers={"X-Internal-Service-Token": TOKEN},
        )
    assert response.status_code == 200
    body = response.json()
    assert "SENTINEL" not in response.text
    assert set(body) == {"batchId", "holdings", "counters"}
    assert json.loads(body["holdings"][0]["rawResponseJson"]) == {
        "schemaVersion": 1, "stockCode": "0050", "sourceDate": "2026-08-20",
        "holdings": [{"stockCode": "2330", "stockName": "台積電", "weight": "58.82", "shares": "530358242"}],
    }
    assert body["holdings"][1] == {
        "stockCode": "0056", "status": "FAILURE", "reason": "ETF_HOLDINGS_FAILED", "rawResponseJson": None,
    }
    assert body["counters"]["QUOTE_FAILED"] == 1
    assert body["counters"]["SUCCESS"] == 0


def test_trades_read_rejects_malformed_dates_and_oversized_ranges(tmp_path):
    client, _gateway, _portfolio, _quotes, trades, _etf_holdings = client_for(ready_config(tmp_path))
    headers = {"X-Internal-Service-Token": TOKEN}
    with client:
        assert client.post(
            "/internal/trades/read", headers=headers, json={"startDate": "not-a-date", "endDate": "2026-08-21"}
        ).status_code == 400
        assert client.post(
            "/internal/trades/read", headers=headers, json={"startDate": "2026-08-21", "endDate": "2026/08/21"}
        ).status_code == 400
        assert client.post(
            "/internal/trades/read",
            headers=headers,
            json={"startDate": "2026-08-21", "endDate": "2026-08-21", "dryRun": True},
        ).status_code == 400
        response = client.post(
            "/internal/trades/read", headers=headers, json={"startDate": "2026-08-22", "endDate": "2026-08-21"}
        )
        assert response.status_code == 400
        assert response.json()["detail"]["reason"] == "INVALID_DATE_RANGE"
        response = client.post(
            "/internal/trades/read", headers=headers, json={"startDate": "2026-08-01", "endDate": "2026-08-09"}
        )
        assert response.status_code == 400
        assert response.json()["detail"]["reason"] == "INVALID_DATE_RANGE"
        # exactly 7 days apart is within bounds
        assert client.post(
            "/internal/trades/read", headers=headers, json={"startDate": "2026-08-01", "endDate": "2026-08-08"}
        ).status_code == 200
    assert trades.calls == 1


def test_trades_read_maps_reconciliation_failure_to_sanitized_503(tmp_path):
    class RejectingTrades:
        def read(self, start_date, end_date, internal_token):
            raise TradeReadError("RECONCILE_FAILED")

    application = create_app(ready_config(tmp_path), Gateway(), Portfolio(), Quotes(), RejectingTrades())
    headers = {"X-Internal-Service-Token": TOKEN}
    with TestClient(application) as client:
        response = client.post(
            "/internal/trades/read", headers=headers, json={"startDate": "2026-08-21", "endDate": "2026-08-21"}
        )
    assert response.status_code == 503
    assert response.json() == {"detail": {"reason": "RECONCILE_FAILED"}}


def test_trades_read_maps_sdk_call_error_to_sanitized_503(tmp_path):
    class FailingTrades:
        def read(self, start_date, end_date, internal_token):
            raise SdkCallError("FILLED_HISTORY_UNAVAILABLE", misconfigured=True)

    application = create_app(ready_config(tmp_path), Gateway(), Portfolio(), Quotes(), FailingTrades())
    headers = {"X-Internal-Service-Token": TOKEN}
    with TestClient(application) as client:
        response = client.post(
            "/internal/trades/read", headers=headers, json={"startDate": "2026-08-21", "endDate": "2026-08-21"}
        )
    assert response.status_code == 503
    assert response.json() == {"detail": {"reason": "FILLED_HISTORY_UNAVAILABLE"}}


def test_enabled_missing_shared_token_is_healthy_but_functionally_misconfigured(tmp_path):
    loader = ready_config(tmp_path)
    (tmp_path / "shared" / "internal-service-token").unlink()
    client, _gateway, portfolio, quotes, trades, etf_holdings = client_for(loader)
    with client:
        health = client.get("/internal/health")
        assert health.status_code == 200
        assert health.json()["configState"] == "MISCONFIGURED"
        assert client.get("/internal/config").status_code == 503
        assert client.post("/internal/portfolio/read", json={"dryRun": True}).status_code == 503
    assert portfolio.calls == quotes.calls == trades.calls == etf_holdings.calls == 0


def test_invalid_enabled_flag_is_misconfigured_not_disabled(tmp_path):
    client, _gateway, portfolio, quotes, trades, etf_holdings = client_for(
        ConfigLoader(tmp_path, lambda: "not-a-boolean")
    )
    with client:
        health = client.get("/internal/health")
        functional = client.get("/internal/config")
    assert health.json()["configState"] == "MISCONFIGURED"
    assert functional.status_code == 503
    assert functional.json() == {"detail": {"reason": "INVALID_ENABLED_FLAG"}}
    assert portfolio.calls == quotes.calls == trades.calls == etf_holdings.calls == 0


def test_runtime_misconfiguration_returns_503_without_retrying_functionality(tmp_path):
    client, gateway, portfolio, quotes, trades, etf_holdings = client_for(ready_config(tmp_path))
    gateway.runtime_misconfigured = True
    headers = {"X-Internal-Service-Token": TOKEN}
    with client:
        assert client.get("/internal/health").json()["configState"] == "MISCONFIGURED"
        assert client.get("/internal/config", headers=headers).status_code == 503
        assert client.post("/internal/portfolio/read", headers=headers, json={"dryRun": True}).status_code == 503
        assert client.post(
            "/internal/market-data/tw-quotes", headers=headers, json={"codes": ["2330"], "purpose": "LIVE"}
        ).status_code == 503
        assert client.post(
            "/internal/market-data/etf-holdings", headers=headers, json={"codes": ["0050"]}
        ).status_code == 503
        assert client.post(
            "/internal/trades/read", headers=headers, json={"startDate": "2026-08-21", "endDate": "2026-08-21"}
        ).status_code == 503
    assert portfolio.calls == quotes.calls == trades.calls == etf_holdings.calls == 0


def test_unexpected_exception_is_sanitized_before_response_and_log(tmp_path, caplog):
    gateway = Gateway()
    application = create_app(ready_config(tmp_path), gateway, ExplodingPortfolio(), Quotes(), Trades())
    headers = {"X-Internal-Service-Token": TOKEN}
    with TestClient(application) as client:
        response = client.post("/internal/portfolio/read", headers=headers, json={"dryRun": True})
    assert response.status_code == 503
    assert response.json() == {"reason": "INTERNAL_FAILURE"}
    assert "TEST_PERSONAL_ID_SENTINEL" not in caplog.text
    assert "TEST_API_KEY_SENTINEL" not in caplog.text
    assert "reason=INTERNAL_FAILURE" in caplog.text


def test_redactor_removes_registered_values_and_sensitive_labels():
    source = (
        "personal-id=TEST_PERSONAL_ID_SENTINEL account=TEST_ACCOUNT_SENTINEL "
        "branch_no=001 api-key=TEST_API_KEY_SENTINEL "
        "certificate-password=TEST_CERT_PASSWORD_SENTINEL cert_path=/private/certificate.pfx"
    )
    output = redact(source, ["TEST_PERSONAL_ID_SENTINEL", "TEST_ACCOUNT_SENTINEL"])
    assert "TEST_PERSONAL_ID_SENTINEL" not in output
    assert "TEST_ACCOUNT_SENTINEL" not in output
    assert "TEST_API_KEY_SENTINEL" not in output
    assert "TEST_CERT_PASSWORD_SENTINEL" not in output
    assert "001" not in output
    assert "/private/certificate.pfx" not in output


def test_log_pipeline_redacts_a_careless_raw_value_not_just_the_redact_function(caplog):
    """Proves the redactor is actually wired into the logging output pipeline.

    Every real call site in this service only ever logs a fixed reason code
    (e.g. "reason=SDK_LOGIN_FAILED"), so calling redact() in isolation (as
    test_redactor_removes_registered_values_and_sensitive_labels does) cannot
    tell us whether a *careless future call site* would actually be caught.
    Here we bypass that convention deliberately: log straight through the
    exact logger objects app.py installs the central filter on, using a
    message that embeds a raw personal-id/account/api-key value the way an
    accidental `logger.warning(f"... {exc}")` might. If the filter is truly
    on the output path, the raw values never reach the emitted record.
    """
    app_module.install_log_redaction(
        logging.getLogger("fubon_broker_service.app"),
        logging.getLogger("fubon_broker_service.sdk_gateway"),
    )
    app_logger = logging.getLogger("fubon_broker_service.app")
    sdk_logger = logging.getLogger("fubon_broker_service.sdk_gateway")

    with caplog.at_level(logging.WARNING):
        app_logger.warning(
            "unexpected raw leak personal-id=%s account=%s",
            "A123456789",
            "00099998888",
        )
        sdk_logger.error("sdk raw response leaked api-key=%s", "TEST_RAW_APIKEY_LEAK_SENTINEL")

    assert "A123456789" not in caplog.text
    assert "00099998888" not in caplog.text
    assert "TEST_RAW_APIKEY_LEAK_SENTINEL" not in caplog.text
    assert caplog.text.count("[REDACTED]") >= 3


def test_redact_mapping_preserves_response_schema_while_redacting_string_leaves():
    payload = {
        "reason": "SDK_LOGIN_FAILED",
        "detail": {"debug": "account=00099998888 unexpected"},
        "positions": [{"stockCode": "2330", "shares": 3}],
        "counters": {"SUCCESS": 1},
        "emptyConfirmed": False,
        "note": None,
    }
    result = redact_mapping(payload)
    assert result["reason"] == "SDK_LOGIN_FAILED"
    assert "00099998888" not in result["detail"]["debug"]
    assert "[REDACTED]" in result["detail"]["debug"]
    assert result["positions"] == [{"stockCode": "2330", "shares": 3}]
    assert result["counters"] == {"SUCCESS": 1}
    assert result["emptyConfirmed"] is False
    assert result["note"] is None
    assert set(result) == set(payload)


def test_selector_must_be_absent_or_a_complete_pair(tmp_path):
    loader = ready_config(tmp_path, branch="001")
    snapshot = loader.load()
    assert snapshot.state == "MISCONFIGURED"
    assert snapshot.reason == "INCOMPLETE_ACCOUNT_SELECTOR"
    assert "TEST_PERSONAL_ID_SENTINEL" not in repr(snapshot)


def test_etf_failure_does_not_change_the_exact_thirteen_counter_keys_seen_by_other_routes(tmp_path):
    expected_keys = {
        "DISABLED", "MISCONFIGURED", "CALENDAR_UNKNOWN", "ACCOUNTING_FAILED", "RECONCILE_FAILED",
        "QUOTE_FAILED", "NO_OWNER", "NO_TODAY_SNAPSHOT", "BROKER_MISSING", "DRY_RUN", "SUCCESS",
        "EMPTY_CLEARED", "ROLLED_BACK",
    }
    client, _gateway, _portfolio, _quotes, _trades, etf = client_for(ready_config(tmp_path))

    async def mixed_result(codes):
        return {"batchId": "etf-batch", "holdings": [
            {"stockCode": codes[0], "status": "SUCCESS", "reason": None, "rawResponseJson": "{}"},
            {"stockCode": codes[1], "status": "FAILURE", "reason": "ETF_HOLDINGS_FAILED", "rawResponseJson": None},
        ]}

    etf.read = mixed_result
    headers = {"X-Internal-Service-Token": TOKEN}
    with client:
        before = client.post("/internal/market-data/tw-quotes", headers=headers,
                             json={"codes": ["2330"], "purpose": "LIVE"})
        holdings = client.post("/internal/market-data/etf-holdings", headers=headers,
                               json={"codes": ["0050", "0056"]})
        after = client.post("/internal/market-data/tw-quotes", headers=headers,
                            json={"codes": ["2330"], "purpose": "LIVE"})
        portfolio = client.post("/internal/portfolio/read", headers=headers, json={"dryRun": True})
    for response in (before, holdings, after, portfolio):
        assert response.status_code == 200
        assert set(response.json()["counters"]) == expected_keys
        assert "ETF_HOLDINGS_FAILED" not in response.json()["counters"]
    assert before.json()["counters"]["QUOTE_FAILED"] == 0
    for response in (holdings, after, portfolio):
        assert response.json()["counters"]["QUOTE_FAILED"] == 1
    assert holdings.json()["holdings"][1]["reason"] == "ETF_HOLDINGS_FAILED"
