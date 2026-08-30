from __future__ import annotations

import threading
import time
from types import SimpleNamespace

import pytest

from fubon_broker_service.config import ConfigLoader
from fubon_broker_service.sdk_gateway import SdkCallError, SdkGateway

from helpers import account, ready_config, response
from accounting_fixtures import bank_row, realized_row, settlement_row


class AttributeAccessSpy:
    """Wraps an object and records every attribute name accessed through it.

    `_target`/`accessed` are installed directly into __dict__ so normal
    attribute lookup finds them without ever going through __getattr__ (which
    would otherwise both recurse and pollute the access log).
    """

    def __init__(self, target):
        object.__setattr__(self, "_target", target)
        object.__setattr__(self, "accessed", [])

    def __getattr__(self, name):
        self.accessed.append(name)
        return getattr(self._target, name)


class Intraday:
    def __init__(self, events):
        self.events = events

    def quote(self, *, symbol):
        self.events.append(f"quote:{symbol}")
        return {"symbol": symbol}

    def tickers(self, *, type, exchange):
        self.events.append(f"tickers:{type}:{exchange}")
        return response([{"symbol": "IR0001", "exchange": "TWSE", "type": "INDEX"}])


class AuthenticationError(RuntimeError):
    """Recognized by SdkGateway._exception_is_auth_invalid via its class name."""


class FakeSdk:
    def __init__(
        self,
        events,
        *,
        auth_fail=False,
        init_fail=False,
        multiple_accounts=False,
        filled_history_auth_fail=False,
        bank_remain_auth_fail=False,
        query_settlement_auth_fail=False,
        realized_gains_and_loses_auth_fail=False,
    ):
        self.events = events
        self.auth_fail = auth_fail
        self.init_fail = init_fail
        self.multiple_accounts = multiple_accounts
        self.filled_history_auth_fail = filled_history_auth_fail
        self.bank_remain_auth_fail = bank_remain_auth_fail
        self.query_settlement_auth_fail = query_settlement_auth_fail
        self.realized_gains_and_loses_auth_fail = realized_gains_and_loses_auth_fail
        self.accounting = SimpleNamespace(
            inventories=self.inventories,
            unrealized_gains_and_loses=self.unrealized,
            bank_remain=self.bank_remain,
            query_settlement=self.query_settlement,
            realized_gains_and_loses=self.realized_gains_and_loses,
        )
        self.stock = AttributeAccessSpy(
            SimpleNamespace(
                filled_history=self.filled_history,
                place_order=self._trading_forbidden,
                cancel_order=self._trading_forbidden,
                modify_price=self._trading_forbidden,
                modify_quantity=self._trading_forbidden,
                batch_place_order=self._trading_forbidden,
                batch_cancel_order=self._trading_forbidden,
            )
        )

    def filled_history(self, _account, start_date, end_date):
        self.events.append(f"filled_history:{start_date}:{end_date}")
        if self.filled_history_auth_fail:
            self.filled_history_auth_fail = False
            return response(None, success=False, code=401)
        return response([])

    @staticmethod
    def _trading_forbidden(*_args, **_kwargs):
        raise AssertionError("a trading/order SDK method must never be invoked by read_filled_trades")

    def apikey_login(self, *_args):
        self.events.append("login")
        accounts = [account()]
        if self.multiple_accounts:
            accounts.append(account("002", "00007654321"))
        return response(accounts)

    def init_realtime(self):
        self.events.append("init_realtime")
        if self.init_fail:
            return response(None, success=False)
        self.marketdata = SimpleNamespace(
            rest_client=SimpleNamespace(stock=SimpleNamespace(intraday=Intraday(self.events)))
        )
        return response(None)

    def inventories(self, _account):
        self.events.append("inventories")
        if self.auth_fail:
            self.auth_fail = False
            return response(None, success=False, code=401)
        return response([])

    def unrealized(self, _account):
        self.events.append("unrealized")
        return response([])

    def bank_remain(self, _account):
        self.events.append("bank_remain")
        if self.bank_remain_auth_fail:
            self.bank_remain_auth_fail = False
            raise AuthenticationError()
        return response(SimpleNamespace(**bank_row()))

    def query_settlement(self, _account, range_param):
        self.events.append(f"query_settlement:{range_param}")
        if self.query_settlement_auth_fail:
            self.query_settlement_auth_fail = False
            raise AuthenticationError()
        return response(SimpleNamespace(
            account=SimpleNamespace(branch_no="001", account="00001234567"),
            details=[SimpleNamespace(**settlement_row())],
        ))

    def realized_gains_and_loses(self, _account):
        self.events.append("realized_gains_and_loses")
        if self.realized_gains_and_loses_auth_fail:
            self.realized_gains_and_loses_auth_fail = False
            raise AuthenticationError()
        return response([SimpleNamespace(**realized_row())])

    def logout(self):
        self.events.append("logout")

    def shutdown(self):
        self.events.append("shutdown")


def test_login_then_init_realtime_precedes_accounting_and_quote(tmp_path):
    events = []
    sdk = FakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)
    gateway.read_accounting_pair()
    gateway.quote("2330")
    assert events[:4] == ["login", "init_realtime", "inventories", "unrealized"]
    assert events[4] == "quote:2330"
    gateway.shutdown()
    gateway.shutdown()
    assert events.count("logout") == 1
    assert events.count("shutdown") == 1
    assert events.index("logout") < events.index("shutdown")


def test_auth_invalid_reconnect_cleans_old_session_then_reruns_both_calls(tmp_path):
    events = []
    sdks = [FakeSdk(events, auth_fail=True), FakeSdk(events)]
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdks.pop(0), sleeper=lambda _seconds: None)
    gateway.read_accounting_pair()
    assert events.count("login") == 2
    assert events.count("inventories") == 2
    assert events.count("unrealized") == 2
    first_cleanup = events.index("logout")
    assert first_cleanup < [index for index, value in enumerate(events) if value == "login"][1]


def test_no_selector_requires_exactly_one_stock_account(tmp_path):
    events = []
    gateway = SdkGateway(
        ready_config(tmp_path),
        sdk_factory=lambda: FakeSdk(events, multiple_accounts=True),
        sleeper=lambda _seconds: None,
    )
    with pytest.raises(SdkCallError, match="STOCK_ACCOUNT_NOT_UNIQUE"):
        gateway.read_accounting_pair()
    assert events[-2:] == ["logout", "shutdown"]


def test_selector_pair_selects_exact_raw_branch_and_account(tmp_path):
    events = []
    loader = ready_config(tmp_path, branch="002", account="00007654321")
    gateway = SdkGateway(
        loader,
        sdk_factory=lambda: FakeSdk(events, multiple_accounts=True),
        sleeper=lambda _seconds: None,
    )
    pair = gateway.read_accounting_pair()
    assert pair.account.branch_no == "002"
    assert pair.account.account_number == "00007654321"


def test_realtime_init_failure_invalidates_and_cleans_partial_session(tmp_path):
    events = []
    gateway = SdkGateway(
        ready_config(tmp_path), sdk_factory=lambda: FakeSdk(events, init_fail=True), sleeper=lambda _seconds: None
    )
    with pytest.raises(SdkCallError, match="REALTIME_INIT_FAILED"):
        gateway.read_accounting_pair()
    assert events == ["login", "init_realtime", "logout", "shutdown"]


def test_missing_accounting_api_marks_runtime_misconfigured_and_cleans_session(tmp_path):
    events = []
    sdk = FakeSdk(events)
    sdk.accounting = SimpleNamespace()
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)

    with pytest.raises(SdkCallError, match="ACCOUNTING_API_UNAVAILABLE"):
        gateway.read_accounting_pair()

    assert gateway.runtime_misconfigured is True
    assert events == ["login", "init_realtime", "logout", "shutdown"]


def test_runtime_misconfiguration_is_fail_fast_until_secret_digest_changes(tmp_path):
    events = []
    created = 0
    sdks = [FakeSdk(events, init_fail=True), FakeSdk(events)]

    def factory():
        nonlocal created
        created += 1
        return sdks.pop(0)

    loader = ready_config(tmp_path)
    gateway = SdkGateway(loader, sdk_factory=factory, sleeper=lambda _seconds: None)
    with pytest.raises(SdkCallError, match="REALTIME_INIT_FAILED"):
        gateway.read_accounting_pair()
    with pytest.raises(SdkCallError, match="RUNTIME_MISCONFIGURED"):
        gateway.read_accounting_pair()
    assert created == 1

    (tmp_path / "shared" / "internal-service-token").write_text("rotated-test-token", encoding="utf-8")
    gateway.read_accounting_pair()
    assert created == 2


def test_disabled_mode_never_constructs_or_cleans_sdk(tmp_path):
    called = 0

    def factory():
        nonlocal called
        called += 1
        return FakeSdk([])

    gateway = SdkGateway(ConfigLoader(tmp_path, lambda: "false"), sdk_factory=factory)
    with pytest.raises(SdkCallError, match="DISABLED"):
        gateway.read_accounting_pair()
    gateway.shutdown()
    assert called == 0


def test_quote_rate_limit_preserves_retry_after_without_retrying(tmp_path):
    events = []
    sdk = FakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)
    gateway.read_accounting_pair()

    class RateLimited(RuntimeError):
        status_code = 429
        headers = {"Retry-After": "125"}

    def raise_rate_limit(*, symbol):
        events.append(f"rate-limit:{symbol}")
        raise RateLimited()

    sdk.marketdata.rest_client.stock.intraday.quote = raise_rate_limit
    with pytest.raises(SdkCallError, match="RATE_LIMITED") as captured:
        gateway.quote("2330")

    assert captured.value.retry_after_seconds == 125.0
    assert events.count("rate-limit:2330") == 1


def test_read_filled_trades_only_ever_accesses_filled_history_attribute(tmp_path):
    events = []
    sdk = FakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)

    result = gateway.read_filled_trades("2026-08-21", "2026-08-21")

    assert sdk.stock.accessed == ["filled_history"]
    assert events == ["login", "init_realtime", "filled_history:20260821:20260821"]
    assert result.response.data == []
    assert result.account.account_number == "00001234567"


def test_read_filled_trades_does_not_use_accounting_call(tmp_path):
    """Proves the narrow stock.filled_history path is independent of _accounting_call.

    _accounting_call only resolves sdk.accounting.<name> with a single
    `account` argument; if read_filled_trades accidentally routed through it,
    inventories/unrealized would be invoked instead of filled_history (or the
    call would blow up on the extra start_date/end_date arguments).
    """
    events = []
    sdk = FakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)
    gateway.read_filled_trades("2026-08-21", "2026-08-21")
    assert "inventories" not in events
    assert "unrealized" not in events


def test_read_filled_trades_never_exposes_the_stock_object_itself(tmp_path):
    events = []
    sdk = FakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)
    result = gateway.read_filled_trades("2026-08-21", "2026-08-21")
    assert result is not sdk.stock
    assert not hasattr(gateway, "_trade_client")
    assert not hasattr(gateway, "stock")


def test_read_filled_trades_auth_invalid_reconnects_then_reruns_once(tmp_path):
    events = []
    sdks = [
        FakeSdk(events, filled_history_auth_fail=True),
        FakeSdk(events),
    ]
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdks.pop(0), sleeper=lambda _seconds: None)

    gateway.read_filled_trades("2026-08-21", "2026-08-21")

    assert events.count("login") == 2
    assert events.count("filled_history:20260821:20260821") == 2
    first_cleanup = events.index("logout")
    second_login = [index for index, value in enumerate(events) if value == "login"][1]
    assert first_cleanup < second_login


def test_read_filled_trades_gives_up_after_second_auth_failure(tmp_path):
    events = []
    persistent_events = []

    class AlwaysAuthFail(FakeSdk):
        def filled_history(self, _account, start_date, end_date):
            persistent_events.append("filled_history")
            return response(None, success=False, code=401)

    gateway = SdkGateway(
        ready_config(tmp_path),
        sdk_factory=lambda: AlwaysAuthFail(events),
        sleeper=lambda _seconds: None,
    )
    with pytest.raises(SdkCallError, match="AUTH_SESSION_INVALID"):
        gateway.read_filled_trades("2026-08-21", "2026-08-21")
    assert len(persistent_events) == 2


def test_read_filled_trades_serializes_through_accounting_lock(tmp_path):
    concurrency = {"current": 0, "max": 0}
    lock = threading.Lock()

    class SerializingFakeSdk(FakeSdk):
        def filled_history(self, _account, start_date, end_date):
            with lock:
                concurrency["current"] += 1
                concurrency["max"] = max(concurrency["max"], concurrency["current"])
            time.sleep(0.05)
            with lock:
                concurrency["current"] -= 1
            return response([])

    events = []
    sdk = SerializingFakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)

    threads = [
        threading.Thread(target=lambda: gateway.read_filled_trades("2026-08-21", "2026-08-21"))
        for _ in range(3)
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert concurrency["max"] == 1


def test_read_filled_trades_shares_accounting_budget_pool_with_accounting_call(tmp_path):
    waits = []
    sdk = FakeSdk([])
    gateway = SdkGateway(
        ready_config(tmp_path),
        sdk_factory=lambda: sdk,
        monotonic=lambda: 0.0,
        sleeper=lambda seconds: waits.append(seconds),
    )
    # Exhaust the shared 5-calls/sec budget via the existing accounting path.
    for _ in range(5):
        gateway._wait_for_accounting_budget()
    assert waits == []
    gateway.read_filled_trades("2026-08-21", "2026-08-21")
    assert waits and waits[0] > 0


def test_read_bank_balance_returns_the_raw_accounting_call_result(tmp_path):
    events = []
    sdk = FakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)

    result = gateway.read_bank_balance()

    assert events == ["login", "init_realtime", "bank_remain"]
    assert result.response.is_success is True
    assert result.response.data.branch_no == "001"
    assert result.account.branch_no == "001"
    assert result.response.data.account == "00001234567"
    assert result.response.data.currency == "TWD"


def test_read_bank_balance_auth_invalid_reconnects_then_reruns_once(tmp_path):
    events = []
    sdks = [FakeSdk(events, bank_remain_auth_fail=True), FakeSdk(events)]
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdks.pop(0), sleeper=lambda _seconds: None)

    gateway.read_bank_balance()

    assert events.count("login") == 2
    assert events.count("bank_remain") == 2
    first_cleanup = events.index("logout")
    second_login = [index for index, value in enumerate(events) if value == "login"][1]
    assert first_cleanup < second_login


def test_read_bank_balance_gives_up_after_second_auth_failure(tmp_path):
    events = []

    class AlwaysAuthFail(FakeSdk):
        def bank_remain(self, _account):
            events.append("bank_remain")
            raise AuthenticationError()

    gateway = SdkGateway(
        ready_config(tmp_path), sdk_factory=lambda: AlwaysAuthFail(events), sleeper=lambda _seconds: None
    )
    with pytest.raises(SdkCallError, match="AUTH_SESSION_INVALID"):
        gateway.read_bank_balance()
    assert events.count("bank_remain") == 2


def test_read_bank_balance_serializes_through_accounting_lock(tmp_path):
    concurrency = {"current": 0, "max": 0}
    lock = threading.Lock()

    class SerializingFakeSdk(FakeSdk):
        def bank_remain(self, _account):
            with lock:
                concurrency["current"] += 1
                concurrency["max"] = max(concurrency["max"], concurrency["current"])
            time.sleep(0.05)
            with lock:
                concurrency["current"] -= 1
            return super().bank_remain(_account)

    events = []
    sdk = SerializingFakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)

    threads = [threading.Thread(target=gateway.read_bank_balance) for _ in range(3)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert concurrency["max"] == 1


def test_read_bank_balance_shares_accounting_budget_pool_with_accounting_call(tmp_path):
    waits = []
    sdk = FakeSdk([])
    gateway = SdkGateway(
        ready_config(tmp_path),
        sdk_factory=lambda: sdk,
        monotonic=lambda: 0.0,
        sleeper=lambda seconds: waits.append(seconds),
    )
    # Exhaust the shared 5-calls/sec budget via the existing accounting path.
    for _ in range(5):
        gateway._wait_for_accounting_budget()
    assert waits == []
    gateway.read_bank_balance()
    assert waits and waits[0] > 0


def test_read_realized_gains_returns_the_raw_accounting_call_result(tmp_path):
    events = []
    sdk = FakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)

    result = gateway.read_realized_gains()

    assert events == ["login", "init_realtime", "realized_gains_and_loses"]
    assert result.response.is_success is True
    assert result.response.data[0].stock_no == "2330"
    assert result.response.data[0].buy_sell == "Sell"


def test_read_realized_gains_auth_invalid_reconnects_then_reruns_once(tmp_path):
    events = []
    sdks = [FakeSdk(events, realized_gains_and_loses_auth_fail=True), FakeSdk(events)]
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdks.pop(0), sleeper=lambda _seconds: None)

    gateway.read_realized_gains()

    assert events.count("login") == 2
    assert events.count("realized_gains_and_loses") == 2
    first_cleanup = events.index("logout")
    second_login = [index for index, value in enumerate(events) if value == "login"][1]
    assert first_cleanup < second_login


def test_read_realized_gains_gives_up_after_second_auth_failure(tmp_path):
    events = []

    class AlwaysAuthFail(FakeSdk):
        def realized_gains_and_loses(self, _account):
            events.append("realized_gains_and_loses")
            raise AuthenticationError()

    gateway = SdkGateway(
        ready_config(tmp_path), sdk_factory=lambda: AlwaysAuthFail(events), sleeper=lambda _seconds: None
    )
    with pytest.raises(SdkCallError, match="AUTH_SESSION_INVALID"):
        gateway.read_realized_gains()
    assert events.count("realized_gains_and_loses") == 2


def test_read_realized_gains_serializes_through_accounting_lock(tmp_path):
    concurrency = {"current": 0, "max": 0}
    lock = threading.Lock()

    class SerializingFakeSdk(FakeSdk):
        def realized_gains_and_loses(self, _account):
            with lock:
                concurrency["current"] += 1
                concurrency["max"] = max(concurrency["max"], concurrency["current"])
            time.sleep(0.05)
            with lock:
                concurrency["current"] -= 1
            return super().realized_gains_and_loses(_account)

    events = []
    sdk = SerializingFakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)

    threads = [threading.Thread(target=gateway.read_realized_gains) for _ in range(3)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert concurrency["max"] == 1


def test_read_realized_gains_shares_accounting_budget_pool_with_accounting_call(tmp_path):
    waits = []
    sdk = FakeSdk([])
    gateway = SdkGateway(
        ready_config(tmp_path),
        sdk_factory=lambda: sdk,
        monotonic=lambda: 0.0,
        sleeper=lambda seconds: waits.append(seconds),
    )
    # Exhaust the shared 5-calls/sec budget via the existing accounting path.
    for _ in range(5):
        gateway._wait_for_accounting_budget()
    assert waits == []
    gateway.read_realized_gains()
    assert waits and waits[0] > 0


def test_read_settlement_returns_the_raw_accounting_call_result_and_passes_range(tmp_path):
    events = []
    sdk = FakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)

    result = gateway.read_settlement("3d")

    assert events == ["login", "init_realtime", "query_settlement:3d"]
    assert result.response.is_success is True
    assert len(result.response.data.details) == 1
    assert result.response.data.details[0].currency == "TWD"


def test_read_settlement_auth_invalid_reconnects_then_reruns_once(tmp_path):
    events = []
    sdks = [FakeSdk(events, query_settlement_auth_fail=True), FakeSdk(events)]
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdks.pop(0), sleeper=lambda _seconds: None)

    gateway.read_settlement("3d")

    assert events.count("login") == 2
    assert events.count("query_settlement:3d") == 2
    first_cleanup = events.index("logout")
    second_login = [index for index, value in enumerate(events) if value == "login"][1]
    assert first_cleanup < second_login


def test_read_settlement_gives_up_after_second_auth_failure(tmp_path):
    # Uses a response-flagged auth failure (is_success=False, code=401), not a raised exception --
    # mirrors test_read_filled_trades_gives_up_after_second_auth_failure exactly. read_settlement
    # (like read_filled_trades) bypasses _accounting_call, so a *raised* exception on the second
    # attempt would fall into its own `except Exception` branch and come out as
    # SETTLEMENT_TRANSPORT_FAILED rather than AUTH_SESSION_INVALID; the response-flagged shape is
    # instead caught by `if self._response_auth_invalid(response): raise SdkCallError(...)`, whose
    # `except SdkCallError` branch bare-`raise`s on the second attempt and so preserves
    # AUTH_SESSION_INVALID as the final, honest reason.
    events = []
    persistent_events = []

    class AlwaysAuthFail(FakeSdk):
        def query_settlement(self, _account, range_param):
            persistent_events.append(f"query_settlement:{range_param}")
            return response(None, success=False, code=401)

    gateway = SdkGateway(
        ready_config(tmp_path), sdk_factory=lambda: AlwaysAuthFail(events), sleeper=lambda _seconds: None
    )
    with pytest.raises(SdkCallError, match="AUTH_SESSION_INVALID"):
        gateway.read_settlement("3d")
    assert len(persistent_events) == 2


def test_read_settlement_serializes_through_accounting_lock(tmp_path):
    concurrency = {"current": 0, "max": 0}
    lock = threading.Lock()

    class SerializingFakeSdk(FakeSdk):
        def query_settlement(self, _account, range_param):
            with lock:
                concurrency["current"] += 1
                concurrency["max"] = max(concurrency["max"], concurrency["current"])
            time.sleep(0.05)
            with lock:
                concurrency["current"] -= 1
            return super().query_settlement(_account, range_param)

    events = []
    sdk = SerializingFakeSdk(events)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, sleeper=lambda _seconds: None)

    threads = [threading.Thread(target=gateway.read_settlement, args=("3d",)) for _ in range(3)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert concurrency["max"] == 1


def test_read_settlement_shares_accounting_budget_pool_with_accounting_call(tmp_path):
    waits = []
    sdk = FakeSdk([])
    gateway = SdkGateway(
        ready_config(tmp_path),
        sdk_factory=lambda: sdk,
        monotonic=lambda: 0.0,
        sleeper=lambda seconds: waits.append(seconds),
    )
    # Exhaust the shared 5-calls/sec budget via the existing accounting path.
    for _ in range(5):
        gateway._wait_for_accounting_budget()
    assert waits == []
    gateway.read_settlement("3d")
    assert waits and waits[0] > 0


def test_taiex_symbol_is_verified_against_official_index_tickers_before_stream_use(tmp_path):
    events = []
    gateway = SdkGateway(
        ready_config(tmp_path), sdk_factory=lambda: FakeSdk(events), sleeper=lambda _seconds: None
    )

    gateway.verify_taiex_index_symbol("IR0001")

    assert events[:3] == ["login", "init_realtime", "tickers:INDEX:TWSE"]
    with pytest.raises(SdkCallError, match="TAIEX_INDEX_SYMBOL_UNVERIFIED"):
        gateway.verify_taiex_index_symbol("NOT_TAIEX")
