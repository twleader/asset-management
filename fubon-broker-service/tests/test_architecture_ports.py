"""Offline dependency and privacy boundaries for the existing read-only SDK adapter."""
from __future__ import annotations

import ast
import asyncio
from dataclasses import fields
from datetime import UTC, date, datetime
import hashlib
import json
from pathlib import Path

import pytest

from fubon_broker_service import ports
from fubon_broker_service.bank_balance import BankBalanceService
from fubon_broker_service.captures import (
    AccountingPair, AccountingRead, OrderPortfolioRead, SelectedAccount, StockPushConnection,
)
from fubon_broker_service.config import ConfigLoader
from fubon_broker_service.config_values import ConfigSnapshot, SecretPresence
from fubon_broker_service.dividends import DividendService
from fubon_broker_service.errors import SdkCallError
from fubon_broker_service.payload_limits import check_marketdata_size
from fubon_broker_service.portfolio import RawIdentity
from fubon_broker_service.quotes import QuoteService
from fubon_broker_service.sdk_gateway import SdkGateway
from fubon_broker_service.session_identity import session_config_digest

from accounting_fixtures import NOW, SELECTED, bank_row
from helpers import fixed_now, quote_raw, response
from market_fixtures import DIVIDEND_FROM, DIVIDEND_TO


SOURCE = Path(ports.__file__).parent
CONSUMERS = {
    "portfolio": "PortfolioReader", "trades": "TradeReader", "bank_balance": "BankBalanceReader",
    "settlement": "SettlementReader", "realized_gain": "RealizedGainReader", "quotes": "QuoteReader",
    "etf_holdings": "EtfHoldingsReader", "dividends": "DividendReader",
    "technical_indicators": "TechnicalIndicatorReader", "market_data_v1": "MarketDataV1Source",
    "taiex_index_stream": "IndexStreamGateway", "stock_push_stream": "StockPushGateway",
}
NEUTRAL = {"ports", "captures", "errors", "raw_values", "payload_limits", "session_identity", "config_values"}


def imports(tree):
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            yield node.module or ""
        elif isinstance(node, ast.Import):
            yield from (alias.name for alias in node.names)


def test_sdk_and_vendor_imports_are_confined_to_composition_and_outer_adapters():
    for path in SOURCE.rglob("*.py"):
        for imported in imports(ast.parse(path.read_text())):
            if "sdk_gateway" in imported.split("."):
                assert path.stem in {"app", "order_results_adapter"}, path.name
            if imported.split(".")[0] == "fubon_neo":
                assert path.stem == "sdk_gateway", path.name


@pytest.mark.parametrize("module,port_name", CONSUMERS.items())
def test_each_consumer_is_typed_to_its_port_and_never_calls_gateway_private_state(module, port_name):
    tree = ast.parse((SOURCE / f"{module}.py").read_text())
    constructors = [node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef) and node.name == "__init__"]
    gateway = next(arg for node in constructors for arg in node.args.args if arg.arg == "gateway")
    assert ast.unparse(gateway.annotation) == port_name
    for node in ast.walk(tree):
        if isinstance(node, ast.Name):
            assert node.id != "SdkGateway"
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Attribute):
            if node.value.attr == "_gateway":
                assert not node.attr.startswith("_"), (module, node.attr)


@pytest.mark.parametrize("module", sorted(NEUTRAL | {"accounting_normalization"}))
def test_neutral_values_and_normalizer_cannot_import_outer_io(module):
    forbidden = {"sdk_gateway", "config", "app", "order_results_adapter", "fubon_neo", "httpx", "requests", "socket"}
    for imported in imports(ast.parse((SOURCE / f"{module}.py").read_text())):
        assert not (set(imported.split(".")) & forbidden), (module, imported)


@pytest.mark.parametrize("port_name", sorted(set(CONSUMERS.values())))
def test_real_gateway_structurally_satisfies_each_small_port_without_loading_sdk(port_name):
    # A failing factory proves that even conformance inspection performs no broker activity.
    def forbidden_sdk():
        pytest.fail("Protocol conformance must not initialize a broker SDK")
    gateway = SdkGateway(ConfigLoader(Path("/unused-offline-config"), lambda: "false"), sdk_factory=forbidden_sdk)
    assert isinstance(gateway, getattr(ports, port_name))


class BalanceOnly:
    def __init__(self, failure=None):
        self.failure, self.calls = failure, 0

    def read_bank_balance(self, *, deadline=None):
        self.calls += 1
        if self.failure is not None:
            raise self.failure
        return AccountingRead(response(bank_row(balance=0)), SELECTED, "offline-capture-token")


def test_single_capability_fake_produces_normalized_balance_and_preserves_gateway_failure():
    gateway = BalanceOnly()
    assert isinstance(gateway, ports.BankBalanceReader)
    assert not isinstance(gateway, ports.PortfolioReader)
    result = BankBalanceService(gateway, now=lambda: NOW).read()
    assert (result["balance"], result["availableBalance"], result["currency"]) == ("0", "0", "TWD")
    assert gateway.calls == 1
    assert SELECTED.account_number not in json.dumps(result)
    assert "offline-capture-token" not in json.dumps(result)
    failure = SdkCallError("ACCOUNTING_TIMEOUT", completion_event=None)
    failed = BalanceOnly(failure)
    with pytest.raises(SdkCallError) as raised:
        BankBalanceService(failed, now=lambda: NOW).read()
    assert raised.value is failure
    assert failed.calls == 1


class QuoteOnly:
    def __init__(self, failure=None):
        self.failure, self.calls, self.deadlines, self.dispatches = failure, [], [], 0

    def quote(self, code, *, deadline=None, before_dispatch=None):
        assert before_dispatch is not None
        before_dispatch()
        self.dispatches += 1
        self.calls.append(code)
        self.deadlines.append(deadline)
        if self.failure is not None:
            raise self.failure
        return quote_raw(code)


@pytest.mark.parametrize("failure", [None, SdkCallError("RATE_LIMITED", retry_after_seconds=61)])
def test_quote_only_fake_retains_dispatch_callback_deadline_and_rate_limit_result(failure):
    gateway = QuoteOnly(failure)
    assert isinstance(gateway, ports.QuoteReader)
    result = asyncio.run(QuoteService(gateway, now=fixed_now).read(["2330"]))["quotes"][0]
    assert gateway.calls == ["2330"] and gateway.dispatches == 1
    assert isinstance(gateway.deadlines[0], float)
    if failure is None:
        assert result["status"] == "SUCCESS"
        assert result["quote"]["actualPrice"] == "100.1"
    else:
        assert result == {"stockCode": "2330", "status": "FAILURE", "reason": "RATE_LIMITED", "quote": None}


class DividendOnly:
    def __init__(self, failure=None):
        self.failure, self.calls = failure, []

    def read_dividends(self, start_date, end_date):
        self.calls.append((start_date, end_date))
        if self.failure is not None:
            raise self.failure
        return {"data": []}


def test_dividend_only_fake_preserves_fixed_scope_empty_evidence_and_upstream_failure():
    gateway = DividendOnly()
    assert isinstance(gateway, ports.DividendReader)
    result = DividendService(gateway, now=lambda: NOW).read(["2330"], DIVIDEND_FROM, DIVIDEND_TO)
    assert gateway.calls == [(DIVIDEND_FROM, DIVIDEND_TO)]
    assert result["rows"] == [{"symbol": "2330", "status": "PARTIAL", "usable": False,
                               "reason": "NO_MATCHING_EVENTS", "events": []}]
    failure = SdkCallError("MARKETDATA_TIMEOUT")
    with pytest.raises(SdkCallError) as raised:
        DividendService(DividendOnly(failure), now=lambda: NOW).read(["2330"], DIVIDEND_FROM, DIVIDEND_TO)
    assert raised.value is failure


class SecretRaw:
    def __repr__(self):
        raise AssertionError("raw SDK objects must never be represented")


def test_ephemeral_captures_never_render_raw_account_or_token_even_nested():
    raw = SecretRaw()
    selected = SelectedAccount(raw, "BRANCH_SENTINEL", "ACCOUNT_SENTINEL", True)
    read = AccountingRead(raw, selected, "TOKEN_SENTINEL")
    pair = AccountingPair(raw, raw, selected, "TOKEN_SENTINEL")
    captures = [selected, read, pair, OrderPortfolioRead(read, pair), StockPushConnection(raw, 7)]
    for capture in captures:
        text = repr(capture)
        assert all(sentinel not in text for sentinel in ("BRANCH_SENTINEL", "ACCOUNT_SENTINEL", "TOKEN_SENTINEL"))
    assert all(not field.repr for field in fields(AccountingPair))
    identity = RawIdentity(date(2026, 8, 28), "ACCOUNT_SENTINEL", "BRANCH_SENTINEL", "2330", "Stock")
    assert "ACCOUNT_SENTINEL" not in repr(identity) and "BRANCH_SENTINEL" not in repr(identity)


def test_pure_payload_limit_keeps_utf8_size_boundary_and_sanitized_failure():
    payload = {"message": "測試"}
    size = len(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
    check_marketdata_size(payload, maximum_bytes=size)
    with pytest.raises(SdkCallError, match="^MARKETDATA_RESPONSE_TOO_LARGE$"):
        check_marketdata_size(payload, maximum_bytes=size - 1)
    recursive = []
    recursive.append(recursive)
    with pytest.raises(SdkCallError, match="^MARKETDATA_SCHEMA_INVALID$"):
        check_marketdata_size(recursive)


def test_neutral_config_digest_retains_exact_identity_bytes_and_safe_config_repr():
    config = ConfigSnapshot(True, "READY", SecretPresence(True, True, True, True, True, True),
                            personal_id="PERSON_SENTINEL", api_key="KEY_SENTINEL", certificate_password="PASS_SENTINEL",
                            certificate_path=Path("/unused/CERT_SENTINEL"), account_branch_no="BRANCH_SENTINEL",
                            account_number="ACCOUNT_SENTINEL", internal_service_token="TOKEN_SENTINEL")
    expected = hashlib.sha256("\0".join(("PERSON_SENTINEL", "KEY_SENTINEL", "PASS_SENTINEL", "/unused/CERT_SENTINEL",
                                         "BRANCH_SENTINEL", "ACCOUNT_SENTINEL", "TOKEN_SENTINEL")).encode()).digest()
    assert session_config_digest(config) == expected
    assert "SENTINEL" not in repr(config)
