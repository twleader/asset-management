from __future__ import annotations

import hashlib
import logging
import queue
import threading
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from .config import ConfigLoader, ConfigSnapshot


logger = logging.getLogger(__name__)


class SdkCallError(RuntimeError):
    def __init__(
        self,
        reason: str,
        *,
        auth_invalid: bool = False,
        misconfigured: bool = False,
        retry_after_seconds: float | None = None,
    ) -> None:
        super().__init__(reason)
        self.reason = reason
        self.auth_invalid = auth_invalid
        self.misconfigured = misconfigured
        self.retry_after_seconds = retry_after_seconds


@dataclass(frozen=True)
class SelectedAccount:
    raw: object
    branch_no: str
    account_number: str


@dataclass(frozen=True)
class AccountingPair:
    inventories: object
    unrealized: object
    account: SelectedAccount
    internal_token: str


def raw_field(value: object, name: str) -> object | None:
    if isinstance(value, dict):
        return value.get(name)
    return getattr(value, name, None)


def enum_text(value: object) -> str | None:
    if value is None:
        return None
    candidate = getattr(value, "value", value)
    if not isinstance(candidate, (str, int)):
        candidate = getattr(value, "name", None)
    if candidate is None:
        return None
    return str(candidate)


class SdkGateway:
    """One active SDK session, initialized and cleaned under a single mutex."""

    ACCOUNT_CALL_TIMEOUT_SECONDS = 5.0
    QUOTE_CALL_TIMEOUT_SECONDS = 5.0
    CLEANUP_CALL_TIMEOUT_SECONDS = 2.0
    MAX_BLOCKING_CALLS = 4

    def __init__(
        self,
        config_loader: ConfigLoader,
        sdk_factory: Callable[[], object] | None = None,
        monotonic: Callable[[], float] = time.monotonic,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        self._config_loader = config_loader
        self._sdk_factory = sdk_factory or self._default_sdk_factory
        self._monotonic = monotonic
        self._sleeper = sleeper
        self._session_lock = threading.RLock()
        self._accounting_lock = threading.Lock()
        self._blocking_slots = threading.BoundedSemaphore(self.MAX_BLOCKING_CALLS)
        self._account_starts: deque[float] = deque()
        self._sdk: object | None = None
        self._account: SelectedAccount | None = None
        self._stock_client: object | None = None
        self._websocket_stock_client: object | None = None
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

    def read_accounting_pair(self) -> AccountingPair:
        with self._accounting_lock:
            for attempt in range(2):
                config = self._require_config()
                account = self._ensure_session(config)
                try:
                    inventories = self._accounting_call("inventories", account.raw)
                    unrealized = self._accounting_call("unrealized_gains_and_loses", account.raw)
                    if self._response_auth_invalid(inventories) or self._response_auth_invalid(unrealized):
                        raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
                    return AccountingPair(
                        inventories=inventories,
                        unrealized=unrealized,
                        account=account,
                        internal_token=config.internal_service_token or "",
                    )
                except SdkCallError as exc:
                    if exc.auth_invalid and attempt == 0:
                        with self._session_lock:
                            self._invalidate_locked()
                        continue
                    raise
                except Exception as exc:
                    if self._exception_is_auth_invalid(exc) and attempt == 0:
                        with self._session_lock:
                            self._invalidate_locked()
                        continue
                    raise SdkCallError("ACCOUNTING_TRANSPORT_FAILED") from None
            raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)

    def selected_account(self) -> SelectedAccount:
        """Read-only accessor for the currently selected account identity.

        Reuses the same cached session as read_accounting_pair/read_filled_trades
        (a cache hit does no SDK I/O); never invokes an accounting or stock
        method itself. Exists so downstream raw-row validation (matching a
        filled-trade row's account/branch_no against the selected account)
        does not have to duplicate _ensure_session's account-selection logic.
        """
        config = self._require_config()
        return self._ensure_session(config)

    def read_filled_trades(self, start_date: str, end_date: str) -> object:
        """Narrow, read-only access to sdk.stock.filled_history only.

        Deliberately does NOT go through _accounting_call: that helper only
        resolves sdk.accounting.<method_name> and calls it with a single
        `account` argument, while filled_history lives under the sdk.stock
        namespace and needs extra start_date/end_date arguments -- the two
        call shapes are incompatible, and forcing this through _accounting_call
        would mix the "accounting" namespace resolution with "stock".

        sdk.stock is also the SAME namespace that holds place_order,
        cancel_order, modify_price, modify_quantity, batch_place_order and
        batch_cancel_order (the SDK's order/trading API). To keep this
        integration's reachable surface strictly read-only, this method
        resolves and invokes exactly one attribute on that namespace
        ("filled_history") and never returns, caches, or otherwise exposes
        the `stock` object itself to any caller.
        """
        with self._accounting_lock:
            for attempt in range(2):
                config = self._require_config()
                account = self._ensure_session(config)
                self._wait_for_accounting_budget()
                with self._session_lock:
                    sdk = self._sdk
                stock = raw_field(sdk, "stock") if sdk is not None else None
                filled_history = raw_field(stock, "filled_history") if stock is not None else None
                if not callable(filled_history):
                    self._mark_misconfigured()
                    raise SdkCallError("FILLED_HISTORY_UNAVAILABLE", misconfigured=True)
                try:
                    response = self._run_bounded(
                        lambda: filled_history(account.raw, start_date, end_date),
                        self.ACCOUNT_CALL_TIMEOUT_SECONDS,
                        "FILLED_HISTORY_TIMEOUT",
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
                    if self._exception_is_auth_invalid(exc) and attempt == 0:
                        with self._session_lock:
                            self._invalidate_locked()
                        continue
                    raise SdkCallError("FILLED_HISTORY_TRANSPORT_FAILED") from None
            raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)

    def quote(self, code: str) -> object:
        for attempt in range(2):
            config = self._require_config()
            self._ensure_session(config)
            with self._session_lock:
                client = self._stock_client
            if client is None:
                raise SdkCallError("REALTIME_NOT_INITIALIZED", misconfigured=True)
            try:
                intraday = raw_field(client, "intraday")
                quote_method = raw_field(intraday, "quote") if intraday is not None else None
                if not callable(quote_method):
                    self._mark_misconfigured()
                    raise SdkCallError("QUOTE_CLIENT_UNAVAILABLE", misconfigured=True)
                response = self._run_bounded(
                    lambda: quote_method(symbol=code), self.QUOTE_CALL_TIMEOUT_SECONDS, "QUOTE_TIMEOUT"
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
            if self._response_auth_invalid(response):
                raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
            if raw_field(response, "is_success") is False:
                raise SdkCallError("INDEX_TICKERS_REJECTED", misconfigured=True)
            rows = raw_field(response, "data") if raw_field(response, "is_success") is True else response
            if not isinstance(rows, list):
                raise SdkCallError("INDEX_TICKERS_INVALID", misconfigured=True)
            for row in rows:
                if (raw_field(row, "symbol") == symbol
                        and raw_field(row, "exchange") == "TWSE"
                        and raw_field(row, "type") == "INDEX"):
                    return
            raise SdkCallError("TAIEX_INDEX_SYMBOL_UNVERIFIED", misconfigured=True)
        except SdkCallError:
            raise
        except Exception:
            raise SdkCallError("INDEX_TICKERS_TRANSPORT_FAILED") from None

    def shutdown(self) -> None:
        with self._session_lock:
            self._cleanup_locked()

    def _require_config(self) -> ConfigSnapshot:
        config = self._config_loader.load()
        if not config.enabled:
            raise SdkCallError("DISABLED")
        if config.state != "READY":
            raise SdkCallError(config.reason or "MISCONFIGURED", misconfigured=True)
        return config

    def _ensure_session(self, config: ConfigSnapshot) -> SelectedAccount:
        digest = self._session_config_digest(config)
        with self._session_lock:
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

            self._cleanup_locked()
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
                )
                if raw_field(login, "is_success") is not True:
                    raise SdkCallError("SDK_LOGIN_FAILED", misconfigured=True)
                accounts = raw_field(login, "data")
                if not isinstance(accounts, list):
                    raise SdkCallError("INVALID_ACCOUNT_LIST", misconfigured=True)
                selected = self._select_account(accounts, config)

                init_result = self._run_bounded(
                    sdk.init_realtime, self.ACCOUNT_CALL_TIMEOUT_SECONDS, "REALTIME_INIT_TIMEOUT"
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
                self._config_digest = digest
                self._session_cleaned = False
                self._runtime_misconfigured = False
                self._misconfigured_digest = None
                return selected
            except SdkCallError as exc:
                if exc.misconfigured:
                    self._runtime_misconfigured = True
                    self._misconfigured_digest = digest
                self._cleanup_locked()
                raise
            except Exception:
                self._runtime_misconfigured = True
                self._misconfigured_digest = digest
                self._cleanup_locked()
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

        if config.account_branch_no is not None and config.account_number is not None:
            matches = [
                candidate
                for candidate in stock_accounts
                if candidate.branch_no == config.account_branch_no
                and candidate.account_number == config.account_number
            ]
            if len(matches) != 1:
                raise SdkCallError("ACCOUNT_SELECTOR_NOT_UNIQUE", misconfigured=True)
            return matches[0]
        if len(stock_accounts) != 1:
            raise SdkCallError("STOCK_ACCOUNT_NOT_UNIQUE", misconfigured=True)
        return stock_accounts[0]

    def _accounting_call(self, method_name: str, account: object) -> object:
        self._wait_for_accounting_budget()
        with self._session_lock:
            sdk = self._sdk
        accounting = raw_field(sdk, "accounting") if sdk is not None else None
        method = raw_field(accounting, method_name) if accounting is not None else None
        if not callable(method):
            self._mark_misconfigured()
            raise SdkCallError("ACCOUNTING_API_UNAVAILABLE", misconfigured=True)
        try:
            return self._run_bounded(
                lambda: method(account), self.ACCOUNT_CALL_TIMEOUT_SECONDS, "ACCOUNTING_TIMEOUT"
            )
        except SdkCallError:
            raise
        except Exception as exc:
            if self._exception_is_auth_invalid(exc):
                raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True) from None
            if self._exception_is_rate_limited(exc):
                raise SdkCallError("RATE_LIMITED") from None
            raise SdkCallError("ACCOUNTING_TRANSPORT_FAILED") from None

    def _wait_for_accounting_budget(self) -> None:
        now = self._monotonic()
        while self._account_starts and now - self._account_starts[0] >= 1.0:
            self._account_starts.popleft()
        if len(self._account_starts) >= 5:
            wait = 1.0 - (now - self._account_starts[0])
            if wait > 0:
                self._sleeper(wait)
            now = self._monotonic()
            while self._account_starts and now - self._account_starts[0] >= 1.0:
                self._account_starts.popleft()
        self._account_starts.append(self._monotonic())

    def _run_bounded(self, call: Callable[[], Any], timeout: float, timeout_reason: str) -> Any:
        if not self._blocking_slots.acquire(timeout=0.1):
            raise SdkCallError("SDK_CALL_SATURATED")
        output: queue.Queue[tuple[bool, object]] = queue.Queue(maxsize=1)

        def invoke() -> None:
            try:
                output.put((True, call()))
            except BaseException as exc:  # exception object remains internal; never serialized or logged
                output.put((False, exc))
            finally:
                self._blocking_slots.release()

        worker = threading.Thread(target=invoke, name="fubon-sdk-call", daemon=True)
        worker.start()
        worker.join(timeout)
        if worker.is_alive():
            raise SdkCallError(timeout_reason)
        ok, value = output.get_nowait()
        if ok:
            return value
        assert isinstance(value, BaseException)
        raise value

    def _cleanup_locked(self) -> None:
        sdk = self._sdk
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
                self._run_bounded(method, self.CLEANUP_CALL_TIMEOUT_SECONDS, "CLEANUP_TIMEOUT")
            except BaseException:
                logger.warning("Fubon SDK cleanup skipped reason=CLEANUP_FAILED step=%s", method_name)

    def _invalidate_locked(self) -> None:
        self._cleanup_locked()

    def _mark_misconfigured(self) -> None:
        with self._session_lock:
            digest = self._config_digest
            self._runtime_misconfigured = True
            self._misconfigured_digest = digest
            self._cleanup_locked()

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
