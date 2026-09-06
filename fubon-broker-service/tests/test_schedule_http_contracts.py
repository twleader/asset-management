import asyncio
import hashlib
import hmac
import json

from fastapi.testclient import TestClient
import pytest

from fubon_broker_service.app import create_app
from fubon_broker_service.bank_balance import BankBalanceService
from fubon_broker_service.config import ConfigLoader
from fubon_broker_service.counters import OutcomeCounters
from fubon_broker_service.dividends import DividendService
from fubon_broker_service.quotes import QuoteService
from fubon_broker_service.realized_gain import RealizedGainService
from fubon_broker_service.sdk_gateway import SdkGateway
from fubon_broker_service.settlement import AMOUNT_FIELDS, SettlementService
from fubon_broker_service.stock_push_stream import StockPushStream
from fubon_broker_service.technical_indicators import TechnicalIndicatorService

from accounting_fixtures import NOW as ACCOUNTING_NOW, bank_row, realized_row, settlement_data, settlement_row
from helpers import TOKEN, filled_trade_row, fixed_now, quote_raw, ready_config, response
from market_fixtures import DIVIDEND_FROM, DIVIDEND_TO, NOW, TECHNICAL_FROM, TODAY, dividend_row, technical_result, technical_v2_result
from stock_push_fixtures import FakeWebsocket, STOCK_NOW, StreamFixture, packet
from test_market_gateway import MarketSdk


HEADER = {"X-Internal-Service-Token": TOKEN}
DIVIDENDS = "/internal/market-data/dividends/read"
TECHNICAL = "/internal/market-data/technical-indicators/read"
BASIC = "/internal/market-data/stock-basic/read"
CANDLES = "/internal/market-data/intraday-candles/read"
SUBSCRIPTIONS = "/internal/market-data/stock-push/subscriptions"
STOCK_STREAM = "/internal/market-data/stock-push/stream"
ACCOUNTING = ["/internal/bank-balance/read", "/internal/settlement/read", "/internal/realized-gains/read"]
PROTECTED = [
    ("GET", "/internal/config", None),
    ("POST", "/internal/portfolio/read", {"dryRun": True}),
    ("POST", "/internal/market-data/tw-quotes", {"codes": ["2330"], "purpose": "LIVE"}),
    ("POST", "/internal/market-data/etf-holdings", {"codes": ["0050"]}),
    ("POST", "/internal/trades/read", {"startDate": "2026-08-21", "endDate": "2026-08-21"}),
    ("GET", "/internal/market-data/taiex-index/stream", None),
    *[("POST", path, None) for path in ACCOUNTING],
    ("POST", DIVIDENDS, {"symbols": ["2330"], "from": DIVIDEND_FROM, "to": DIVIDEND_TO}),
    ("POST", TECHNICAL, {"symbol": "2330"}),
    ("POST", SUBSCRIPTIONS, {"symbols": ["2330"]}),
    ("GET", STOCK_STREAM, None),
]
COUNTER_KEYS = {"DISABLED", "MISCONFIGURED", "CALENDAR_UNKNOWN", "ACCOUNTING_FAILED", "RECONCILE_FAILED",
                "QUOTE_FAILED", "NO_OWNER", "NO_TODAY_SNAPSHOT", "BROKER_MISSING", "DRY_RUN", "SUCCESS",
                "EMPTY_CLEARED", "ROLLED_BACK"}


class RouteSdk(MarketSdk):
    def __init__(self):
        super().__init__([])
        self.bank_source = bank_row(balance=0, available_balance=0)
        self.dividend_source = {"data": [dividend_row()]}
        self.technical_source = None

    def init_realtime(self):
        result = super().init_realtime()
        self.marketdata.rest_client.stock.intraday.quote = lambda **params: quote_raw(params["symbol"])
        return result

    def handle(self, kind, params):
        if kind == "dividends":
            return self.dividend_source
        if kind in {"sma", "rsi"}:
            return technical_v2_result(
                kind, params["symbol"], start=params["from"], end=params["to"],
                timeframe=params["timeframe"],
                parameters={key: value for key, value in params.items()
                            if key not in {"symbol", "from", "to", "timeframe"}},
            )
        return self.technical_source if self.technical_source is not None else technical_result(kind, params["symbol"])

    def bank_remain(self, selected):
        self.events.append("bank_remain")
        return response(self.bank_source)

    def query_settlement(self, selected, range_param):
        self.events.append(("settlement", range_param))
        return response(settlement_data([settlement_row(
            date="2026/08/28", settlement_date=None, currency=None, **{field: None for field in AMOUNT_FIELDS})]))

    def realized_gains_and_loses(self, selected):
        self.events.append("realized")
        return response([realized_row()])

    def filled_history(self, selected, start, end):
        self.events.append(("filled_history", start, end))
        return response([filled_trade_row()])


def app_fixture(tmp_path, *, state="READY"):
    loader = (ready_config(tmp_path, branch="001", account="00001234567") if state == "READY"
              else ConfigLoader(tmp_path, lambda: "false" if state == "DISABLED" else "true"))
    sdk = RouteSdk()
    gateway = SdkGateway(loader, sdk_factory=lambda: sdk, sleeper=lambda _delay: None)
    app = create_app(loader, gateway, quote_service=QuoteService(gateway, now=fixed_now),
                     bank_balance_service=BankBalanceService(gateway, now=lambda: ACCOUNTING_NOW),
                     settlement_service=SettlementService(gateway, now=lambda: ACCOUNTING_NOW),
                     realized_gain_service=RealizedGainService(gateway, now=lambda: ACCOUNTING_NOW),
                     dividend_service=DividendService(gateway, now=lambda: NOW),
                     technical_indicator_service=TechnicalIndicatorService(gateway, now=lambda: NOW))
    return TestClient(app), sdk


@pytest.mark.parametrize("headers,status", [({}, 401), ({"X-Internal-Service-Token": "wrong"}, 403),
                                           ([("X-Internal-Service-Token", TOKEN), ("X-Internal-Service-Token", TOKEN)], 401)])
def test_every_protected_route_rejects_missing_wrong_or_duplicate_token_without_sdk(tmp_path, headers, status):
    client, sdk = app_fixture(tmp_path)
    with client:
        for method, path, body in PROTECTED:
            response = client.request(method, path, headers=headers, **({"json": body} if body is not None else {}))
            assert response.status_code == status, (path, response.text)
        assert sdk.events == [] and sdk.market_calls == []


@pytest.mark.parametrize("state", ["DISABLED", "MISCONFIGURED"])
def test_every_protected_route_enforces_config_three_state_without_sdk(tmp_path, state):
    client, sdk = app_fixture(tmp_path, state=state)
    with client:
        assert client.get("/internal/health").status_code == 200
        for method, path, body in PROTECTED:
            response = client.request(method, path, headers=HEADER, **({"json": body} if body is not None else {}))
            assert response.status_code == 503, (path, response.text)
        assert sdk.events == []


def test_exact_sixteen_routes_and_http_methods_have_no_alias_or_write_surface(tmp_path):
    client, sdk = app_fixture(tmp_path)
    actual = {(route.path, tuple(route.methods)) for route in client.app.routes}
    expected = {(path, (method,)) for method, path, _body in PROTECTED} | {
        (BASIC, ("POST",)), (CANDLES, ("POST",)), ("/internal/health", ("GET",))}
    assert actual == expected and len(actual) == 16
    with client:
        for method, path, _body in [*PROTECTED, ("POST", BASIC, {"symbol": "2330"}), ("POST", CANDLES, {"symbol": "2330"})]:
            for wrong in {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"} - {method}:
                assert client.request(wrong, path, headers=HEADER).status_code == 405, (wrong, path)
            assert client.request(method, path + "/", headers=HEADER, follow_redirects=False).status_code == 404
        for path in ("/docs", "/openapi.json", "/internal/orders", "/internal/market-data/history"):
            assert client.get(path, headers=HEADER).status_code == 404
        assert sdk.events == []


@pytest.mark.parametrize("body", [b"{}", b" ", b"null", b'{"account":"FAKE"}', b'{"range":"7d"}'])
def test_accounting_routes_reject_every_body_and_query_selector_before_sdk(tmp_path, body):
    client, sdk = app_fixture(tmp_path)
    with client:
        for path in ACCOUNTING:
            assert client.post(path, content=body, headers=HEADER).status_code == 400
            assert client.post(path + "?account=FAKE", headers=HEADER).status_code == 400
        assert sdk.events == []


def test_new_json_routes_reject_unknown_fields_duplicate_keys_and_bad_iso_dates(tmp_path):
    client, sdk = app_fixture(tmp_path)
    with client:
        for method, path, body in PROTECTED:
            if method != "POST" or body is None or path not in {DIVIDENDS, TECHNICAL, SUBSCRIPTIONS, "/internal/trades/read"}:
                continue
            assert client.post(path, json={**body, "account": "FAKE"}, headers=HEADER).status_code == 400
            key, value = next(iter(body.items()))
            duplicate = json.dumps(body)[:-1] + "," + json.dumps(key) + ":" + json.dumps(value) + "}"
            assert client.post(path, content=duplicate, headers=HEADER).status_code == 400
            assert client.post(path + "?url=FAKE", json=body, headers=HEADER).status_code == 400
        assert client.post(TECHNICAL, json={"symbol": "2330", "from": TECHNICAL_FROM}, headers=HEADER).status_code == 400
        assert sdk.events == []


def test_request_body_cap_applies_to_chunked_stream_before_unbounded_accumulation(tmp_path):
    client, sdk = app_fixture(tmp_path)
    with client:
        chunks = (b" " * 4096 for _ in range(17))
        response = client.post(SUBSCRIPTIONS, content=chunks, headers=HEADER)
        assert response.status_code == 400 and sdk.events == []


def test_official_accounting_result_to_http_keeps_zero_hmac_nulls_and_true_vendor_dates(tmp_path):
    client, sdk = app_fixture(tmp_path)
    expected_fingerprint = hmac.new(TOKEN.encode(), b"001:00001234567", hashlib.sha256).hexdigest()[:24]
    with client:
        bank = client.post(ACCOUNTING[0], headers=HEADER)
        settlement = client.post(ACCOUNTING[1], headers=HEADER)
        realized = client.post(ACCOUNTING[2], headers=HEADER)
        trades = client.post("/internal/trades/read", json={"startDate": "2026-08-21", "endDate": "2026-08-21"}, headers=HEADER)
    assert bank.status_code == settlement.status_code == realized.status_code == trades.status_code == 200
    assert bank.json() == {"queryDate": "2026-08-28", "observedAt": "2026-08-28T05:40:00Z",
                           "accountFingerprint": expected_fingerprint, "currency": "TWD", "balance": "0", "availableBalance": "0"}
    detail = settlement.json()["details"][0]
    assert detail["status"] == "NO_DATA_OBSERVED" and detail["sourceQueryDate"] == "2026-08-28"
    assert detail["settlementDate"] is None and all(detail[field] is None for field in AMOUNT_FIELDS.values())
    assert settlement.json()["accountBindingExplicit"] is True
    assert settlement.json()["coverageStatus"] == "SDK_RANGE_3D_RETURNED_ROWS"
    assert settlement.json()["reason"] is None
    assert realized.json()["rows"][0]["orderType"] == "Stock"
    assert realized.json()["rows"][0]["sourceDate"] == "2026-08-27"
    assert realized.json()["accountBindingExplicit"] is True
    assert "cost" not in str(realized.json()) and "filledNo" not in str(realized.json())
    assert trades.json()["trades"][0]["filledDate"] == "2026-08-21"
    assert ("filled_history", "20260821", "20260821") in sdk.events
    for response in (bank, settlement, realized, trades):
        assert response.json()["accountFingerprint"] == expected_fingerprint
        assert "00001234567" not in response.text and TOKEN not in response.text
        assert "branch_no" not in response.text and '"account"' not in response.text


def test_market_http_routes_keep_exact_coverage_group_shapes_and_thirteen_counter_keys_after_errors(tmp_path):
    client, sdk = app_fixture(tmp_path)
    with client:
        dividends = client.post(DIVIDENDS, json={"symbols": ["2330", "0050"], "from": DIVIDEND_FROM, "to": DIVIDEND_TO}, headers=HEADER)
        assert dividends.status_code == 200
        assert [row["symbol"] for row in dividends.json()["rows"]] == ["2330", "0050"]
        technical = client.post(TECHNICAL, json={"symbol": "2330"}, headers=HEADER)
        assert technical.status_code == 200 and len(technical.content) < 512 * 1024
        assert list(technical.json()) == ["schemaVersion", "captureId", "symbol", "market", "provider", "queryFrom", "queryTo", "profiles"]
        assert len(technical.json()["profiles"]) == 17
        sdk.bank_source = bank_row(balance=-1)
        assert client.post(ACCOUNTING[0], headers=HEADER).status_code == 503
        sdk.dividend_source = {"data": None}
        assert client.post(DIVIDENDS, json={"symbols": ["2330"], "from": DIVIDEND_FROM, "to": DIVIDEND_TO}, headers=HEADER).status_code == 503
        sdk.technical_source = {"data": []}
        assert any(profile["status"] == "SCHEMA_INVALID" for profile in
                   client.post(TECHNICAL, json={"symbol": "2330"}, headers=HEADER).json()["profiles"])
        assert client.post(SUBSCRIPTIONS, json={"symbols": ["2330"]}, headers=HEADER).status_code == 503
        quote = client.post("/internal/market-data/tw-quotes", json={"codes": ["2330"], "purpose": "LIVE"}, headers=HEADER)
    assert quote.status_code == 200 and quote.json()["quotes"][0]["status"] == "SUCCESS"
    assert set(quote.json()["counters"]) == COUNTER_KEYS and len(COUNTER_KEYS) == 13
    assert quote.json()["counters"]["QUOTE_FAILED"] >= 2
    assert quote.json()["counters"]["RECONCILE_FAILED"] >= 1


def test_real_stock_subscriptions_to_http_sse_and_last_consumer_cleanup(tmp_path):
    class OneTradeWebsocket(FakeWebsocket):
        def subscribe(self, payload):
            super().subscribe(payload)
            self.emit("message", packet(channel_id=self.ids["2330"]))
    class OneFrameStream(StockPushStream):
        async def events(self):
            iterator = super().events()
            try:
                yield await asyncio.wait_for(anext(iterator), timeout=2)
            finally:
                await iterator.aclose()
    fixture = StreamFixture(tmp_path, websocket_factory=OneTradeWebsocket)
    counters = OutcomeCounters()
    fixture.stream = OneFrameStream(fixture.gateway, now=lambda: STOCK_NOW, counters=counters)
    app = create_app(fixture.loader, fixture.gateway, stock_push_stream=fixture.stream, counters=counters)
    try:
        with TestClient(app) as client:
            assert client.get(STOCK_STREAM, headers=HEADER).status_code == 503
            assert fixture.sdks == []
            assert client.post(SUBSCRIPTIONS, json={"symbols": [f"S{i}" for i in range(301)]}, headers=HEADER).json() == {"detail": {"reason": "SUBSCRIPTION_LIMIT"}}
            assert client.post(SUBSCRIPTIONS, json={"symbols": ["2330"]}, headers=HEADER).json() == {"outcome": "ACCEPTED", "symbolCount": 1, "leaseSeconds": 120}
            result = client.get(STOCK_STREAM, headers=HEADER)
            assert result.status_code == 200 and result.headers["content-type"].startswith("text/event-stream")
            assert result.headers["cache-control"] == "no-store" and result.headers["x-accel-buffering"] == "no"
            assert result.text.startswith("event: stock-price\nid: 2330:")
            data = json.loads(result.text.split("data: ")[1])
            assert data["price"] == "123.5" and data["tradeSize"] == 3 and data["volume"] is None
            assert fixture.stream._desired == set()
            assert client.get(STOCK_STREAM, headers=HEADER).status_code == 503
            assert set(counters.snapshot()) == COUNTER_KEYS
    finally:
        fixture.close()
