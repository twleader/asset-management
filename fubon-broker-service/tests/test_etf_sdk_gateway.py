from __future__ import annotations

import threading
from types import SimpleNamespace

import pytest

from fubon_broker_service.config import ConfigLoader
from fubon_broker_service.sdk_gateway import SdkCallError, SdkGateway

from helpers import account, ready_config, response


class ForbiddenNamespace:
    def __init__(self):
        self.accessed = []

    def __getattr__(self, name):
        self.accessed.append(name)
        raise AssertionError("ETF market-data lookup accessed a forbidden SDK namespace")


class MarketDataSdk:
    def __init__(self, events, lookup):
        self.events = events
        self.lookup = lookup
        self.stock = ForbiddenNamespace()
        self.accounting = ForbiddenNamespace()

    def apikey_login(self, *_args):
        self.events.append("login")
        return response([account()])

    def init_realtime(self):
        self.events.append("init_realtime")
        self.marketdata = SimpleNamespace(rest_client=SimpleNamespace(stock=SimpleNamespace(
            ownership=SimpleNamespace(etf_holdings=self.etf_holdings),
        )))
        return response(None)

    def etf_holdings(self, *, symbol):
        self.events.append(f"etf_holdings:{symbol}")
        return self.lookup(symbol)

    def logout(self):
        self.events.append("logout")

    def shutdown(self):
        self.events.append("shutdown")


def test_etf_query_only_touches_the_ownership_read_method(tmp_path):
    events = []
    provider = {"symbol": "0050", "data": []}
    sdk = MarketDataSdk(events, lambda _symbol: provider)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk)
    assert gateway.read_etf_holdings("0050") is provider
    assert events == ["login", "init_realtime", "etf_holdings:0050"]
    assert sdk.stock.accessed == sdk.accounting.accessed == []
    gateway.shutdown()
    gateway.shutdown()
    assert events[-2:] == ["logout", "shutdown"]
    assert events.count("logout") == events.count("shutdown") == 1


@pytest.mark.parametrize("as_exception", [False, True])
def test_auth_failure_reconnects_once_and_returns_second_session_result(tmp_path, as_exception):
    events = []

    class AuthenticationError(RuntimeError):
        status_code = 401

    def fail(_symbol):
        if as_exception:
            raise AuthenticationError("SECRET_SENTINEL")
        return response(None, success=False, code=401)

    instances = [MarketDataSdk(events, fail), MarketDataSdk(events, lambda code: {"symbol": code, "data": []})]
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: instances.pop(0))
    assert gateway.read_etf_holdings("0050") == {"symbol": "0050", "data": []}
    assert events == [
        "login", "init_realtime", "etf_holdings:0050", "logout", "shutdown",
        "login", "init_realtime", "etf_holdings:0050",
    ]
    gateway.shutdown()


@pytest.mark.parametrize("as_exception", [False, True])
def test_repeated_auth_failure_stops_after_one_retry_without_leaking_provider_message(tmp_path, as_exception):
    events = []

    class AuthenticationError(RuntimeError):
        status_code = 403

    def fail(_symbol):
        if as_exception:
            raise AuthenticationError("ACCOUNT_SENTINEL SECRET_SENTINEL")
        return response(None, success=False, code=403)

    instances = [MarketDataSdk(events, fail), MarketDataSdk(events, fail)]
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: instances.pop(0))
    with pytest.raises(SdkCallError, match="^AUTH_SESSION_INVALID$") as captured:
        gateway.read_etf_holdings("0050")
    assert captured.value.auth_invalid is True
    assert events.count("login") == events.count("etf_holdings:0050") == 2
    assert "SENTINEL" not in str(captured.value)
    gateway.shutdown()


def test_rate_limit_keeps_retry_after_and_does_not_retry(tmp_path):
    events = []

    class RateLimited(RuntimeError):
        status_code = 429
        headers = {"Retry-After": "125"}

    def fail(_symbol):
        raise RateLimited("SECRET_SENTINEL")

    sdk = MarketDataSdk(events, fail)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk)
    with pytest.raises(SdkCallError, match="^RATE_LIMITED$") as captured:
        gateway.read_etf_holdings("0050")
    assert captured.value.retry_after_seconds == 125.0
    assert events.count("etf_holdings:0050") == 1
    assert events.count("login") == 1
    gateway.shutdown()


def test_transport_failure_never_exposes_sdk_error_message(tmp_path):
    events = []

    def fail(_symbol):
        raise RuntimeError("ACCOUNT_SENTINEL SECRET_SENTINEL")

    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: MarketDataSdk(events, fail))
    with pytest.raises(SdkCallError, match="^ETF_HOLDINGS_TRANSPORT_FAILED$"):
        gateway.read_etf_holdings("0050")
    assert events.count("login") == events.count("etf_holdings:0050") == 1
    gateway.shutdown()


def test_slow_sdk_call_times_out_and_does_not_release_the_shared_slot_early(tmp_path):
    events = []
    release = threading.Event()
    finished = threading.Event()

    def blocked(_symbol):
        try:
            assert release.wait(2)
            return {"symbol": "0050", "data": []}
        finally:
            finished.set()

    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: MarketDataSdk(events, blocked))
    gateway.QUOTE_CALL_TIMEOUT_SECONDS = 0.01
    gateway._blocking_slots = threading.BoundedSemaphore(1)
    try:
        with pytest.raises(SdkCallError, match="^ETF_HOLDINGS_TIMEOUT$"):
            gateway.read_etf_holdings("0050")
        with pytest.raises(SdkCallError, match="^SDK_CALL_SATURATED$"):
            gateway.read_etf_holdings("0056")
        assert events.count("etf_holdings:0050") == 1
        assert "etf_holdings:0056" not in events
    finally:
        release.set()
        assert finished.wait(2)
        gateway.shutdown()


def test_missing_etf_method_marks_runtime_misconfigured_and_is_not_retried(tmp_path):
    events = []

    class MissingMethodSdk(MarketDataSdk):
        def init_realtime(self):
            result = super().init_realtime()
            self.marketdata.rest_client.stock.ownership = SimpleNamespace()
            return result

    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: MissingMethodSdk(events, None))
    with pytest.raises(SdkCallError, match="^ETF_HOLDINGS_CLIENT_UNAVAILABLE$"):
        gateway.read_etf_holdings("0050")
    assert gateway.runtime_misconfigured is True
    with pytest.raises(SdkCallError, match="^RUNTIME_MISCONFIGURED$"):
        gateway.read_etf_holdings("0050")
    assert events == ["login", "init_realtime", "logout", "shutdown"]


@pytest.mark.parametrize("enabled, reason", [("false", "DISABLED"), ("true", "MISSING_REQUIRED_SECRET")])
def test_disabled_or_missing_configuration_never_constructs_sdk(tmp_path, enabled, reason):
    constructed = []
    gateway = SdkGateway(
        ConfigLoader(tmp_path, lambda: enabled), sdk_factory=lambda: constructed.append(True),
    )
    with pytest.raises(SdkCallError, match=f"^{reason}$"):
        gateway.read_etf_holdings("0050")
    assert constructed == []
