from __future__ import annotations

import hashlib
import json
import logging
import queue
import re
import threading
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from .config import ConfigLoader, ConfigSnapshot
from .normalization import strict_iso_date


logger = logging.getLogger(__name__)


_PORTFOLIO_NATIVE_ENUM_TEXT = re.compile(r"\A(?:OrderType|BSAction)\.([A-Za-z_][A-Za-z0-9_]*)\Z")


class SdkCallError(RuntimeError):
    def __init__(
        self,
        reason: str,
        *,
        auth_invalid: bool = False,
        misconfigured: bool = False,
        retry_after_seconds: float | None = None,
        completion_event: threading.Event | None = None,
    ) -> None:
        super().__init__(reason)
        self.reason = reason
        self.auth_invalid = auth_invalid
        self.misconfigured = misconfigured
        self.retry_after_seconds = retry_after_seconds
        # A bounded native SDK worker can outlive the caller that observed its
        # timeout.  The event is deliberately opaque and local-only; callers use
        # it solely to keep an admitted single-flight alive until the worker has
        # released its native slot in finally.
        self.completion_event = completion_event


@dataclass(frozen=True)
class SelectedAccount:
    raw: object = field(repr=False)
    branch_no: str = field(repr=False)
    account_number: str = field(repr=False)
    selector_explicit: bool = False


@dataclass(frozen=True)
class AccountingRead:
    response: object = field(repr=False)
    account: SelectedAccount = field(repr=False)
    internal_token: str = field(repr=False)


@dataclass(frozen=True)
class AccountingPair:
    inventories: object
    unrealized: object
    account: SelectedAccount
    internal_token: str


@dataclass(frozen=True)
class StockPushConnection:
    client: object = field(repr=False)
    generation: int


def raw_field(value: object, name: str) -> object | None:
    if isinstance(value, dict):
        return value.get(name)
    return getattr(value, name, None)


def enum_text(value: object, *, allow_native_portfolio_text: bool = False) -> str | None:
    if value is None:
        return None
    candidate = getattr(value, "value", value)
    if not isinstance(candidate, (str, int)):
        candidate = getattr(value, "name", None)
    if candidate is not None:
        return str(candidate)
    if not allow_native_portfolio_text:
        return None
    try:
        native_text = str(value)
    except Exception:
        return None
    match = _PORTFOLIO_NATIVE_ENUM_TEXT.fullmatch(native_text)
    return match.group(1) if match else None


class SdkGateway:
    """One active SDK session, initialized and cleaned under a single mutex."""

    ACCOUNT_CALL_TIMEOUT_SECONDS = 5.0
    QUOTE_CALL_TIMEOUT_SECONDS = 5.0
    CLEANUP_CALL_TIMEOUT_SECONDS = 2.0
    READER_DEADLINE_SECONDS = 30.0
    MAX_BLOCKING_CALLS = 5
    # Task408 is stricter than the frozen historical 60/min limiter.  It is
    # acquired immediately before *each actual* technical/ticker/candle SDK
    # invocation, including an authentication retry.
    MARKETDATA_START_LIMIT = 38

    def __init__(
        self,
        config_loader: ConfigLoader,
        sdk_factory: Callable[[], object] | None = None,
        monotonic: Callable[[], float] = time.monotonic,
        sleeper: Callable[[float], None] = time.sleep,
        normal_mode_factory: Callable[[], object] | None = None,
    ) -> None:
        self._config_loader = config_loader
        self._sdk_factory = sdk_factory or self._default_sdk_factory
        self._monotonic = monotonic
        self._sleeper = sleeper
        self._normal_mode_factory = normal_mode_factory or self._default_normal_mode
        self._session_lock = threading.RLock()
        self._accounting_lock = threading.Lock()
        self._blocking_slots = threading.BoundedSemaphore(self.MAX_BLOCKING_CALLS)
        self._account_starts: deque[float] = deque()
        self._history_lock = threading.Lock()
        self._history_starts: deque[float] = deque()
        self._marketdata_start_lock = threading.Lock()
        self._marketdata_starts: deque[float] = deque()
        self._history_paused_until = 0.0
        self._shutdown_event = threading.Event()
        self._sdk: object | None = None
        self._account: SelectedAccount | None = None
        self._stock_client: object | None = None
        self._websocket_stock_client: object | None = None
        self._stock_push_websocket_client: object | None = None
        self._session_generation = 0
        self._config_digest: bytes | None = None
        self._session_cleaned = True
        self._runtime_misconfigured = False
        self._misconfigured_digest: bytes | None = None

    @property
    def runtime_misconfigured(self) -> bool:
        with self._session_lock:
            return self._runtime_misconfigured

    def runtime_misconfigured_for(self, config: ConfigSnapshot) -> bool:
        digest = self._session_config_digest(config)
        with self._session_lock:
            return self._runtime_misconfigured and self._misconfigured_digest == digest

    @staticmethod
    def _default_sdk_factory() -> object:
        # Deliberately import only the login/accounting/market-data SDK root. No order API is imported.
        from fubon_neo.sdk import FubonSDK

        return FubonSDK()

    @staticmethod
    def _default_normal_mode() -> object:
        # Mode is only a market-data connection selector; no order model is imported.
        from fubon_neo.sdk import Mode

        return Mode.Normal

    @property
    def session_generation(self) -> int:
        # An immutable integer published while holding the session lock. Readers do not take
        # that lock from SDK callbacks, which may run while bounded cleanup owns it.
        return self._session_generation

    def current_config(self) -> ConfigSnapshot:
        """Local file/flag inspection only; it cannot create an SDK session."""
        return self._config_loader.load()

    def _reader_deadline(self, deadline: float | None) -> float:
        """Keep a caller deadline, but never let one reader exceed 30 seconds."""
        local_deadline = self._monotonic() + self.READER_DEADLINE_SECONDS
        return local_deadline if deadline is None else min(deadline, local_deadline)

    def _remaining(self, deadline: float | None, timeout_reason: str) -> float:
        if deadline is None:
            return float("inf")
        remaining = deadline - self._monotonic()
        if remaining <= 0:
            raise SdkCallError(timeout_reason)
        return remaining

    def _acquire_lock(self, lock: object, deadline: float | None, timeout_reason: str) -> None:
        acquire = getattr(lock, "acquire")
        if deadline is None:
            acquire()
            return
        if not acquire(timeout=self._remaining(deadline, timeout_reason)):
            raise SdkCallError(timeout_reason)

    def read_accounting_pair(self, *, deadline: float | None = None) -> AccountingPair:
        deadline = self._reader_deadline(deadline)
        self._acquire_lock(self._accounting_lock, deadline, "ACCOUNTING_TIMEOUT")
        try:
            for attempt in range(2):
                try:
                    # Keep method, account and token attached to one session even if a concurrent
                    # market-data request needs to replace that session.
                    self._acquire_lock(self._session_lock, deadline, "ACCOUNTING_TIMEOUT")
                    try:
                        config = self._require_config()
                        account = self._ensure_session(
                            config, deadline=deadline, timeout_reason="ACCOUNTING_TIMEOUT"
                        )
                        inventories = self._accounting_call(
                            "inventories", account.raw, deadline=deadline, timeout_reason="ACCOUNTING_TIMEOUT"
                        )
                        unrealized = self._accounting_call(
                            "unrealized_gains_and_loses", account.raw,
                            deadline=deadline, timeout_reason="ACCOUNTING_TIMEOUT"
                        )
                        if self._response_auth_invalid(inventories) or self._response_auth_invalid(unrealized):
                            raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
                        return AccountingPair(inventories, unrealized, account, config.internal_service_token or "")
                    finally:
                        self._session_lock.release()
                except SdkCallError as exc:
                    if exc.auth_invalid and attempt == 0:
                        self._acquire_lock(self._session_lock, deadline, "ACCOUNTING_TIMEOUT")
                        try:
                            self._invalidate_locked(deadline=deadline, timeout_reason="ACCOUNTING_TIMEOUT")
                        finally:
                            self._session_lock.release()
                        continue
                    raise
                except Exception as exc:
                    if self._exception_is_auth_invalid(exc) and attempt == 0:
                        self._acquire_lock(self._session_lock, deadline, "ACCOUNTING_TIMEOUT")
                        try:
                            self._invalidate_locked(deadline=deadline, timeout_reason="ACCOUNTING_TIMEOUT")
                        finally:
                            self._session_lock.release()
                        continue
                    raise SdkCallError("ACCOUNTING_TRANSPORT_FAILED") from None
            raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
        finally:
            self._accounting_lock.release()

    def selected_account(self) -> SelectedAccount:
        """Legacy session accessor; reconciling readers use the atomic AccountingRead instead."""
        config = self._require_config()
        return self._ensure_session(config)

    def read_filled_trades(
        self, start_date: str, end_date: str, *, deadline: float | None = None
    ) -> AccountingRead:
        """Only sdk.stock.filled_history is reachable on the broker trading namespace."""
        start, end = strict_iso_date(start_date), strict_iso_date(end_date)
        if start > end or (end - start).days > 7:
            raise SdkCallError("INVALID_DATE_RANGE")
        return self._read_accounting(
            "filled_history", start.strftime("%Y%m%d"), end.strftime("%Y%m%d"), deadline=deadline
        )

    def read_bank_balance(self, *, deadline: float | None = None) -> AccountingRead:
        return self._read_accounting("bank_remain", deadline=deadline)

    def read_settlement(self, range_param: str = "3d", *, deadline: float | None = None) -> AccountingRead:
        if range_param != "3d":
            raise SdkCallError("INVALID_SETTLEMENT_RANGE")
        return self._read_accounting("query_settlement", "3d", deadline=deadline)

    def read_realized_gains(self, *, deadline: float | None = None) -> AccountingRead:
        return self._read_accounting("realized_gains_and_loses", deadline=deadline)

    def read_dividends(self, start_date: str, end_date: str) -> object:
        return self._marketdata_read("corporate_actions", "dividends", {
            "start_date": start_date, "end_date": end_date,
        })

    def read_technical_indicator(self, kind: str, symbol: str, start_date: str, end_date: str,
                                 *, timeframe: str = "D", parameters: dict[str, object] | None = None,
                                 deadline: float | None = None) -> object:
        legacy_parameters = {
            "kdj": {"rPeriod": 9, "kPeriod": 3, "dPeriod": 3},
            "macd": {"fast": 12, "slow": 26, "signal": 9},
            "bb": {"period": 20},
        }
        allowed = {"sma", "rsi", *legacy_parameters}
        if kind not in allowed or timeframe not in {"D", "W"}:
            raise SdkCallError("TECHNICAL_METHOD_UNAVAILABLE", misconfigured=True)
        if parameters is None:
            if kind not in legacy_parameters:
                raise SdkCallError("TECHNICAL_METHOD_UNAVAILABLE", misconfigured=True)
            parameters = legacy_parameters[kind]
        if not isinstance(parameters, dict) or not parameters:
            raise SdkCallError("TECHNICAL_METHOD_UNAVAILABLE", misconfigured=True)
        return self._marketdata_read("technical", kind, {
            "symbol": symbol, "from": start_date, "to": end_date,
            "timeframe": timeframe, **parameters,
        }, deadline=deadline)

    def read_ticker(self, symbol: str, *, deadline: float | None = None) -> object:
        # `type` is deliberately omitted: only an odd-lot request is allowed to set it.
        return self._marketdata_read("intraday", "ticker", {"symbol": symbol}, deadline=deadline)

    def read_intraday_candles(self, symbol: str, *, deadline: float | None = None) -> object:
        # This is ordinary-lot, one-minute ascending data; never add `type=EQUITY`.
        return self._marketdata_read("intraday", "candles", {
            "symbol": symbol, "timeframe": 1, "sort": "asc",
        }, deadline=deadline)

    def read_intraday_volumes(self, symbol: str, *, deadline: float | None = None) -> object:
        # Ordinary-lot accumulated price-volume only.  This is intentionally a
        # narrow read adapter, never a generic method dispatcher.
        return self._marketdata_read("intraday", "volumes", {"symbol": symbol}, deadline=deadline)

    def read_historical_daily_candles(self, symbol: str, start_date: str, end_date: str,
                                      *, deadline: float | None = None) -> object:
        return self._marketdata_read("historical", "candles", {
            "symbol": symbol, "from": start_date, "to": end_date, "timeframe": "D",
            "adjusted": False, "fields": "open,high,low,close,volume,turnover,change", "sort": "asc",
        }, deadline=deadline)

    def _marketdata_read(self, namespace: str, method_name: str, params: dict[str, object],
                         *, deadline: float | None = None) -> object:
        if (namespace, method_name) not in {
            ("corporate_actions", "dividends"), ("technical", "kdj"),
            ("technical", "macd"), ("technical", "bb"), ("technical", "sma"),
            ("technical", "rsi"), ("intraday", "ticker"), ("intraday", "candles"),
            ("intraday", "volumes"), ("historical", "candles"),
        }:
            raise SdkCallError("MARKETDATA_METHOD_UNAVAILABLE", misconfigured=True)
        for attempt in range(2):
            self._take_history_budget(deadline=deadline)
            config = self._require_config()
            self._ensure_session(config)
            with self._session_lock:
                client = self._stock_client
            method = raw_field(raw_field(client, namespace), method_name)
            if not callable(method):
                raise SdkCallError("MARKETDATA_METHOD_UNAVAILABLE", misconfigured=True)
            try:
                remaining = self.QUOTE_CALL_TIMEOUT_SECONDS if deadline is None else deadline - self._monotonic()
                if remaining <= 0:
                    raise SdkCallError("MARKETDATA_TIMEOUT")
                def invoke_marketdata() -> object:
                    # Another worker may receive 429 while this request is waiting for a login
                    # or blocking-call slot. Recheck immediately before starting the SDK call.
                    with self._history_lock:
                        now = self._monotonic()
                        if self._shutdown_event.is_set():
                            raise SdkCallError("SERVICE_SHUTDOWN")
                        if deadline is not None and now >= deadline:
                            raise SdkCallError("MARKETDATA_TIMEOUT")
                        if now < self._history_paused_until:
                            raise SdkCallError("RATE_LIMITED", retry_after_seconds=self._history_paused_until - now)
                    if namespace in {"technical", "intraday", "historical"}:
                        self._take_marketdata_start_permit(deadline=deadline)
                    return method(**params)
                response = self._run_bounded(
                    invoke_marketdata, min(self.QUOTE_CALL_TIMEOUT_SECONDS, remaining), "MARKETDATA_TIMEOUT",
                )
                if self._response_auth_invalid(response):
                    raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
                self._check_marketdata_size(response)
                return response
            except SdkCallError as exc:
                if exc.auth_invalid and attempt == 0:
                    with self._session_lock:
                        self._invalidate_locked()
                    continue
                raise
            except Exception as exc:
                if self._exception_is_rate_limited(exc):
                    retry_after = self._exception_retry_after(exc)
                    with self._history_lock:
                        self._history_paused_until = max(
                            self._history_paused_until, self._monotonic() + max(60.0, retry_after or 0.0),
                        )
                    raise SdkCallError("RATE_LIMITED", retry_after_seconds=retry_after) from None
                if self._exception_is_auth_invalid(exc):
                    if attempt == 0:
                        with self._session_lock:
                            self._invalidate_locked()
                        continue
                    raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True) from None
                raise SdkCallError("MARKETDATA_TRANSPORT_FAILED") from None
        raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)

    def _take_history_budget(self, *, deadline: float | None = None) -> None:
        wait_budget = 5.0
        while not self._shutdown_event.is_set():
            with self._history_lock:
                now = self._monotonic()
                if deadline is not None and now >= deadline:
                    raise SdkCallError("MARKETDATA_TIMEOUT")
                while self._history_starts and now - self._history_starts[0] >= 60.0:
                    self._history_starts.popleft()
                if now < self._history_paused_until:
                    raise SdkCallError("RATE_LIMITED", retry_after_seconds=self._history_paused_until - now)
                if len(self._history_starts) < 60:
                    self._history_starts.append(now)
                    return
                until_slot = 60.0 - (now - self._history_starts[0])
                if wait_budget <= 0:
                    raise SdkCallError("HISTORY_BUDGET_EXHAUSTED", retry_after_seconds=until_slot)
                delay = min(0.1, until_slot, wait_budget)
                if deadline is not None:
                    delay = min(delay, deadline - now)
            # Never hold the shared budget lock while waiting; Java paces symbols so this is
            # normally only a small overlap with another historical reader, not a minute sleep.
            if self._sleeper is time.sleep:
                self._shutdown_event.wait(delay)
            else:
                self._sleeper(delay)
            wait_budget -= delay
        raise SdkCallError("SERVICE_SHUTDOWN")

    def _take_marketdata_start_permit(self, *, deadline: float | None = None) -> None:
        """Acquire the Task408 38-start rolling permit immediately before SDK I/O.

        It is intentionally independent from `_take_history_budget`: that older
        60/min gate remains a second defence and may account non-Task408 history
        readers.  This permit only counts actual technical/ticker/candle starts.
        """
        while not self._shutdown_event.is_set():
            with self._marketdata_start_lock:
                now = self._monotonic()
                if deadline is not None and now >= deadline:
                    raise SdkCallError("HISTORY_BUDGET_EXHAUSTED")
                while self._marketdata_starts and now - self._marketdata_starts[0] >= 60.0:
                    self._marketdata_starts.popleft()
                if len(self._marketdata_starts) < self.MARKETDATA_START_LIMIT:
                    self._marketdata_starts.append(now)
                    return
                wait = max(0.0, 60.0 - (now - self._marketdata_starts[0]))
                if deadline is not None and now + wait >= deadline:
                    raise SdkCallError("HISTORY_BUDGET_EXHAUSTED", retry_after_seconds=wait)
            # Like the existing history limiter, no shared lock is held while waiting.
            if self._sleeper is time.sleep:
                self._shutdown_event.wait(wait)
            else:
                self._sleeper(wait)
        raise SdkCallError("SERVICE_SHUTDOWN")

    @staticmethod
    def _check_marketdata_size(response: object) -> None:
        # SDK responses are already decoded; bound the adapter payload without logging it or
        # allocating another unbounded serialized copy. The blocking-call semaphore also bounds
        # timed-out SDK requests that cannot be interrupted by Python.
        try:
            size = 0
            for chunk in json.JSONEncoder(ensure_ascii=False, default=str).iterencode(response):
                size += len(chunk.encode("utf-8"))
                if size > 8 * 1024 * 1024:
                    raise SdkCallError("MARKETDATA_RESPONSE_TOO_LARGE")
        except (TypeError, ValueError):
            raise SdkCallError("MARKETDATA_SCHEMA_INVALID") from None

    def _read_accounting(
        self, method_name: str, *args: object, deadline: float | None = None
    ) -> AccountingRead:
        if method_name not in {"filled_history", "bank_remain", "query_settlement", "realized_gains_and_loses"}:
            raise SdkCallError("READ_METHOD_UNAVAILABLE", misconfigured=True)
        timeout_reason = "FILLED_HISTORY_TIMEOUT" if method_name == "filled_history" else "ACCOUNTING_TIMEOUT"
        deadline = self._reader_deadline(deadline)
        self._acquire_lock(self._accounting_lock, deadline, timeout_reason)
        try:
            for attempt in range(2):
                try:
                    self._acquire_lock(self._session_lock, deadline, timeout_reason)
                    try:
                        config = self._require_config()
                        account = self._ensure_session(
                            config, deadline=deadline, timeout_reason=timeout_reason
                        )
                        if method_name == "filled_history":
                            self._wait_for_accounting_budget(deadline=deadline, timeout_reason=timeout_reason)
                            stock = raw_field(self._sdk, "stock")
                            method = raw_field(stock, "filled_history")
                            if not callable(method):
                                self._mark_misconfigured(deadline=deadline, timeout_reason=timeout_reason)
                                raise SdkCallError("FILLED_HISTORY_UNAVAILABLE", misconfigured=True)
                            response = self._run_bounded(
                                lambda: method(account.raw, *args),
                                self.ACCOUNT_CALL_TIMEOUT_SECONDS, timeout_reason, deadline=deadline,
                            )
                        else:
                            response = self._accounting_call(
                                method_name, account.raw, *args, deadline=deadline, timeout_reason=timeout_reason
                            )
                        if self._response_auth_invalid(response):
                            raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
                        return AccountingRead(response, account, config.internal_service_token or "")
                    finally:
                        self._session_lock.release()
                except SdkCallError as exc:
                    if exc.auth_invalid and attempt == 0:
                        self._acquire_lock(self._session_lock, deadline, timeout_reason)
                        try:
                            self._invalidate_locked(deadline=deadline, timeout_reason=timeout_reason)
                        finally:
                            self._session_lock.release()
                        continue
                    raise
                except Exception as exc:
                    if self._exception_is_auth_invalid(exc):
                        if attempt == 0:
                            self._acquire_lock(self._session_lock, deadline, timeout_reason)
                            try:
                                self._invalidate_locked(deadline=deadline, timeout_reason=timeout_reason)
                            finally:
                                self._session_lock.release()
                            continue
                        raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True) from None
                    if self._exception_is_rate_limited(exc):
                        raise SdkCallError("RATE_LIMITED") from None
                    reason = "FILLED_HISTORY_TRANSPORT_FAILED" if method_name == "filled_history" else "ACCOUNTING_TRANSPORT_FAILED"
                    raise SdkCallError(reason) from None
            raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
        finally:
            self._accounting_lock.release()

    def quote(
        self,
        code: str,
        *,
        deadline: float | None = None,
        before_dispatch: Callable[[], None] | None = None,
    ) -> object:
        deadline = self._reader_deadline(deadline)
        for attempt in range(2):
            config = self._require_config()
            self._ensure_session(config, deadline=deadline, timeout_reason="QUOTE_TIMEOUT")
            self._acquire_lock(self._session_lock, deadline, "QUOTE_TIMEOUT")
            try:
                client = self._stock_client
            finally:
                self._session_lock.release()
            if client is None:
                raise SdkCallError("REALTIME_NOT_INITIALIZED", misconfigured=True)
            try:
                intraday = raw_field(client, "intraday")
                quote_method = raw_field(intraday, "quote") if intraday is not None else None
                if not callable(quote_method):
                    self._mark_misconfigured(deadline=deadline, timeout_reason="QUOTE_TIMEOUT")
                    raise SdkCallError("QUOTE_CLIENT_UNAVAILABLE", misconfigured=True)

                def invoke_quote() -> object:
                    # This is the only point at which QuoteService's 240/min budget can be
                    # debited.  Slot acquisition has already succeeded, and both deadline and
                    # the global 429 circuit are rechecked immediately before native I/O.
                    self._remaining(deadline, "QUOTE_TIMEOUT")
                    if before_dispatch is not None:
                        before_dispatch()
                    self._remaining(deadline, "QUOTE_TIMEOUT")
                    return quote_method(symbol=code)

                response = self._run_bounded(
                    invoke_quote, self.QUOTE_CALL_TIMEOUT_SECONDS, "QUOTE_TIMEOUT", deadline=deadline
                )
                if self._response_auth_invalid(response):
                    raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
                return response
            except SdkCallError as exc:
                if exc.auth_invalid and attempt == 0:
                    self._acquire_lock(self._session_lock, deadline, "QUOTE_TIMEOUT")
                    try:
                        self._invalidate_locked(deadline=deadline, timeout_reason="QUOTE_TIMEOUT")
                    finally:
                        self._session_lock.release()
                    continue
                raise
            except Exception as exc:
                if self._exception_is_rate_limited(exc):
                    raise SdkCallError(
                        "RATE_LIMITED", retry_after_seconds=self._exception_retry_after(exc)
                    ) from None
                if self._exception_is_auth_invalid(exc) and attempt == 0:
                    self._acquire_lock(self._session_lock, deadline, "QUOTE_TIMEOUT")
                    try:
                        self._invalidate_locked(deadline=deadline, timeout_reason="QUOTE_TIMEOUT")
                    finally:
                        self._session_lock.release()
                    continue
                raise SdkCallError("QUOTE_TRANSPORT_FAILED") from None
        raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)

    def read_etf_holdings(self, symbol: str) -> object:
        """Read-only ETF constituent-holdings lookup (Requirement 123 / Task 389).

        Mirrors quote()'s session/timeout/retry structure exactly -- this call also
        lives under sdk.marketdata.rest_client.stock and never touches the
        accounting namespace (_accounting_lock / _wait_for_accounting_budget()),
        which is reserved for the trading-account query line. EtfHoldingsService
        validates the official provider schema and creates the normalized DTO;
        this internal gateway return value must never be exposed directly.
        """
        for attempt in range(2):
            config = self._require_config()
            self._ensure_session(config)
            with self._session_lock:
                client = self._stock_client
            if client is None:
                raise SdkCallError("REALTIME_NOT_INITIALIZED", misconfigured=True)
            try:
                ownership = raw_field(client, "ownership")
                etf_holdings_method = raw_field(ownership, "etf_holdings") if ownership is not None else None
                if not callable(etf_holdings_method):
                    self._mark_misconfigured()
                    raise SdkCallError("ETF_HOLDINGS_CLIENT_UNAVAILABLE", misconfigured=True)
                response = self._run_bounded(
                    lambda: etf_holdings_method(symbol=symbol),
                    self.QUOTE_CALL_TIMEOUT_SECONDS, "ETF_HOLDINGS_TIMEOUT",
                )
                if self._response_auth_invalid(response):
                    raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
                return response
            except SdkCallError as exc:
                if exc.auth_invalid and attempt == 0:
                    with self._session_lock:
                        self._invalidate_locked()
                    continue
                raise
            except Exception as exc:
                if self._exception_is_rate_limited(exc):
                    raise SdkCallError(
                        "RATE_LIMITED", retry_after_seconds=self._exception_retry_after(exc)
                    ) from None
                if self._exception_is_auth_invalid(exc) and attempt == 0:
                    with self._session_lock:
                        self._invalidate_locked()
                    continue
                if self._exception_is_auth_invalid(exc):
                    raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True) from None
                raise SdkCallError("ETF_HOLDINGS_TRANSPORT_FAILED") from None
        raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)

    def realtime_stock_websocket(self) -> object:
        """Returns the read-only market-data websocket client from the existing SDK session."""
        config = self._require_config()
        self._ensure_session(config)
        with self._session_lock:
            client = self._websocket_stock_client
        if client is None:
            self._mark_misconfigured()
            raise SdkCallError("INDICES_CLIENT_UNAVAILABLE", misconfigured=True)
        return client

    def stock_push_websocket(self) -> StockPushConnection:
        """A dedicated Normal-mode reference; saved REST and index references remain untouched."""
        config = self._require_config()
        if config.stock_push_reason:
            raise SdkCallError(config.stock_push_reason, misconfigured=True)
        if not config.stock_push_enabled:
            raise SdkCallError("STOCK_PUSH_DISABLED")
        self._ensure_session(config)
        with self._session_lock:
            if self._shutdown_event.is_set():
                raise SdkCallError("SERVICE_SHUTDOWN")
            if self._stock_push_websocket_client is not None:
                return StockPushConnection(self._stock_push_websocket_client, self._session_generation)
            sdk = self._sdk
            if sdk is None:
                raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
            try:
                result = self._run_bounded(
                    lambda: sdk.init_realtime(self._normal_mode_factory()),
                    self.ACCOUNT_CALL_TIMEOUT_SECONDS, "STOCK_REALTIME_INIT_TIMEOUT",
                )
                if raw_field(result, "is_success") is False:
                    raise SdkCallError("STOCK_REALTIME_INIT_FAILED")
                marketdata = raw_field(sdk, "marketdata")
                websocket = raw_field(raw_field(marketdata, "websocket_client"), "stock")
                if websocket is None or websocket is self._websocket_stock_client:
                    raise SdkCallError("STOCK_PUSH_CLIENT_UNAVAILABLE")
                self._stock_push_websocket_client = websocket
                return StockPushConnection(websocket, self._session_generation)
            except SdkCallError:
                raise
            except Exception:
                raise SdkCallError("STOCK_REALTIME_INIT_FAILED") from None

    def stock_push_call(self, client: object, method_name: str, payload: dict[str, object] | None = None) -> None:
        if method_name not in {"connect", "subscribe", "unsubscribe"}:
            raise SdkCallError("STOCK_PUSH_METHOD_UNAVAILABLE")
        method = raw_field(client, method_name)
        if not callable(method):
            raise SdkCallError("STOCK_PUSH_CLIENT_UNAVAILABLE")
        self._run_bounded(
            method if payload is None else lambda: method(payload),
            self.QUOTE_CALL_TIMEOUT_SECONDS, "STOCK_PUSH_CALL_TIMEOUT",
        )

    def release_stock_push_websocket(self, client: object | None) -> None:
        with self._session_lock:
            if client is None or self._stock_push_websocket_client is not client:
                return
            self._stock_push_websocket_client = None
        self._disconnect_stock_push(client)

    def _disconnect_stock_push(self, client: object | None, *, deadline: float | None = None) -> None:
        method = raw_field(client, "disconnect")
        if callable(method):
            try:
                self._run_bounded(
                    method,
                    self.CLEANUP_CALL_TIMEOUT_SECONDS,
                    "STOCK_PUSH_CLEANUP_TIMEOUT",
                    deadline=deadline,
                )
            except BaseException:
                logger.warning("Fubon stock stream cleanup failed reason=CLEANUP_FAILED")

    def verify_taiex_index_symbol(self, symbol: str) -> None:
        """Verifies the deployment-selected Taiwan index against the official tickers catalog."""
        if not isinstance(symbol, str) or not symbol:
            raise SdkCallError("INVALID_TAIEX_INDEX_SYMBOL", misconfigured=True)
        config = self._require_config()
        self._ensure_session(config)
        with self._session_lock:
            client = self._stock_client
        intraday = raw_field(client, "intraday") if client is not None else None
        tickers = raw_field(intraday, "tickers") if intraday is not None else None
        if not callable(tickers):
            self._mark_misconfigured()
            raise SdkCallError("INDEX_TICKERS_UNAVAILABLE", misconfigured=True)
        try:
            response = self._run_bounded(
                lambda: tickers(type="INDEX", exchange="TWSE"),
                self.QUOTE_CALL_TIMEOUT_SECONDS,
                "INDEX_TICKERS_TIMEOUT",
            )
            if isinstance(response, list):
                rows = response
                mapping_envelope = False
            elif isinstance(response, dict):
                if self._response_auth_invalid(response):
                    raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
                is_success_present = "is_success" in response
                is_success = response.get("is_success")
                data_present = "data" in response
                data = response.get("data")
                if is_success is False:
                    raise SdkCallError("INDEX_TICKERS_REJECTED", misconfigured=True)
                if (is_success_present and is_success is not True) or not data_present:
                    raise SdkCallError("INDEX_TICKERS_INVALID", misconfigured=True)
                if response.get("exchange") != "TWSE" or response.get("type") != "INDEX":
                    raise SdkCallError("INDEX_TICKERS_INVALID", misconfigured=True)
                rows = data
                mapping_envelope = True
            else:
                raise SdkCallError("INDEX_TICKERS_INVALID", misconfigured=True)
            if not isinstance(rows, list):
                raise SdkCallError("INDEX_TICKERS_INVALID", misconfigured=True)
            for row in rows:
                if mapping_envelope and raw_field(row, "symbol") == symbol:
                    return
                if (not mapping_envelope and raw_field(row, "symbol") == symbol
                        and raw_field(row, "exchange") == "TWSE"
                        and raw_field(row, "type") == "INDEX"):
                    return
            raise SdkCallError("TAIEX_INDEX_SYMBOL_UNVERIFIED", misconfigured=True)
        except SdkCallError:
            raise
        except Exception:
            raise SdkCallError("INDEX_TICKERS_TRANSPORT_FAILED") from None

    def shutdown(self) -> None:
        self._shutdown_event.set()
        with self._session_lock:
            self._cleanup_locked()

    def _require_config(self) -> ConfigSnapshot:
        config = self._config_loader.load()
        if not config.enabled:
            raise SdkCallError("DISABLED")
        if config.state != "READY":
            raise SdkCallError(config.reason or "MISCONFIGURED", misconfigured=True)
        return config

    def _ensure_session(
        self,
        config: ConfigSnapshot,
        *,
        deadline: float | None = None,
        timeout_reason: str = "SESSION_TIMEOUT",
    ) -> SelectedAccount:
        self._acquire_lock(self._session_lock, deadline, timeout_reason)
        try:
            return self._ensure_session_locked(config, deadline=deadline, timeout_reason=timeout_reason)
        finally:
            self._session_lock.release()

    def _ensure_session_locked(
        self, config: ConfigSnapshot, *, deadline: float | None, timeout_reason: str
    ) -> SelectedAccount:
        digest = self._session_config_digest(config)
        if (
            self._sdk is not None
            and self._account is not None
            and self._stock_client is not None
            and self._config_digest == digest
            and not self._session_cleaned
        ):
            return self._account
        if self._runtime_misconfigured and self._misconfigured_digest == digest:
            raise SdkCallError("RUNTIME_MISCONFIGURED", misconfigured=True)
        if self._misconfigured_digest != digest:
            self._runtime_misconfigured = False
            self._misconfigured_digest = None

        self._cleanup_locked(deadline=deadline, timeout_reason=timeout_reason)
        try:
            sdk = self._sdk_factory()
            # Publish the partial session immediately so every later failure is bounded-cleaned.
            self._sdk = sdk
            self._session_cleaned = False
            login = self._run_bounded(
                lambda: sdk.apikey_login(
                    config.personal_id,
                    config.api_key,
                    str(config.certificate_path),
                    config.certificate_password,
                ),
                self.ACCOUNT_CALL_TIMEOUT_SECONDS,
                "LOGIN_TIMEOUT",
                deadline=deadline,
            )
            if raw_field(login, "is_success") is not True:
                raise SdkCallError("SDK_LOGIN_FAILED", misconfigured=True)
            accounts = raw_field(login, "data")
            if not isinstance(accounts, list):
                raise SdkCallError("INVALID_ACCOUNT_LIST", misconfigured=True)
            selected = self._select_account(accounts, config)

            init_result = self._run_bounded(
                sdk.init_realtime,
                self.ACCOUNT_CALL_TIMEOUT_SECONDS,
                "REALTIME_INIT_TIMEOUT",
                deadline=deadline,
            )
            if raw_field(init_result, "is_success") is False:
                raise SdkCallError("REALTIME_INIT_FAILED", misconfigured=True)
            marketdata = raw_field(sdk, "marketdata")
            rest_client = raw_field(marketdata, "rest_client") if marketdata is not None else None
            stock_client = raw_field(rest_client, "stock") if rest_client is not None else None
            websocket_client = raw_field(marketdata, "websocket_client") if marketdata is not None else None
            websocket_stock_client = raw_field(websocket_client, "stock") if websocket_client is not None else None
            if stock_client is None:
                raise SdkCallError("REALTIME_CLIENT_MISSING", misconfigured=True)

            self._sdk = sdk
            self._account = selected
            self._stock_client = stock_client
            self._websocket_stock_client = websocket_stock_client
            self._session_generation += 1
            self._config_digest = digest
            self._session_cleaned = False
            self._runtime_misconfigured = False
            self._misconfigured_digest = None
            return selected
        except SdkCallError as exc:
            if exc.misconfigured:
                self._runtime_misconfigured = True
                self._misconfigured_digest = digest
            self._cleanup_locked(deadline=deadline, timeout_reason=timeout_reason)
            raise
        except Exception:
            self._runtime_misconfigured = True
            self._misconfigured_digest = digest
            self._cleanup_locked(deadline=deadline, timeout_reason=timeout_reason)
            raise SdkCallError("SDK_LOGIN_FAILED", misconfigured=True) from None

    def _select_account(self, accounts: list[object], config: ConfigSnapshot) -> SelectedAccount:
        stock_accounts: list[SelectedAccount] = []
        for account in accounts:
            account_type = enum_text(raw_field(account, "account_type"))
            if account_type is None or account_type.lower() != "stock":
                continue
            branch = raw_field(account, "branch_no")
            number = raw_field(account, "account")
            if not isinstance(branch, str) or not branch or not isinstance(number, str) or not number:
                raise SdkCallError("INVALID_STOCK_ACCOUNT", misconfigured=True)
            stock_accounts.append(SelectedAccount(account, branch, number))

        if config.presence.account_selector_pair:
            if not all(
                isinstance(value, str) and value
                for value in (config.account_branch_no, config.account_number)
            ):
                raise SdkCallError("INVALID_ACCOUNT_SELECTOR", misconfigured=True)
            matches = [
                candidate
                for candidate in stock_accounts
                if candidate.branch_no == config.account_branch_no
                and candidate.account_number == config.account_number
            ]
            if len(matches) != 1:
                raise SdkCallError("ACCOUNT_SELECTOR_NOT_UNIQUE", misconfigured=True)
            selected = matches[0]
            return SelectedAccount(selected.raw, selected.branch_no, selected.account_number, True)
        if len(stock_accounts) != 1:
            raise SdkCallError("STOCK_ACCOUNT_NOT_UNIQUE", misconfigured=True)
        return stock_accounts[0]

    def _accounting_call(
        self,
        method_name: str,
        account: object,
        *args: object,
        deadline: float | None = None,
        timeout_reason: str = "ACCOUNTING_TIMEOUT",
    ) -> object:
        if method_name not in {"inventories", "unrealized_gains_and_loses", "bank_remain", "query_settlement", "realized_gains_and_loses"}:
            raise SdkCallError("ACCOUNTING_API_UNAVAILABLE", misconfigured=True)
        self._wait_for_accounting_budget(deadline=deadline, timeout_reason=timeout_reason)
        self._acquire_lock(self._session_lock, deadline, timeout_reason)
        try:
            sdk = self._sdk
        finally:
            self._session_lock.release()
        accounting = raw_field(sdk, "accounting") if sdk is not None else None
        method = raw_field(accounting, method_name) if accounting is not None else None
        if not callable(method):
            self._mark_misconfigured(deadline=deadline, timeout_reason=timeout_reason)
            raise SdkCallError("ACCOUNTING_API_UNAVAILABLE", misconfigured=True)
        try:
            return self._run_bounded(
                lambda: method(account, *args),
                self.ACCOUNT_CALL_TIMEOUT_SECONDS,
                timeout_reason,
                deadline=deadline,
            )
        except SdkCallError:
            raise
        except Exception as exc:
            if self._exception_is_auth_invalid(exc):
                raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True) from None
            if self._exception_is_rate_limited(exc):
                raise SdkCallError("RATE_LIMITED") from None
            raise SdkCallError("ACCOUNTING_TRANSPORT_FAILED") from None

    def _wait_for_accounting_budget(
        self, *, deadline: float | None = None, timeout_reason: str = "ACCOUNTING_TIMEOUT"
    ) -> None:
        while True:
            now = self._monotonic()
            while self._account_starts and now - self._account_starts[0] >= 1.0:
                self._account_starts.popleft()
            if len(self._account_starts) < 5:
                self._account_starts.append(now)
                return
            wait = max(0.0, 1.0 - (now - self._account_starts[0]))
            # Do not turn accounting pacing into an unbounded sleep.  A caller
            # that supplied a reader deadline spends only its remaining time.
            self._sleeper(min(wait, self._remaining(deadline, timeout_reason)))

    def _run_bounded(
        self,
        call: Callable[[], Any],
        timeout: float,
        timeout_reason: str,
        *,
        deadline: float | None = None,
    ) -> Any:
        # The semaphore protects real native SDK workers, including workers that
        # outlive a caller timeout.  Reader paths wait at most their single shared
        # absolute deadline; legacy non-reader callers keep the historic short
        # saturation probe.
        if deadline is None:
            slot_wait = 0.1
        else:
            slot_wait = deadline - self._monotonic()
            if slot_wait <= 0:
                raise SdkCallError("SDK_CALL_SATURATED")
        if not self._blocking_slots.acquire(timeout=slot_wait):
            raise SdkCallError("SDK_CALL_SATURATED")
        output: queue.Queue[tuple[bool, object]] = queue.Queue(maxsize=1)
        completed = threading.Event()

        def invoke() -> None:
            try:
                output.put((True, call()))
            except BaseException as exc:  # exception object remains internal; never serialized or logged
                output.put((False, exc))
            finally:
                self._blocking_slots.release()
                completed.set()

        try:
            # Slot acquisition may itself consume most of the reader deadline.
            # Never let a just-admitted worker start after it has expired.
            join_timeout = min(timeout, self._remaining(deadline, timeout_reason))
        except BaseException:
            self._blocking_slots.release()
            raise
        worker = threading.Thread(target=invoke, name="fubon-sdk-call", daemon=True)
        worker.start()
        worker.join(join_timeout)
        if worker.is_alive():
            # The worker owns the slot until its finally block.  QuoteService uses
            # this completion signal to retain its admitted key for the same span.
            raise SdkCallError(timeout_reason, completion_event=completed)
        ok, value = output.get_nowait()
        if ok:
            return value
        assert isinstance(value, BaseException)
        raise value

    def _cleanup_locked(
        self, *, deadline: float | None = None, timeout_reason: str = "CLEANUP_TIMEOUT"
    ) -> None:
        sdk = self._sdk
        stock_push = self._stock_push_websocket_client
        self._stock_push_websocket_client = None
        if sdk is not None or stock_push is not None:
            self._session_generation += 1
        self._disconnect_stock_push(stock_push, deadline=deadline)
        if sdk is None or self._session_cleaned:
            self._sdk = None
            self._account = None
            self._stock_client = None
            self._websocket_stock_client = None
            self._config_digest = None
            self._session_cleaned = True
            return
        self._sdk = None
        self._account = None
        self._stock_client = None
        self._websocket_stock_client = None
        self._config_digest = None
        self._session_cleaned = True
        for method_name in ("logout", "shutdown"):
            method = raw_field(sdk, method_name)
            if not callable(method):
                continue
            try:
                self._run_bounded(
                    method,
                    self.CLEANUP_CALL_TIMEOUT_SECONDS,
                    "CLEANUP_TIMEOUT",
                    deadline=deadline,
                )
            except BaseException:
                logger.warning("Fubon SDK cleanup skipped reason=CLEANUP_FAILED step=%s", method_name)

    def _invalidate_locked(
        self, *, deadline: float | None = None, timeout_reason: str = "CLEANUP_TIMEOUT"
    ) -> None:
        self._cleanup_locked(deadline=deadline, timeout_reason=timeout_reason)

    def _mark_misconfigured(
        self, *, deadline: float | None = None, timeout_reason: str = "CLEANUP_TIMEOUT"
    ) -> None:
        self._acquire_lock(self._session_lock, deadline, timeout_reason)
        try:
            digest = self._config_digest
            self._runtime_misconfigured = True
            self._misconfigured_digest = digest
            self._cleanup_locked(deadline=deadline, timeout_reason=timeout_reason)
        finally:
            self._session_lock.release()

    @staticmethod
    def _session_config_digest(config: ConfigSnapshot) -> bytes:
        joined = "\0".join(
            (
                config.personal_id or "",
                config.api_key or "",
                config.certificate_password or "",
                str(config.certificate_path or ""),
                config.account_branch_no or "",
                config.account_number or "",
                config.internal_service_token or "",
            )
        )
        return hashlib.sha256(joined.encode("utf-8")).digest()

    @staticmethod
    def _response_auth_invalid(response: object) -> bool:
        if raw_field(response, "is_success") is not False:
            return False
        code = enum_text(raw_field(response, "code")) or enum_text(raw_field(response, "error_code"))
        return (code or "").upper() in {"401", "403", "AUTH_INVALID", "SESSION_INVALID", "TOKEN_EXPIRED"}

    @staticmethod
    def _exception_is_auth_invalid(exc: BaseException) -> bool:
        code = getattr(exc, "status_code", None) or getattr(exc, "code", None)
        if str(code).upper() in {"401", "403", "AUTH_INVALID", "SESSION_INVALID", "TOKEN_EXPIRED"}:
            return True
        return exc.__class__.__name__.lower() in {"authenticationerror", "sessioninvaliderror"}

    @staticmethod
    def _exception_is_rate_limited(exc: BaseException) -> bool:
        code = getattr(exc, "status_code", None) or getattr(exc, "code", None)
        return str(code).upper() in {"429", "RATE_LIMITED", "TOO_MANY_REQUESTS"}

    @staticmethod
    def _exception_retry_after(exc: BaseException) -> float | None:
        candidate = getattr(exc, "retry_after", None) or getattr(exc, "retry_after_seconds", None)
        headers = getattr(exc, "headers", None)
        if candidate is None and isinstance(headers, dict):
            candidate = headers.get("retry-after") or headers.get("Retry-After")
        if isinstance(candidate, bool) or candidate is None:
            return None
        try:
            parsed = float(str(candidate).strip())
        except ValueError:
            return None
        return parsed if parsed >= 0 and parsed != float("inf") else None
