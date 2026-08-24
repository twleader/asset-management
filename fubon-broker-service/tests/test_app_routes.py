from __future__ import annotations

import logging
from pathlib import Path

from fastapi.testclient import TestClient

import fubon_broker_service.app as app_module
from fubon_broker_service.app import create_app
from fubon_broker_service.config import ConfigLoader
from fubon_broker_service.redaction import redact, redact_mapping

from helpers import TOKEN, ready_config


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


class ExplodingPortfolio:
    def read(self):
        raise RuntimeError("TEST_PERSONAL_ID_SENTINEL TEST_API_KEY_SENTINEL")


def client_for(loader):
    gateway = Gateway()
    portfolio = Portfolio()
    quotes = Quotes()
    application = create_app(loader, gateway, portfolio, quotes)
    return TestClient(application), gateway, portfolio, quotes


def test_disabled_health_is_up_without_reading_sdk_or_secrets(tmp_path):
    client, gateway, portfolio, quotes = client_for(ConfigLoader(tmp_path, lambda: "false"))
    with client:
        response = client.get("/internal/health")
        assert response.status_code == 200
        assert set(response.json()) == {"status", "configState", "sdkVersion", "platform"}
        assert response.json()["status"] == "UP"
        assert response.json()["configState"] == "NOT_CONFIGURED"
        assert client.post("/internal/portfolio/read", json={"dryRun": True}).status_code == 503
    assert portfolio.calls == quotes.calls == 0
    assert gateway.shutdown_calls == 1


def test_exact_four_routes_auth_and_methods(tmp_path):
    client, _gateway, portfolio, quotes = client_for(ready_config(tmp_path))
    headers = {"X-Internal-Service-Token": TOKEN}
    assert {(route.path, frozenset(route.methods or ())) for route in client.app.routes} == {
        ("/internal/health", frozenset({"GET"})),
        ("/internal/config", frozenset({"GET"})),
        ("/internal/portfolio/read", frozenset({"POST"})),
        ("/internal/market-data/tw-quotes", frozenset({"POST"})),
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
        assert client.get("/docs").status_code == 404
        assert client.get("/redoc").status_code == 404
        assert client.get("/openapi.json").status_code == 404
        assert client.get("/not-allowed").status_code == 404
        assert client.post("/internal/health").status_code == 405
        assert client.get("/internal/portfolio/read", headers=headers).status_code == 405
    assert portfolio.calls == quotes.calls == 1


def test_portfolio_has_no_commit_mode_and_invalid_requests_are_400(tmp_path):
    client, _gateway, portfolio, _quotes = client_for(ready_config(tmp_path))
    headers = {"X-Internal-Service-Token": TOKEN}
    with client:
        assert client.post("/internal/portfolio/read", headers=headers, json={"dryRun": False}).status_code == 400
        assert client.post(
            "/internal/portfolio/read",
            headers=headers,
            json={"dryRun": True, "personalId": "forbidden"},
        ).status_code == 400
    assert portfolio.calls == 0


def test_enabled_missing_shared_token_is_healthy_but_functionally_misconfigured(tmp_path):
    loader = ready_config(tmp_path)
    (tmp_path / "shared" / "internal-service-token").unlink()
    client, _gateway, portfolio, quotes = client_for(loader)
    with client:
        health = client.get("/internal/health")
        assert health.status_code == 200
        assert health.json()["configState"] == "MISCONFIGURED"
        assert client.get("/internal/config").status_code == 503
        assert client.post("/internal/portfolio/read", json={"dryRun": True}).status_code == 503
    assert portfolio.calls == quotes.calls == 0


def test_invalid_enabled_flag_is_misconfigured_not_disabled(tmp_path):
    client, _gateway, portfolio, quotes = client_for(ConfigLoader(tmp_path, lambda: "not-a-boolean"))
    with client:
        health = client.get("/internal/health")
        functional = client.get("/internal/config")
    assert health.json()["configState"] == "MISCONFIGURED"
    assert functional.status_code == 503
    assert functional.json() == {"detail": {"reason": "INVALID_ENABLED_FLAG"}}
    assert portfolio.calls == quotes.calls == 0


def test_runtime_misconfiguration_returns_503_without_retrying_functionality(tmp_path):
    client, gateway, portfolio, quotes = client_for(ready_config(tmp_path))
    gateway.runtime_misconfigured = True
    headers = {"X-Internal-Service-Token": TOKEN}
    with client:
        assert client.get("/internal/health").json()["configState"] == "MISCONFIGURED"
        assert client.get("/internal/config", headers=headers).status_code == 503
        assert client.post("/internal/portfolio/read", headers=headers, json={"dryRun": True}).status_code == 503
        assert client.post(
            "/internal/market-data/tw-quotes", headers=headers, json={"codes": ["2330"], "purpose": "LIVE"}
        ).status_code == 503
    assert portfolio.calls == quotes.calls == 0


def test_unexpected_exception_is_sanitized_before_response_and_log(tmp_path, caplog):
    gateway = Gateway()
    application = create_app(ready_config(tmp_path), gateway, ExplodingPortfolio(), Quotes())
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
