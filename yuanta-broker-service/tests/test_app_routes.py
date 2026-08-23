from __future__ import annotations

import logging

from fastapi.testclient import TestClient

import yuanta_broker_service.app as app_module
from yuanta_broker_service.app import create_app
from yuanta_broker_service.config import ConfigLoader
from yuanta_broker_service.sdk_gateway import YuantaBrokerGateway
from yuanta_broker_service.security import redact, redact_mapping

from helpers import TOKEN, FakeYuantaSparkGateway, quote_row, ready_config, stock_inventory_row


ALL_FUNCTIONAL_ROUTES = [
    ("POST", "/internal/accounting/inventory-stock", {}),
    ("POST", "/internal/accounting/inventory-futures", {}),
    ("POST", "/internal/accounting/unrealized-pnl", {}),
    ("POST", "/internal/accounting/realized-pnl", {"startDate": "2026-08-01", "endDate": "2026-08-21"}),
    ("POST", "/internal/accounting/settlement", {}),
    ("POST", "/internal/accounting/futures-margin", {}),
    ("POST", "/internal/market-data/quote", {"codes": ["2330"]}),
    ("POST", "/internal/market-data/five-best", {"codes": ["2330"]}),
    ("POST", "/internal/market-data/intraday-ticks", {"code": "2330"}),
    ("POST", "/internal/market-data/kline", {"code": "2330", "interval": "1D", "count": 5}),
    ("POST", "/internal/market-data/instrument-info", {"code": "2330"}),
    ("POST", "/internal/reports/order-execution", {}),
]


def client_for(loader, gateway=None):
    gateway = gateway or YuantaBrokerGateway(raw_factory=lambda cfg: FakeYuantaSparkGateway())
    application = create_app(loader, gateway)
    return TestClient(application), gateway


def _headers():
    return {"X-Internal-Service-Token": TOKEN}


# --- health / disabled ------------------------------------------------------


def test_disabled_health_is_up_without_touching_gateway(tmp_path):
    client, gateway = client_for(ConfigLoader(tmp_path, lambda: "false"))
    with client:
        response = client.get("/internal/health")
        assert response.status_code == 200
        assert set(response.json()) == {"status", "configState", "sdkComponentVersion", "platform"}
        assert response.json()["status"] == "UP"
        assert response.json()["configState"] == "NOT_CONFIGURED"
        assert response.json()["sdkComponentVersion"] is None
        assert client.post("/internal/accounting/inventory-stock", json={}).status_code == 503
        assert (
            client.post("/internal/accounting/inventory-stock", json={}).json()["detail"]["reason"]
            == "NOT_CONFIGURED"
        )


def test_invalid_enabled_flag_is_misconfigured_not_disabled(tmp_path):
    client, _gateway = client_for(ConfigLoader(tmp_path, lambda: "not-a-boolean"))
    with client:
        health = client.get("/internal/health")
        functional = client.post("/internal/accounting/inventory-stock", json={})
    assert health.json()["configState"] == "MISCONFIGURED"
    assert functional.status_code == 503
    assert functional.json()["detail"]["reason"] == "MISCONFIGURED"


def test_enabled_missing_internal_token_is_healthy_but_functionally_misconfigured(tmp_path):
    loader = ready_config(tmp_path)
    (tmp_path / "internal-service-token").unlink()
    client, _gateway = client_for(loader)
    with client:
        health = client.get("/internal/health")
        assert health.status_code == 200
        assert health.json()["configState"] == "MISCONFIGURED"
        assert client.get("/internal/config").status_code == 503
        assert client.post("/internal/accounting/inventory-stock", json={}).status_code == 503


# --- exact 14-route allowlist ------------------------------------------------


def test_exact_fourteen_route_allowlist(tmp_path):
    client, _gateway = client_for(ready_config(tmp_path))
    routes = {(route.path, frozenset(route.methods or ())) for route in client.app.routes if hasattr(route, "methods")}
    expected = {
        ("/internal/health", frozenset({"GET"})),
        ("/internal/config", frozenset({"GET"})),
        ("/internal/accounting/inventory-stock", frozenset({"POST"})),
        ("/internal/accounting/inventory-futures", frozenset({"POST"})),
        ("/internal/accounting/unrealized-pnl", frozenset({"POST"})),
        ("/internal/accounting/realized-pnl", frozenset({"POST"})),
        ("/internal/accounting/settlement", frozenset({"POST"})),
        ("/internal/accounting/futures-margin", frozenset({"POST"})),
        ("/internal/market-data/quote", frozenset({"POST"})),
        ("/internal/market-data/five-best", frozenset({"POST"})),
        ("/internal/market-data/intraday-ticks", frozenset({"POST"})),
        ("/internal/market-data/kline", frozenset({"POST"})),
        ("/internal/market-data/instrument-info", frozenset({"POST"})),
        ("/internal/reports/order-execution", frozenset({"POST"})),
    }
    assert routes == expected
    with client:
        assert client.get("/docs").status_code == 404
        assert client.get("/redoc").status_code == 404
        assert client.get("/openapi.json").status_code == 404
        assert client.get("/not-a-route").status_code == 404
        assert client.post("/internal/health").status_code == 405
        assert client.get("/internal/accounting/inventory-stock").status_code == 405


def test_token_gate_covers_thirteen_routes(tmp_path):
    client, _gateway = client_for(ready_config(tmp_path))
    with client:
        assert client.get("/internal/config").status_code == 401
        assert client.get("/internal/config", headers={"X-Internal-Service-Token": "wrong"}).status_code == 403
        assert client.get("/internal/config", headers=_headers()).status_code == 200
        for method, path, body in ALL_FUNCTIONAL_ROUTES:
            no_token = client.request(method, path, json=body)
            assert no_token.status_code == 401, path
            wrong_token = client.request(method, path, json=body, headers={"X-Internal-Service-Token": "wrong"})
            assert wrong_token.status_code == 403, path
            ok = client.request(method, path, json=body, headers=_headers())
            assert ok.status_code == 200, (path, ok.text)


def test_duplicate_token_headers_are_rejected(tmp_path):
    client, _gateway = client_for(ready_config(tmp_path))
    with client:
        response = client.get(
            "/internal/config",
            headers=[("X-Internal-Service-Token", TOKEN), ("X-Internal-Service-Token", TOKEN)],
        )
        assert response.status_code == 401


# --- state machine on functional endpoints ----------------------------------


def test_all_functional_endpoints_503_when_not_ready(tmp_path):
    client, _gateway = client_for(ConfigLoader(tmp_path, lambda: "false"))
    with client:
        for method, path, body in ALL_FUNCTIONAL_ROUTES:
            response = client.request(method, path, json=body, headers=_headers())
            assert response.status_code == 503, path
            assert response.json()["detail"]["reason"] == "NOT_CONFIGURED"


def test_accounting_endpoint_503_login_failed_without_downgrading_config(tmp_path):
    fake = FakeYuantaSparkGateway()
    fake.login_error = RuntimeError("bad credentials")
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    client, _gateway = client_for(ready_config(tmp_path), gateway)
    with client:
        response = client.post("/internal/accounting/inventory-stock", json={}, headers=_headers())
        assert response.status_code == 503
        assert response.json()["detail"]["reason"] == "LOGIN_FAILED"
        assert client.get("/internal/health").json()["configState"] == "READY"

        fake.login_error = None
        retry = client.post("/internal/accounting/inventory-stock", json={}, headers=_headers())
        assert retry.status_code == 200


def test_market_data_endpoint_503_unavailable_without_downgrading_config(tmp_path):
    fake = FakeYuantaSparkGateway()
    fake.market_data_error = RuntimeError("feed down")
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    client, _gateway = client_for(ready_config(tmp_path), gateway)
    with client:
        response = client.post("/internal/market-data/quote", json={"codes": ["2330"]}, headers=_headers())
        assert response.status_code == 503
        assert response.json()["detail"]["reason"] == "MARKET_DATA_UNAVAILABLE"
        assert client.get("/internal/health").json()["configState"] == "READY"

        fake.market_data_error = None
        retry = client.post("/internal/market-data/quote", json={"codes": ["2330"]}, headers=_headers())
        assert retry.status_code == 200


def test_account_selector_failure_downgrades_config_to_misconfigured(tmp_path):
    fake = FakeYuantaSparkGateway()
    fake.accounts = []  # zero stock candidates
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    client, _gateway = client_for(ready_config(tmp_path), gateway)
    with client:
        response = client.post("/internal/accounting/inventory-stock", json={}, headers=_headers())
        assert response.status_code == 503
        assert client.get("/internal/health").json()["configState"] == "MISCONFIGURED"
        assert client.get("/internal/config", headers=_headers()).status_code == 503


# --- request validation -----------------------------------------------------


def test_realized_pnl_requires_both_dates(tmp_path):
    client, _gateway = client_for(ready_config(tmp_path))
    with client:
        assert client.post(
            "/internal/accounting/realized-pnl", json={"startDate": "2026-08-01"}, headers=_headers()
        ).status_code == 400
        assert client.post("/internal/accounting/realized-pnl", json={}, headers=_headers()).status_code == 400


def test_quote_codes_bounds(tmp_path):
    client, _gateway = client_for(ready_config(tmp_path))
    with client:
        assert client.post("/internal/market-data/quote", json={"codes": []}, headers=_headers()).status_code == 400
        assert client.post(
            "/internal/market-data/quote", json={"codes": [str(i) for i in range(101)]}, headers=_headers()
        ).status_code == 400


def test_kline_requires_exactly_one_pagination_mode(tmp_path):
    client, _gateway = client_for(ready_config(tmp_path))
    with client:
        missing_both = client.post(
            "/internal/market-data/kline", json={"code": "2330", "interval": "1D"}, headers=_headers()
        )
        assert missing_both.status_code == 400
        both = client.post(
            "/internal/market-data/kline",
            json={"code": "2330", "interval": "1D", "count": 5, "startDate": "2026-08-01", "endDate": "2026-08-21"},
            headers=_headers(),
        )
        assert both.status_code == 400


def test_unexpected_field_is_rejected(tmp_path):
    client, _gateway = client_for(ready_config(tmp_path))
    with client:
        response = client.post(
            "/internal/accounting/inventory-stock", json={"unexpected": True}, headers=_headers()
        )
        assert response.status_code == 400


# --- decimal/quantity boundaries (Task 364.10) via HTTP ---------------------


def test_inventory_stock_marks_bad_row_error_without_dropping_good_rows(tmp_path):
    fake = FakeYuantaSparkGateway()
    fake.responses["stock_inventory"] = [
        stock_inventory_row(code="2330"),
        stock_inventory_row(code="2317", shares=-5),
        stock_inventory_row(code="1101", cost_price="1e10"),
    ]
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    client, _gateway = client_for(ready_config(tmp_path), gateway)
    with client:
        response = client.post("/internal/accounting/inventory-stock", json={}, headers=_headers())
    assert response.status_code == 200
    rows = response.json()["rows"]
    assert rows[0]["status"] == "SUCCESS"
    assert rows[0]["stockCode"] == "2330"
    assert rows[1]["status"] == "ERROR"
    assert rows[1]["reason"] == "INTEGER_RANGE_EXCEEDED"
    assert rows[2]["status"] == "ERROR"
    assert rows[2]["reason"] == "NON_CANONICAL_DECIMAL"


def test_quote_marks_nan_and_infinity_as_error_entries(tmp_path):
    fake = FakeYuantaSparkGateway()
    fake.responses["quote:2330"] = quote_row()
    fake.responses["quote:2317"] = quote_row(last_price="NaN")
    fake.responses["quote:1101"] = quote_row(last_price="Infinity")
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    client, _gateway = client_for(ready_config(tmp_path), gateway)
    with client:
        response = client.post(
            "/internal/market-data/quote", json={"codes": ["2330", "2317", "1101"]}, headers=_headers()
        )
    assert response.status_code == 200
    quotes = response.json()["quotes"]
    assert quotes[0]["status"] == "SUCCESS"
    assert quotes[1]["status"] == "ERROR"
    assert quotes[1]["reason"] == "NON_FINITE_DECIMAL"
    assert quotes[2]["status"] == "ERROR"
    assert quotes[2]["reason"] == "NON_FINITE_DECIMAL"


# --- redaction ---------------------------------------------------------------


def test_unexpected_exception_is_sanitized_before_response_and_log(tmp_path, caplog):
    class ExplodingGateway(YuantaBrokerGateway):
        def get_stock_inventory(self, config):
            raise RuntimeError("leak TEST_ACCOUNT_SENTINEL TEST_PASSWORD_SENTINEL")

    gateway = ExplodingGateway(raw_factory=lambda cfg: FakeYuantaSparkGateway())
    application = create_app(ready_config(tmp_path), gateway)
    with TestClient(application) as client:
        response = client.post("/internal/accounting/inventory-stock", json={}, headers=_headers())
    assert response.status_code == 503
    assert response.json() == {"reason": "INTERNAL_FAILURE"}
    assert "TEST_ACCOUNT_SENTINEL" not in caplog.text
    assert "TEST_PASSWORD_SENTINEL" not in caplog.text
    assert "reason=INTERNAL_FAILURE" in caplog.text


def test_redactor_removes_registered_values_and_sensitive_labels():
    source = (
        "account=TEST_ACCOUNT_SENTINEL password=TEST_PASSWORD_SENTINEL "
        "branch_no=001 certificate-password=TEST_CERT_PASSWORD_SENTINEL "
        "cert_path=/private/certificate.pfx internal-service-token=TEST_INTERNAL_TOKEN_SENTINEL"
    )
    output = redact(source, ["TEST_ACCOUNT_SENTINEL", "TEST_PASSWORD_SENTINEL"])
    assert "TEST_ACCOUNT_SENTINEL" not in output
    assert "TEST_PASSWORD_SENTINEL" not in output
    assert "TEST_CERT_PASSWORD_SENTINEL" not in output
    assert "001" not in output
    assert "/private/certificate.pfx" not in output
    assert "TEST_INTERNAL_TOKEN_SENTINEL" not in output


def test_log_pipeline_redacts_a_careless_raw_value_not_just_the_redact_function(caplog):
    """Proves the redactor is wired into the logging output pipeline, not just
    callable in isolation — bypasses the "only log fixed reason codes"
    convention deliberately to simulate a careless future call site."""
    app_module.install_log_redaction(
        logging.getLogger("yuanta_broker_service.app"),
        logging.getLogger("yuanta_broker_service.sdk_gateway"),
    )
    app_logger = logging.getLogger("yuanta_broker_service.app")
    sdk_logger = logging.getLogger("yuanta_broker_service.sdk_gateway")

    with caplog.at_level(logging.WARNING):
        app_logger.warning("unexpected raw leak account=%s password=%s", "0001234567", "hunter2")
        sdk_logger.error("sdk raw response leaked certificate-password=%s", "TEST_RAW_LEAK_SENTINEL")

    assert "0001234567" not in caplog.text
    assert "hunter2" not in caplog.text
    assert "TEST_RAW_LEAK_SENTINEL" not in caplog.text
    assert caplog.text.count("[REDACTED]") >= 3


def test_redact_mapping_preserves_response_schema_while_redacting_string_leaves():
    payload = {
        "reason": "LOGIN_FAILED",
        "detail": {"debug": "account=0001234567 unexpected"},
        "rows": [{"stockCode": "2330", "shares": 3}],
        "note": None,
    }
    result = redact_mapping(payload)
    assert result["reason"] == "LOGIN_FAILED"
    assert "0001234567" not in result["detail"]["debug"]
    assert "[REDACTED]" in result["detail"]["debug"]
    assert result["rows"] == [{"stockCode": "2330", "shares": 3}]
    assert result["note"] is None
    assert set(result) == set(payload)


def test_health_response_never_contains_secret_values(tmp_path):
    client, _gateway = client_for(ready_config(tmp_path))
    with client:
        response = client.get("/internal/health")
    assert response.status_code == 200
    body = response.text
    assert "TEST_ACCOUNT_SENTINEL" not in body
    assert "TEST_PASSWORD_SENTINEL" not in body
    assert "TEST_CERT_PASSWORD_SENTINEL" not in body
    assert TOKEN not in body
